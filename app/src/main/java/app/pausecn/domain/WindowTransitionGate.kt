package app.pausecn.domain

enum class WindowDismissal { IGNORE, DISMISS_ONLY, DISMISS_WITH_COOLDOWN }

/** Event ordering only: no timers, foreground polling, page content or package history. */
class WindowTransitionGate {
    private var latestEventUptimeMs = 0L
    private var blockedThroughUptimeMs = 0L

    fun onDismissal(
        sourceUptimeMs: Long,
        nowUptimeMs: Long,
        critical: Boolean,
    ): WindowDismissal {
        val timestamp = sourceUptimeMs.takeIf { it in 1..nowUptimeMs } ?: nowUptimeMs
        // A queued launcher event must not dismiss a newer target window. Critical safety
        // surfaces always get the conservative escape path, even with missing timestamps.
        if (!critical && timestamp < latestEventUptimeMs) return WindowDismissal.IGNORE
        val boundary = if (critical) nowUptimeMs else timestamp
        blockedThroughUptimeMs = maxOf(blockedThroughUptimeMs, boundary)
        latestEventUptimeMs = maxOf(latestEventUptimeMs, boundary)
        return if (critical) WindowDismissal.DISMISS_WITH_COOLDOWN else WindowDismissal.DISMISS_ONLY
    }

    fun shouldEvaluateTarget(sourceUptimeMs: Long, nowUptimeMs: Long): Boolean {
        if (sourceUptimeMs !in 1..nowUptimeMs || sourceUptimeMs <= blockedThroughUptimeMs ||
            sourceUptimeMs < latestEventUptimeMs
        ) return false
        latestEventUptimeMs = sourceUptimeMs
        return true
    }

    fun blockPendingEvents(nowUptimeMs: Long) {
        blockedThroughUptimeMs = maxOf(blockedThroughUptimeMs, nowUptimeMs)
        latestEventUptimeMs = maxOf(latestEventUptimeMs, nowUptimeMs)
    }
}
