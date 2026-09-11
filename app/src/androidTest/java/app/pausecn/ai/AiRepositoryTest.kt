package app.pausecn.ai

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pausecn.data.PauseDatabase
import app.pausecn.data.PauseRepository
import app.pausecn.data.TargetRuleEntity
import app.pausecn.data.InterventionEventEntity
import app.pausecn.data.InterventionOutcome
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiRepositoryTest {
    private lateinit var database: PauseDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            PauseDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun missingKeyAndDisabledAiNeverCallTransport() = runBlocking {
        val credentials = FakeCredentials()
        val transport = ScriptedTransport { validCompletion() }
        val repository = AiRepository(database, credentials, transport, ::realPhrasePrompt)

        database.aiDao().save(AiConfig(enabled = true))
        repository.load()
        assertEquals("请先填写 Key", repository.generatePhrases())

        credentials.key = "test-key"
        database.aiDao().save(requireNotNull(database.aiDao().config()).copy(enabled = false))
        repository.load()
        assertEquals("请先启用 AI", repository.generatePhrases())
        assertEquals(0, transport.calls.get())
    }

    @Test
    fun validTwelvePhrasesRemainCandidatesUntilApproved() = runBlocking {
        val transport = ScriptedTransport { validCompletion(tokens = 42) }
        val repository = enabledRepository(transport)

        assertEquals(
            "已生成12条候选短句。请先检查全部内容，确认后才用于真实停顿。",
            repository.generatePhrases(),
        )
        assertEquals(12, repository.state.value.phrases.size)
        assertTrue(repository.state.value.phrases.none(AiPhrase::approved))
        assertEquals(PromptSelector.DEFAULT, repository.choose(TARGET, 1_000, 1_000).text)

        repository.approvePhrases()

        val approvedChoice = repository.choose(TARGET, 2_000, 2_000)
        assertNotEquals(PromptSelector.DEFAULT, approvedChoice.text)
        assertTrue(approvedChoice.id != null)
        assertEquals(42, repository.state.value.tokens)
        assertEquals(1, transport.calls.get())
        assertEquals(listOf(realPhrasePrompt()), transport.systemPrompts)
    }

    @Test
    fun partialCandidatesStillRequireApprovalAndMissingSceneUsesFallback() = runBlocking {
        val partial = JSONObject().put(
            "phrases",
            JSONArray()
                .put(JSONObject().put("scene", PromptScene.ORDINARY.name).put("text", "把注意力放回眼前这一步。"))
                .put(JSONObject().put("scene", PromptScene.GOAL.name).put("text", "请看 https://example.com")),
        ).toString()
        val repository = enabledRepository(ScriptedTransport { AiCompletion(partial, 11) })

        val result = repository.generatePhrases()

        assertTrue(result.startsWith("已生成1条候选短句。另有1条未采用。"))
        assertEquals(1, repository.state.value.phrases.size)
        assertTrue(repository.state.value.phrases.none(AiPhrase::approved))
        assertEquals(PromptSelector.DEFAULT, repository.choose(TARGET, 1_000, 1_000).text)

        repository.approvePhrases()
        assertEquals("把注意力放回眼前这一步。", repository.choose(TARGET, 2_000, 2_000).text)
        repository.selector.complete(TARGET, continued = true, wallMs = 3_000, elapsedMs = 3_000)
        repository.selector.complete(TARGET, continued = true, wallMs = 4_000, elapsedMs = 4_000)
        assertEquals(
            PromptSelector.fallback(PromptScene.REPEATED),
            repository.choose(TARGET, 5_000, 5_000).text,
        )
    }

    @Test
    fun authenticationFormatAndNetworkFailuresKeepFallbackWithoutAutomaticRetry() = runBlocking {
        val failures = ArrayDeque<() -> AiCompletion>().apply {
            add { throw AiRequestException("请检查 Key、账户权限或余额；不会自动重试。") }
            add { AiCompletion("{\"phrases\":[]}", 7) }
            add { throw AiRequestException("网络或返回格式异常；可能已计费，请自行决定是否重试。") }
        }
        val transport = ScriptedTransport { failures.removeFirst().invoke() }
        val repository = enabledRepository(transport)

        assertEquals("请检查 Key、账户权限或余额；不会自动重试。", repository.generatePhrases())
        assertEquals(1, transport.calls.get())
        assertEquals(PromptSelector.DEFAULT, repository.choose(TARGET, 1_000, 1_000).text)

        assertEquals(
            PhraseValidationException(PhraseProblem.BATCH_SIZE).publicMessage,
            repository.generatePhrases(),
        )
        assertEquals(2, transport.calls.get())
        assertEquals(PromptSelector.DEFAULT, repository.choose(TARGET, 2_000, 2_000).text)

        assertEquals("网络或返回格式异常；可能已计费，请自行决定是否重试。", repository.generatePhrases())
        assertEquals(3, transport.calls.get())
        assertEquals(PromptSelector.DEFAULT, repository.choose(TARGET, 3_000, 3_000).text)
        assertTrue(database.aiDao().phrases().isEmpty())
    }

    @Test
    fun concurrentGenerateIsSingleFlight() = runBlocking {
        val transport = BlockingTransport(validCompletion())
        val repository = enabledRepository(transport)
        val first = async(Dispatchers.Default) { repository.generatePhrases() }
        try {
            assertTrue(transport.entered.await(5, TimeUnit.SECONDS))
            assertEquals("已有一个请求进行中；没有重复发送。", repository.generatePhrases())
            assertEquals(1, transport.calls.get())
        } finally {
            transport.release.countDown()
        }
        assertEquals(
            "已生成12条候选短句。请先检查全部内容，确认后才用于真实停顿。",
            withTimeout(5_000) { first.await() },
        )
    }

    @Test
    fun disablingAiDropsAResponseFromTransportThatIgnoresCancellation() = runBlocking {
        val transport = BlockingTransport(validCompletion())
        val repository = enabledRepository(transport)
        val request = async(Dispatchers.Default) { repository.generatePhrases() }
        try {
            assertTrue(transport.entered.await(5, TimeUnit.SECONDS))
            repository.setEnabled(false)
        } finally {
            transport.release.countDown()
        }
        assertCancelled(request)

        assertFalse(requireNotNull(database.aiDao().config()).enabled)
        assertTrue(database.aiDao().phrases().isEmpty())
    }

    @Test
    fun fullResetDropsAResponseAndClearsCredentialsPhrasesAndLedger() = runBlocking {
        val transport = BlockingTransport(validCompletion())
        val credentials = FakeCredentials("test-key")
        val repository = AiRepository(database, credentials, transport, ::realPhrasePrompt)
        repository.load()
        repository.setEnabled(true)
        val oldInstanceId = repository.state.value.config.instanceId
        val request = async(Dispatchers.Default) { repository.generatePhrases() }
        try {
            assertTrue(transport.entered.await(5, TimeUnit.SECONDS))
            repository.beginReset()
            repository.finishReset()
        } finally {
            transport.release.countDown()
        }
        assertCancelled(request)

        val resetConfig = requireNotNull(database.aiDao().config())
        assertFalse(resetConfig.enabled)
        assertNotEquals(oldInstanceId, resetConfig.instanceId)
        assertFalse(credentials.hasKey())
        assertEquals(0, database.aiDao().requestCount())
        assertTrue(database.aiDao().phrases().isEmpty())
    }

    @Test
    fun twentyFirstAttemptCanGenerateAndIsStillCounted() = runBlocking {
        val dao = database.aiDao()
        dao.save(AiConfig(enabled = true))
        repeat(20) { index ->
            dao.reserve(
                AiRequestRecord(
                    id = "attempt-$index",
                    day = LocalDate.now().toString(),
                    startedAt = System.currentTimeMillis() - index,
                    status = "FAILED_OR_UNKNOWN",
                ),
            )
        }
        val transport = ScriptedTransport { validCompletion() }
        val repository = AiRepository(database, FakeCredentials("test-key"), transport, ::realPhrasePrompt)
        repository.load()

        assertEquals(20, repository.state.value.attempts)
        assertEquals(
            "已生成12条候选短句。请先检查全部内容，确认后才用于真实停顿。",
            repository.generatePhrases(),
        )
        assertEquals(1, transport.calls.get())
        assertEquals(21, dao.attempts(LocalDate.now().toString()))
    }

    @Test
    fun sameStyleRetryFailureKeepsExistingPhrases() = runBlocking {
        val responses = ArrayDeque<() -> AiCompletion>().apply {
            add { validCompletion(tokens = 9) }
            add { AiCompletion("{\"phrases\":[]}", 4) }
        }
        val transport = ScriptedTransport { responses.removeFirst().invoke() }
        val repository = enabledRepository(transport)

        repository.generatePhrases()
        repository.approvePhrases()
        val before = database.aiDao().phrases()

        assertEquals(
            PhraseValidationException(PhraseProblem.BATCH_SIZE).publicMessage,
            repository.generatePhrases(),
        )
        assertEquals(before, database.aiDao().phrases())
        assertTrue(repository.state.value.phrases.all(AiPhrase::approved))
        assertEquals(2, transport.calls.get())
    }

    @Test
    fun profilePersistsLocallyWithStableRevisionAndObeysDeleteAndResetBoundaries() = runBlocking {
        val credentials = FakeCredentials("test-key")
        val transport = ScriptedTransport { validCompletion() }
        val repository = AiRepository(database, credentials, transport, ::realPhrasePrompt)
        repository.load()
        repository.setEnabled(true)
        val configBeforeProfile = repository.state.value.config
        val existingPhrase = AiPhrase(
            id = "existing-profile-independent-phrase",
            scene = PromptScene.ORDINARY.name,
            text = "已有通用短句",
            styleVersion = configBeforeProfile.styleVersion,
            approved = true,
        )
        database.aiDao().insertPhrases(listOf(existingPhrase))

        repository.saveProfile("  完成长篇写作\r\n保持专注  ", "  提醒简短  ")
        val first = requireNotNull(repository.state.value.profile)
        assertEquals("完成长篇写作\n保持专注", first.goal)
        assertEquals("提醒简短", first.preferences)
        assertEquals(configBeforeProfile, repository.state.value.config)
        assertTrue(repository.state.value.config.enabled)
        assertEquals(listOf(existingPhrase), repository.state.value.phrases)
        assertEquals(0, transport.calls.get())

        val reloaded = AiRepository(database, credentials, transport, ::realPhrasePrompt)
        reloaded.load()
        assertEquals(first, reloaded.state.value.profile)

        reloaded.saveProfile("完成长篇写作\n保持专注", "提醒简短")
        assertEquals(first.revision, requireNotNull(reloaded.state.value.profile).revision)
        reloaded.saveProfile(first.goal, "只在必要时提醒")
        val updatedRevision = requireNotNull(reloaded.state.value.profile).revision
        assertNotEquals(first.revision, updatedRevision)
        assertEquals(0, transport.calls.get())

        reloaded.generatePhrases()
        val sentUser = JSONObject(transport.userPrompts.single())
        assertEquals(setOf("style"), sentUser.keys().asSequence().toSet())
        assertFalse(transport.userPrompts.single().contains(first.goal))
        assertFalse(transport.userPrompts.single().contains("只在必要时提醒"))
        reloaded.clearGeneratedHistory()
        assertNotNull(reloaded.state.value.profile)

        reloaded.deleteProfile()
        assertEquals(null, reloaded.state.value.profile)
        reloaded.saveProfile("重置前目标", "")
        assertNotEquals(updatedRevision, requireNotNull(reloaded.state.value.profile).revision)
        reloaded.beginReset()
        reloaded.finishReset()
        assertEquals(null, reloaded.state.value.profile)
        assertEquals(null, database.aiDao().profile())
    }

    @Test
    fun personalizationRequiresExplicitConsentAndOmitsUnauthorizedProfileAndReasons() = runBlocking {
        database.targetRuleDao().upsert(TargetRuleEntity(TARGET, "虚构目标"))
        database.interventionEventDao().insert(
            InterventionEventEntity(packageName = TARGET, appLabel = "虚构目标", occurredAtEpochMs = System.currentTimeMillis(),
                outcome = InterventionOutcome.CONTINUED.name, purpose = "未授权理由"),
        )
        val transport = ScriptedTransport { personalizedCompletion() }
        val repository = AiRepository(database, FakeCredentials("test-key"), transport,
            ::realPhrasePrompt, ::realPersonalizedPrompt)
        repository.load()
        repository.setEnabled(true)
        repository.saveProfile("未授权画像目标", "未授权偏好")

        assertTrue(repository.prepareNext(TARGET, stable = false).startsWith("未准备"))
        assertEquals(0, transport.calls.get())

        repository.setPersonalization(enabled = true, useProfile = false, useReasons = false)
        assertEquals("已加入准备队列，不会延长停顿。", repository.prepareNext(TARGET, stable = false))
        waitUntil { database.aiDao().personalizedPhrases().isNotEmpty() }

        assertEquals(realPersonalizedPrompt(), transport.systemPrompts.single())
        val sent = JSONObject(transport.userPrompts.single())
        val sources = sent.getJSONArray("sources")
        assertEquals(1, sources.length())
        assertEquals("recent", sources.getJSONObject(0).getString("id"))
        assertEquals(1, sources.getJSONObject(0).getInt("continued"))
        assertFalse(transport.userPrompts.single().contains("未授权画像目标"))
        assertFalse(transport.userPrompts.single().contains("未授权理由"))
        assertFalse(transport.userPrompts.single().contains("reason_"))
        assertFalse(transport.userPrompts.single().contains(TARGET))
    }

    @Test
    fun preparedPhraseIsAppIsolatedPreviewSafeAndConsumedOnlyWhenShown() = runBlocking {
        database.targetRuleDao().upsert(TargetRuleEntity(TARGET, "目标甲"))
        database.targetRuleDao().upsert(TargetRuleEntity("other.target", "目标乙"))
        val transport = ScriptedTransport { personalizedCompletion() }
        val repository = AiRepository(database, FakeCredentials("test-key"), transport,
            ::realPhrasePrompt, ::realPersonalizedPrompt)
        repository.load()
        repository.setEnabled(true)
        repository.setPersonalization(enabled = true, useProfile = false, useReasons = false)
        val eventId = database.interventionEventDao().insert(
            InterventionEventEntity(
                packageName = TARGET,
                appLabel = "目标甲",
                occurredAtEpochMs = System.currentTimeMillis(),
                outcome = InterventionOutcome.CONTINUED.name,
                purpose = "只查一项资料",
            ),
        )
        repository.onCompletedIntervention(eventId)
        waitUntil { database.aiDao().personalizedPhrases().isNotEmpty() }
        val cachedBeforeMaintenance = database.aiDao().personalizedPhrases()
        assertTrue(cachedBeforeMaintenance.all { it.kind == PersonalizedCachePolicy.STABLE })
        val stablePayload = JSONObject(transport.userPrompts.single())
        assertEquals(PersonalizedCachePolicy.STABLE, stablePayload.getString("kind"))
        assertEquals(0, stablePayload.getJSONArray("sources").length())
        assertFalse(transport.userPrompts.single().contains("recent"))
        assertFalse(transport.userPrompts.single().contains("reason_"))
        val prune = PauseRepository(ApplicationProvider.getApplicationContext(), database).pruneHistory()
        assertEquals(0, prune.expiredRows)
        assertEquals(0, prune.overflowRows)
        assertEquals(cachedBeforeMaintenance, database.aiDao().personalizedPhrases())
        repository.load()

        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val preview = repository.choose(TARGET, wall, elapsed)
        assertTrue(preview.id.orEmpty().startsWith("personal:"))
        assertEquals(preview, repository.choose(TARGET, wall + 1, elapsed + 1))
        assertFalse(repository.choose("other.target", wall, elapsed).id.orEmpty().startsWith("personal:"))

        repository.shown(TARGET, preview, wall + 2, elapsed + 2)
        val consumedId = preview.id!!.removePrefix("personal:")
        waitUntil { database.aiDao().personalizedPhrases().single { it.id == consumedId }.consumed }
        assertNotEquals(preview.id, repository.choose(TARGET, wall + 3, elapsed + 3).id)
    }

    @Test
    fun forgettingAuthorizedReasonWhileRequestIsInFlightPreventsOldResponseWriteback() = runBlocking {
        database.targetRuleDao().upsert(TargetRuleEntity(TARGET, "目标甲"))
        val reason = "只查一项资料"
        database.interventionEventDao().insert(
            InterventionEventEntity(packageName = TARGET, appLabel = "目标甲", occurredAtEpochMs = System.currentTimeMillis(),
                outcome = InterventionOutcome.CONTINUED.name, purpose = reason),
        )
        val transport = BlockingTransport(personalizedCompletion(setOf("reason_0")))
        val repository = AiRepository(database, FakeCredentials("test-key"), transport,
            ::realPhrasePrompt, ::realPersonalizedPrompt)
        repository.load()
        repository.setEnabled(true)
        repository.setPersonalization(enabled = true, useProfile = false, useReasons = true)
        repository.prepareNext(TARGET, stable = false)
        assertTrue(transport.entered.await(5, TimeUnit.SECONDS))

        PauseRepository(ApplicationProvider.getApplicationContext(), database).forgetReason(TARGET, reason)
        transport.release.countDown()
        waitUntil { !repository.state.value.busy }

        assertTrue(database.aiDao().personalizedPhrases().isEmpty())
    }

    private suspend fun enabledRepository(transport: AiTransport): AiRepository {
        val repository = AiRepository(database, FakeCredentials("test-key"), transport, ::realPhrasePrompt)
        repository.load()
        repository.setEnabled(true)
        return repository
    }

    private fun realPhrasePrompt(): String =
        ApplicationProvider.getApplicationContext<Context>().assets
            .open(PhraseProtocol.PROMPT_ASSET)
            .bufferedReader()
            .use { it.readText() }

    private fun realPersonalizedPrompt(): String =
        ApplicationProvider.getApplicationContext<Context>().assets
            .open(PersonalizationProtocol.PROMPT_ASSET)
            .bufferedReader()
            .use { it.readText() }

    private suspend fun waitUntil(condition: suspend () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }

    private suspend fun assertCancelled(request: kotlinx.coroutines.Deferred<String>) {
        var cancelled = false
        try {
            withTimeout(5_000) { request.await() }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue("The invalidated request must be cancelled", cancelled)
        assertTrue("The underlying request job must be cancelled", request.isCancelled)
    }

    private class FakeCredentials(var key: String? = null) : ApiCredentialStore {
        override fun hasKey() = key != null
        override fun read() = requireNotNull(key)
        override fun save(value: String) { key = value }
        override fun clear() { key = null }
    }

    private class ScriptedTransport(
        private val response: () -> AiCompletion,
    ) : AiTransport {
        val calls = AtomicInteger()
        val systemPrompts = mutableListOf<String>()
        val userPrompts = mutableListOf<String>()

        override suspend fun complete(key: String, model: String, system: String, user: String): AiCompletion {
            calls.incrementAndGet()
            synchronized(systemPrompts) { systemPrompts += system }
            synchronized(userPrompts) { userPrompts += user }
            return response()
        }
    }

    /** Deliberately blocks a worker thread and ignores cancellation until the test releases it. */
    private class BlockingTransport(
        private val response: AiCompletion,
    ) : AiTransport {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override suspend fun complete(key: String, model: String, system: String, user: String): AiCompletion {
            calls.incrementAndGet()
            entered.countDown()
            while (true) {
                try {
                    release.await()
                    return response
                } catch (_: InterruptedException) {
                    // Intentionally non-cooperative: repository invalidation must still prevent write-back.
                }
            }
        }
    }

    companion object {
        private const val TARGET = "example.target"

        private fun validCompletion(tokens: Int = 0): AiCompletion {
            val labels = mapOf(
                PromptScene.ORDINARY to "普通",
                PromptScene.REPEATED to "重复",
                PromptScene.GOAL to "目标",
                PromptScene.CONTEXT to "情境",
                PromptScene.PURPOSE to "用途",
                PromptScene.FEEDBACK to "反馈",
            )
            val phrases = JSONArray()
            PromptScene.entries.forEach { scene ->
                listOf("甲", "乙").forEach { suffix ->
                    phrases.put(
                        JSONObject()
                            .put("scene", scene.name)
                            .put("text", "可以${labels.getValue(scene)}${suffix}吗？"),
                    )
                }
            }
            return AiCompletion(JSONObject().put("phrases", phrases).toString(), tokens)
        }

        private fun personalizedCompletion(sourceIds: Set<String> = emptySet()): AiCompletion {
            val phrases = JSONArray()
            listOf(PromptScene.ORDINARY, PromptScene.REPEATED).forEach { scene ->
                listOf("甲", "乙").forEach { suffix -> phrases.put(
                    JSONObject().put("scene", scene.name).put("text", "个性提醒$suffix-${scene.name}"),
                ) }
            }
            return AiCompletion(JSONObject().put("source_ids", JSONArray(sourceIds.toList()))
                .put("phrases", phrases).toString(), 5)
        }
    }
}
