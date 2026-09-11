package app.pausecn.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTextTest {
    @Test
    fun `normalization keeps multiline text and canonicalizes line endings`() {
        val input = "  长期目标\r\n偏好\r第二行\t补充  "

        assertTrue(ProfileText.valid(input))
        assertEquals("长期目标\n偏好\n第二行\t补充", ProfileText.normalized(input))
    }

    @Test
    fun `limit counts Unicode code points rather than UTF16 units`() {
        val exactlyLimit = "😀".repeat(ProfileText.MAX_CODE_POINTS)

        assertTrue(ProfileText.valid(exactlyLimit))
        assertEquals(exactlyLimit, ProfileText.normalized(exactlyLimit))
        assertFalse(ProfileText.valid(exactlyLimit + "界"))
    }

    @Test
    fun `rejects controls bidi formatting and malformed surrogates`() {
        assertFalse(ProfileText.valid("目标\u0000内容"))
        assertFalse(ProfileText.valid("目标\u202e内容"))
        assertFalse(ProfileText.valid("目标\u2066内容"))
        assertFalse(ProfileText.valid("目标\uD83D内容"))
    }
}
