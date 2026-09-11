package app.pausecn.ai

import app.pausecn.data.PauseDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Mutations go through the same lock/epoch barrier as reminder generation. Never calls HTTP. */
class ConversationRepository internal constructor(
    private val database: PauseDatabase,
    private val ai: AiRepository,
    private val retentionDays: suspend () -> Int,
) {
    private val dao get() = database.conversationDao()

    suspend fun configure(enabled: Boolean, saveMessages: Boolean, useMemories: Boolean) = ai.changeConversation {
        val old = dao.config() ?: ConversationConfig()
        dao.saveConfig(old.copy(enabled = enabled, saveMessages = saveMessages, useMemories = useMemories,
            epoch = old.epoch + 1))
    }

    suspend fun confirm(id: String) = ai.changeConversation {
        val now = System.currentTimeMillis()
        val memory = dao.memories(now).firstOrNull { it.id == id } ?: error("候选已删除或过期")
        dao.saveMemory(memory.copy(confirmed = true, revision = UUID.randomUUID().toString()))
    }

    suspend fun forget(id: String, deleteSource: Boolean = false) = ai.changeConversation {
        val memory = dao.memory(id) ?: return@changeConversation
        dao.excludeSource(memory.sourceMessageId)
        dao.deleteSourceMemories(memory.sourceMessageId)
        dao.deleteMemory(id)
        if (deleteSource) dao.deleteMessage(memory.sourceMessageId)
    }

    suspend fun deleteMessage(id: String, includeIndependent: Boolean = false) = ai.changeConversation {
        dao.deleteSourceMemories(id, includeIndependent)
        dao.deleteMessage(id)
    }

    /** User explicitly changes retention; neither extraction nor confirmation silently does this. */
    suspend fun keepIndependently(id: String) = ai.changeConversation {
        val now = System.currentTimeMillis()
        val memory = dao.memories(now).firstOrNull { it.id == id } ?: error("记忆已删除或过期")
        check(memory.kind != "CONTEXT") { "仅今天的情境不能直接变成长期事实，请另行填写长期目标。" }
        if (!memory.independent) {
            check(dao.independentCount() < 50) { "最多保留50条独立偏好，请先整理已有条目。" }
            dao.saveMemory(memory.copy(confirmed = true, independent = true, expiresAt = Long.MAX_VALUE,
                revision = UUID.randomUUID().toString(), createdAt = now))
        }
    }

    /** Explicit user save, independent of AI extraction and of the automatic message-saving switch. */
    suspend fun remember(pkg: String, text: String, kind: String) {
        require(ConversationPolicy.textValid(text, ConversationPolicy.MEMORY_LIMIT) && kind in ConversationPolicy.KINDS)
        val days = retentionDays()
        ai.changeConversation {
            check(pkg.isEmpty() || database.targetRuleDao().getAllForExport().any { it.packageName == pkg && it.enabled })
            val now = System.currentTimeMillis()
            val expires = ConversationPolicy.expiresAt(now, days, kind)
            val source = ConversationMessage(sessionId = "explicit:${UUID.randomUUID()}", scopePackage = pkg,
                role = "USER", text = text.trim(), createdAt = now, expiresAt = expires)
            dao.insertMessage(source)
            dao.saveMemory(UserMemory(sourceMessageId = source.id, scopePackage = pkg, text = source.text,
                kind = kind, confirmed = true, createdAt = now, expiresAt = expires))
        }
    }

    suspend fun correct(id: String, text: String) {
        require(ConversationPolicy.textValid(text, ConversationPolicy.MEMORY_LIMIT))
        val days = retentionDays()
        ai.changeConversation {
            val now = System.currentTimeMillis()
            val old = dao.memories(now).firstOrNull { it.id == id } ?: error("记忆已删除或过期")
            dao.excludeSource(old.sourceMessageId)
            dao.deleteSourceMemories(old.sourceMessageId)
            dao.deleteMemory(old.id)
            val expires = ConversationPolicy.expiresAt(now, days, old.kind)
            val source = ConversationMessage(sessionId = "correction:${UUID.randomUUID()}",
                scopePackage = old.scopePackage, role = "USER", text = text.trim(), createdAt = now, expiresAt = expires)
            dao.insertMessage(source)
            dao.saveMemory(UserMemory(sourceMessageId = source.id, scopePackage = old.scopePackage,
                text = text.trim(), kind = old.kind, confirmed = true, createdAt = now,
                expiresAt = if (old.independent) Long.MAX_VALUE else expires, independent = old.independent))
        }
    }

    suspend fun rejectPhrase(pkg: String, phrase: String, instruction: String) {
        require(phrase.isNotBlank() && ConversationPolicy.textValid(instruction, ConversationPolicy.MEMORY_LIMIT))
        val days = retentionDays()
        ai.changeConversation {
            val now = System.currentTimeMillis()
            dao.insertFeedback(PhraseFeedback(scopePackage = pkg,
                phraseFingerprint = ConversationPolicy.phraseFingerprint(phrase), instruction = instruction.trim(),
                createdAt = now, expiresAt = ConversationPolicy.expiresAt(now, days)))
        }
    }

    suspend fun deleteFeedback(id: String) = ai.changeConversation { dao.deleteFeedback(id) }
}

