package app.pausecn.reports

import app.pausecn.data.ExportStamp
import app.pausecn.usage.HeatmapCell
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ReportCacheTest {
    @Test
    fun `codec round trips unknown zero and complete heatmap cells`() {
        val original = snapshot()

        val restored = ReportCacheCodec.decode(ReportCacheCodec.encode(original))

        assertEquals(original, restored)
        assertEquals(0L, restored.facts.pauseCells[0].value)
        assertEquals(null, restored.facts.pauseCells[1].value)
        assertEquals(24, restored.facts.usageCells.size)
    }

    @Test
    fun `codec rejects unsupported versions and oversized input`() {
        val wrongVersion = JSONObject(ReportCacheCodec.encode(snapshot())).put("version", 3).toString()

        assertThrows(IllegalArgumentException::class.java) { ReportCacheCodec.decode(wrongVersion) }
        assertThrows(IllegalArgumentException::class.java) {
            ReportCacheCodec.decode("x".repeat(ReportCacheCodec.MAX_JSON + 1))
        }
    }

    @Test
    fun `day and week slots survive relative label changes and ignore templates`() {
        val zone = ZoneId.of("UTC")
        fun at(period: ReportPeriod, instant: String) =
            ReportWindow.at(period, Instant.parse(instant).toEpochMilli(), zone)

        val today = at(ReportPeriod.TODAY, "2026-09-09T12:00:00Z")
        val sameDayAsYesterday = at(ReportPeriod.YESTERDAY, "2026-09-10T12:00:00Z")
        val thisWeek = at(ReportPeriod.THIS_WEEK, "2026-09-09T12:00:00Z")
        val sameWeekAsLastWeek = at(ReportPeriod.LAST_WEEK, "2026-09-16T12:00:00Z")

        assertEquals(today.slot, sameDayAsYesterday.slot)
        assertEquals(thisWeek.slot, sameWeekAsLastWeek.slot)
        ReportTemplate.entries.forEach { assertFalse(today.slot.contains(it.name)) }
    }

    @Test
    fun `boot change prevents restoring an otherwise current snapshot`() {
        val original = snapshot(bootId = "boot-a")

        assertFalse(original.canKeepComparedWith(original.copy(bootId = "boot-b")))
    }

    @Test
    fun `serialized interpretation remains parseable by the report protocol`() {
        val interpretation = ReportInterpretation(
            observations = listOf(ReportObservation("这是一条观察", listOf("recorded_pauses"))),
            suggestion = "可以先观察，再自行决定。",
        )

        assertEquals(
            interpretation,
            ReportProtocol.parse(ReportCacheCodec.interpretation(interpretation), setOf("recorded_pauses")),
        )
    }

    private fun snapshot(bootId: String = "boot-a"): LocalReportSnapshot {
        val date = LocalDate.of(2026, 9, 9)
        fun cells() = (0..23).map { hour ->
            HeatmapCell(
                date = date,
                hour = hour,
                value = when (hour) { 0 -> 0L; 1 -> null; else -> hour * 1_000L },
                partial = hour == 1,
                actualDurationMs = 3_600_000,
                future = false,
                capturedAt = if (hour == 1) null else 123_000,
            )
        }
        val window = ReportWindow(
            ReportPeriod.TODAY, date, date.plusDays(1), "UTC", 0, 86_400_000, 43_200_000,
        )
        val facts = ReportFacts(
            window = window,
            createdAt = 43_200_000,
            targets = listOf(ReportTarget("app.a", "应用甲", 1)),
            counts = ReportCounts(1, 0, 0, 0, 0),
            foregroundMs = null,
            usagePartial = true,
            usageCapturedAt = null,
            pauseCells = cells(),
            usageCells = cells(),
            notes = listOf("未知不等于零"),
            fingerprint = "fingerprint",
            sourceFingerprint = "source",
            validUntil = 90_000_000,
            categories = listOf(ReportCategorySummary("学习阅读", ReportCounts(1, 0, 0, 0, 0), null, true)),
        )
        return LocalReportSnapshot(
            facts, ExportStamp("instance", 1, 2, 3, "usage-revision", categoryRevision = 7), setOf(2, 1),
            setOf("hour-b", "hour-a"), 30, 42_000_000, true, bootId,
        )
    }
}
