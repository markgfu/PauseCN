package app.pausecn.data

import android.content.Context
import android.os.SystemClock
import androidx.datastore.dataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalDataResetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun settingsResetReturnsEveryValueToPrivacySafeDefaults() = runBlocking {
        val resetStoreName = "settings-reset-${System.nanoTime()}"
        val store = SettingsStore(context, resetStoreName)
        try {
            store.acceptDisclosureAndAgeEligibility()
            store.completeOnboardingPreview()
            store.setScheduleEnabled(false)
            store.setInterventionSeconds(15)
            store.setTemporaryPassMinutes(30)
            store.setHistoryRetentionDays(365)
            store.pauseFor(15 * 60_000L)
            val paused = store.settings.first()
            assertTrue(paused.isGloballyPaused(System.currentTimeMillis(), SystemClock.elapsedRealtime()))

            store.resetAll()
            val reset = store.settings.first()

            assertFalse(reset.disclosureAccepted)
            assertFalse(reset.ageEligibilityConfirmed)
            assertFalse(reset.onboardingPreviewCompleted)
            assertTrue(reset.schedule.enabled)
            assertEquals(6, reset.interventionSeconds)
            assertEquals(5, reset.temporaryPassMinutes)
            assertEquals(DEFAULT_HISTORY_RETENTION_DAYS, reset.historyRetentionDays)
            assertEquals(0, reset.globallyPausedUntilEpochMs)
            assertEquals(0, reset.globallyPausedAtEpochMs)
            assertEquals(0, reset.globallyPausedAtElapsedMs)
            assertEquals(0, reset.globallyPausedUntilElapsedMs)
        } finally {
            store.close()
            context.dataStoreFile("$resetStoreName.preferences_pb").delete()
        }

        val corruptStoreName = "settings-corrupt-${System.nanoTime()}"
        val corruptFile = context.dataStoreFile("$corruptStoreName.preferences_pb")
        corruptFile.parentFile?.mkdirs()
        val corruptBytes = "pausecn-settings-corruption".toByteArray()
        corruptFile.writeBytes(corruptBytes)
        val healthStore = SettingsHealthStore(context, "settings-health-${System.nanoTime()}")
        val corruptStore = SettingsStore(context, corruptStoreName, healthStore)
        val recoveryManager = requireNotNull(corruptStore.recoveryManager)
        val monitor = SettingsHealthMonitor(corruptStore, healthStore, recoveryManager)
        try {
            assertFalse(monitor.checkSettings())
            val recovery = healthStore.state.value as SettingsHealthState.RecoveryRequired
            val recoveryFile = requireNotNull(recoveryManager.recoveryFile(recovery.evidence))
            assertTrue(recoveryFile.isFile)
            assertArrayEquals(corruptBytes, recoveryFile.readBytes())
            assertEquals(corruptBytes.size.toLong(), recovery.evidence.byteCount)
            assertFalse(corruptBytes.contentEquals(corruptFile.readBytes()))
            assertEquals(SettingsSnapshot(), corruptStore.settings.first())

            assertTrue(monitor.confirmSafeDefaults())
            assertEquals(SettingsHealthState.Healthy, healthStore.state.value)
            assertTrue(monitor.clearRecoveryCopies())
            assertFalse(recoveryFile.exists())
        } finally {
            corruptStore.close()
            recoveryManager.clearRecoveryCopies()
            healthStore.clearRecoveryEvidence()
            corruptFile.delete()
        }
    }

    @Test
    fun runtimeAndHealthStoresCanBeFullyCleared() {
        val sessionGate = SessionGate(context)
        val healthStore = HealthStore(context)
        val now = System.currentTimeMillis()

        val elapsed = SystemClock.elapsedRealtime()
        sessionGate.grantPass("example.app", 60_000, now, elapsed)
        sessionGate.grantExitCooldown("example.app", 8_000, now, elapsed)
        sessionGate.grantSafetyCooldown(3_000, now, elapsed)
        healthStore.markConnected(now)
        healthStore.observeAccessibilityState(true, now, elapsed, bootCount = 42)
        healthStore.markHeartbeat(now, elapsed, bootCount = 42)
        assertEquals(ServiceHeartbeatSnapshot(now, elapsed, 42), healthStore.lastHeartbeat())
        assertEquals(ServiceStartObservationSnapshot(now, elapsed, 42), healthStore.serviceStartObservation())
        healthStore.observeAccessibilityState(false, now + 1, elapsed + 1, bootCount = 42)
        assertEquals(ServiceHeartbeatSnapshot(), healthStore.lastHeartbeat())
        assertEquals(ServiceStartObservationSnapshot(), healthStore.serviceStartObservation())
        healthStore.observeAccessibilityState(true, now + 2, elapsed + 2, bootCount = 42)
        assertTrue(sessionGate.clearAll())
        assertTrue(healthStore.clearAll())

        assertFalse(sessionGate.hasValidPass("example.app", now, elapsed))
        assertFalse(sessionGate.hasActiveExitCooldown("example.app", now, elapsed))
        assertFalse(sessionGate.hasActiveSafetyCooldown(now, elapsed))
        assertEquals(0, healthStore.lastConnectedAt())
        assertEquals(0, healthStore.lastHeartbeatAt())
        assertEquals(ServiceHeartbeatSnapshot(), healthStore.lastHeartbeat())
        assertEquals(ServiceStartObservationSnapshot(), healthStore.serviceStartObservation())
    }

    @Test
    fun exitCooldownOnlySuppressesImmediateReturnEvents() {
        val sessionGate = SessionGate(context)
        sessionGate.clearAll()
        sessionGate.grantExitCooldown(
            "example.app",
            durationMs = 8_000,
            nowEpochMs = 10_000,
            nowElapsedMs = 5_000,
        )

        assertTrue(sessionGate.hasActiveExitCooldown("example.app", nowEpochMs = 17_999, nowElapsedMs = 12_999))
        assertFalse(sessionGate.hasActiveExitCooldown("example.app", nowEpochMs = 18_000, nowElapsedMs = 13_000))

        sessionGate.grantExitCooldown("clock.app", 8_000, nowEpochMs = 10_000, nowElapsedMs = 5_000)
        assertFalse(sessionGate.hasActiveExitCooldown("clock.app", nowEpochMs = 1, nowElapsedMs = 6_000))
    }

    @Test
    fun safetyCooldownSuppressesOnlyTransitionWindow() {
        val sessionGate = SessionGate(context)
        sessionGate.clearAll()
        sessionGate.grantSafetyCooldown(durationMs = 3_000, nowEpochMs = 10_000, nowElapsedMs = 5_000)

        assertTrue(sessionGate.hasActiveSafetyCooldown(nowEpochMs = 12_999, nowElapsedMs = 7_999))
        assertFalse(sessionGate.hasActiveSafetyCooldown(nowEpochMs = 13_000, nowElapsedMs = 8_000))

        sessionGate.markShown("duplicate.app", nowEpochMs = 10_000, nowElapsedMs = 5_000)
        assertTrue(sessionGate.isDuplicate("duplicate.app", nowEpochMs = 9_000, nowElapsedMs = 5_100))
        assertFalse(sessionGate.isDuplicate("duplicate.app", nowEpochMs = 9_000, nowElapsedMs = 6_500))
    }
}
