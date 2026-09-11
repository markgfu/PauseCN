package app.pausecn.ai

import app.pausecn.data.PauseDatabase
import app.pausecn.data.TargetRuleEntity
import app.pausecn.domain.ContinueReason
import org.json.JSONArray
import org.json.JSONObject

internal data class PersonalizationSnapshot(
    val config: AiConfig, val consent: PersonalizationConfig, val profileRevision: String,
    val target: TargetRuleEntity, val kind: String, val fingerprint: String,
    val createdAt: Long, val expiresAt: Long, val elapsedAt: Long,
    val sourceIds: Set<String>, val userJson: String,
)

/** Caller holds a short Room transaction. Never sends package identifiers or raw event rows. */
internal suspend fun personalizationSnapshot(database: PauseDatabase, pkg: String, stable: Boolean,
    now: Long, elapsed: Long, retentionDays: Int, scene: PromptScene): PersonalizationSnapshot? {
    val dao = database.aiDao()
    val config = dao.config() ?: return null
    val consent = dao.personalization() ?: return null
    if (!config.enabled || !consent.enabled || config.manualPhrase.isNotBlank()) return null
    val target = database.targetRuleDao().getAllForExport().firstOrNull { it.packageName == pkg && it.enabled } ?: return null
    val profile = if (consent.useProfile) dao.profile() else null
    val conversation = database.conversationDao().config() ?: ConversationConfig()
    val retentionMs = retentionDays.coerceIn(1, 30) * 86_400_000L
    val allMemories = database.conversationDao().memories(now, retentionMs)
    val profileRevision = ConversationPolicy.backgroundRevision(profile?.revision.orEmpty(), conversation, allMemories, pkg, now)
    val sources = JSONArray()
    val ids = linkedSetOf<String>()
    fun source(id: String, value: JSONObject) { ids += id; sources.put(value.put("id", id)) }
    val categories = app.pausecn.data.appCategorySnapshot(database)
    val category = categories.category(pkg).takeIf { categories.settings.sendToAi }
    category?.let { source("app_category", JSONObject().put("category", it)
        .put("meaning", "应用主分类，可能为自动建议或用户整理，不代表本次用途或行为判断")) }
    profile?.let {
        source("profile", JSONObject().put("goal", it.goal).put("preferences", it.preferences))
    }
    val kind = if (stable) PersonalizedCachePolicy.STABLE else PersonalizedCachePolicy.RECENT
    var expiry = if (stable) Long.MAX_VALUE else now + PersonalizedCachePolicy.RECENT_TTL_MS
    if (conversation.useMemories) {
        ConversationPolicy.relevant(allMemories, pkg, now)
            .filter { !stable || it.kind != "CONTEXT" }.forEachIndexed { index, memory ->
                source("memory_$index", JSONObject().put("text", memory.text).put("kind", memory.kind)
                    .put("meaning", "用户已确认的过去陈述，不是本次用途"))
                expiry = minOf(expiry, memory.expiresAt)
            }
        database.conversationDao().feedback(now, retentionMs).filter { it.scopePackage == pkg }.take(3).forEachIndexed { index, feedback ->
            source("feedback_$index", JSONObject().put("instruction", feedback.instruction))
            expiry = minOf(expiry, feedback.expiresAt,
                feedback.createdAt + retentionDays.coerceIn(1, 30) * 86_400_000L)
        }
    }
    val reasonTexts = mutableListOf<String>()
    if (!stable) {
        val days = retentionDays.coerceIn(1, 30)
        val recent = database.interventionEventDao().recentCompletedForAi(pkg,
            maxOf(target.createdAtEpochMs, now - PersonalizedCachePolicy.RECENT_TTL_MS), now)
        if (recent.isNotEmpty()) {
            source("recent", JSONObject().put("scope", "最近30分钟内最多20条已记录的完成停顿，不是所有启动")
                .put("continued", recent.count { it.outcome == "CONTINUED" })
                .put("exited", recent.count { it.outcome == "EXITED" }))
            expiry = minOf(expiry, recent.minOf { it.occurredAtEpochMs } + days * 86_400_000L)
        }
        if (consent.useReasons) {
            database.interventionEventDao().reasonsForAi(pkg,
                maxOf(target.createdAtEpochMs, now - days * 86_400_000L), now)
                .filter { ContinueReason.isValid(it.text) }.forEachIndexed { index, reason ->
                    reasonTexts += reason.text
                    source("reason_$index", JSONObject().put("text", reason.text).put("uses", reason.uses)
                        .put("meaning", "过去选择继续时的用户陈述，非下次目的"))
                    expiry = minOf(expiry, reason.firstUsedAt + days * 86_400_000L)
                }
        }
    }
    if (now >= expiry) return null
    val fingerprint = PersonalizationProtocol.fingerprint(config.instanceId, config.privacyEpoch.toString(),
        config.styleVersion.toString(), consent.epoch.toString(), profileRevision,
        target.createdAtEpochMs.toString(), target.label, category.orEmpty(), kind, if (stable) "" else scene.name,
        *reasonTexts.toTypedArray())
    return PersonalizationSnapshot(config, consent, profileRevision, target, kind, fingerprint,
        now, expiry, elapsed, ids, JSONObject().put("style", config.style).put("target_name", target.label)
            .put("kind", kind).put("next_scene", scene.name).put("sources", sources).toString())
}
