package app.pausecn.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderContextTest {
    @Test
    fun `saved request context is explained while legacy cache is not reconstructed`() {
        val context = JSONObject()
            .put("style", "简短中性")
            .put("target_name", "阅读应用")
            .put("kind", "RECENT")
            .put("sources", JSONArray()
                .put(JSONObject().put("id", "memory_0").put("text", "先完成今天的阅读"))
                .put(JSONObject().put("id", "recent").put("continued", 2).put("exited", 1)))
            .toString()

        val lines = ReminderContext.lines(context)

        assertTrue(lines.any { it.contains("应用：阅读应用；风格：简短中性") })
        assertTrue(lines.any { it.contains("已确认记忆摘要") && it.contains("先完成今天的阅读") })
        assertTrue(lines.any { it.contains("继续 2 次") && it.contains("离开 1 次") })
        assertEquals(
            listOf("此旧版本短句未保存生成来源，不能根据现在的背景倒推。"),
            ReminderContext.lines(""),
        )
    }

    @Test
    fun `snapshot category is explained without treating unknown sources as known`() {
        val context = JSONObject()
            .put("style", "简短中性")
            .put("target_name", "计算器")
            .put("kind", "STABLE")
            .put("sources", JSONArray()
                .put(JSONObject().put("id", "app_category").put("category", "工具"))
                .put(JSONObject().put("id", "future_source").put("text", "不应猜测")))
            .toString()

        val lines = ReminderContext.lines(context)

        assertTrue(lines.any { it.contains("生成时允许参考的应用分类：工具") && it.contains("不是本次用途") })
        assertTrue(lines.any { it == "未识别的来源，不作解释。" })
        assertTrue(lines.none { it.contains("不应猜测") })
    }
}
