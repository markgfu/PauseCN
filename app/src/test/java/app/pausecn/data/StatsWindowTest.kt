package app.pausecn.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class StatsWindowTest {
    @Test
    fun `current week starts on local Monday instead of seven days ago`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 9, 3, 18, 30, 0, 0, zone).toInstant().toEpochMilli()
        val expected = ZonedDateTime.of(2026, 8, 31, 0, 0, 0, 0, zone).toInstant().toEpochMilli()

        assertEquals(expected, currentWeekStartEpochMs(now, zone))
        assertEquals(
            StatsWindow(expected, now + 60_000),
            currentWeekStatsWindow(now, zone),
        )
        assertEquals(60_000, millisUntilNextStatsMinute(now))
    }

    @Test
    fun `week boundary respects the device time zone`() {
        val zone = ZoneId.of("America/New_York")
        val monday = ZonedDateTime.of(2026, 3, 9, 0, 0, 0, 0, zone)

        assertEquals(
            monday.minusWeeks(1).toInstant().toEpochMilli(),
            currentWeekStartEpochMs(monday.minusNanos(1).toInstant().toEpochMilli(), zone),
        )
        assertEquals(
            monday.toInstant().toEpochMilli(),
            currentWeekStartEpochMs(monday.toInstant().toEpochMilli(), zone),
        )
        assertEquals(
            StatsWindow(
                startEpochMsInclusive = monday.toInstant().toEpochMilli(),
                endEpochMsExclusive = monday.plusMinutes(1).toInstant().toEpochMilli(),
            ),
            currentWeekStatsWindow(monday.toInstant().toEpochMilli(), zone),
        )

        val stats = StatsSnapshot(
            total = 5,
            exited = 1,
            continued = 1,
            dismissed = 2,
            displayFailed = 1,
        )
        assertEquals(4, stats.triggerSuccesses)
        assertEquals(80, stats.triggerSuccessRate)
        assertEquals(2, stats.completedDecisions)
        assertEquals(50, stats.exitRate)

        val beforeClockCorrection = currentWeekStatsWindow(
            monday.plusDays(3).plusHours(12).plusSeconds(45).toInstant().toEpochMilli(),
            zone,
        )
        val afterClockCorrection = currentWeekStatsWindow(
            monday.plusDays(2).plusHours(12).plusSeconds(45).toInstant().toEpochMilli(),
            zone,
        )
        assertEquals(
            beforeClockCorrection.endEpochMsExclusive - 24L * 60 * 60_000,
            afterClockCorrection.endEpochMsExclusive,
        )
        assertEquals(
            15_000,
            afterClockCorrection.endEpochMsExclusive -
                monday.plusDays(2).plusHours(12).plusSeconds(45).toInstant().toEpochMilli(),
        )
        assertEquals(
            15_000,
            millisUntilNextStatsMinute(
                monday.plusDays(2).plusHours(12).plusSeconds(45).toInstant().toEpochMilli(),
            ),
        )
        assertEquals(
            1,
            millisUntilNextStatsMinute(
                monday.plusDays(2).plusHours(12).plusSeconds(59).plusNanos(999_000_000)
                    .toInstant()
                    .toEpochMilli(),
            ),
        )
    }
}
