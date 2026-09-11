package app.pausecn.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptSelectorTest {
    private val wallOffset = 1_000_000_000L

    @Test
    fun `short text counts Unicode code points and rejects controls`() {
        assertTrue(PromptSelector.validShortText("🙂".repeat(36)))
        assertFalse(PromptSelector.validShortText("🙂".repeat(37)))
        assertFalse(PromptSelector.validShortText("提醒\n一下"))
        assertFalse(PromptSelector.validShortText("提醒\u2028一下"))
        assertFalse(PromptSelector.validShortText("提醒\u2029一下"))
        assertFalse(PromptSelector.validShortText("提醒\u200B一下"))
        assertFalse(PromptSelector.validShortText("提醒\uDB40\uDC01一下"))
        assertFalse(PromptSelector.validShortText("\uD83D"))
    }

    @Test
    fun `preview choose does not advance phrase rotation until shown`() {
        val selector = PromptSelector()
        val config = AiConfig(enabled = true, styleVersion = 7)
        val phrases = listOf(
            phrase(id = "first", text = "第一句", styleVersion = 7),
            phrase(id = "second", text = "第二句", styleVersion = 7),
        )

        val preview = selector.choose(PACKAGE, config, phrases, wall(1_000), 1_000)
        val repeatedPreview = selector.choose(PACKAGE, config, phrases, wall(2_000), 2_000)
        assertEquals("first", preview.id)
        assertEquals(preview, repeatedPreview)

        selector.shown(PACKAGE, preview, wall(3_000), 3_000)

        assertEquals(
            "second",
            selector.choose(PACKAGE, config, phrases, wall(4_000), 4_000).id,
        )
    }

    @Test
    fun `shown repeated prompt suppresses another adjustment for thirty minutes`() {
        val selector = PromptSelector()
        selector.complete(PACKAGE, continued = true, wall(1_000), 1_000)
        selector.complete(PACKAGE, continued = true, wall(2_000), 2_000)

        val repeated = selector.choose(PACKAGE, AiConfig(), emptyList(), wall(3_000), 3_000)
        assertEquals(PromptScene.REPEATED, repeated.scene)
        assertEquals(
            PromptScene.REPEATED,
            selector.choose(PACKAGE, AiConfig(), emptyList(), wall(4_000), 4_000).scene,
        )

        selector.shown(PACKAGE, repeated, wall(5_000), 5_000)
        selector.complete(
            PACKAGE,
            continued = true,
            wall(5_000 + PromptSelector.WINDOW - 2_000),
            5_000 + PromptSelector.WINDOW - 2_000,
        )
        selector.complete(
            PACKAGE,
            continued = true,
            wall(5_000 + PromptSelector.WINDOW - 1_000),
            5_000 + PromptSelector.WINDOW - 1_000,
        )

        assertEquals(
            PromptScene.ORDINARY,
            selector.choose(
                PACKAGE,
                AiConfig(),
                emptyList(),
                wall(5_000 + PromptSelector.WINDOW - 1),
                5_000 + PromptSelector.WINDOW - 1,
            ).scene,
        )
        assertEquals(
            PromptScene.REPEATED,
            selector.choose(
                PACKAGE,
                AiConfig(),
                emptyList(),
                wall(5_000 + PromptSelector.WINDOW),
                5_000 + PromptSelector.WINDOW,
            ).scene,
        )
    }

    @Test
    fun `system clock change clears continued sequence on the next commit`() {
        val selector = PromptSelector()
        selector.complete(PACKAGE, continued = true, wall(1_000), 1_000)
        selector.complete(PACKAGE, continued = true, wall(2_000), 2_000)
        assertEquals(
            PromptScene.REPEATED,
            selector.choose(PACKAGE, AiConfig(), emptyList(), wall(3_000), 3_000).scene,
        )

        selector.complete(
            PACKAGE,
            continued = true,
            wallMs = wall(4_000) + 6_000,
            elapsedMs = 4_000,
        )

        assertEquals(
            PromptScene.ORDINARY,
            selector.choose(
                PACKAGE,
                AiConfig(),
                emptyList(),
                wallMs = wall(5_000) + 6_000,
                elapsedMs = 5_000,
            ).scene,
        )
    }

    @Test
    fun `disabled AI uses only default or manual phrase`() {
        val selector = PromptSelector()
        val generated = listOf(phrase(id = "generated", text = "AI 缓存句", styleVersion = 1))

        val defaultChoice = selector.choose(
            PACKAGE,
            AiConfig(enabled = false, styleVersion = 1),
            generated,
            wall(1_000),
            1_000,
        )
        assertEquals(PromptSelector.DEFAULT, defaultChoice.text)
        assertEquals(null, defaultChoice.id)

        val manualChoice = selector.choose(
            PACKAGE,
            AiConfig(enabled = false, styleVersion = 1, manualPhrase = "先放下手机"),
            generated,
            wall(2_000),
            2_000,
        )
        assertEquals("先放下手机", manualChoice.text)
        assertEquals(null, manualChoice.id)
    }

    private fun phrase(id: String, text: String, styleVersion: Long) = AiPhrase(
        id = id,
        scene = PromptScene.ORDINARY.name,
        text = text,
        styleVersion = styleVersion,
        approved = true,
    )

    private fun wall(elapsedMs: Long) = wallOffset + elapsedMs

    companion object {
        private const val PACKAGE = "example.target"
    }
}
