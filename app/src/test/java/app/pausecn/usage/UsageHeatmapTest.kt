package app.pausecn.usage

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageHeatmapTest {
    @Test
    fun `separate consent periods in one hour keep their gap partial`() {
        val rows = listOf(
            row("period-1", APP_A, "UTC", 0, 10 * MINUTE, foreground = 5 * MINUTE, unknown = 50 * MINUTE),
            row("period-2", APP_A, "UTC", 50 * MINUTE, HOUR, foreground = 5 * MINUTE, unknown = 50 * MINUTE),
        )

        val cell = cell(HeatmapMetric.FOREGROUND, setOf(APP_A), rows, emptyList())

        assertEquals(10 * MINUTE, cell.value)
        assertTrue(cell.partial)
        assertEquals(HOUR, cell.actualDurationMs)
    }

    @Test
    fun `old zones and unselected packages are filtered while pauses use supplied facts`() {
        val rows = listOf(
            row("selected", APP_A, "UTC", 0, HOUR, foreground = 7 * MINUTE, unknown = 0),
            row("old-zone", APP_A, "Asia/Shanghai", 0, HOUR, foreground = 13 * MINUTE, unknown = 0),
            row("other-app", APP_B, "UTC", 0, HOUR, foreground = 17 * MINUTE, unknown = 0),
        )
        val pauses = listOf(
            UsagePausePoint(APP_A, 1_000),
            UsagePausePoint(APP_B, 2_000),
        )

        assertEquals(7 * MINUTE, cell(HeatmapMetric.FOREGROUND, setOf(APP_A), rows, pauses).value)
        assertEquals(1L, cell(HeatmapMetric.PAUSES, setOf(APP_A), rows, pauses).value)
    }

    @Test
    fun `clock rollback rejects snapshots evaluated or captured in the future`() {
        val rows = listOf(
            row("valid", APP_A, "UTC", 0, 10 * MINUTE,
                foreground = 5 * MINUTE, unknown = 50 * MINUTE, capturedAt = 10 * MINUTE),
            row("future-evaluation", APP_A, "UTC", 0, 40 * MINUTE,
                foreground = 20 * MINUTE, unknown = 20 * MINUTE, capturedAt = 10 * MINUTE),
            row("future-capture", APP_A, "UTC", 0, 10 * MINUTE,
                foreground = 7 * MINUTE, unknown = 50 * MINUTE, capturedAt = 40 * MINUTE),
        )

        val cell = cell(HeatmapMetric.FOREGROUND, setOf(APP_A), rows, emptyList(), now = 30 * MINUTE)

        assertEquals(5 * MINUTE, cell.value)
        assertEquals(10 * MINUTE, cell.capturedAt)
        assertTrue(cell.partial)
    }

    private fun cell(metric: HeatmapMetric, packages: Set<String>, rows: List<UsageHourEntity>,
        pauses: List<UsagePausePoint>, now: Long = HOUR): HeatmapCell = UsageHeatmap.cells(
        TODAY, ZoneId.of("UTC"), now, packages, metric, rows, pauses,
    ).single { it.date == TODAY && it.hour == 0 }

    private fun row(period: String, pkg: String, zone: String, evaluatedFrom: Long, evaluatedTo: Long,
        foreground: Long, unknown: Long, capturedAt: Long = HOUR) = UsageHourEntity(
        periodId = period, packageName = pkg, zoneId = zone, start = 0, end = HOUR,
        localDate = TODAY.toString(), localHour = 0,
        evaluatedFrom = evaluatedFrom, evaluatedTo = evaluatedTo,
        foregroundMs = foreground, unknownMs = unknown,
        completeness = if (unknown == 0L) UsageCompleteness.ESTIMATED.name else UsageCompleteness.PARTIAL.name,
        limitations = "", capturedAt = capturedAt,
    )

    private companion object {
        val TODAY: LocalDate = LocalDate.of(1970, 1, 1)
        const val APP_A = "app.a"
        const val APP_B = "app.b"
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
