package app.pausecn.reports

import app.pausecn.usage.HeatmapMetric
import app.pausecn.usage.UsageHeatmap
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportWindowTest {
    @Test
    fun `four periods use Monday boundaries across calendar years`() {
        val zone = ZoneId.of("UTC")
        val now = Instant.parse("2024-01-01T12:00:00Z").toEpochMilli()

        val today = ReportWindow.at(ReportPeriod.TODAY, now, zone)
        val yesterday = ReportWindow.at(ReportPeriod.YESTERDAY, now, zone)
        val thisWeek = ReportWindow.at(ReportPeriod.THIS_WEEK, now, zone)
        val lastWeek = ReportWindow.at(ReportPeriod.LAST_WEEK, now, zone)

        assertEquals(LocalDate.of(2024, 1, 1), today.startDate)
        assertEquals(LocalDate.of(2024, 1, 2), today.endDateExclusive)
        assertEquals(LocalDate.of(2023, 12, 31), yesterday.startDate)
        assertEquals(LocalDate.of(2024, 1, 1), yesterday.endDateExclusive)
        assertEquals(LocalDate.of(2024, 1, 1), thisWeek.startDate)
        assertEquals(LocalDate.of(2024, 1, 8), thisWeek.endDateExclusive)
        assertEquals(LocalDate.of(2023, 12, 25), lastWeek.startDate)
        assertEquals(LocalDate.of(2024, 1, 1), lastWeek.endDateExclusive)
        assertEquals(now, today.cutoff)
        assertEquals(yesterday.end, yesterday.cutoff)
        assertEquals(lastWeek.end, lastWeek.cutoff)
    }

    @Test
    fun `DST transition report days contain 23 and 25 real hours`() {
        val zone = ZoneId.of("America/New_York")

        fun duration(date: LocalDate): Long {
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val cells = UsageHeatmap.cellsBetween(
                date, date.plusDays(1), zone, end, setOf("app.a"), HeatmapMetric.PAUSES,
                emptyList(), emptyList(),
            )
            assertEquals(end - start, cells.sumOf { it.actualDurationMs })
            return end - start
        }

        assertEquals(23 * HOUR, duration(LocalDate.of(2024, 3, 10)))
        assertEquals(25 * HOUR, duration(LocalDate.of(2024, 11, 3)))
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
