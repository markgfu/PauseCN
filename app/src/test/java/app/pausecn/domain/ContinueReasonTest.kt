package app.pausecn.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueReasonTest {
    @Test
    fun composeCombinesKnownSelectionAndFreeInputWithoutDuplicatingIt() {
        assertEquals("回消息", ContinueReason.compose("回消息", ""))
        assertEquals("补充项目进度", ContinueReason.compose(null, "　补充项目进度　"))
        assertEquals("搜资料 · 查会议地点", ContinueReason.compose("搜资料", " 查会议地点 "))
        assertEquals("搜资料", ContinueReason.compose("搜资料", "搜资料"))
        assertNull(ContinueReason.compose("未知选项", "说明"))

        assertTrue(ContinueReason.isCasual("随便看看"))
        assertTrue(ContinueReason.isCasual("随便看看 · 放松片刻"))
        assertFalse(ContinueReason.isCasual("不是随便看看"))
        assertFalse(ContinueReason.isCasual(null))
    }

    @Test
    fun validationCountsUnicodeCodePointsAndRejectsUnsafeOrOversizedText() {
        val eightyEmoji = "😀".repeat(ContinueReason.MAX_CODE_POINTS)
        assertTrue(ContinueReason.isValid(eightyEmoji))
        assertFalse(ContinueReason.isValid("😀".repeat(ContinueReason.MAX_CODE_POINTS + 1)))
        assertFalse(ContinueReason.isValid("   "))
        assertFalse(ContinueReason.isValid("第一行\n第二行"))
        assertFalse(ContinueReason.isValid("带方向控制\u202e"))
        assertNull(ContinueReason.compose("回消息", eightyEmoji))
    }
}
