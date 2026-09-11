package app.pausecn.reports

import app.pausecn.usage.UsageAggregation
import app.pausecn.usage.UsageCompleteness
import app.pausecn.usage.UsageHourEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportFactBuilderTest {
    @Test
    fun `cutoff and target filters are half open and failed displays are not pauses`() {
        val window = window(ReportPeriod.TODAY, "2026-09-08T12:00:00Z")
        val events = listOf(
            ReportEvent(1, APP, window.start + HOUR, "SHOWN"),
            ReportEvent(2, APP, window.start + 2 * HOUR, "DISPLAY_FAILED"),
            ReportEvent(3, APP, window.cutoff, "CONTINUED"),
            ReportEvent(4, OTHER_APP, window.start + HOUR, "EXITED"),
        )

        val facts = build(window, events = events)

        assertEquals(1, facts.counts.pending)
        assertEquals(1, facts.counts.displayFailed)
        assertEquals(0, facts.counts.exited)
        assertEquals(0, facts.counts.continued)
        assertEquals(1, facts.counts.recorded)
        assertNull(facts.counts.exitPercent)
        assertEquals(1L, facts.pauseCells.sumOf { it.value ?: 0 })
    }

    @Test
    fun `daily and weekly facts directly sum retained hourly snapshots`() {
        val yesterday = window(ReportPeriod.YESTERDAY, "2026-09-08T12:00:00Z")
        val yesterdayHour = hour(yesterday.start, 7 * MINUTE, capturedAt = yesterday.end + HOUR)
        val daily = build(yesterday, observedAt = yesterday.end + 2 * HOUR, hours = listOf(yesterdayHour))
        assertEquals(7 * MINUTE, daily.foregroundMs)
        assertEquals(yesterday.end + HOUR, daily.usageCapturedAt)

        val week = window(ReportPeriod.THIS_WEEK, "2026-09-10T12:00:00Z")
        val weekly = build(
            week,
            hours = listOf(
                hour(week.start, 11 * MINUTE, capturedAt = week.cutoff),
                hour(week.start + 2 * 86_400_000L, 13 * MINUTE, capturedAt = week.cutoff),
            ),
        )
        assertEquals(24 * MINUTE, weekly.foregroundMs)
    }

    @Test
    fun `missing and old-zone usage remain unknown instead of becoming zero`() {
        val window = window(ReportPeriod.TODAY, "2026-09-08T12:00:00Z")
        val oldZone = hour(window.start, 20 * MINUTE, zone = "Asia/Shanghai", capturedAt = window.cutoff)

        val facts = build(window, hours = listOf(oldZone))

        assertNull(facts.foregroundMs)
        assertTrue(facts.usagePartial)
        assertTrue(facts.notes.any { it.contains("旧时区") })
        assertTrue(facts.notes.any { it.contains("未知不等于零") })
    }

    @Test
    fun `source fingerprint represents selected facts while cutoff versions the report`() {
        val firstWindow = window(ReportPeriod.TODAY, "2026-09-08T10:00:00Z")
        val laterWindow = window(ReportPeriod.TODAY, "2026-09-08T11:00:00Z")
        val event = ReportEvent(1, APP, firstWindow.start + HOUR, "EXITED")
        val first = build(firstWindow, events = listOf(event))
        val later = build(laterWindow, events = listOf(event))

        assertEquals(first.sourceFingerprint, later.sourceFingerprint)
        assertNotEquals(first.fingerprint, later.fingerprint)
        assertEquals(event.occurredAtEpochMs + RETENTION, first.validUntil)
        assertFalse(first.window.ongoing.not())
    }

    @Test
    fun `category summaries aggregate each selected package without changing overall totals`() {
        val window = window(ReportPeriod.TODAY, "2026-09-08T12:00:00Z")
        val targets = listOf(
            ReportTarget(APP, "应用甲", window.start, "学习阅读"),
            ReportTarget(OTHER_APP, "应用乙", window.start, "学习阅读"),
        )
        val facts = ReportFactBuilder.build(
            window, window.cutoff, targets,
            listOf(ReportEvent(1, APP, window.start + HOUR, "EXITED"), ReportEvent(2, OTHER_APP, window.start + HOUR, "CONTINUED")),
            listOf(hour(window.start, 7 * MINUTE, capturedAt = window.cutoff, pkg = APP),
                hour(window.start, 5 * MINUTE, capturedAt = window.cutoff, pkg = OTHER_APP)),
            RETENTION, true, true,
        )

        assertEquals(2, facts.counts.recorded)
        assertEquals(12 * MINUTE, facts.foregroundMs)
        assertEquals(1, facts.categories.size)
        assertEquals(2, facts.categories.single().counts.recorded)
        assertEquals(12 * MINUTE, facts.categories.single().foregroundMs)
    }

    private fun build(
        window: ReportWindow,
        observedAt: Long = window.cutoff,
        events: List<ReportEvent> = emptyList(),
        hours: List<UsageHourEntity> = emptyList(),
    ) = ReportFactBuilder.build(
        window = window,
        observedAt = observedAt,
        targets = listOf(ReportTarget(APP, "应用甲", window.start)),
        events = events,
        hours = hours,
        retentionMs = RETENTION,
        usageEnabled = true,
        permissionObserved = true,
    )

    private fun window(period: ReportPeriod, now: String): ReportWindow =
        ReportWindow.at(period, Instant.parse(now).toEpochMilli(), ZoneId.of("UTC"))

    private fun hour(start: Long, foreground: Long, zone: String = "UTC", capturedAt: Long, pkg: String = APP) = UsageHourEntity(
        periodId = "period-$start-$zone",
        packageName = pkg,
        zoneId = zone,
        start = start,
        end = start + HOUR,
        algorithm = UsageAggregation.VERSION,
        localDate = Instant.ofEpochMilli(start).atZone(ZoneId.of(zone)).toLocalDate().toString(),
        localHour = Instant.ofEpochMilli(start).atZone(ZoneId.of(zone)).hour,
        evaluatedFrom = start,
        evaluatedTo = start + HOUR,
        foregroundMs = foreground,
        unknownMs = 0,
        completeness = UsageCompleteness.ESTIMATED.name,
        limitations = "fixture",
        capturedAt = capturedAt,
    )

    private companion object {
        const val APP = "app.a"
        const val OTHER_APP = "app.b"
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val RETENTION = 30L * 86_400_000L
    }
}
