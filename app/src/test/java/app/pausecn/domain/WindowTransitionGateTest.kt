package app.pausecn.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowTransitionGateTest {
    @Test
    fun `launcher dismisses without cooldown and only blocks events through its source time`() {
        val gate = WindowTransitionGate()

        assertEquals(
            WindowDismissal.DISMISS_ONLY,
            gate.onDismissal(sourceUptimeMs = 100, nowUptimeMs = 130, critical = false),
        )
        assertFalse(gate.shouldEvaluateTarget(sourceUptimeMs = 100, nowUptimeMs = 131))
        assertTrue(gate.shouldEvaluateTarget(sourceUptimeMs = 101, nowUptimeMs = 131))
    }

    @Test
    fun `critical surface dismisses with cooldown and blocks queued targets through receipt time`() {
        val gate = WindowTransitionGate()

        assertEquals(
            WindowDismissal.DISMISS_WITH_COOLDOWN,
            gate.onDismissal(sourceUptimeMs = 100, nowUptimeMs = 130, critical = true),
        )
        assertFalse(gate.shouldEvaluateTarget(sourceUptimeMs = 110, nowUptimeMs = 131))
        assertFalse(gate.shouldEvaluateTarget(sourceUptimeMs = 130, nowUptimeMs = 131))
        assertTrue(gate.shouldEvaluateTarget(sourceUptimeMs = 131, nowUptimeMs = 131))
    }

    @Test
    fun `stale launcher cannot dismiss a newer target and lock boundary rejects pending events`() {
        val gate = WindowTransitionGate()

        assertTrue(gate.shouldEvaluateTarget(sourceUptimeMs = 200, nowUptimeMs = 220))
        assertEquals(
            WindowDismissal.IGNORE,
            gate.onDismissal(sourceUptimeMs = 199, nowUptimeMs = 230, critical = false),
        )

        gate.blockPendingEvents(nowUptimeMs = 240)
        assertFalse(gate.shouldEvaluateTarget(sourceUptimeMs = 239, nowUptimeMs = 250))
        assertFalse(gate.shouldEvaluateTarget(sourceUptimeMs = 240, nowUptimeMs = 250))
        assertTrue(gate.shouldEvaluateTarget(sourceUptimeMs = 241, nowUptimeMs = 250))
    }
}
