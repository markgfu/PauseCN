package app.pausecn.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.edit
import kotlin.math.abs

class SessionGate(context: Context) {
    private val preferences = context.getSharedPreferences("runtime_gate", Context.MODE_PRIVATE)

    fun grantPass(
        packageName: String,
        durationMs: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ) = grantWindow("pass_$packageName", durationMs, nowEpochMs, nowElapsedMs)

    fun hasValidPass(
        packageName: String,
        nowEpochMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean = isWindowActive("pass_$packageName", nowEpochMs, nowElapsedMs)

    fun grantExitCooldown(
        packageName: String,
        durationMs: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ) = grantWindow("exit_$packageName", durationMs, nowEpochMs, nowElapsedMs)

    fun hasActiveExitCooldown(
        packageName: String,
        nowEpochMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean = isWindowActive("exit_$packageName", nowEpochMs, nowElapsedMs)

    fun grantSafetyCooldown(
        durationMs: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ) = grantWindow(KEY_SAFETY_COOLDOWN, durationMs, nowEpochMs, nowElapsedMs)

    fun hasActiveSafetyCooldown(
        nowEpochMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean = isWindowActive(KEY_SAFETY_COOLDOWN, nowEpochMs, nowElapsedMs)

    fun markShown(
        packageName: String,
        nowEpochMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ) {
        preferences.edit {
            putLong("shown_epoch_$packageName", nowEpochMs)
            putLong("shown_elapsed_$packageName", nowElapsedMs)
        }
    }

    fun isDuplicate(
        packageName: String,
        nowEpochMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean {
        val shownEpochMs = preferences.getLong("shown_epoch_$packageName", 0)
        val shownElapsedMs = preferences.getLong("shown_elapsed_$packageName", 0)
        val elapsedDelta = nowElapsedMs - shownElapsedMs
        val sameTimebase = hasConsistentTimebase(shownEpochMs, shownElapsedMs, nowEpochMs, nowElapsedMs)
        val duplicate = sameTimebase && elapsedDelta in 0 until DUPLICATE_WINDOW_MS
        if (!duplicate && (shownEpochMs != 0L || shownElapsedMs != 0L)) {
            preferences.edit {
                remove("shown_epoch_$packageName")
                remove("shown_elapsed_$packageName")
            }
        }
        return duplicate
    }

    /**
     * Privacy reset is the one runtime-store operation that must be durable before the UI reports
     * success. Ordinary short-lived gates intentionally use apply(): losing one after a process
     * death fails closed by showing another intervention, while blocking the accessibility event
     * thread on every gate write would hurt responsiveness.
     */
    @SuppressLint("UseKtx") // KTX edit returns Unit; privacy reset must verify commit().
    fun clearAll(): Boolean = preferences.edit().clear().commit() && preferences.all.isEmpty()

    private fun grantWindow(prefix: String, durationMs: Long, nowEpochMs: Long, nowElapsedMs: Long) {
        val safeDurationMs = durationMs.coerceAtLeast(0)
        preferences.edit {
            putLong("${prefix}_issued_epoch", nowEpochMs)
            putLong("${prefix}_issued_elapsed", nowElapsedMs)
            putLong("${prefix}_until_epoch", nowEpochMs + safeDurationMs)
            putLong("${prefix}_until_elapsed", nowElapsedMs + safeDurationMs)
        }
    }

    private fun isWindowActive(prefix: String, nowEpochMs: Long, nowElapsedMs: Long): Boolean {
        val issuedEpochMs = preferences.getLong("${prefix}_issued_epoch", 0)
        val issuedElapsedMs = preferences.getLong("${prefix}_issued_elapsed", 0)
        val untilEpochMs = preferences.getLong("${prefix}_until_epoch", 0)
        val untilElapsedMs = preferences.getLong("${prefix}_until_elapsed", 0)
        val active = isExpiringWindowActive(
            issuedEpochMs = issuedEpochMs,
            issuedElapsedMs = issuedElapsedMs,
            untilEpochMs = untilEpochMs,
            untilElapsedMs = untilElapsedMs,
            nowEpochMs = nowEpochMs,
            nowElapsedMs = nowElapsedMs,
        )
        if (!active && (issuedEpochMs != 0L || issuedElapsedMs != 0L || untilEpochMs != 0L || untilElapsedMs != 0L)) {
            preferences.edit {
                remove("${prefix}_issued_epoch")
                remove("${prefix}_issued_elapsed")
                remove("${prefix}_until_epoch")
                remove("${prefix}_until_elapsed")
            }
        }
        return active
    }

    companion object {
        private const val DUPLICATE_WINDOW_MS = 1_500L
        private const val KEY_SAFETY_COOLDOWN = "safety_cooldown_until"
    }
}

internal fun isExpiringWindowActive(
    issuedEpochMs: Long,
    issuedElapsedMs: Long,
    untilEpochMs: Long,
    untilElapsedMs: Long,
    nowEpochMs: Long,
    nowElapsedMs: Long,
): Boolean =
    issuedEpochMs > 0 &&
        issuedElapsedMs >= 0 &&
        untilEpochMs >= issuedEpochMs &&
        untilElapsedMs >= issuedElapsedMs &&
        nowEpochMs in issuedEpochMs until untilEpochMs &&
        nowElapsedMs in issuedElapsedMs until untilElapsedMs &&
        hasConsistentTimebase(issuedEpochMs, issuedElapsedMs, nowEpochMs, nowElapsedMs)

private fun hasConsistentTimebase(
    issuedEpochMs: Long,
    issuedElapsedMs: Long,
    nowEpochMs: Long,
    nowElapsedMs: Long,
): Boolean {
    if (issuedEpochMs <= 0 || issuedElapsedMs < 0 || nowEpochMs <= 0 || nowElapsedMs < 0) return false
    val issuedBootEstimate = issuedEpochMs - issuedElapsedMs
    val currentBootEstimate = nowEpochMs - nowElapsedMs
    return abs(currentBootEstimate - issuedBootEstimate) <= MAX_TIMEBASE_DRIFT_MS
}

private const val MAX_TIMEBASE_DRIFT_MS = 2 * 60_000L

data class ServiceHeartbeatSnapshot(
    val epochMs: Long = 0,
    val elapsedMs: Long = -1,
    val bootCount: Int = UNKNOWN_BOOT_COUNT,
)

data class ServiceStartObservationSnapshot(
    val epochMs: Long = 0,
    val elapsedMs: Long = -1,
    val bootCount: Int = UNKNOWN_BOOT_COUNT,
)

class HealthStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("service_health", Context.MODE_PRIVATE)

    fun markConnected(nowEpochMs: Long = System.currentTimeMillis()) {
        preferences.edit { putLong(KEY_CONNECTED, nowEpochMs) }
    }

    fun recordWindowEvent(nowEpochMs: Long = System.currentTimeMillis()) {
        preferences.edit { putLong(KEY_LAST_EVENT, nowEpochMs) }
    }

    fun markHeartbeat(
        nowEpochMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        bootCount: Int = currentBootCount(),
    ) {
        preferences.edit {
            putLong(KEY_HEARTBEAT, nowEpochMs)
            putLong(KEY_HEARTBEAT_ELAPSED, nowElapsedMs)
            putInt(KEY_HEARTBEAT_BOOT_COUNT, bootCount)
        }
    }

    /**
     * Records when the app first observes an enabled accessibility permission without relying on
     * the service process to run. This gives the UI a durable deadline for reporting a service
     * that never connects. Disabling the permission invalidates the previous heartbeat so a later
     * re-enable must prove liveness with a fresh heartbeat.
     */
    fun observeAccessibilityState(
        enabled: Boolean,
        nowEpochMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
        bootCount: Int = currentBootCount(),
    ) {
        if (!enabled) {
            if (
                preferences.contains(KEY_ENABLED_OBSERVED_EPOCH) ||
                preferences.contains(KEY_ENABLED_OBSERVED_ELAPSED) ||
                preferences.contains(KEY_ENABLED_OBSERVED_BOOT_COUNT) ||
                preferences.contains(KEY_HEARTBEAT) ||
                preferences.contains(KEY_HEARTBEAT_ELAPSED) ||
                preferences.contains(KEY_HEARTBEAT_BOOT_COUNT)
            ) {
                preferences.edit {
                    remove(KEY_ENABLED_OBSERVED_EPOCH)
                    remove(KEY_ENABLED_OBSERVED_ELAPSED)
                    remove(KEY_ENABLED_OBSERVED_BOOT_COUNT)
                    remove(KEY_HEARTBEAT)
                    remove(KEY_HEARTBEAT_ELAPSED)
                    remove(KEY_HEARTBEAT_BOOT_COUNT)
                }
            }
            return
        }

        if (!preferences.contains(KEY_ENABLED_OBSERVED_EPOCH)) {
            preferences.edit {
                putLong(KEY_ENABLED_OBSERVED_EPOCH, nowEpochMs)
                putLong(KEY_ENABLED_OBSERVED_ELAPSED, nowElapsedMs)
                putInt(KEY_ENABLED_OBSERVED_BOOT_COUNT, bootCount)
            }
        }
    }

    fun lastConnectedAt(): Long = preferences.getLong(KEY_CONNECTED, 0)
    fun lastWindowEventAt(): Long = preferences.getLong(KEY_LAST_EVENT, 0)
    fun lastHeartbeatAt(): Long = preferences.getLong(KEY_HEARTBEAT, 0)
    fun lastHeartbeat(): ServiceHeartbeatSnapshot = ServiceHeartbeatSnapshot(
        epochMs = lastHeartbeatAt(),
        elapsedMs = preferences.getLong(KEY_HEARTBEAT_ELAPSED, -1),
        bootCount = preferences.getInt(KEY_HEARTBEAT_BOOT_COUNT, UNKNOWN_BOOT_COUNT),
    )
    fun serviceStartObservation(): ServiceStartObservationSnapshot = ServiceStartObservationSnapshot(
        epochMs = preferences.getLong(KEY_ENABLED_OBSERVED_EPOCH, 0),
        elapsedMs = preferences.getLong(KEY_ENABLED_OBSERVED_ELAPSED, -1),
        bootCount = preferences.getInt(KEY_ENABLED_OBSERVED_BOOT_COUNT, UNKNOWN_BOOT_COUNT),
    )

    fun currentBootCount(): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, UNKNOWN_BOOT_COUNT)
    }.getOrDefault(UNKNOWN_BOOT_COUNT)

    @SuppressLint("UseKtx") // KTX edit returns Unit; privacy reset must verify commit().
    fun clearAll(): Boolean = preferences.edit().clear().commit() && preferences.all.isEmpty()

    companion object {
        private const val KEY_CONNECTED = "connected_at"
        private const val KEY_LAST_EVENT = "last_window_event_at"
        private const val KEY_HEARTBEAT = "heartbeat_at"
        private const val KEY_HEARTBEAT_ELAPSED = "heartbeat_at_elapsed"
        private const val KEY_HEARTBEAT_BOOT_COUNT = "heartbeat_boot_count"
        private const val KEY_ENABLED_OBSERVED_EPOCH = "enabled_observed_at"
        private const val KEY_ENABLED_OBSERVED_ELAPSED = "enabled_observed_at_elapsed"
        private const val KEY_ENABLED_OBSERVED_BOOT_COUNT = "enabled_observed_boot_count"
    }
}

private const val UNKNOWN_BOOT_COUNT = -1
