package app.pausecn.ai

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Entity(tableName = "conversation_config")
data class ConversationConfig(
    @PrimaryKey val id: Int = 1,
    val enabled: Boolean = false,
    val saveMessages: Boolean = false,
    val useMemories: Boolean = false,
    val epoch: Long = 0,
)

@Entity(tableName = "conversation_messages")
data class ConversationMessage(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val scopePackage: String = "",
    val role: String,
    val text: String,
    val createdAt: Long,
    val expiresAt: Long,
    val aiEligible: Boolean = true,
)

@Entity(tableName = "user_memories")
data class UserMemory(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sourceMessageId: String,
    val scopePackage: String = "",
    val text: String,
    val kind: String,
    val confirmed: Boolean = false,
    val revision: String = UUID.randomUUID().toString(),
    val createdAt: Long,
    val expiresAt: Long,
    @ColumnInfo(defaultValue = "0") val independent: Boolean = false,
)

/** Only explicit feedback is stored. A fingerprint is used to suppress an exact sentence locally. */
@Entity(tableName = "phrase_feedback")
data class PhraseFeedback(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val scopePackage: String,
    val phraseFingerprint: String,
    val instruction: String,
    val createdAt: Long,
    val expiresAt: Long,
)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversation_config WHERE id = 1") suspend fun config(): ConversationConfig?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveConfig(row: ConversationConfig)
    @Query("UPDATE conversation_config SET epoch = epoch + 1") suspend fun invalidate()
    @Query("SELECT id, sessionId, scopePackage, role, text, createdAt, MIN(expiresAt, createdAt + :retentionMs) AS expiresAt, aiEligible FROM conversation_messages WHERE MIN(expiresAt, createdAt + :retentionMs) > :now ORDER BY createdAt DESC, id DESC LIMIT 100")
    suspend fun messages(now: Long, retentionMs: Long = 30L * 86_400_000): List<ConversationMessage>
    @Query("SELECT * FROM conversation_messages WHERE id = :id") suspend fun message(id: String): ConversationMessage?
    @Insert suspend fun insertMessage(row: ConversationMessage)
    @Query("UPDATE conversation_messages SET aiEligible = 0 WHERE id = :id") suspend fun excludeSource(id: String)
    @Query("DELETE FROM conversation_messages WHERE id = :id") suspend fun deleteMessage(id: String)
    @Query("DELETE FROM conversation_messages WHERE role = 'ASSISTANT'") suspend fun clearReplies()
    @Query("""SELECT user_memories.id, sourceMessageId, user_memories.scopePackage, user_memories.text,
        kind, confirmed, revision, user_memories.createdAt, independent,
        CASE WHEN independent = 1 THEN user_memories.expiresAt ELSE
            MIN(user_memories.expiresAt, conversation_messages.expiresAt, conversation_messages.createdAt + :retentionMs) END AS expiresAt
        FROM user_memories LEFT JOIN conversation_messages ON user_memories.sourceMessageId = conversation_messages.id
        WHERE (independent = 1 AND confirmed = 1 AND user_memories.expiresAt > :now) OR
            (independent = 0 AND MIN(user_memories.expiresAt, conversation_messages.expiresAt, conversation_messages.createdAt + :retentionMs) > :now
                AND conversation_messages.aiEligible = 1 AND conversation_messages.role = 'USER')
        ORDER BY user_memories.createdAt DESC, user_memories.id LIMIT 150""")
    suspend fun memories(now: Long, retentionMs: Long = 30L * 86_400_000): List<UserMemory>
    @Query("SELECT * FROM user_memories WHERE id = :id") suspend fun memory(id: String): UserMemory?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveMemory(row: UserMemory)
    @Query("DELETE FROM user_memories WHERE sourceMessageId = :id AND (independent = 0 OR :includeIndependent)")
    suspend fun deleteSourceMemories(id: String, includeIndependent: Boolean = false)
    @Query("DELETE FROM user_memories WHERE id = :id") suspend fun deleteMemory(id: String)
    @Query("SELECT COUNT(*) FROM user_memories WHERE independent = 1") suspend fun independentCount(): Int
    @Query("DELETE FROM user_memories WHERE confirmed = 0") suspend fun clearCandidates()
    @Query("SELECT id, scopePackage, phraseFingerprint, instruction, createdAt, MIN(expiresAt, createdAt + :retentionMs) AS expiresAt FROM phrase_feedback WHERE MIN(expiresAt, createdAt + :retentionMs) > :now ORDER BY createdAt DESC, id LIMIT 100")
    suspend fun feedback(now: Long, retentionMs: Long = 30L * 86_400_000): List<PhraseFeedback>
    @Insert suspend fun insertFeedback(row: PhraseFeedback)
    @Query("DELETE FROM phrase_feedback WHERE id = :id") suspend fun deleteFeedback(id: String)
    @Query("DELETE FROM conversation_messages WHERE expiresAt <= :now OR createdAt < :cutoff OR id NOT IN (SELECT id FROM conversation_messages ORDER BY createdAt DESC, id DESC LIMIT :keepRows)")
    suspend fun pruneMessages(now: Long, cutoff: Long, keepRows: Int = 100): Int
    @Query("DELETE FROM user_memories WHERE expiresAt <= :now OR (independent = 0 AND sourceMessageId NOT IN (SELECT id FROM conversation_messages WHERE aiEligible = 1 AND role = 'USER'))")
    suspend fun pruneMemories(now: Long): Int
    @Query("DELETE FROM phrase_feedback WHERE expiresAt <= :now OR createdAt < :cutoff OR id NOT IN (SELECT id FROM phrase_feedback ORDER BY createdAt DESC, id DESC LIMIT 100)")
    suspend fun pruneFeedback(now: Long, cutoff: Long): Int
    @Query("DELETE FROM conversation_messages") suspend fun clearMessages()
    @Query("DELETE FROM user_memories") suspend fun clearMemories()
    @Query("DELETE FROM user_memories WHERE independent = 0") suspend fun clearDerivedMemories()
    @Query("SELECT COUNT(*) FROM user_memories") suspend fun memoryCount(): Int
    @Query("SELECT COUNT(*) FROM conversation_messages") suspend fun messageCount(): Int
    @Query("SELECT COUNT(*) FROM phrase_feedback") suspend fun feedbackCount(): Int
    @Query("DELETE FROM phrase_feedback") suspend fun clearFeedback()
    @Query("DELETE FROM conversation_config") suspend fun clearConfig()
}

