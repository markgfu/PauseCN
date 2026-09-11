package app.pausecn.ai

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPolicyTest {
    @Test
    fun `relevant memories require confirmation matching scope and live expiry`() {
        val now = 1_000L
        val rows = listOf(
            memory("global", "", confirmed = true, expiresAt = 2_000, createdAt = 900),
            memory("target", APP_A, confirmed = true, expiresAt = 2_000, createdAt = 800),
            memory("other", APP_B, confirmed = true, expiresAt = 2_000, createdAt = 950),
            memory("candidate", APP_A, confirmed = false, expiresAt = 2_000, createdAt = 990),
            memory("expired", APP_A, confirmed = true, expiresAt = now, createdAt = 995),
        )

        assertEquals(listOf("target", "global"),
            ConversationPolicy.relevant(rows, APP_A, now).map(UserMemory::id))
        assertEquals(listOf("global"),
            ConversationPolicy.relevant(rows, "unconfigured.app", now).map(UserMemory::id))
    }

    @Test
    fun `context memory expires by local day end while stable kinds use retention`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = 1_786_200_000_000L
        val contextExpiry = ConversationPolicy.expiresAt(now, retentionDays = 30, kind = "CONTEXT", zone = zone)
        val goalExpiry = ConversationPolicy.expiresAt(now, retentionDays = 30, kind = "GOAL", zone = zone)

        assertTrue(contextExpiry > now)
        assertTrue(contextExpiry <= now + 86_400_000L)
        assertEquals(now + 30L * 86_400_000L, goalExpiry)
    }

    @Test
    fun `background revision is stable and changes only for relevant enabled memory state`() {
        val now = 1_000L
        val config = ConversationConfig(useMemories = true, epoch = 7)
        val target = memory("target", APP_A, confirmed = true, expiresAt = 2_000, revision = "rev-a")
        val irrelevant = memory("other", APP_B, confirmed = true, expiresAt = 2_000, revision = "rev-b")
        val rows = listOf(target, irrelevant)

        val first = ConversationPolicy.backgroundRevision("profile", config, rows, APP_A, now)
        val second = ConversationPolicy.backgroundRevision("profile", config, rows.reversed(), APP_A, now)
        assertEquals(first, second)
        assertNotEquals(first, ConversationPolicy.backgroundRevision(
            "profile", config, listOf(target.copy(revision = "changed"), irrelevant), APP_A, now))
        assertEquals(first, ConversationPolicy.backgroundRevision(
            "profile", config, rows + memory("expired", APP_A, true, now, revision = "ignored"), APP_A, now))

        val disabled = config.copy(useMemories = false, epoch = 99)
        assertEquals("profile", ConversationPolicy.backgroundRevision("profile", disabled, rows, APP_A, now))
    }

    @Test
    fun `feedback blocks only exact normalized phrase in its package before expiry`() {
        val now = 1_000L
        val rejected = PhraseFeedback(
            id = "feedback", scopePackage = APP_A,
            phraseFingerprint = ConversationPolicy.phraseFingerprint("精确短句"),
            instruction = "不要再显示这句话", createdAt = 500, expiresAt = 2_000,
        )

        assertTrue(ConversationPolicy.rejected(listOf(rejected), APP_A, "  精确短句  ", now))
        assertFalse(ConversationPolicy.rejected(listOf(rejected), APP_A, "精确短句。", now))
        assertFalse(ConversationPolicy.rejected(listOf(rejected), APP_B, "精确短句", now))
        assertFalse(ConversationPolicy.rejected(listOf(rejected), APP_A, "精确短句", rejected.expiresAt))
    }

    @Test
    fun `neutral fallback skips rejected backups and returns empty when all are rejected`() {
        val now = 1_000L
        val default = PromptSelector.fallback(PromptScene.ORDINARY)
        val firstBackup = "停一停，按自己的需要决定。"
        val lastBackup = "可以继续，也可以暂时离开。"
        fun reject(id: String, phrase: String) = PhraseFeedback(
            id = id, scopePackage = APP_A,
            phraseFingerprint = ConversationPolicy.phraseFingerprint(phrase),
            instruction = "不要使用这句", createdAt = 500, expiresAt = 2_000,
        )

        val rejectedBackups = listOf(reject("default", default), reject("first", firstBackup))
        assertEquals(lastBackup,
            ConversationPolicy.neutralFallback(rejectedBackups, APP_A, PromptScene.ORDINARY, now))
        assertEquals("", ConversationPolicy.neutralFallback(
            rejectedBackups + reject("last", lastBackup), APP_A, PromptScene.ORDINARY, now))
    }

    private fun memory(
        id: String,
        scope: String,
        confirmed: Boolean,
        expiresAt: Long,
        createdAt: Long = 500,
        revision: String = "revision-$id",
    ) = UserMemory(
        id = id, sourceMessageId = "source-$id", scopePackage = scope,
        text = "memory-$id", kind = "GOAL", confirmed = confirmed,
        revision = revision, createdAt = createdAt, expiresAt = expiresAt,
    )

    private companion object {
        const val APP_A = "app.a"
        const val APP_B = "app.b"
    }
}
