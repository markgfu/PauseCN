package app.pausecn.data

import app.pausecn.ai.*
import org.json.JSONArray
import org.json.JSONObject

/** Each private category requires an explicit choice. Credentials and internal request context have no export field. */
data class LocalExportOptions(val profile: Boolean = false, val memories: Boolean = false,
    val conversations: Boolean = false, val feedback: Boolean = false, val generated: Boolean = false,
    val usage: Boolean = false, val reports: Boolean = false, val reportInterpretations: Boolean = false,
    val appCategories: Boolean = false) {
    val anyAi: Boolean get() = profile || memories || conversations || feedback || generated
    fun categories(): List<String> = buildList {
        add("base")
        if (profile) add("profile")
        if (memories) add("memories")
        if (conversations) add("conversations")
        if (feedback) add("feedback")
        if (generated) add("generated")
        if (usage) add("usage")
        if (reports) add("reports")
        if (reports && reportInterpretations) add("report_interpretations")
        if (appCategories) add("app_categories")
    }
}

data class ExportedPhrase(val text: String, val scene: String, val approved: Boolean,
    val scopePackage: String = "", val createdAt: Long? = null, val expiresAt: Long? = null)

data class AiExportData(val profile: UserProfile? = null, val style: String = "", val manualPhrase: String = "",
    val memories: List<UserMemory> = emptyList(), val messages: List<ConversationMessage> = emptyList(),
    val feedback: List<PhraseFeedback> = emptyList(), val phrases: List<ExportedPhrase> = emptyList()) {
    fun validUntil(): Long = minOf(memories.minOfOrNull { it.expiresAt } ?: Long.MAX_VALUE,
        messages.minOfOrNull { it.expiresAt } ?: Long.MAX_VALUE,
        feedback.minOfOrNull { it.expiresAt } ?: Long.MAX_VALUE,
        phrases.mapNotNull { it.expiresAt }.minOrNull() ?: Long.MAX_VALUE)
}

internal data class ExportStamp(val instanceId: String, val privacyEpoch: Long, val personalEpoch: Long,
    val conversationEpoch: Long, val usageRevision: String? = null, val categoryRevision: Long = 0)

/** Caller holds the same Room transaction as the base snapshot. No credential-store dependency. */
internal suspend fun readAiExport(database: PauseDatabase, options: LocalExportOptions, now: Long, days: Int): AiExportData? {
    if (!options.anyAi) return null
    val dao = database.aiDao()
    val conversation = database.conversationDao()
    val retentionMs = days.coerceIn(1, 30) * 86_400_000L
    val config = if (options.profile || options.generated) dao.config() else null
    val messages = if (options.conversations) conversation.messages(now, retentionMs).filter { options.generated || it.role == "USER" } else emptyList()
    val phrases = if (options.generated) dao.phrases().filter { it.styleVersion == config?.styleVersion }.map {
        ExportedPhrase(it.text, it.scene, it.approved)
    } + dao.personalizedPhrases().filter { it.expiresAt > now }.map {
        ExportedPhrase(it.text, it.scene, true, it.packageName, it.createdAt, it.expiresAt)
    } else emptyList()
    return AiExportData(if (options.profile) dao.profile() else null,
        if (options.profile) config?.style.orEmpty() else "", if (options.profile) config?.manualPhrase.orEmpty() else "",
        if (options.memories) conversation.memories(now, retentionMs) else emptyList(), messages,
        if (options.feedback) conversation.feedback(now, retentionMs) else emptyList(), phrases)
}

/** Defend at serialization too: a caller passing populated unselected fields cannot bypass the UI choice. */
internal fun encodeAiExport(data: AiExportData, options: LocalExportOptions): String = JSONObject().apply {
    if (options.profile) put("profile", JSONObject().put("goal", data.profile?.goal.orEmpty())
        .put("preferences", data.profile?.preferences.orEmpty()).put("style", data.style).put("manualPhrase", data.manualPhrase))
    if (options.memories) put("memories", JSONArray().apply { data.memories.forEach { memory ->
        put(JSONObject().put("id", memory.id).put("scopePackage", memory.scopePackage).put("text", memory.text)
            .put("kind", memory.kind).put("confirmed", memory.confirmed).put("independent", memory.independent)
            .put("createdAt", memory.createdAt).put("expiresAt", if (memory.independent) JSONObject.NULL else memory.expiresAt)
            .apply { if (options.conversations && data.messages.any { it.id == memory.sourceMessageId }) put("sourceMessageId", memory.sourceMessageId) })
    } })
    if (options.conversations) put("conversations", JSONArray().apply { data.messages.filter { options.generated || it.role == "USER" }.forEach {
        put(JSONObject().put("id", it.id).put("scopePackage", it.scopePackage).put("role", it.role).put("text", it.text)
            .put("createdAt", it.createdAt).put("expiresAt", it.expiresAt).put("aiEligible", it.aiEligible))
    } })
    if (options.feedback) put("feedback", JSONArray().apply { data.feedback.forEach {
        put(JSONObject().put("scopePackage", it.scopePackage).put("instruction", it.instruction)
            .put("phraseFingerprint", it.phraseFingerprint).put("createdAt", it.createdAt).put("expiresAt", it.expiresAt))
    } })
    if (options.generated) put("phrases", JSONArray().apply { data.phrases.forEach {
        put(JSONObject().put("scopePackage", it.scopePackage).put("scene", it.scene).put("text", it.text).put("approved", it.approved)
            .put("createdAt", it.createdAt ?: JSONObject.NULL).put("expiresAt", it.expiresAt ?: JSONObject.NULL))
    } })
}.toString()

internal suspend fun exportStamp(database: PauseDatabase, includeUsage: Boolean = false): ExportStamp {
    val config = database.aiDao().config()
    return ExportStamp(config?.instanceId.orEmpty(), config?.privacyEpoch ?: 0,
        database.aiDao().personalization()?.epoch ?: 0, database.conversationDao().config()?.epoch ?: 0,
        if (includeUsage) database.usageDao().config()?.revision.orEmpty() else null,
        database.appCategoryDao().settings()?.revision ?: 0)
}
