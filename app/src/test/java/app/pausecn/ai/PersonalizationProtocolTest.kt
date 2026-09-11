package app.pausecn.ai

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PersonalizationProtocolTest {
    @Test
    fun `accepts authorized sources and keeps at most two supported phrases per scene`() {
        val phrases = JSONArray()
            .put(row(PromptScene.ORDINARY, "普通一"))
            .put(row(PromptScene.ORDINARY, "普通二"))
            .put(row(PromptScene.ORDINARY, "普通三"))
            .put(row(PromptScene.REPEATED, "重复一"))
            .put(row(PromptScene.REPEATED, "重复二"))
            .put(row(PromptScene.GOAL, "不会进入个性缓存"))
        val content = JSONObject()
            .put("source_ids", JSONArray().put("profile").put("reason_0"))
            .put("phrases", phrases)
            .toString()

        val parsed = PersonalizationProtocol.parse(content, version = 7, sourceIds = setOf("profile", "reason_0"))

        assertEquals(listOf("普通一", "普通二", "重复一", "重复二"), parsed.map(AiPhrase::text))
        assertTrue(parsed.all { it.scene in setOf(PromptScene.ORDINARY.name, PromptScene.REPEATED.name) })
    }

    @Test
    fun `rejects unknown duplicate or malformed source references`() {
        val validPhrase = JSONArray().put(row(PromptScene.ORDINARY, "先想清楚再进入"))
        listOf(
            JSONArray().put("profile").put("invented"),
            JSONArray().put("profile").put("profile"),
            JSONArray().put(3),
        ).forEach { references ->
            val error = captureValidationError {
                PersonalizationProtocol.parse(
                    JSONObject().put("source_ids", references).put("phrases", validPhrase).toString(),
                    version = 8,
                    sourceIds = setOf("profile"),
                )
            }
            assertEquals(PhraseProblem.ROW_FIELDS, error.code)
        }
    }

    @Test
    fun `fingerprint is deterministic and length delimited`() {
        assertEquals(
            PersonalizationProtocol.fingerprint("ab", "c"),
            PersonalizationProtocol.fingerprint("ab", "c"),
        )
        assertNotEquals(
            PersonalizationProtocol.fingerprint("ab", "c"),
            PersonalizationProtocol.fingerprint("a", "bc"),
        )
        assertNotEquals(
            PersonalizationProtocol.fingerprint("profile", "reason"),
            PersonalizationProtocol.fingerprint("reason", "profile"),
        )
    }

    @Test
    fun `shipped personalized prompt example matches parser contract`() {
        val prompt = File("src/main/assets/${PersonalizationProtocol.PROMPT_ASSET}").readText()
        val example = Regex("示例.*?：\\s*(\\{.*})\\s*\\n", RegexOption.DOT_MATCHES_ALL)
            .find(prompt)?.groupValues?.get(1)
            ?: throw AssertionError("The shipped personalized prompt must contain a JSON example")

        val parsed = PersonalizationProtocol.parse(example, version = 9, sourceIds = emptySet())

        assertEquals(4, parsed.size)
        assertEquals(2, parsed.count { it.scene == PromptScene.ORDINARY.name })
        assertEquals(2, parsed.count { it.scene == PromptScene.REPEATED.name })
    }

    @Test
    fun `one feedback compliant phrase is accepted without filling other scenes`() {
        val content = JSONObject().put("source_ids", JSONArray().put("feedback_1"))
            .put("phrases", JSONArray().put(row(PromptScene.ORDINARY, "哼，这次想算什么？"))).toString()
        val parsed = PersonalizationProtocol.parse(content, 10, setOf("feedback_1"))
        assertEquals(listOf("哼，这次想算什么？"), parsed.map(AiPhrase::text))
        val prompt = File("src/main/assets/${PersonalizationProtocol.PROMPT_ASSET}").readText()
        assertTrue(prompt.contains("生成1–4条"))
        assertTrue(prompt.contains("每一条候选短句都必须独立满足当前应用反馈"))
    }

    private fun row(scene: PromptScene, text: String) =
        JSONObject().put("scene", scene.name).put("text", text)

    private fun captureValidationError(block: () -> Unit): PhraseValidationException {
        try {
            block()
        } catch (error: PhraseValidationException) {
            return error
        }
        fail("Expected PhraseValidationException")
        throw AssertionError("unreachable")
    }
}
