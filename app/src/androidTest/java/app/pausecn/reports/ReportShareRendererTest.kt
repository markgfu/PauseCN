package app.pausecn.reports

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReportShareRendererTest {
    @Test
    fun rendersExplicitNonUserExampleWithAiTextToBoundedPng() {
        val model = ReportShareModel(
            template = ReportTemplate.REFLECTION,
            dates = "示例 · 非用户数据 · 2026-09-01 — 2026-09-07",
            cutoff = "截至 09-08 00:00（不含截止瞬间） · Asia/Shanghai",
            metrics = listOf(
                SharedReportMetric("已记录停顿", "示例 7 次"),
                SharedReportMetric("主动离开", "示例 2 次"),
                SharedReportMetric("继续打开", "示例 3 次"),
                SharedReportMetric("目标前台时长之和", "示例 1 小时 1 分钟"),
            ),
            neutralSummary = "示例复盘：记录是为了理解自己的选择，不是给自己打分。",
            appNames = emptyList(),
            omittedAppNames = 0,
            cells = emptyList(),
            heatmapUnit = "次",
            caption = "示例感想：今天也给注意力留了一点空间。",
            notes = listOf("示例 · 非用户数据；仅用于本地渲染排版烟测。"),
            aiObservations = listOf(
                "示例观察一：哼，至少这次停顿让选择慢了半拍。",
                "示例观察二：别误会，只是继续打开也可以是想清楚后的决定。",
            ),
            aiSuggestion = "示例建议：下次先停一下，再由你自己决定。",
        )

        val bitmap = ReportShareRenderer.render(model)
        try {
            assertEquals(1080, bitmap.width)
            assertTrue(bitmap.height in 1400..8000)
            val output = File(
                requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.externalCacheDir),
                "alpha24-render-example.png",
            )
            output.outputStream().use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            assertTrue(output.isFile)
            assertTrue(output.length() > 0)
        } finally {
            bitmap.recycle()
        }
    }
}
