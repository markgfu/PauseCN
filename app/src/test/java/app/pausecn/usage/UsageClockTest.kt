package app.pausecn.usage

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageClockTest {
    @Test
    fun `same boot with matching wall and elapsed progress is stable`() {
        val previous = time(wall = 1_000_000, elapsed = 200_000)
        val current = time(wall = 1_060_000, elapsed = 260_000)

        assertEquals(UsageClockChange.STABLE, UsageClock.change(previous, current))
    }

    @Test
    fun `wall clock forward jump and rollback are detected`() {
        val previous = time(wall = 1_000_000, elapsed = 200_000)

        assertEquals(UsageClockChange.WALL_CHANGED, UsageClock.change(
            previous, time(wall = 1_180_001, elapsed = 260_000),
        ))
        assertEquals(UsageClockChange.WALL_CHANGED, UsageClock.change(
            previous, time(wall = 999_999, elapsed = 260_000),
        ))
    }

    @Test
    fun `boot identity change or elapsed reset is a reboot`() {
        val previous = time(wall = 1_000_000, elapsed = 200_000)

        assertEquals(UsageClockChange.REBOOT, UsageClock.change(
            previous, time(wall = 1_010_000, elapsed = 10_000),
        ))
        assertEquals(UsageClockChange.REBOOT, UsageClock.change(
            previous, time(wall = 1_060_000, elapsed = 260_000, boot = "boot:other"),
        ))
    }

    @Test
    fun `zone change is detected after otherwise stable progress`() {
        val previous = time(wall = 1_000_000, elapsed = 200_000, zone = "Asia/Shanghai")
        val current = time(wall = 1_060_000, elapsed = 260_000, zone = "America/New_York")

        assertEquals(UsageClockChange.ZONE_CHANGED, UsageClock.change(previous, current))
    }

    private fun time(wall: Long, elapsed: Long, boot: String = "boot:test", zone: String = "UTC") =
        UsageTime(wall, elapsed, boot, zone)
}
