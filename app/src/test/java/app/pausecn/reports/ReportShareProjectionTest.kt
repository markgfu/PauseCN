package app.pausecn.reports

import app.pausecn.data.RulePatch
import app.pausecn.usage.HeatmapCell
import app.pausecn.usage.HeatmapMetric
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportShareProjectionTest {
    @Test
    fun `all three templates use the same core metrics`() {
        val facts = facts(foregroundMs = 3_661_000)
        val models = ReportTemplate.entries.map { template ->
            ReportShareProjection.build(
                facts,
                ReportShareOptions(template = template, allowHourlyPattern = template == ReportTemplate.HEATMAP),
            )
        }

        models.drop(1).forEach { assertEquals(models.first().metrics, it.metrics) }
        assertEquals(
            listOf("7 次", "2 次", "3 次", "1 小时 1 分钟"),
            models.first().metrics.map { it.value },
        )
    }

    @Test
    fun `default projection excludes app names hourly cells and private fact notes`() {
        val model = ReportShareProjection.build(
            facts(notes = listOf("私人记忆：不要分享", "package.secret")),
            ReportShareOptions(),
        )

        assertTrue(model.appNames.isEmpty())
        assertTrue(model.cells.isEmpty())
        assertFalse(model.toString().contains("私人记忆"))
        assertFalse(model.toString().contains("package.secret"))
    }

    @Test
    fun `heatmap requires confirmation and copies only the snapshot range`() {
        val facts = facts()
        assertThrows(IllegalArgumentException::class.java) {
            ReportShareProjection.build(facts, ReportShareOptions(template = ReportTemplate.HEATMAP))
        }

        val model = ReportShareProjection.build(
            facts,
            ReportShareOptions(
                template = ReportTemplate.HEATMAP,
                allowHourlyPattern = true,
                metric = HeatmapMetric.PAUSES,
            ),
        )
        assertEquals(facts.pauseCells.size, model.cells.size)
        assertEquals(facts.pauseCells.first().date.toString(), model.cells.first().date)
        assertEquals(facts.pauseCells.first().hour, model.cells.first().hour)
        assertEquals(facts.pauseCells.last().date.toString(), model.cells.last().date)
        assertEquals(facts.pauseCells.last().hour, model.cells.last().hour)
    }

    @Test
    fun `explicit app names are capped at eight without package identifiers`() {
        val targets = (1..10).map { ReportTarget("package.private.$it", "应用$it", 0) }

        val model = ReportShareProjection.build(
            facts(targets = targets),
            ReportShareOptions(showAppNames = true),
        )

        assertEquals((1..8).map { "应用$it" }, model.appNames)
        assertEquals(2, model.omittedAppNames)
        assertFalse(model.toString().contains("package.private"))
    }

    @Test
    fun `unknown duration is not zero and caption limit is 160 code points`() {
        val accepted = ReportShareProjection.build(
            facts(foregroundMs = null),
            ReportShareOptions(caption = "文".repeat(160)),
        )

        assertEquals("暂无可用数据", accepted.metrics.single { it.label == "目标前台时长之和" }.value)
        assertFalse(accepted.metrics.any { it.value == "0 秒" })
        assertEquals(160, accepted.caption.codePointCount(0, accepted.caption.length))
        assertThrows(IllegalArgumentException::class.java) {
            ReportShareProjection.build(facts(), ReportShareOptions(caption = "文".repeat(161)))
        }
    }

    @Test
    fun `AI text stays excluded by default even when an interpretation is available`() {
        val interpretation = interpretation()

        val model = ReportShareProjection.build(facts(), ReportShareOptions(), interpretation)

        assertTrue(model.aiObservations.isEmpty())
        assertEquals(null, model.aiSuggestion)
        assertFalse(model.toString().contains("傲娇观察原文"))
        assertFalse(model.toString().contains("傲娇建议原文"))
    }

    @Test
    fun `explicit AI sharing copies only observation text and suggestion without sources or rule patch`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReportShareProjection.build(facts(), ReportShareOptions(includeAi = true), null)
        }

        val model = ReportShareProjection.build(
            facts(),
            ReportShareOptions(includeAi = true),
            interpretation(),
        )

        assertEquals(listOf("傲娇观察原文"), model.aiObservations)
        assertEquals("傲娇建议原文", model.aiSuggestion)
        assertFalse(model.toString().contains("private_source_id"))
        assertFalse(model.toString().contains("waitSeconds"))
        assertFalse(model.toString().contains("RulePatch"))
    }

    @Test
    fun `categories stay private by default and appear only after explicit selection`() {
        val categorized = facts().copy(categories = listOf(
            ReportCategorySummary("学习阅读", ReportCounts(2, 1, 0, 0, 0), 90_000, true),
        ))

        val denied = ReportShareProjection.build(categorized, ReportShareOptions())
        assertTrue(denied.categories.isEmpty())
        assertFalse(denied.toString().contains("学习阅读"))

        val allowed = ReportShareProjection.build(categorized, ReportShareOptions(includeCategories = true))
        assertEquals("学习阅读", allowed.categories.single().label)
        assertTrue(allowed.categories.single().value.contains("3 次停顿"))
    }

    private fun interpretation() = ReportInterpretation(
        observations = listOf(ReportObservation("傲娇观察原文", listOf("private_source_id"))),
        suggestion = "傲娇建议原文",
        rulePatch = RulePatch(waitSeconds = 10),
    )

    private fun facts(
        foregroundMs: Long? = 90_000,
        targets: List<ReportTarget> = listOf(ReportTarget("package.secret", "私密应用", 0)),
        notes: List<String> = emptyList(),
    ): ReportFacts {
        val date = LocalDate.of(2026, 9, 9)
        fun cells() = (0..23).map { hour ->
            HeatmapCell(date, hour, if (hour == 1) null else hour.toLong(), hour == 2, 3_600_000)
        }
        return ReportFacts(
            window = ReportWindow(ReportPeriod.TODAY, date, date.plusDays(1), "UTC", 0, 86_400_000, 43_200_000),
            createdAt = 43_200_000,
            targets = targets,
            counts = ReportCounts(exited = 2, continued = 3, interrupted = 1, pending = 1, displayFailed = 0),
            foregroundMs = foregroundMs,
            usagePartial = true,
            usageCapturedAt = null,
            pauseCells = cells(),
            usageCells = cells(),
            notes = notes,
            fingerprint = "facts",
            sourceFingerprint = "source",
            validUntil = 86_400_000,
        )
    }
}
