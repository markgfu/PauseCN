package app.pausecn.ai

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PhraseProtocolTest {
    @Test
    fun acceptsMarkdownFenceExtraFieldsRelaxedWordingAndCollapsesWhitespace() {
        val content = JSONObject()
            .put("request_id", "ignored")
            .put(
                "phrases",
                JSONArray()
                    .put(row(PromptScene.ORDINARY.name, "先停\n一下，\t再决定。"))
                    .put(row(PromptScene.GOAL.name, "你之前设了 3 个目标。"))
                    .put(row(PromptScene.PURPOSE.name, "确认密码或 Key 再继续。").put("note", true)),
            )
            .toString()

        val batch = PhraseProtocol.parseBatch("```json\n$content\n```", version = 17)

        assertEquals(
            listOf("先停 一下， 再决定。", "你之前设了 3 个目标。", "确认密码或 Key 再继续。"),
            batch.phrases.map(AiPhrase::text),
        )
        assertTrue(batch.phrases.all { it.styleVersion == 17L && !it.approved })
    }

    @Test
    fun keepsMoreThanTwoPerSceneAndOnlyDeduplicatesWithinThatScene() {
        val shared = "先看清目标，再决定。"
        val content = JSONObject().put(
            "phrases",
            JSONArray()
                .put(row(PromptScene.ORDINARY.name, shared))
                .put(row(PromptScene.ORDINARY.name, "稍停一下，再决定。"))
                .put(row(PromptScene.ORDINARY.name, "把注意力放回眼前。"))
                .put(row(PromptScene.ORDINARY.name, shared))
                .put(row(PromptScene.GOAL.name, shared)),
        ).toString()

        val batch = PhraseProtocol.parseBatch(content, version = 23)

        assertEquals(4, batch.phrases.size)
        assertEquals(3, batch.phrases.count { it.scene == PromptScene.ORDINARY.name })
        assertEquals(1, batch.phrases.count { it.scene == PromptScene.GOAL.name })
        assertTrue(batch.issues.isNotEmpty())
        assertEquals(
            setOf(PromptScene.REPEATED, PromptScene.CONTEXT, PromptScene.PURPOSE, PromptScene.FEEDBACK),
            batch.missingScenes.toSet(),
        )
    }

    @Test
    fun filtersUnsafeOrMalformedRowsWithoutLeakingTheirContents() {
        val secret = "PRIVATE-CONTENT-SHOULD-NOT-LEAK"
        val content = JSONObject().put(
            "phrases",
            JSONArray()
                .put(row(PromptScene.ORDINARY.name, "先停一下，再决定。"))
                .put(JSONObject().put("scene", 7).put("text", "$secret-type"))
                .put(row("NOT_A_SCENE", "$secret-unknown"))
                .put(row(PromptScene.GOAL.name, "${"很".repeat(37)}$secret"))
                .put(row(PromptScene.CONTEXT.name, "看看 http://x.co"))
                .put(row(PromptScene.PURPOSE.name, "密钥 sk-abcdefghijklmnop"))
                .put(row(PromptScene.FEEDBACK.name, "左右控制\u202e$secret"))
                .put(JSONObject().put("scene", PromptScene.REPEATED.name).put("text", JSONArray().put(secret))),
        ).toString()

        val batch = PhraseProtocol.parseBatch(content, version = 29)
        val diagnostics = batch.issues.joinToString("|") + batch.publicMessage

        assertEquals(listOf("先停一下，再决定。"), batch.phrases.map(AiPhrase::text))
        assertTrue(batch.issues.size >= 7)
        assertTrue(batch.issues.any { it.problem == PhraseProblem.SENSITIVE_REQUEST })
        assertFalse(diagnostics.contains(secret))
        assertFalse(diagnostics.contains("NOT_A_SCENE"))

        val noUsable = captureValidationError {
            PhraseProtocol.parseBatch(
                JSONObject().put("phrases", JSONArray().put(row(PromptScene.ORDINARY.name, "www.example.com/$secret"))).toString(),
                version = 30,
            )
        }
        assertEquals(PhraseProblem.NO_USABLE_PHRASES, noUsable.code)
        assertFalse(noUsable.publicMessage.contains(secret))
        assertFalse(noUsable.message.orEmpty().contains(secret))
    }

    @Test
    fun acceptsThirtySixButRejectsInvalidRootAndOversizedResponseWithSanitizedErrors() {
        val maximum = JSONArray()
        repeat(36) { index -> maximum.put(row(PromptScene.ORDINARY.name, "候选 ${index + 1}")) }
        assertEquals(
            36,
            PhraseProtocol.parseBatch(JSONObject().put("phrases", maximum).toString(), version = 37).phrases.size,
        )

        val secret = "PRIVATE-ROOT-SHOULD-NOT-LEAK"
        val oversized = JSONArray()
        repeat(37) { index -> oversized.put(row(PromptScene.ORDINARY.name, "第 ${index + 1} 条")) }
        listOf(
            "[\"$secret\"]",
            JSONObject().put("phrases", JSONArray()).toString(),
            JSONObject().put("phrases", "$secret-not-an-array").toString(),
            JSONObject().put("phrases", oversized).put(secret, true).toString(),
        ).forEach { payload ->
            val error = captureValidationError { PhraseProtocol.parseBatch(payload, version = 41) }
            assertTrue(error.publicMessage.isNotBlank())
            assertFalse(error.publicMessage.contains(secret))
            assertFalse(error.message.orEmpty().contains(secret))
        }
    }

    @Test
    fun shippedPromptNamesTheProtocolAndContainsAParsableExample() {
        val prompt = File("src/main/assets/${PhraseProtocol.PROMPT_ASSET}").readText()

        assertTrue(prompt.contains("36"))
        PromptScene.entries.forEach { scene -> assertTrue(prompt.contains(scene.name)) }
        val example = Regex("```json\\s*(\\{.*?})\\s*```", RegexOption.DOT_MATCHES_ALL)
            .find(prompt)
            ?.groupValues
            ?.get(1)
            ?: throw AssertionError("The shipped prompt must include a fenced JSON example")
        val parsed = PhraseProtocol.parseBatch(example, version = 43)
        assertEquals(12, parsed.phrases.size)
        assertTrue(parsed.issues.isEmpty())
    }

    private fun row(scene: String, text: String) = JSONObject().put("scene", scene).put("text", text)

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