object ConversationPolicy {
    const val MESSAGE_LIMIT = 1_200
    const val MEMORY_LIMIT = 160
    val KINDS = setOf("GOAL", "PREFERENCE", "CONTEXT")
    fun textValid(text: String, limit: Int = MESSAGE_LIMIT): Boolean =
        text.isNotBlank() && ProfileText.valid(text, limit)

    fun expiresAt(now: Long, retentionDays: Int, kind: String = "GOAL", zone: ZoneId = ZoneId.systemDefault()): Long {
        val retainedUntil = now + retentionDays.coerceIn(1, 30) * 86_400_000L
        if (kind != "CONTEXT") return retainedUntil
        val dayEnd = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return minOf(retainedUntil, dayEnd, now + 86_400_000L)
    }

    fun relevant(memories: List<UserMemory>, pkg: String, now: Long): List<UserMemory> = memories.asSequence()
        .filter { it.confirmed && it.expiresAt > now && (it.scopePackage.isEmpty() || it.scopePackage == pkg) }
        .sortedWith(compareByDescending<UserMemory> { it.scopePackage == pkg && pkg.isNotEmpty() }.thenByDescending { it.createdAt })
        .take(5).toList()

    fun phraseFingerprint(text: String): String = PersonalizationProtocol.fingerprint(text.trim())

    fun backgroundRevision(profileRevision: String, config: ConversationConfig,
        memories: List<UserMemory>, pkg: String, now: Long): String {
        if (!config.useMemories) return profileRevision
        return PersonalizationProtocol.fingerprint(profileRevision, config.epoch.toString(),
            *relevant(memories, pkg, now).map { it.revision }.toTypedArray())
    }

    fun rejected(feedback: List<PhraseFeedback>, pkg: String, text: String, now: Long): Boolean =
        feedback.any { it.scopePackage == pkg && it.expiresAt > now &&
            it.phraseFingerprint == phraseFingerprint(text) }

    fun neutralFallback(feedback: List<PhraseFeedback>, pkg: String, scene: PromptScene, now: Long): String =
        listOf(PromptSelector.fallback(scene), "停一停，按自己的需要决定。", "可以继续，也可以暂时离开。")
            .firstOrNull { !rejected(feedback, pkg, it, now) }.orEmpty()
}
