package app.pausecn.ai

import androidx.room.withTransaction
import app.pausecn.data.PauseDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import android.os.SystemClock
import app.pausecn.data.TargetRuleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap
import app.pausecn.reports.*

data class AiUiState(
    val loaded: Boolean = false,
    val config: AiConfig = AiConfig(),
    val phrases: List<AiPhrase> = emptyList(),
    val hasKey: Boolean = false,
    val busy: Boolean = false,
    val attempts: Int = 0,
    val tokens: Int = 0,
    val profile: UserProfile? = null,
    val personalization: PersonalizationConfig = PersonalizationConfig(),
    val personalPhrases: List<PersonalizedPhrase> = emptyList(),
    val targets: List<TargetRuleEntity> = emptyList(),
    val personalStatus: String = "",
    val cacheRevision: Long = -1,
    val conversation: ConversationConfig = ConversationConfig(),
    val messages: List<ConversationMessage> = emptyList(),
    val memories: List<UserMemory> = emptyList(),
    val feedback: List<PhraseFeedback> = emptyList(),
    val lastDisplayed: Map<String, DisplayedReminder> = emptyMap(),
    val categories: app.pausecn.data.AppCategorySnapshot = app.pausecn.data.AppCategorySnapshot(),
)

/** Shared by UI and future workers. Only snapshots/commits hold the local lock, never HTTP. */
class AiRepository(
    private val database: PauseDatabase,
    private val credentials: ApiCredentialStore,
    private val transport: AiTransport = DeepSeekClient(),
    private val phrasePrompt: () -> String,
    private val personalizedPrompt: () -> String = { error("个性化生成要求尚未配置") },
    private val retentionDays: suspend () -> Int = { 30 },
    private val conversationPrompt: () -> String = { error("交流生成要求尚未配置") },
    private val reportPrompt: () -> String = { error("报告生成要求尚未配置") },
    private val readRuleState: suspend () -> app.pausecn.data.RuleState = { error("设置读取尚未配置") },
    private val prepareAutomaticFacts: suspend () -> Unit = {},
    private val automaticReady: () -> Boolean = { true },
    private val categoryPrompt: () -> String = { error("分类要求尚未配置") },
) {
    private val dao get() = database.aiDao()
    private val local = Mutex()
    private val requestGate = Mutex()
    private val mutableState = MutableStateFlow(AiUiState())
    val state = mutableState.asStateFlow()
    val selector = PromptSelector()
    private var activeRequest: Job? = null
    private var activeAutomaticClaim: String? = null
    private var suspendedForReset = false
    private val guard get() = database.personalizationGuard
    private val consumed = ConcurrentHashMap.newKeySet<String>()
    private val preparationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preparationQueue = PreparationQueue()
    private val workerLock = Any()
    private var preparationWorker: Job? = null
    private var activeIntent: PreparationIntent? = null
    val conversations = ConversationRepository(database, this, retentionDays)

    suspend fun load() = withContext(Dispatchers.IO) { local.withLock { refreshLocked() } }

    /** Only the separate, explicit name-upload confirmation in the classification screen calls this. */
    suspend fun generateCategories(plan: CategoryAiPlan, progress: (Int, Int) -> Unit = { _, _ -> }): CategoryAiDelivery = withContext(Dispatchers.IO) {
        if (!requestGate.tryLock()) throw AiRequestException("已有AI请求进行中，请完成后再试；未排队或扣费。")
        val job = currentCoroutineContext()[Job]
        try {
            require(plan.apps.isNotEmpty())
            val system = categoryPrompt().also { require(it.isNotBlank() && it.length <= 10_000) }
            // Validate the whole batch schedule before the first charged request.
            val inputs = plan.batches.map { CategoryAiProtocol.input(plan, it).also { input -> require(system.length + input.length <= 12_000) } }
            val config = local.withLock { guard.readStable { database.withTransaction {
                val current = configLocked()
                check(!suspendedForReset && current.enabled && credentials.hasKey()) { "请先在AI陪伴的连接与风格中启用AI并保存Key。" }
                check((database.appCategoryDao().settings()?.revision ?: 0) == plan.revision) { "分类已变化，请重新检查发送范围。" }
                activeRequest = job
                mutableState.value = state.value.copy(busy = true)
                current
            } } }
            val suggestions = mutableListOf<CategoryAiSuggestion>()
            var completed = 0
            var notice = "已生成建议，确认前不会更改分类。"
            for ((index, batch) in plan.batches.withIndex()) {
                currentCoroutineContext().ensureActive()
                progress(index + 1, inputs.size)
                var record: AiRequestRecord? = null
                var tokens = 0
                var status = "FAILED_OR_UNKNOWN"
                try {
                    val key = local.withLock { guard.readStable { database.withTransaction {
                        check(!suspendedForReset && configLocked() == config && (database.appCategoryDao().settings()?.revision ?: 0) == plan.revision)
                        val credential = credentials.read()
                        record = AiRequestRecord(day = LocalDate.now().toString(), startedAt = System.currentTimeMillis())
                        dao.reserve(requireNotNull(record))
                        dao.pruneRequests(System.currentTimeMillis() - 30L * 86_400_000)
                        credential
                    } } }
                    currentCoroutineContext().ensureActive()
                    val response = transport.complete(key, config.model, system, inputs[index])
                    tokens = response.totalTokens
                    val parsed = CategoryAiProtocol.parse(response.content, plan, batch)
                    local.withLock { guard.readStable {
                        check(!suspendedForReset && configLocked() == config && (database.appCategoryDao().settings()?.revision ?: 0) == plan.revision)
                    } }
                    suggestions += parsed; completed += batch.size; status = "SUCCEEDED"
                } catch (cancelled: CancellationException) {
                    status = "CANCELLED_OR_UNKNOWN"; throw cancelled
                } catch (_: Exception) {
                    notice = "本次已停止，未自动重试；可能已计费。仅保留此前成功的建议，未完成的应用不变。"
                    break
                } finally {
                    withContext(NonCancellable) { local.withLock { record?.let { dao.finish(it.id, status, tokens) } } }
                }
            }
            local.withLock { guard.readStable {
                check(!suspendedForReset && configLocked() == config && (database.appCategoryDao().settings()?.revision ?: 0) == plan.revision)
            } }
            CategoryAiDelivery(plan, config, suggestions.toList(), completed, notice)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            throw AiRequestException(if (error is AiRequestException) error.publicMessage
                else "分类未完成或来源已变化；请先确认AI已启用及发送范围有效。不会自动重试，未改动分类。")
        } finally {
            try { withContext(NonCancellable) { local.withLock {
                if (activeRequest === job) activeRequest = null
                mutableState.value = state.value.copy(busy = false)
                try { refreshLocked() } catch (_: Exception) { /* Keep cleanup from masking the request outcome. */ }
            } } } finally { requestGate.unlock() }
        }
    }

    suspend fun applyCategories(delivery: CategoryAiDelivery, selected: Set<String>, installed: Set<String>) = withContext(Dispatchers.IO) {
        local.withLock {
            check(!suspendedForReset)
            app.pausecn.data.AppCategoryRepository(database).applyAi(delivery, selected, installed)
            refreshLocked()
        }
    }

    private suspend fun configLocked(): AiConfig = dao.config() ?: AiConfig().also { dao.save(it) }

    private suspend fun refreshLocked() {
        database.reportDao().pruneCache(System.currentTimeMillis())
        val revision = guard.revision
        val retentionMs = retentionDays().coerceIn(1, 30) * 86_400_000L
        val next = database.withTransaction {
            val config = configLocked()
            val conversation = database.conversationDao().config() ?: ConversationConfig().also { database.conversationDao().saveConfig(it) }
            val day = LocalDate.now().toString()
            dao.prunePersonalized(System.currentTimeMillis())
            mutableState.value.copy(loaded = true, config = config,
                categories = app.pausecn.data.appCategorySnapshot(database),
                phrases = dao.phrases(), hasKey = credentials.hasKey(), attempts = dao.attempts(day), tokens = dao.tokens(day),
                profile = dao.profile(), personalization = dao.personalization() ?: PersonalizationConfig(),
                personalPhrases = dao.personalizedPhrases(), targets = database.targetRuleDao().getAllForExport().filter { it.enabled },
                conversation = conversation,
                messages = database.conversationDao().messages(System.currentTimeMillis(), retentionMs),
                memories = database.conversationDao().memories(System.currentTimeMillis(), retentionMs),
                feedback = database.conversationDao().feedback(System.currentTimeMillis(), retentionMs),
                lastDisplayed = if (conversation.epoch != state.value.conversation.epoch) emptyMap() else state.value.lastDisplayed,
                cacheRevision = revision)
        }
        consumed.retainAll(next.personalPhrases.map { it.id }.toSet())
        mutableState.value = next
    }

    private suspend fun <T> mutatePersonalLocked(preserveCandidates: Boolean = false, block: suspend () -> T): T {
        preparationQueue.clear()
        activeRequest?.cancel()
        synchronized(workerLock) { preparationWorker?.cancel() }
        return guard.mutate { database.withTransaction {
            dao.invalidatePersonalization()
            dao.clearPersonalized()
            if (database.conversationDao().config() == null) database.conversationDao().saveConfig(ConversationConfig())
            database.conversationDao().invalidate()
            database.conversationDao().clearReplies()
            if (!preserveCandidates) database.conversationDao().clearCandidates()
            block()
        } }
    }

    internal suspend fun changeConversation(block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        local.withLock {
            check(!suspendedForReset)
            val days = retentionDays()
            mutatePersonalLocked(preserveCandidates = true) {
                pruneConversationData(database, System.currentTimeMillis(), days)
                block()
                pruneConversationData(database, System.currentTimeMillis(), days)
            }
            refreshLocked()
        }
    }

    /** Saving does not grant upload permission or trigger a paid request. */
    suspend fun saveProfile(goal: String, preferences: String) = withContext(Dispatchers.IO) {
        val normalizedGoal = ProfileText.normalized(goal)
        val normalizedPreferences = ProfileText.normalized(preferences)
        require(normalizedGoal.isNotBlank() || normalizedPreferences.isNotBlank()) { "请填写背景，清空请使用删除入口" }
        local.withLock {
            check(!suspendedForReset)
            val old = dao.profile()
            if (old == null || old.goal != normalizedGoal || old.preferences != normalizedPreferences) {
                mutatePersonalLocked {
                    dao.saveProfile(UserProfile(goal = normalizedGoal, preferences = normalizedPreferences,
                        updatedAtEpochMs = System.currentTimeMillis()))
                }
            }
            refreshLocked()
        }
    }

    suspend fun deleteProfile() = withContext(Dispatchers.IO) {
        local.withLock {
            check(!suspendedForReset)
            mutatePersonalLocked { dao.deleteProfile() }
            check(dao.profile() == null) { "本地背景删除未完成" }
            refreshLocked()
        }
    }

    suspend fun saveStyle(style: String, model: String, manual: String) = withContext(Dispatchers.IO) {
        require(style.isNotBlank() && style.length <= 300 && model in DeepSeekClient.MODELS)
        require(manual.isEmpty() || PromptSelector.validShortText(manual)) { "手写短句最多36个字，不能包含换行或控制字符" }
        local.withLock {
            check(!suspendedForReset)
            activeRequest?.cancel()
            val old = configLocked()
            mutatePersonalLocked {
                dao.save(old.copy(style = style, model = model, manualPhrase = manual,
                    styleVersion = old.styleVersion + 1))
                dao.clearPhrases()
            }
            selector.clear()
            refreshLocked()
        }
    }

    suspend fun setEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        local.withLock {
            check(!suspendedForReset)
            val old = configLocked()
            if (enabled) check(credentials.hasKey()) { "请先在手机内保存 Key" }
            activeRequest?.cancel()
            mutatePersonalLocked { dao.save(old.copy(enabled = enabled, privacyEpoch = old.privacyEpoch + 1)) }
            selector.clear()
            refreshLocked()
        }
    }

    suspend fun saveKey(key: String) = withContext(Dispatchers.IO) {
        local.withLock {
            check(!suspendedForReset)
            activeRequest?.cancel()
            val old = configLocked()
            mutatePersonalLocked { dao.save(old.copy(enabled = false, privacyEpoch = old.privacyEpoch + 1)) }
            try { credentials.save(key.trim()) } finally { refreshLocked() }
        }
    }

    suspend fun deleteKey() = withContext(Dispatchers.IO) {
        local.withLock {
            check(!suspendedForReset)
            activeRequest?.cancel()
            val old = configLocked()
            mutatePersonalLocked { dao.save(old.copy(enabled = false, privacyEpoch = old.privacyEpoch + 1)) }
            try { credentials.clear() } finally { refreshLocked() }
        }
    }

    suspend fun approvePhrases() = withContext(Dispatchers.IO) {
        local.withLock {
            check(!suspendedForReset)
            val config = configLocked()
            check(config.enabled)
            dao.approve(config.styleVersion)
            refreshLocked()
        }
    }

    fun choose(packageName: String, wallMs: Long, elapsedMs: Long): PromptChoice {
        val snapshot = state.value
        val selectedFallback = selector.choose(packageName, snapshot.config, snapshot.phrases.filterNot {
            ConversationPolicy.rejected(snapshot.feedback, packageName, it.text, wallMs)
        }, wallMs, elapsedMs)
        val filteredFallback = if (snapshot.config.manualPhrase.isBlank() && ConversationPolicy.rejected(snapshot.feedback,
                packageName, selectedFallback.text, wallMs)) selectedFallback.copy(
            text = ConversationPolicy.neutralFallback(snapshot.feedback, packageName, selectedFallback.scene, wallMs), id = null) else selectedFallback
        val fallback = filteredFallback.copy(contextJson = ReminderContext.local(snapshot.config, filteredFallback),
            contextRevision = snapshot.cacheRevision)
        if (snapshot.config.manualPhrase.isNotBlank() || guard.changing || snapshot.cacheRevision != guard.revision) return fallback
        val target = snapshot.targets.firstOrNull { it.packageName == packageName }
        val profileRevision = backgroundRevision(snapshot, packageName, wallMs)
        val choice = snapshot.personalPhrases.asSequence().filter {
            it.packageName == packageName && it.scene == fallback.scene.name && it.id !in consumed &&
                !ConversationPolicy.rejected(snapshot.feedback, packageName, it.text, wallMs) &&
                PersonalizedCachePolicy.valid(it, snapshot.config, snapshot.personalization, profileRevision,
                    target?.createdAtEpochMs, wallMs, elapsedMs)
        }.sortedBy { if (it.kind == PersonalizedCachePolicy.RECENT) 0 else 1 }.firstOrNull()
        return choice?.let { PromptChoice(it.text, fallback.scene, "personal:${it.id}", it.contextJson,
            it.expiresAt, it.createdAt, snapshot.cacheRevision) } ?: fallback
    }

    private fun backgroundRevision(snapshot: AiUiState, pkg: String, now: Long) = ConversationPolicy.backgroundRevision(
        if (snapshot.personalization.useProfile) snapshot.profile?.revision.orEmpty() else "",
        snapshot.conversation, snapshot.memories, pkg, now)

    /** Called only after an actual overlay mount; preview never consumes a candidate. */
    fun shown(packageName: String, choice: PromptChoice, wallMs: Long, elapsedMs: Long) {
        selector.shown(packageName, choice, wallMs, elapsedMs)
        // Only the latest actual display per selected target; ephemeral, not exported or sent automatically.
        mutableState.value = state.value.copy(lastDisplayed = if (!guard.changing && choice.contextRevision == guard.revision)
            state.value.lastDisplayed + (packageName to DisplayedReminder(choice.text, choice.contextJson,
                choice.contextGeneratedAt, choice.contextExpiresAt, state.value.conversation.epoch)) else state.value.lastDisplayed - packageName)
        choice.id?.takeIf { it.startsWith("personal:") }?.removePrefix("personal:")?.let { id ->
            consumed += id
            preparationScope.launch {
                try { local.withLock { dao.consumePersonalized(id); refreshLocked() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* In-memory consumption remains in force until restart. */ }
            }
        }
    }

    fun explanationFor(pkg: String, text: String, now: Long): DisplayedReminder? {
        if (guard.changing || state.value.cacheRevision != guard.revision) return null
        return state.value.lastDisplayed[pkg]?.takeIf { it.text == text && it.expiresAt > now && it.revision == state.value.conversation.epoch }
            ?: usablePersonalPhrases(pkg, now).firstOrNull { it.text == text }?.let {
                DisplayedReminder(it.text, it.contextJson, it.createdAt, it.expiresAt, guard.revision, actualDisplay = false)
            }
    }

    suspend fun setPersonalization(enabled: Boolean, useProfile: Boolean, useReasons: Boolean) = withContext(Dispatchers.IO) {
        local.withLock {
            check(!suspendedForReset)
            if (enabled) check(configLocked().enabled && credentials.hasKey()) { "请先启用 AI" }
            activeRequest?.cancel()
            mutatePersonalLocked {
                val old = dao.personalization() ?: PersonalizationConfig()
                dao.savePersonalization(old.copy(enabled = enabled, useProfile = useProfile, useReasons = useReasons,
                    epoch = old.epoch + 1))
            }
            refreshLocked()
        }
    }

    /** No timer or startup calls this. Only an explicit prepare action or a committed real choice. */
    suspend fun prepareNext(packageName: String, stable: Boolean = true): String = withContext(Dispatchers.IO) {
        val snapshot = snapshotFor(packageName, stable) ?: return@withContext "未准备：请启用个性化、选择目标并清空优先显示的手写短句。"
        val enough = local.withLock { dao.personalizedPhrases().filter {
            it.packageName == packageName && it.kind == snapshot.kind && it.fingerprint == snapshot.fingerprint &&
                it.id !in consumed && PersonalizedCachePolicy.valid(it, snapshot.config, snapshot.consent,
                    snapshot.profileRevision, snapshot.target.createdAtEpochMs, snapshot.createdAt, snapshot.elapsedAt)
        }.map { it.scene }.toSet().containsAll(setOf(PromptScene.ORDINARY.name, PromptScene.REPEATED.name)) }
        if (enough) return@withContext "已有可用提醒，复用缓存，未额外请求。"
        val intent = PreparationIntent(packageName, stable, snapshot.fingerprint, snapshot.expiresAt)
        synchronized(workerLock) {
            if (activeIntent?.let { it.packageName == packageName && it.stable == stable &&
                    it.fingerprint == snapshot.fingerprint } == true) return@synchronized
            preparationQueue.offer(intent)
            if (preparationWorker == null || preparationWorker?.isActive == false) {
                val worker = preparationScope.launch(start = CoroutineStart.LAZY) {
                    val ownJob = currentCoroutineContext()[Job]
                    try {
                        while (true) {
                            val next = synchronized(workerLock) {
                                preparationQueue.poll().also {
                                    activeIntent = it
                                    if (it == null && preparationWorker === ownJob) preparationWorker = null
                                }
                            } ?: break
                            generatePersonalized(next)
                        }
                    } finally {
                        synchronized(workerLock) {
                            if (preparationWorker === ownJob) {
                                activeIntent = null
                                preparationWorker = null
                                preparationQueue.clear()
                            }
                        }
                    }
                }
                preparationWorker = worker
                worker.start()
            }
        }
        mutableState.value = state.value.copy(personalStatus = "已加入准备队列；完成后自动用于下次停顿，调用可能收费。")
        "已加入准备队列，不会延长停顿。"
    }

    suspend fun onCompletedIntervention(eventId: Long) {
        val event = database.interventionEventDao().byId(eventId) ?: return
        if (event.outcome !in setOf("CONTINUED", "EXITED")) return
        val snapshot = snapshotFor(event.packageName, false) ?: return
        val nextScene = selector.choose(event.packageName, snapshot.config, emptyList(),
            snapshot.createdAt, snapshot.elapsedAt).scene.name
        val rows = local.withLock { dao.personalizedPhrases() }
        // A real completed choice supplies recent context. Replenishing a consumed STABLE
        // batch here would starve that context (especially with one phrase per scene).
        // Manual prepare still defaults to STABLE; this path enqueues at most one RECENT batch.
        val sufficient = PersonalizedCachePolicy.hasRecentForNextChoice(
            rows, event.packageName, nextScene, snapshot.fingerprint,
        ) { row ->
            row.id !in consumed && PersonalizedCachePolicy.valid(row, snapshot.config, snapshot.consent,
                snapshot.profileRevision, snapshot.target.createdAtEpochMs, snapshot.createdAt, snapshot.elapsedAt)
        }
        if (!sufficient) prepareNext(event.packageName, stable = false)
    }

    fun usablePersonalPhrases(packageName: String, now: Long = System.currentTimeMillis()): List<PersonalizedPhrase> {
        val snapshot = state.value
        if (guard.changing || snapshot.cacheRevision != guard.revision) return emptyList()
        val target = snapshot.targets.firstOrNull { it.packageName == packageName }
        return snapshot.personalPhrases.filter {
            it.packageName == packageName && it.id !in consumed && PersonalizedCachePolicy.valid(it,
                snapshot.config, snapshot.personalization,
                backgroundRevision(snapshot, packageName, now),
                target?.createdAtEpochMs, now, SystemClock.elapsedRealtime())
                && !ConversationPolicy.rejected(snapshot.feedback, packageName, it.text, now)
        }
    }

    private suspend fun snapshotFor(pkg: String, stable: Boolean): PersonalizationSnapshot? {
        val days = retentionDays()
        return local.withLock {
            if (suspendedForReset || guard.changing) return@withLock null
            database.withTransaction {
                val now = System.currentTimeMillis()
                val elapsed = SystemClock.elapsedRealtime()
                val scene = selector.choose(pkg, configLocked(), emptyList(), now, elapsed).scene
                personalizationSnapshot(database, pkg, stable, now, elapsed, days, scene)
            }
        }
    }

    private suspend fun generatePersonalized(intent: PreparationIntent) = requestGate.withLock {
        var record: AiRequestRecord? = null
        var tokens = 0
        val job = currentCoroutineContext()[Job]
        try {
            if (System.currentTimeMillis() >= intent.expiresAt) return@withLock
            val snapshot = snapshotFor(intent.packageName, intent.stable) ?: return@withLock
            if (snapshot.fingerprint != intent.fingerprint) return@withLock
            val system = personalizedPrompt().also { check(it.isNotBlank() && it.length <= 10_000) }
            val daysBeforeSend = retentionDays()
            val key = local.withLock {
                database.withTransaction {
                    check(!suspendedForReset && !guard.changing && configLocked().enabled)
                    val now = System.currentTimeMillis()
                    val beforeSend = personalizationSnapshot(database, intent.packageName, intent.stable, now,
                        SystemClock.elapsedRealtime(), daysBeforeSend, selector.choose(intent.packageName,
                            configLocked(), emptyList(), now, SystemClock.elapsedRealtime()).scene)
                    check(beforeSend != null && beforeSend.fingerprint == snapshot.fingerprint)
                    val credential = credentials.read()
                    record = AiRequestRecord(day = LocalDate.now().toString(), startedAt = now)
                    dao.reserve(requireNotNull(record))
                    dao.pruneRequests(now - 30L * 86_400_000)
                    activeRequest = job
                    mutableState.value = state.value.copy(busy = true)
                    credential
                }
            }
            currentCoroutineContext().ensureActive()
            val response = transport.complete(key, snapshot.config.model, system, snapshot.userJson)
            tokens = response.totalTokens
            val parsed = PersonalizationProtocol.parse(response.content, snapshot.config.styleVersion, snapshot.sourceIds)
            val now = System.currentTimeMillis()
            val days = retentionDays()
            local.withLock {
                database.withTransaction {
                    check(!suspendedForReset)
                    val current = personalizationSnapshot(database, intent.packageName, intent.stable, now,
                        SystemClock.elapsedRealtime(), days, selector.choose(intent.packageName, configLocked(), emptyList(),
                            now, SystemClock.elapsedRealtime()).scene)
                    check(current != null && current.fingerprint == snapshot.fingerprint &&
                        now < minOf(snapshot.expiresAt, intent.expiresAt))
                    val feedback = database.conversationDao().feedback(now, days.coerceIn(1, 30) * 86_400_000L)
                    val rows = parsed.filterNot { ConversationPolicy.rejected(feedback, intent.packageName, it.text, now) }
                        .map { phrase -> PersonalizedPhrase(packageName = intent.packageName,
                        targetCreatedAt = snapshot.target.createdAtEpochMs, scene = phrase.scene, text = phrase.text,
                        kind = snapshot.kind, instanceId = snapshot.config.instanceId, privacyEpoch = snapshot.config.privacyEpoch,
                        personalizationEpoch = snapshot.consent.epoch, styleVersion = snapshot.config.styleVersion,
                        profileRevision = snapshot.profileRevision, fingerprint = snapshot.fingerprint, createdAt = snapshot.createdAt,
                        expiresAt = minOf(snapshot.expiresAt, current.expiresAt, intent.expiresAt),
                        clockOffset = snapshot.createdAt - snapshot.elapsedAt, contextJson = snapshot.userJson) }
                    check(rows.isNotEmpty()) { "生成内容均已被用户否定，保留旧缓存" }
                    dao.clearPersonalizedTarget(intent.packageName, snapshot.kind)
                    dao.insertPersonalized(rows)
                    dao.finish(requireNotNull(record).id, "SUCCEEDED", tokens)
                    mutableState.value = state.value.copy(personalStatus = "已为所选应用准备${rows.size}条提醒，将自动用于下次停顿。")
                }
                refreshLocked()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            withContext(NonCancellable) { local.withLock {
                record?.let { dao.finish(it.id, "FAILED_OR_UNKNOWN", tokens) }
                mutableState.value = state.value.copy(personalStatus = when (error) {
                    is PhraseValidationException -> error.publicMessage
                    is AiRequestException -> error.publicMessage
                    else -> "本次未更新提醒；保留有效缓存，不自动重试，可能已产生费用。"
                })
            } }
        } finally {
            withContext(NonCancellable) { local.withLock {
                if (activeRequest === job) activeRequest = null
                mutableState.value = state.value.copy(busy = false)
                refreshLocked()
            } }
        }
    }

    suspend fun generatePhrases(): String = withContext(Dispatchers.IO) {
        if (!requestGate.tryLock()) return@withContext "已有一个请求进行中；没有重复发送。"
        var record: AiRequestRecord? = null
        var returnedTokens = 0
        var systemPrompt = ""
        val job = currentCoroutineContext()[Job]
        try {
            val snapshot = local.withLock {
                check(!suspendedForReset)
                val config = configLocked()
                check(config.enabled) { "请先启用 AI" }
                check(credentials.hasKey()) { "请先填写 Key" }
                // Load the actual packaged specification before charging a request attempt.
                systemPrompt = phrasePrompt()
                check(systemPrompt.isNotBlank() && systemPrompt.length <= 10_000) { "短句生成要求不可用，未发送请求" }
                // Decrypt before reserving; failed keystore access is not a request attempt.
                val key = try { credentials.read() } catch (_: Exception) {
                    throw AiRequestException("加密凭据不可用，请在手机内重新填写 Key。")
                }
                database.withTransaction {
                    val day = LocalDate.now().toString()
                    record = AiRequestRecord(day = day, startedAt = System.currentTimeMillis())
                    dao.reserve(requireNotNull(record))
                    dao.pruneRequests(System.currentTimeMillis() - 30L * 86_400_000)
                }
                activeRequest = job
                mutableState.value = state.value.copy(busy = true)
                refreshLocked()
                config to key
            }
            val response = transport.complete(snapshot.second, snapshot.first.model, systemPrompt,
                JSONObject().put("style", snapshot.first.style).toString())
            returnedTokens = response.totalTokens
            val batch = PhraseProtocol.parseBatch(response.content, snapshot.first.styleVersion)
            local.withLock {
                val current = configLocked()
                check(!suspendedForReset && current.enabled && current.instanceId == snapshot.first.instanceId &&
                    current.privacyEpoch == snapshot.first.privacyEpoch && current.styleVersion == snapshot.first.styleVersion) {
                    "设置或隐私状态已改变，旧响应已丢弃"
                }
                database.withTransaction {
                    dao.clearPhrases()
                    dao.insertPhrases(batch.phrases)
                    dao.finish(requireNotNull(record).id, "SUCCEEDED", response.totalTokens)
                }
                refreshLocked()
            }
            batch.publicMessage
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            withContext(NonCancellable) { local.withLock {
                record?.let { dao.finish(it.id,
                    if (error is PhraseValidationException) "REJECTED_${error.code.name}" else "FAILED_OR_UNKNOWN",
                    returnedTokens) }
                refreshLocked()
            } }
            when (error) {
                is AiRequestException -> error.publicMessage
                is PhraseValidationException -> error.publicMessage
                is IllegalStateException -> "${error.message ?: "当前无法生成"}"
                else -> "本地数据处理失败，未应用新内容；请重试。"
            }
        } finally {
            withContext(NonCancellable) { local.withLock {
                if (activeRequest === job) activeRequest = null
                mutableState.value = state.value.copy(busy = false)
            } }
            requestGate.unlock()
        }
    }

    suspend fun cancelRequest() = local.withLock {
        preparationQueue.clear()
        activeRequest?.cancel()
        synchronized(workerLock) { preparationWorker?.cancel() }
    }

    /** One explicit message, one shared request slot; never auto-retries or changes settings. */
    suspend fun sendConversation(sessionId: String, pkg: String, text: String): ConversationDelivery = withContext(Dispatchers.IO) {
        require(ConversationPolicy.textValid(text))
        if (!requestGate.tryLock()) throw AiRequestException("已有请求进行中，本条未发送；可稍后再点发送。")
        val job = currentCoroutineContext()[Job]
        var record: AiRequestRecord? = null
        var tokens = 0
        try {
            val days = retentionDays()
            val system = conversationPrompt().also { check(it.isNotBlank() && it.length <= 10_000) }
            val (snapshot, key) = local.withLock {
                guard.mutate { database.withTransaction {
                    check(!suspendedForReset)
                    pruneConversationData(database, System.currentTimeMillis(), days, keepMessages = 98)
                    val config = configLocked()
                    val consent = database.conversationDao().config() ?: ConversationConfig()
                    check(config.enabled && consent.enabled) { "请先启用 AI 和交流授权" }
                    val credential = credentials.read()
                    val now = System.currentTimeMillis()
                    val source = ConversationMessage(sessionId = sessionId, scopePackage = pkg, role = "USER",
                        text = text.trim(), createdAt = now, expiresAt = ConversationPolicy.expiresAt(now, days))
                    if (consent.saveMessages) database.conversationDao().insertMessage(source)
                    val prepared = conversationSnapshot(database, source, now, days) ?: error("交流背景已失效")
                    record = AiRequestRecord(day = LocalDate.now().toString(), startedAt = now)
                    dao.reserve(requireNotNull(record))
                    dao.pruneRequests(now - 30L * 86_400_000)
                    activeRequest = job
                    mutableState.value = state.value.copy(busy = true)
                    prepared to credential
                } }
            }
            currentCoroutineContext().ensureActive()
            val response = transport.complete(key, snapshot.config.model, system, snapshot.userJson)
            tokens = response.totalTokens
            val reply = ConversationProtocol.parse(response.content, snapshot.candidateSourceIds)
            val currentDays = retentionDays()
            var deliveryExpires = snapshot.expiresAt
            local.withLock {
                database.withTransaction {
                    check(!suspendedForReset && !guard.changing)
                    val now = System.currentTimeMillis()
                    val current = conversationSnapshot(database, snapshot.source, now, currentDays)
                    check(current != null && current.fingerprint == snapshot.fingerprint && now < snapshot.expiresAt)
                    deliveryExpires = minOf(snapshot.expiresAt, current.expiresAt)
                    if (snapshot.consent.saveMessages) {
                        val conversationDao = database.conversationDao()
                        conversationDao.insertMessage(ConversationMessage(sessionId = sessionId, scopePackage = pkg,
                            role = "ASSISTANT", text = reply.reply, createdAt = now,
                            expiresAt = minOf(snapshot.expiresAt, current.expiresAt)))
                        reply.candidates.forEach { candidate ->
                            val source = conversationDao.message(candidate.sourceId) ?: return@forEach
                            if (source.aiEligible && source.role == "USER" && source.expiresAt > now) {
                                conversationDao.saveMemory(UserMemory(sourceMessageId = source.id, scopePackage = pkg,
                                    text = candidate.text, kind = candidate.kind, createdAt = now,
                                    expiresAt = minOf(source.expiresAt, snapshot.expiresAt, current.expiresAt,
                                        ConversationPolicy.expiresAt(now, currentDays, candidate.kind))))
                            }
                        }
                    }
                    dao.finish(requireNotNull(record).id, "SUCCEEDED", tokens)
                }
                refreshLocked()
            }
            ConversationDelivery(reply, snapshot.config.instanceId, snapshot.consent.epoch,
                snapshot.config.privacyEpoch, deliveryExpires)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            withContext(NonCancellable) { local.withLock { record?.let { dao.finish(it.id, "FAILED_OR_UNKNOWN", tokens) } } }
            throw AiRequestException(if (error is AiRequestException) error.publicMessage
                else "本次交流未完成或背景已变化，没有应用旧回复；不自动重试，可能已产生费用。")
        } finally {
            try {
                withContext(NonCancellable) { local.withLock {
                    if (activeRequest === job) activeRequest = null
                    mutableState.value = state.value.copy(busy = false)
                    refreshLocked()
                } }
            } finally { requestGate.unlock() }
        }
    }

    suspend fun setReportConsent(choice: ReportConfig) = withContext(Dispatchers.IO) {
        local.withLock {
            check(!suspendedForReset)
            activeRequest?.cancel()
            guard.mutate { database.withTransaction {
                val old = database.reportDao().config() ?: ReportConfig()
                database.reportDao().save(choice.copy(id = 1, epoch = old.epoch + 1))
            } }
        }
    }

    internal suspend fun reportDeliveryCurrent(delivery: ReportDelivery, reports: ReportRepository): Boolean = withContext(Dispatchers.IO) {
        val days = retentionDays()
        local.withLock { guard.readStable { database.withTransaction { reportRequestCurrent(delivery.request, reports, days) } } }
    }

    private suspend fun reportRequestCurrent(request: ReportRequestSnapshot, reports: ReportRepository, days: Int): Boolean {
        if (request.automatic != null && (!automaticReady() || !AutomaticReportPolicy.current(database.automaticReportDao().state(), request.automatic))) return false
        val now = System.currentTimeMillis()
        if (suspendedForReset || now >= request.expiresAt || app.pausecn.data.sanitizeHistoryRetentionDays(days) != request.local.retentionDays ||
            database.reportDao().config() != request.consent || dao.config() != request.ai || !reports.isCurrentInTransaction(request.local) ||
            database.reportDao().cached(request.local.facts.window.slot)?.fingerprint != request.local.facts.fingerprint) return false
        val background = reportBackground(database, request.local.facts, request.consent, now, days)
        return background.json == request.background.json && now < background.expiresAt
    }

    /** Rehydrates only current metadata; no raw background or credentials are read from the cache. */
    internal suspend fun restoreReport(snapshot: LocalReportSnapshot, reports: ReportRepository): ReportDelivery? = withContext(Dispatchers.IO) {
        val days = retentionDays()
        local.withLock { guard.readStable { database.withTransaction {
            val cache = database.reportDao().cached(snapshot.facts.window.slot) ?: return@withTransaction null
            // An old report/share screen must not validate or delete a newer report in this slot.
            if (cache.fingerprint != snapshot.facts.fingerprint || cache.createdAt != snapshot.facts.createdAt) return@withTransaction null
            if (cache.interpretationJson.isBlank()) return@withTransaction null
            try {
                val now = System.currentTimeMillis()
                val config = dao.config() ?: error("AI关闭")
                val consent = database.reportDao().config() ?: error("报告关闭")
                check(config.enabled && consent.enabled && cache.expiresAt > now && cache.fingerprint == snapshot.facts.fingerprint)
                check(cache.aiTag == ReportCacheCodec.aiTag(config) && cache.consentEpoch == consent.epoch)
                val background = reportBackground(database, snapshot.facts, consent, now, days)
                check(cache.backgroundHash == ReportCacheCodec.hash(background.json))
                val basis = if (consent.useSettings) RuleSuggestionProtocol.decodeBasis(cache.ruleBasisJson) else null
                val (user, ids) = ReportProtocol.input(snapshot.facts, consent, config.style, background, basis)
                val request = ReportRequestSnapshot(snapshot, consent, config, background, user, ids,
                    minOf(snapshot.facts.validUntil, cache.expiresAt, background.expiresAt), basis)
                check(reportRequestCurrent(request, reports, days))
                ReportDelivery(ReportProtocol.parse(cache.interpretationJson, ids, basis), request)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { database.reportDao().deleteCache(cache.slot); null }
        } } }
    }

    /** One explicit report request uses the same gate and ledger as phrases and conversations. */
    internal suspend fun generateReport(facts: LocalReportSnapshot, reports: ReportRepository,
        automatic: AutomaticReportTicket? = null): ReportDelivery = withContext(Dispatchers.IO) {
        if (!requestGate.tryLock()) throw AiRequestException("已有请求进行中，本次报告未发送；可稍后再试。", "REPORT_BUSY_NOT_SENT")
        val job = currentCoroutineContext()[Job]
        var record: AiRequestRecord? = null
        var tokens = 0
        var stage = ReportRequestStage.PREPARING
        var reportedFailure: AiRequestException? = null
        try {
            val system = reportPrompt().also { check(it.isNotBlank() && it.length <= 10_000) }
            val days = retentionDays()
            // DataStore read outside the Room transaction. If consent changes meanwhile, the inner snapshot rejects or omits it.
            val rules = if (database.reportDao().config()?.useSettings == true) readRuleState() else null
            val (request, key) = local.withLock { guard.readStable { database.withTransaction {
                check(!suspendedForReset && reports.isCurrentInTransaction(facts))
                if (automatic != null) check(automaticReady() && AutomaticReportPolicy.current(database.automaticReportDao().state(), automatic) &&
                    AutomaticReportPolicy.matches(automatic, facts.facts)) { "自动任务日期或授权已变化" }
                check(facts.retentionDays == app.pausecn.data.sanitizeHistoryRetentionDays(days))
                val config = dao.config() ?: error("请先启用AI")
                val consent = database.reportDao().config() ?: ReportConfig()
                check(config.enabled && consent.enabled) { "请先启用AI与报告授权" }
                check(!consent.useUsage || facts.systemUsagePermission) { "系统使用授权已撤销，请先关闭时长发送或重新授权" }
                check(facts.facts.counts.recorded > 0 || (consent.useUsage && facts.facts.foregroundMs != null)) { "本期没有可供AI解读的事实" }
                val now = System.currentTimeMillis()
                val background = reportBackground(database, facts.facts, consent, now, days)
                val basis = rules.takeIf { consent.useSettings }
                val (user, ids) = ReportProtocol.input(facts.facts, consent, config.style, background, basis)
                check(system.length + user.length <= 12_000) { "背景过长，请减少可选背景后生成" }
                val cacheDeadline = database.reportDao().cached(facts.facts.window.slot)?.expiresAt ?: 0L
                val prepared = ReportRequestSnapshot(facts, consent, config, background, user, ids,
                    minOf(facts.facts.validUntil, background.expiresAt, cacheDeadline), basis, automatic)
                check(now < prepared.expiresAt)
                val credential = credentials.read()
                record = AiRequestRecord(day = LocalDate.now().toString(), startedAt = now)
                dao.reserve(requireNotNull(record)); dao.pruneRequests(now - 30L * 86_400_000)
                activeRequest = job; mutableState.value = state.value.copy(busy = true)
                activeAutomaticClaim = automatic?.claimId
                prepared to credential
            } } }
            currentCoroutineContext().ensureActive()
            stage = ReportRequestStage.REQUESTING
            val response = transport.complete(key, request.ai.model, system, request.userJson)
            tokens = response.totalTokens
            stage = ReportRequestStage.PARSING
            val parsed = ReportProtocol.parse(response.content, request.sourceIds, request.ruleBasis)
            stage = ReportRequestStage.REVALIDATING
            val currentDays = retentionDays()
            local.withLock { guard.readStable { database.withTransaction {
                currentCoroutineContext().ensureActive()
                if (!reportRequestCurrent(request, reports, currentDays)) throw ReportSourceChangedException()
                stage = ReportRequestStage.SAVING
                if (database.reportDao().saveInterpretation(request.local.facts.window.slot, request.local.facts.fingerprint,
                    ReportCacheCodec.interpretation(parsed), ReportCacheCodec.aiTag(request.ai), request.consent.epoch,
                    ReportCacheCodec.hash(request.background.json), request.expiresAt,
                    request.ruleBasis?.let(RuleSuggestionProtocol::encodeBasis).orEmpty()) != 1) throw ReportSourceChangedException()
                dao.finish(requireNotNull(record).id, "SUCCEEDED", tokens)
            } } }
            ReportDelivery(parsed, request)
        } catch (cancelled: CancellationException) {
            // Reserved UNKNOWN stays in the shared ledger. A cancellation is not proof of no charge.
            throw cancelled
        } catch (error: Exception) {
            val failure = reportFailure(stage, error, record != null)
            reportedFailure = AiRequestException(failure.message, failure.code)
            // Ledger failures must not mask the original safe diagnostic. No response text is persisted.
            withContext(NonCancellable) { local.withLock {
                try { record?.let { dao.finish(it.id, failure.code, tokens) } } catch (_: Exception) { }
            } }
            throw requireNotNull(reportedFailure)
        } finally {
            try { withContext(NonCancellable) { local.withLock {
                if (activeRequest === job) { activeRequest = null; activeAutomaticClaim = null }
                mutableState.value = state.value.copy(busy = false)
                try { refreshLocked() } catch (cancelled: CancellationException) {
                    if (reportedFailure == null) throw cancelled
                }
                catch (_: Exception) {
                    if (reportedFailure == null && job?.isCancelled != true) throw AiRequestException(
                        "报告处理后的本地状态刷新失败，请先重新打开报告查看，不要立即重试。\n诊断码：REPORT_STATE_REFRESH_FAILED", "REPORT_STATE_REFRESH_FAILED")
                }
            } } } finally { requestGate.unlock() }
        }
    }

    /** User-confirmed patch only. Source mutation is fenced across the short local settings commit, never a network call. */
    suspend fun setAutomaticReports(enabled: Boolean) = withContext(Dispatchers.IO) {
        local.withLock { guard.mutate { database.withTransaction {
            check(!suspendedForReset)
            if (enabled) check(dao.config()?.enabled == true && database.reportDao().config()?.enabled == true && credentials.hasKey()) {
                "请先启用AI、保存Key并开启报告发送授权"
            }
            if (!enabled && activeAutomaticClaim != null) activeRequest?.cancel()
            val previous = database.automaticReportDao().state() ?: AutomaticReportState()
            database.automaticReportDao().save(previous.copy(enabled = enabled, epoch = previous.epoch + 1))
        } } }
    }

    /** Called only by the opt-in Android job. Claim persists BEFORE local report loading or HTTP admission. */
    internal suspend fun runAutomaticYesterday(reports: ReportRepository) = withContext(Dispatchers.IO) {
        val ticket = local.withLock { guard.readStable { database.withTransaction {
            if (!automaticReady() || suspendedForReset || dao.config()?.enabled != true || database.reportDao().config()?.enabled != true) return@withTransaction null
            val current = database.automaticReportDao().state() ?: return@withTransaction null
            val claimed = AutomaticReportPolicy.claim(current, System.currentTimeMillis(), java.time.ZoneId.systemDefault(), java.util.UUID.randomUUID().toString())
                ?: return@withTransaction null
            database.automaticReportDao().save(claimed.first)
            claimed.second
        } } } ?: return@withContext
        var outcome = "FAILED_OR_UNKNOWN"
        var stage = AutomaticReportStage.PREPARING_FACTS
        try {
            prepareAutomaticFacts()
            // Yesterday must be a closed, fresh local snapshot, not yesterday's still-partial TODAY cache.
            stage = AutomaticReportStage.LOADING_FACTS
            val (snapshot, _) = reports.load(ReportPeriod.YESTERDAY, retentionDays(), force = true)
            stage = AutomaticReportStage.VALIDATING_FACTS
            check(AutomaticReportPolicy.matches(ticket, snapshot.facts))
            stage = AutomaticReportStage.GENERATING
            generateReport(snapshot, reports, ticket)
            outcome = "SUCCEEDED"
        } catch (cancelled: CancellationException) { outcome = "CANCELLED_OR_UNKNOWN"; throw cancelled }
        catch (error: Exception) {
            outcome = AutomaticReportDiagnostic.failure(stage, error)
            // Fixed diagnostic only. All failures still consume this date, without a retry.
        }
        finally { withContext(NonCancellable) {
            database.automaticReportDao().finish(ticket.claimId, outcome, System.currentTimeMillis())
        } }
    }

    internal suspend fun reportProposalCurrent(delivery: ReportDelivery, reports: ReportRepository): Boolean {
        val days = retentionDays()
        return local.withLock { guard.readStable { database.withTransaction { proposalCurrentInTransaction(delivery, reports, days) } } }
    }

    private suspend fun proposalCurrentInTransaction(delivery: ReportDelivery, reports: ReportRepository, days: Int): Boolean {
        val basis = delivery.request.ruleBasis ?: return false
        if (!delivery.request.consent.useSettings || delivery.result.rulePatch == null || !reportRequestCurrent(delivery.request, reports, days)) return false
        val row = database.reportDao().cached(delivery.request.local.facts.window.slot) ?: return false
        return row.expiresAt > System.currentTimeMillis() && row.ruleBasisJson == RuleSuggestionProtocol.encodeBasis(basis) &&
            ReportProtocol.parse(row.interpretationJson, delivery.request.sourceIds, basis) == delivery.result
    }

    internal suspend fun applyReportRuleProposal(delivery: ReportDelivery, reports: ReportRepository,
        store: app.pausecn.data.SettingsStore, expected: app.pausecn.data.RuleState, patch: app.pausecn.data.RulePatch) {
        val days = retentionDays()
        local.withLock { guard.readStable {
            val expires = database.withTransaction {
                check(proposalCurrentInTransaction(delivery, reports, days)) { "报告建议已失效" }
                database.reportDao().cached(delivery.request.local.facts.window.slot)?.expiresAt ?: 0L
            }
            // No Room transaction held during DataStore IO. CAS also runs within the actual DataStore edit.
            store.applyRuleChange(expected, patch) {
                System.currentTimeMillis() < minOf(expires, delivery.request.expiresAt) && reports.quickContextCurrent(delivery.request.local)
            }
        } }
    }

    /** Must precede old-data reset. Remains fail-closed if subsequent reset fails. */
    suspend fun beginReset() = withContext(Dispatchers.IO) {
        local.withLock {
            suspendedForReset = true
            activeRequest?.cancel()
            mutableState.value = AiUiState()
            selector.clear()
            val old = configLocked()
            mutatePersonalLocked { dao.save(old.copy(enabled = false, privacyEpoch = old.privacyEpoch + 1)) }
        }
    }

    suspend fun finishReset() = withContext(Dispatchers.IO) {
        local.withLock {
            check(suspendedForReset) { "必须先停用 AI 才能完成重置" }
            credentials.clear()
            val fresh = AiConfig()
            database.withTransaction {
                dao.clearPhrases(); dao.clearRequests(); dao.deleteProfile(); dao.clearPersonalized()
                dao.clearPersonalizationConfig(); dao.save(fresh)
                database.conversationDao().apply { clearMessages(); clearMemories(); clearFeedback(); clearConfig() }
                database.reportDao().clearConfig()
                database.reportDao().clearCache()
                database.automaticReportDao().clear()
            }
            check(!credentials.hasKey() && dao.phrases().isEmpty() && dao.requestCount() == 0 &&
                dao.profile() == null && dao.personalizedPhrases().isEmpty() && dao.personalization() == null && dao.config() == fresh)
            check(database.conversationDao().run { memoryCount() == 0 && messageCount() == 0 && feedbackCount() == 0 && config() == null })
            check(database.reportDao().config() == null)
            check(database.automaticReportDao().state() == null)
            suspendedForReset = false
            refreshLocked()
        }
    }

    suspend fun clearGeneratedHistory() = withContext(Dispatchers.IO) {
        local.withLock {
            activeRequest?.cancel()
            val old = configLocked()
            mutatePersonalLocked {
                dao.save(old.copy(privacyEpoch = old.privacyEpoch + 1))
                dao.clearPhrases()
                database.conversationDao().apply { clearMessages(); clearDerivedMemories(); clearFeedback() }
            }
            selector.clear()
            refreshLocked()
        }
    }
}
