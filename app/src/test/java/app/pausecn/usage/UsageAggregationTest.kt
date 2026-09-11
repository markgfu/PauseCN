package app.pausecn.usage

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageAggregationTest {
    @Test
    fun `overlapping activities are unioned and a refreshed calculation is identical`() {
        val signals = listOf(
            resumed(10, "ActivityA"),
            resumed(20, "ActivityB"),
            paused(30, "ActivityA"),
            stopped(40, "ActivityB"),
        )

        val first = UsageAggregation.estimate(APP, UsageSpan(0, 60), signals)
        val refreshed = UsageAggregation.estimate(APP, UsageSpan(0, 60), signals)

        assertEquals(listOf(UsageSpan(10, 40)), first.foreground)
        assertEquals(30L, first.foregroundMs)
        assertEquals(first, refreshed)
    }

    @Test
    fun `pause followed by stop closes an activity only once`() {
        val estimate = UsageAggregation.estimate(APP, UsageSpan(0, 40), listOf(
            resumed(10, "ActivityA"),
            paused(20, "ActivityA"),
            stopped(30, "ActivityA"),
        ))

        assertEquals(listOf(UsageSpan(10, 20)), estimate.foreground)
        assertEquals(listOf(UsageSpan(0, 10)), estimate.unknown)
        assertTrue("CLOSE_WITHOUT_RESUME" !in estimate.limitations)
    }

    @Test
    fun `empty input remains unknown instead of becoming a zero estimate`() {
        val window = UsageSpan(0, HOUR)
        val estimate = UsageAggregation.estimate(APP, window, emptyList())
        val hour = UsageAggregation.hourly(
            estimate, LocalDate.of(1970, 1, 1), ZoneId.of("UTC"), listOf(window),
        ).first()

        assertEquals(emptyList<UsageSpan>(), estimate.foreground)
        assertEquals(listOf(window), estimate.unknown)
        assertEquals(UsageCompleteness.UNKNOWN, hour.completeness)
        assertNull(hour.foregroundMs)
        assertEquals(HOUR, hour.unknownMs)
    }

    @Test
    fun `missing endpoints and restart never invent foreground duration`() {
        val window = UsageSpan(0, 50)
        val cases = listOf(
            listOf(paused(20, "ActivityA")),
            listOf(resumed(10, "ActivityA")),
            listOf(resumed(10, "ActivityA"), signal(20, UsageEventKind.STARTUP), paused(30, "ActivityA")),
        )

        cases.forEach { signals ->
            val estimate = UsageAggregation.estimate(APP, window, signals)
            assertEquals(0L, estimate.foregroundMs)
            assertTrue(estimate.unknown.isNotEmpty())
        }
    }

    @Test
    fun `shutdown confirms prior foreground while restart gap remains unknown`() {
        val estimate = UsageAggregation.estimate(APP, UsageSpan(0, 50), listOf(
            resumed(10, "ActivityA"),
            signal(20, UsageEventKind.SHUTDOWN),
            signal(30, UsageEventKind.STARTUP),
        ))

        assertEquals(listOf(UsageSpan(10, 20)), estimate.foreground)
        assertEquals(10L, estimate.foregroundMs)
        assertTrue(estimate.unknown.any { unknown ->
            unknown.intersect(UsageSpan(20, 30)) == UsageSpan(20, 30)
        })
    }

    @Test
    fun `screen and lock boundaries close active use and reopening stays unknown until resume`() {
        val estimate = UsageAggregation.estimate(APP, UsageSpan(0, 70), listOf(
            resumed(10, "ActivityA"),
            signal(20, UsageEventKind.SCREEN_OFF),
            signal(25, UsageEventKind.LOCKED),
            signal(30, UsageEventKind.SCREEN_ON),
            signal(35, UsageEventKind.UNLOCKED),
            resumed(40, "ActivityA"),
            paused(50, "ActivityA"),
        ))

        assertEquals(listOf(UsageSpan(10, 20), UsageSpan(40, 50)), estimate.foreground)
        assertEquals(listOf(UsageSpan(0, 10), UsageSpan(30, 40)), estimate.unknown)
    }

    @Test
    fun `allowed intervals clip evaluation and foreground across midnight`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val firstDate = LocalDate.of(2026, 9, 8)
        val midnight = firstDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val window = UsageSpan(midnight - 30 * MINUTE, midnight + 30 * MINUTE)
        val allowed = listOf(UsageSpan(midnight - 15 * MINUTE, midnight + 15 * MINUTE))
        val estimate = UsageEstimate(APP, window, listOf(window), emptyList(), emptySet())

        val before = UsageAggregation.hourly(estimate, firstDate, zone, allowed).single { it.hour == 23 }
        val after = UsageAggregation.hourly(estimate, firstDate.plusDays(1), zone, allowed).single { it.hour == 0 }

        listOf(before, after).forEach { hour ->
            assertEquals(15 * MINUTE, hour.foregroundMs)
            assertEquals(45 * MINUTE, hour.unknownMs)
            assertEquals(UsageCompleteness.PARTIAL, hour.completeness)
            assertEquals(listOf(15 * MINUTE), hour.evaluatedParts.map(UsageSpan::duration))
        }
    }

    @Test
    fun `disjoint allowed intervals preserve the unauthorized gap`() {
        val zone = ZoneId.of("UTC")
        val date = LocalDate.of(1970, 1, 1)
        val window = UsageSpan(0, HOUR)
        val allowed = listOf(UsageSpan(0, 10 * MINUTE), UsageSpan(50 * MINUTE, HOUR))
        val estimate = UsageEstimate(APP, window, listOf(window), emptyList(), emptySet())

        val hour = UsageAggregation.hourly(estimate, date, zone, allowed).first()

        assertEquals(allowed, hour.evaluatedParts)
        assertEquals(20 * MINUTE, hour.foregroundMs)
        assertEquals(40 * MINUTE, hour.unknownMs)
        assertEquals(UsageCompleteness.PARTIAL, hour.completeness)
    }

    @Test
    fun `DST days expose missing and repeated local hours as real UTC intervals`() {
        val zone = ZoneId.of("America/New_York")
        val spring = UsageAggregation.hours(LocalDate.of(2026, 3, 8), zone)
        val fallDate = LocalDate.of(2026, 11, 1)
        val fall = UsageAggregation.hours(fallDate, zone)
        val fallLabels = fall.map { span ->
            java.time.Instant.ofEpochMilli(span.start).atZone(zone).hour
        }

        assertEquals(23, spring.size)
        assertTrue(spring.all { it.duration == HOUR })
        assertEquals(25, fall.size)
        assertTrue(fall.all { it.duration == HOUR })
        assertEquals(2, fallLabels.count { it == 1 })
        assertTrue(2 !in spring.map { java.time.Instant.ofEpochMilli(it.start).atZone(zone).hour })
    }

    private fun resumed(at: Long, activity: String) = signal(at, UsageEventKind.RESUMED, activity)
    private fun paused(at: Long, activity: String) = signal(at, UsageEventKind.PAUSED, activity)
    private fun stopped(at: Long, activity: String) = signal(at, UsageEventKind.STOPPED, activity)
    private fun signal(at: Long, kind: UsageEventKind, activity: String = "") =
        UsageSignal(at, kind, if (kind in ACTIVITY_KINDS) APP else "", activity)

    private companion object {
        const val APP = "app.a"
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        val ACTIVITY_KINDS = setOf(UsageEventKind.RESUMED, UsageEventKind.PAUSED, UsageEventKind.STOPPED)
    }
}
