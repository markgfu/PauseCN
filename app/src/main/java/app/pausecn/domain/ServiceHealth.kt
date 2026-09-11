package app.pausecn.domain

enum class ServiceHealth {
    DISABLED,
    STARTING,
    HEALTHY,
    STALE,
}

fun evaluateServiceHealth(
    accessibilityEnabled: Boolean,
    lastHeartbeatEpochMs: Long,
    lastHeartbeatElapsedMs: Long,
    lastHeartbeatBootCount: Int,
    enabledObservedEpochMs: Long = 0,
    enabledObservedElapsedMs: Long = -1,
    enabledObservedBootCount: Int = -1,
    nowEpochMs: Long,
    nowElapsedMs: Long,
    currentBootCount: Int,
): ServiceHealth {
    if (!accessibilityEnabled) return ServiceHealth.DISABLED
    if (lastHeartbeatEpochMs <= 0L || lastHeartbeatElapsedMs < 0L) {
        return evaluateMissingHeartbeat(
            enabledObservedEpochMs = enabledObservedEpochMs,
            enabledObservedElapsedMs = enabledObservedElapsedMs,
            enabledObservedBootCount = enabledObservedBootCount,
            nowEpochMs = nowEpochMs,
            nowElapsedMs = nowElapsedMs,
            currentBootCount = currentBootCount,
        )
    }

    val bootChanged = lastHeartbeatBootCount >= 0 &&
        currentBootCount >= 0 &&
        lastHeartbeatBootCount != currentBootCount
    val elapsedAgeMs = nowElapsedMs - lastHeartbeatElapsedMs
    if (bootChanged || elapsedAgeMs < 0L) {
        val epochAgeMs = nowEpochMs - lastHeartbeatEpochMs
        return when {
            epochAgeMs < 0L -> ServiceHealth.STARTING
            epochAgeMs <= STALE_AFTER_MS -> ServiceHealth.STARTING
            else -> ServiceHealth.STALE
        }
    }

    return if (elapsedAgeMs <= STALE_AFTER_MS) {
        ServiceHealth.HEALTHY
    } else {
        ServiceHealth.STALE
    }
}

private fun evaluateMissingHeartbeat(
    enabledObservedEpochMs: Long,
    enabledObservedElapsedMs: Long,
    enabledObservedBootCount: Int,
    nowEpochMs: Long,
    nowElapsedMs: Long,
    currentBootCount: Int,
): ServiceHealth {
    if (enabledObservedEpochMs <= 0L || enabledObservedElapsedMs < 0L) {
        return ServiceHealth.STARTING
    }
    val bootChanged = enabledObservedBootCount >= 0 &&
        currentBootCount >= 0 &&
        enabledObservedBootCount != currentBootCount
    val elapsedAgeMs = nowElapsedMs - enabledObservedElapsedMs
    val ageMs = if (bootChanged || elapsedAgeMs < 0L) {
        nowEpochMs - enabledObservedEpochMs
    } else {
        elapsedAgeMs
    }
    return if (ageMs in 0..SERVICE_START_GRACE_MS) {
        ServiceHealth.STARTING
    } else {
        // A clock rollback or invalid timebase must not create an unbounded "starting" state.
        ServiceHealth.STALE
    }
}

private const val STALE_AFTER_MS = 60 * 60_000L
private const val SERVICE_START_GRACE_MS = 2 * 60_000L
