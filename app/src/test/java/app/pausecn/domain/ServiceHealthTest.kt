package app.pausecn.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceHealthTest {
    private val now = 2_000_000_000L
    private val nowElapsed = 8_000_000L

    @Test
    fun disabledPermissionAlwaysReportsDisabled() {
        assertEquals(
            ServiceHealth.DISABLED,
            health(accessibilityEnabled = false, heartbeatEpochMs = now - 1_000, heartbeatElapsedMs = nowElapsed - 1_000),
        )
    }

    @Test
    fun enabledWithoutHeartbeatIsStarting() {
        assertEquals(ServiceHealth.STARTING, health(heartbeatEpochMs = 0, heartbeatElapsedMs = -1))
        assertEquals(
            "A newly observed enabled permission receives a short service startup grace period.",
            ServiceHealth.STARTING,
            health(
                heartbeatEpochMs = 0,
                heartbeatElapsedMs = -1,
                enabledObservedEpochMs = now - 119_000,
                enabledObservedElapsedMs = nowElapsed - 119_000,
            ),
        )
        assertEquals(
            "An enabled permission that never produces a heartbeat must become actionable.",
            ServiceHealth.STALE,
            health(
                heartbeatEpochMs = 0,
                heartbeatElapsedMs = -1,
                enabledObservedEpochMs = now - 121_000,
                enabledObservedElapsedMs = nowElapsed - 121_000,
            ),
        )
        assertEquals(
            "An epoch-only heartbeat from an older build must use the bounded enabled-observation deadline.",
            ServiceHealth.STALE,
            health(
                heartbeatEpochMs = now - 1_000,
                heartbeatElapsedMs = -1,
                enabledObservedEpochMs = now - 121_000,
                enabledObservedElapsedMs = nowElapsed - 121_000,
            ),
        )
        assertEquals(
            "A previous-boot observation uses wall time only for the bounded startup grace period.",
            ServiceHealth.STALE,
            health(
                heartbeatEpochMs = 0,
                heartbeatElapsedMs = -1,
                enabledObservedEpochMs = now - 121_000,
                enabledObservedElapsedMs = nowElapsed + 1,
                enabledObservedBootCount = 39,
            ),
        )
        assertEquals(
            "A rollback after the observation time must fail out of the starting state.",
            ServiceHealth.STALE,
            health(
                heartbeatEpochMs = 0,
                heartbeatElapsedMs = -1,
                enabledObservedEpochMs = now + 1,
                enabledObservedElapsedMs = nowElapsed + 1,
                enabledObservedBootCount = 39,
            ),
        )
    }

    @Test
    fun futureHeartbeatIsStartingInsteadOfHealthy() {
        assertEquals(
            ServiceHealth.STARTING,
            health(heartbeatEpochMs = now + 1, heartbeatElapsedMs = nowElapsed + 1),
        )
    }

    @Test
    fun recentHeartbeatIsHealthy() {
        assertEquals(
            ServiceHealth.HEALTHY,
            health(heartbeatEpochMs = now - 59 * 60_000, heartbeatElapsedMs = nowElapsed - 59 * 60_000),
        )
        assertEquals(
            "Wall-clock rollback must not override the monotonic heartbeat age.",
            ServiceHealth.HEALTHY,
            health(heartbeatEpochMs = now + 12 * 60 * 60_000, heartbeatElapsedMs = nowElapsed - 1_000),
        )
        assertEquals(
            "Wall-clock advance must not override the monotonic heartbeat age.",
            ServiceHealth.HEALTHY,
            health(heartbeatEpochMs = now - 12 * 60 * 60_000, heartbeatElapsedMs = nowElapsed - 1_000),
        )
    }

    @Test
    fun oldHeartbeatIsStale() {
        assertEquals(
            ServiceHealth.STALE,
            health(heartbeatEpochMs = now - 61 * 60_000, heartbeatElapsedMs = nowElapsed - 61 * 60_000),
        )
        assertEquals(
            "A recent heartbeat from the previous boot receives the normal startup grace period.",
            ServiceHealth.STARTING,
            health(
                heartbeatEpochMs = now - 10 * 60_000,
                heartbeatElapsedMs = nowElapsed + 1,
                heartbeatBootCount = 40,
                currentBootCount = 41,
            ),
        )
        assertEquals(
            "An old heartbeat from the previous boot must become actionable.",
            ServiceHealth.STALE,
            health(
                heartbeatEpochMs = now - 61 * 60_000,
                heartbeatElapsedMs = nowElapsed + 1,
                heartbeatBootCount = 40,
                currentBootCount = 41,
            ),
        )
    }

    private fun health(
        accessibilityEnabled: Boolean = true,
        heartbeatEpochMs: Long,
        heartbeatElapsedMs: Long,
        heartbeatBootCount: Int = 40,
        enabledObservedEpochMs: Long = 0,
        enabledObservedElapsedMs: Long = -1,
        enabledObservedBootCount: Int = 40,
        currentBootCount: Int = 40,
    ): ServiceHealth = evaluateServiceHealth(
        accessibilityEnabled = accessibilityEnabled,
        lastHeartbeatEpochMs = heartbeatEpochMs,
        lastHeartbeatElapsedMs = heartbeatElapsedMs,
        lastHeartbeatBootCount = heartbeatBootCount,
        enabledObservedEpochMs = enabledObservedEpochMs,
        enabledObservedElapsedMs = enabledObservedElapsedMs,
        enabledObservedBootCount = enabledObservedBootCount,
        nowEpochMs = now,
        nowElapsedMs = nowElapsed,
        currentBootCount = currentBootCount,
    )
}
