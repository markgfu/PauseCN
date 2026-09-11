package app.pausecn.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConversationProtocolTest {
    @Test
    fun `only candidates from the supplied user source allowlist are accepted`() {
        val content = JSONObject()
            .put("reply", "先停一下，看看此刻真正想做什么。")
            .put("memories", JSONArray()
                .put(memory("user-1", "我想减少刷视频", "GOAL"))
                .put(memory("assistant-1", "助手猜测的目标", "GOAL"))
                .put(memory("unknown", "未授权来源", "PREFERENCE")))
            .toString()

        val parsed = ConversationProtocol.parse(content, userSourceIds = setOf("user-1"))

        assertEquals("先停一下，看看此刻真正想做什么。", parsed.reply)
        assertEquals(listOf(MemoryCandidate("user-1", "我想减少刷视频", "GOAL")), parsed.candidates)
    }

    @Test
    fun `three valid candidates are accepted but a fourth row rejects the response`() {
        val three = JSONArray()
            .put(memory("user-1", "目标一", "GOAL"))
            .put(memory("user-2", "偏好二", "PREFERENCE"))
            .put(memory("user-3", "背景三", "CONTEXT"))
        val allowed = setOf("user-1", "user-2", "user-3", "user-4")

        assertEquals(3, ConversationProtocol.parse(response(three), allowed).candidates.size)

        three.put(memory("user-4", "目标四", "GOAL"))
        assertFailsWithMessage("候选记忆过多") {
            ConversationProtocol.parse(response(three), allowed)
        }
    }

    @Test
    fun `malformed or unsafe replies are rejected`() {
        val cases = listOf(
            JSONObject().put("memories", JSONArray()).toString(),
            JSONObject().put("reply", "   ").toString(),
            JSONObject().put("reply", "正常回复").toString() + " trailing",
            "[]",
        )

        cases.forEach { content ->
            assertTrue(runCatching { ConversationProtocol.parse(content, emptySet()) }.isFailure)
        }
        assertFailsWithMessage("交流返回过大") {
            ConversationProtocol.parse("x".repeat(16_001), emptySet())
        }
    }

    private fun response(memories: JSONArray) = JSONObject()
        .put("reply", "这是符合约束的回复。")
        .put("memories", memories)
        .toString()

    private fun memory(source: String, text: String, kind: String) = JSONObject()
        .put("source_id", source)
        .put("text", text)
        .put("kind", kind)

    private fun assertFailsWithMessage(message: String, block: () -> Unit) {
        try {
            block()
        } catch (error: IllegalArgumentException) {
            assertEquals(message, error.message)
            return
        } catch (error: IllegalStateException) {
            assertEquals(message, error.message)
            return
        }
        fail("Expected failure: $message")
    }
}
