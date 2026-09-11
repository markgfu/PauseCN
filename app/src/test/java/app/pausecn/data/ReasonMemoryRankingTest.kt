package app.pausecn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReasonMemoryRankingTest {
    @Test
    fun choicesArePerAppRankedByUsesThenRecencyAndKeepFivePresetsPlusThreeCustom() {
        val cache = ReasonMemoryCache()
        cache.replace(
            listOf(
                ReasonMemory(APP_A, "应用甲", "搜资料", uses = 4, lastUsedAt = 800),
                ReasonMemory(APP_A, "应用甲", "自定义较新", uses = 3, lastUsedAt = 900),
                ReasonMemory(APP_A, "应用甲", "自定义较旧", uses = 3, lastUsedAt = 700),
                ReasonMemory(APP_A, "应用甲", "自定义第三", uses = 2, lastUsedAt = 950),
                ReasonMemory(APP_A, "应用甲", "自定义应截断", uses = 1, lastUsedAt = 990),
                ReasonMemory(APP_B, "应用乙", "其他应用高频", uses = 99, lastUsedAt = 999),
            ),
        )

        val choices = cache.choices(APP_A, now = 1_000, retentionDays = 30)

        assertEquals(
            listOf("搜资料", "自定义较新", "自定义较旧", "自定义第三", "回消息", "发内容", "休息一下", "随便看看"),
            choices,
        )
        assertFalse(choices.contains("自定义应截断"))
        assertFalse(choices.contains("其他应用高频"))
    }

    private companion object {
        const val APP_A = "example.alpha"
        const val APP_B = "example.beta"
    }
}
