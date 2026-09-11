package app.pausecn.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PauseDisplayTest {
    @Test
    fun `open or incomplete display never turns countdown time into duration`() {
        val display = display()

        assertNull(display.durationMs)
        assertNull(display.copy(status = "CONFIRMED", endedAt = 120_000).durationMs)
        assertNull(display.copy(status = "UNKNOWN_END").durationMs)
    }

    @Test
    fun `only stable same boot close confirms monotonic duration`() {
        val display = display()

        val stable = display.closed(time(wall = 107_000, elapsed = 6_000))
        assertEquals("CONFIRMED", stable.status)
        assertEquals(5_000L, stable.durationMs)

        val rebooted = display.closed(time(wall = 107_000, elapsed = 500, boot = "boot:other"))
        assertEquals("CLOCK_CHANGED", rebooted.status)
        assertNull(rebooted.durationMs)

        val wallCorrected = display.closed(time(wall = 170_001, elapsed = 6_000))
        assertEquals("CLOCK_CHANGED", wallCorrected.status)
        assertNull(wallCorrected.durationMs)
    }

    private fun display() = PauseDisplay(
        id = "private-display-id",
        packageName = "app.target",
        startedAt = 100_000,
        startedElapsed = 1_000,
        bootId = "boot:test",
        zoneId = "UTC",
    )

    private fun time(wall: Long, elapsed: Long, boot: String = "boot:test") =
        UsageTime(wall, elapsed, boot, "UTC")
}
