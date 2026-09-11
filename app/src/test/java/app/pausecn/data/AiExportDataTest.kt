package app.pausecn.data

import app.pausecn.ai.ConversationMessage
import app.pausecn.ai.PhraseFeedback
import app.pausecn.ai.UserMemory
import app.pausecn.ai.UserProfile
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiExportDataTest {
    @Test
    fun `populated categories do not leak unless explicitly selected`() {
        val json = encodeAiExport(allData(), LocalExportOptions(profile = true))
        val root = JSONObject(json)

        assertTrue(root.has("profile"))
        listOf("memories", "conversations", "feedback", "phrases").forEach {
            assertFalse(root.has(it))
        }
        assertFalse(json.contains("memory-secret"))
        assertFalse(json.contains("conversation-secret"))
        assertFalse(json.contains("feedback-secret"))
        assertFalse(json.contains("phrase-secret"))
    }

    @Test
    fun `assistant messages require both conversations and generated switches`() {
        val conversationsOnly = JSONObject(encodeAiExport(
            allData(), LocalExportOptions(conversations = true),
        )).getJSONArray("conversations")
        assertTrue(conversationsOnly.length() == 1)
        assertTrue(conversationsOnly.getJSONObject(0).getString("role") == "USER")

        val withGenerated = JSONObject(encodeAiExport(
            allData(), LocalExportOptions(conversations = true, generated = true),
        )).getJSONArray("conversations")
        assertTrue(withGenerated.length() == 2)
        assertTrue((0 until withGenerated.length()).any {
            withGenerated.getJSONObject(it).getString("role") == "ASSISTANT"
        })
    }

    @Test
    fun `even full AI export has no request context or credential fields`() {
        val json = encodeAiExport(allData(), LocalExportOptions(
            profile = true, memories = true, conversations = true, feedback = true, generated = true,
        ))
        val forbiddenFields = listOf("contextJson", "credentials", "credential", "apiKey", "key")

        forbiddenFields.forEach { field -> assertFalse(json.contains("\"$field\":")) }
        assertFalse(json.contains("request-context-secret"))
    }

    private fun allData(): AiExportData {
        val source = ConversationMessage(
            id = "user", sessionId = "session", scopePackage = "app.a", role = "USER",
            text = "conversation-secret", createdAt = 100, expiresAt = 1_000,
        )
        return AiExportData(
            profile = UserProfile(goal = "profile-goal", preferences = "profile-preference"),
            style = "profile-style", manualPhrase = "manual-phrase",
            memories = listOf(UserMemory(
                id = "memory", sourceMessageId = source.id, scopePackage = "app.a",
                text = "memory-secret", kind = "GOAL", confirmed = true,
                createdAt = 100, expiresAt = 1_000,
            )),
            messages = listOf(source, source.copy(
                id = "assistant", role = "ASSISTANT", text = "assistant-secret",
            )),
            feedback = listOf(PhraseFeedback(
                id = "feedback", scopePackage = "app.a", phraseFingerprint = "fingerprint",
                instruction = "feedback-secret", createdAt = 100, expiresAt = 1_000,
            )),
            phrases = listOf(ExportedPhrase("phrase-secret", "ORDINARY", approved = true)),
        )
    }
}