internal data class ConversationSnapshot(
    val config: AiConfig, val consent: ConversationConfig, val source: ConversationMessage,
    val fingerprint: String, val expiresAt: Long, val userJson: String, val candidateSourceIds: Set<String>,
)

data class ConversationDelivery(val result: ConversationReply, val instanceId: String,
    val conversationEpoch: Long, val privacyEpoch: Long, val expiresAt: Long)

/** Called only within a short transaction. A page session cannot retrieve another session's chat. */
internal suspend fun conversationSnapshot(database: PauseDatabase, source: ConversationMessage,
    now: Long, retentionDays: Int): ConversationSnapshot? {
    val config = database.aiDao().config() ?: return null
    val dao = database.conversationDao()
    val consent = dao.config() ?: return null
    if (!config.enabled || !consent.enabled || now >= source.expiresAt) return null
    if (consent.saveMessages) {
        val saved = dao.message(source.id) ?: return null
        if (!saved.aiEligible || saved.text != source.text || saved.expiresAt <= now) return null
    }
    val target = database.targetRuleDao().getAllForExport()
        .firstOrNull { it.packageName == source.scopePackage && it.enabled }
    if (source.scopePackage.isNotEmpty() && target == null) return null
    val retentionMs = retentionDays.coerceIn(1, 30) * 86_400_000L
    val memories = if (consent.useMemories) ConversationPolicy.relevant(dao.memories(now, retentionMs), source.scopePackage, now) else emptyList()
    // Historical assistant replies may reflect forgotten memories. With memory use disabled send only this new message.
    val previous = if (consent.saveMessages && consent.useMemories) dao.messages(now, retentionMs).filter {
        it.sessionId == source.sessionId && it.scopePackage == source.scopePackage && it.aiEligible && it.id != source.id
    }.take(5).reversed() else emptyList()
    val messages = previous + source
    val known = dao.memories(now, retentionMs).map { it.sourceMessageId }.toSet()
    val candidates = if (consent.saveMessages) messages.filter { it.role == "USER" && it.id !in known }.map { it.id }.toSet() else emptySet()
    val input = JSONObject().put("style", config.style).put("save_messages", consent.saveMessages)
        .put("target_name", target?.label ?: "整体手机使用")
        .put("messages", JSONArray().apply { messages.forEach { put(JSONObject().put("id", it.id).put("role", it.role).put("text", it.text)) } })
        .put("known_memories", JSONArray().apply { memories.forEach { put(JSONObject().put("source_id", it.sourceMessageId).put("text", it.text).put("kind", it.kind)) } })
        .put("eligible_source_ids", JSONArray(candidates.toList()))
    val categories = app.pausecn.data.appCategorySnapshot(database)
    if (categories.settings.sendToAi && target != null) input.put("app_category", categories.category(target.packageName))
    val expires = minOf(source.expiresAt, messages.minOf { it.expiresAt }, memories.minOfOrNull { it.expiresAt } ?: Long.MAX_VALUE,
        messages.minOf { it.createdAt } + retentionDays.coerceIn(1, 30) * 86_400_000L)
    if (now >= expires) return null
    return ConversationSnapshot(config, consent, source, PersonalizationProtocol.fingerprint(config.instanceId,
        config.privacyEpoch.toString(), config.styleVersion.toString(), consent.epoch.toString(),
        input.toString()), expires, input.toString(), candidates)
}

/** Caller owns the shared mutation barrier and transaction. No network and no Key access. */
internal suspend fun pruneConversationData(database: PauseDatabase, now: Long, retentionDays: Int, keepMessages: Int = 100): Int {
    val dao = database.conversationDao()
    val cutoff = now - retentionDays.coerceIn(1, 30) * 86_400_000L
    val deleted = dao.pruneMessages(now, cutoff, keepMessages) + dao.pruneMemories(now) + dao.pruneFeedback(now, cutoff)
    if (deleted > 0) {
        dao.invalidate()
        dao.clearReplies()
        database.aiDao().invalidatePersonalization()
        database.aiDao().clearPersonalized()
    }
    return deleted
}
