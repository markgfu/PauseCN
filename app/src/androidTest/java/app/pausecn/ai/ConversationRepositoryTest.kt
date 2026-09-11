package app.pausecn.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pausecn.data.PauseDatabase
import app.pausecn.data.TargetRuleEntity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationRepositoryTest {
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
    fun confirmThenForgetExcludesTheSourceAndStaleMemoryCannotBecomeRelevantAgain() = runBlocking {
        val now = System.currentTimeMillis()
        val source = ConversationMessage(
            id = "source", sessionId = "session", scopePackage = "app.a", role = "USER",
            text = "我想减少刷视频", createdAt = now, expiresAt = now + 60_000,
        )
        val candidate = UserMemory(
            id = "memory", sourceMessageId = source.id, scopePackage = source.scopePackage,
            text = source.text, kind = "GOAL", confirmed = false, revision = "candidate-revision",
            createdAt = now, expiresAt = source.expiresAt,
        )
        database.conversationDao().insertMessage(source)
        database.conversationDao().saveMemory(candidate)
        val ai = AiRepository(database, EmptyCredentials, AiTransport { _, _, _, _ -> error("HTTP is forbidden") }, { "unused" })

        ai.conversations.confirm(candidate.id)

        val confirmed = requireNotNull(database.conversationDao().memory(candidate.id))
        assertTrue(confirmed.confirmed)
        assertNotEquals(candidate.revision, confirmed.revision)

        ai.conversations.forget(candidate.id)

        assertFalse(requireNotNull(database.conversationDao().message(source.id)).aiEligible)
        assertNull(database.conversationDao().memory(candidate.id))

        // Models or stale UI state cannot revive a forgotten source: the DAO join is fail-closed.
        database.conversationDao().saveMemory(confirmed)
        assertTrue(database.conversationDao().memories(System.currentTimeMillis()).isEmpty())
    }

    @Test
    fun disablingConversationDropsAResponseFromTransportThatIgnoresCancellation() = runBlocking {
        database.aiDao().save(AiConfig(enabled = true))
        database.conversationDao().saveConfig(
            ConversationConfig(enabled = true, saveMessages = true, useMemories = true),
        )
        database.targetRuleDao().upsert(TargetRuleEntity("app.a", "应用甲"))
        val transport = BlockingConversationTransport()
        val ai = AiRepository(database, KeyCredentials, transport, { "unused" },
            conversationPrompt = { "conversation prompt" })
        val request = async(Dispatchers.Default) {
            ai.sendConversation("session", "app.a", "我只想查一项资料")
        }
        try {
            assertTrue(transport.entered.await(5, TimeUnit.SECONDS))
            ai.conversations.configure(enabled = false, saveMessages = true, useMemories = false)
        } finally {
            transport.release.countDown()
        }
        runCatching { request.await() }

        assertTrue(request.isCancelled)
        assertFalse(requireNotNull(database.conversationDao().config()).enabled)
        assertTrue(database.conversationDao().messages(System.currentTimeMillis()).none { it.role == "ASSISTANT" })
        assertTrue(database.conversationDao().memories(System.currentTimeMillis()).isEmpty())
    }

    @Test
    fun independentMemoryOutlivesItsSourceAndHistoryButExplicitForgetAndResetDeleteIt() = runBlocking {
        val now = System.currentTimeMillis()
        val ai = AiRepository(database, EmptyCredentials,
            AiTransport { _, _, _, _ -> error("HTTP is forbidden") }, { "unused" })
        suspend fun seed(suffix: String): UserMemory {
            val source = ConversationMessage(
                id = "source-$suffix", sessionId = "session", scopePackage = "app.a", role = "USER",
                text = "偏好-$suffix", createdAt = now, expiresAt = now + 60_000,
            )
            val memory = UserMemory(
                id = "memory-$suffix", sourceMessageId = source.id, scopePackage = source.scopePackage,
                text = source.text, kind = "PREFERENCE", confirmed = true,
                createdAt = now, expiresAt = source.expiresAt,
            )
            database.conversationDao().insertMessage(source)
            database.conversationDao().saveMemory(memory)
            ai.conversations.keepIndependently(memory.id)
            return requireNotNull(database.conversationDao().memory(memory.id))
        }

        val forgotten = seed("forget")
        assertTrue(forgotten.independent)
        assertTrue(database.conversationDao().memories(now + 60_001).any { it.id == forgotten.id })
        ai.conversations.deleteMessage(forgotten.sourceMessageId)
        assertTrue(database.conversationDao().memories(now + 60_001).any { it.id == forgotten.id })
        ai.conversations.forget(forgotten.id)
        assertNull(database.conversationDao().memory(forgotten.id))

        val reset = seed("reset")
        ai.clearGeneratedHistory()
        assertTrue(database.conversationDao().memories(now + 60_001).any { it.id == reset.id })
        ai.beginReset()
        ai.finishReset()
        assertNull(database.conversationDao().memory(reset.id))
        assertTrue(database.conversationDao().memoryCount() == 0)
    }

    private object EmptyCredentials : ApiCredentialStore {
        override fun hasKey() = false
        override fun read(): String = error("Key access is forbidden")
        override fun save(value: String) = error("Key writes are forbidden")
        override fun clear() = Unit
    }

    private object KeyCredentials : ApiCredentialStore {
        override fun hasKey() = true
        override fun read() = "test-key"
        override fun save(value: String) = Unit
        override fun clear() = Unit
    }

    /** Simulates a network stack that returns after cancellation instead of cooperating with it. */
    private class BlockingConversationTransport : AiTransport {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override suspend fun complete(key: String, model: String, system: String, user: String): AiCompletion {
            entered.countDown()
            while (true) {
                try {
                    release.await()
                    val source = JSONObject(user).getJSONArray("eligible_source_ids").getString(0)
                    return AiCompletion(JSONObject()
                        .put("reply", "旧回复不应保存")
                        .put("memories", JSONArray().put(JSONObject()
                            .put("source_id", source).put("text", "旧候选不应保存").put("kind", "GOAL")))
                        .toString(), 3)
                } catch (_: InterruptedException) {
                    // Keep waiting so the repository's post-response authorization check is exercised.
                }
            }
        }
    }
}
