package app.pausecn.usage

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Half-open UTC interval. Neither event objects nor another app's text is persisted here. */
data class UsageSpan(val start: Long, val end: Long) {
    init { require(start <= end) }
    val duration: Long get() = end - start
    fun intersect(other: UsageSpan): UsageSpan? =
        if (maxOf(start, other.start) < minOf(end, other.end))
            UsageSpan(maxOf(start, other.start), minOf(end, other.end)) else null
}

enum class UsageEventKind { RESUMED, PAUSED, STOPPED, SCREEN_OFF, SCREEN_ON, LOCKED, UNLOCKED, SHUTDOWN, STARTUP }

data class UsageSignal(
    val at: Long,
    val kind: UsageEventKind,
    val packageName: String = "",
    val activity: String = "",
)

/** Keep interval identity outside the algorithm; intersect separate consent periods before saving. */
data class UsageEstimate(
    val packageName: String,
    val window: UsageSpan,
    val foreground: List<UsageSpan>,
    val unknown: List<UsageSpan>,
    val limitations: Set<String>,
) {
    val foregroundMs: Long get() = foreground.sumOf { it.duration }
}

enum class UsageCompleteness { ESTIMATED, PARTIAL, UNKNOWN }

data class UsageHour(
    val date: LocalDate,
    val hour: Int,
    val zoneId: String,
    val interval: UsageSpan,
    val evaluatedParts: List<UsageSpan>,
    val foregroundMs: Long?,
    val unknownMs: Long,
    val completeness: UsageCompleteness,
)

object UsageAggregation {
    const val VERSION = 1

    fun union(spans: List<UsageSpan>): List<UsageSpan> {
        val result = mutableListOf<UsageSpan>()
        for (span in spans.filter { it.duration > 0 }.sortedBy { it.start }) {
            val last = result.lastOrNull()
            if (last != null && span.start <= last.end) {
                result[result.lastIndex] = UsageSpan(last.start, maxOf(last.end, span.end))
            } else result.add(span)
        }
        return result
    }

    /** Pure replacement calculation: never adds a refreshed query to an earlier query's totals.
     * A matched activity pair or screen/lock/shutdown boundary can close an interval. Startup and
     * query cutoff cannot close it. Class names are not unique activity instance IDs: a repeated
     * resume for an already active class is marked ambiguous instead of inventing a new session.
     */
    fun estimate(packageName: String, window: UsageSpan, signals: List<UsageSignal>): UsageEstimate {
        require(packageName.isNotBlank())
        val foreground = mutableListOf<UsageSpan>()
        val unknown = mutableListOf<UsageSpan>()
        val limitations = linkedSetOf<String>()
        val active = mutableMapOf<String, Long>()
        val closed = mutableSetOf<String>()
        var known = false
        var unknownFrom = window.start
        var lastBoundary = window.start
        var blocked = false
        var shutdown = false

        fun gap(from: Long, to: Long, reason: String) {
            UsageSpan(minOf(from, to), maxOf(from, to)).intersect(window)?.let(unknown::add)
            limitations.add(reason)
        }
        fun establish(at: Long) {
            if (!known) gap(unknownFrom, at, "MISSING_INITIAL_STATE")
            known = true
            lastBoundary = at
        }
        fun closeAll(at: Long) {
            active.values.forEach { start ->
                UsageSpan(start, at).intersect(window)?.let(foreground::add)
            }
            closed.addAll(active.keys)
            active.clear()
        }

        // Kotlin sortedBy is stable: same-source equal-time Activity events retain their order.
        for (event in signals.filter { it.at >= window.start && it.at < window.end }
            .filter { it.kind !in ACTIVITY_KINDS || it.packageName == packageName }
            .sortedBy { it.at }) {
            when (event.kind) {
                UsageEventKind.SCREEN_OFF, UsageEventKind.LOCKED -> {
                    if (shutdown) continue
                    establish(event.at)
                    closeAll(event.at)
                    blocked = true
                }
                UsageEventKind.SCREEN_ON, UsageEventKind.UNLOCKED -> {
                    // Unlocking is not an Activity resume. Without a later resume we cannot
                    // infer that the app stayed inactive (some systems omit the new resume).
                    if (shutdown) continue
                    if (blocked) { known = false; unknownFrom = event.at }
                    blocked = false
                    lastBoundary = event.at
                }
                UsageEventKind.SHUTDOWN -> {
                    establish(event.at)
                    closeAll(event.at)
                    shutdown = true
                    known = false
                    unknownFrom = event.at
                }
                UsageEventKind.STARTUP -> {
                    active.values.forEach { gap(it, event.at, "RESTART_WITHOUT_CLOSE") }
                    active.clear()
                    closed.clear()
                    if (!known) gap(unknownFrom, event.at, "RESTART_GAP")
                    known = false
                    unknownFrom = event.at
                    lastBoundary = event.at
                    blocked = false
                    shutdown = false
                }
                UsageEventKind.RESUMED -> {
                    if (shutdown || blocked) {
                        gap(lastBoundary, event.at, "ACTIVITY_DURING_SYSTEM_BOUNDARY")
                        continue
                    }
                    establish(event.at)
                    if (event.activity.isBlank()) limitations.add("MISSING_ACTIVITY_CLASS")
                    val previous = active.putIfAbsent(event.activity, event.at)
                    if (previous != null) {
                        gap(previous, event.at, "AMBIGUOUS_ACTIVITY_INSTANCE")
                        // Discard the unidentifiable earlier open interval. A later matching
                        // pause may confirm only the span beginning at this latest resume.
                        active[event.activity] = event.at
                    }
                    closed.remove(event.activity)
                }
                UsageEventKind.PAUSED, UsageEventKind.STOPPED -> {
                    if (shutdown) continue
                    val start = active.remove(event.activity)
                    if (start != null) {
                        UsageSpan(start, event.at).intersect(window)?.let(foreground::add)
                        closed.add(event.activity)
                        lastBoundary = event.at
                    } else if (event.activity !in closed && !blocked) {
                        gap(if (known) lastBoundary else unknownFrom, event.at, "CLOSE_WITHOUT_RESUME")
                        known = true
                        lastBoundary = event.at
                        closed.add(event.activity)
                    }
                    // Normal PAUSED -> STOPPED pairs must not create a second interval/gap.
                }
            }
        }
        active.values.forEach { gap(it, window.end, "OPEN_AT_CUTOFF") }
        if (!known) gap(unknownFrom, window.end, "NO_CONFIRMED_STATE")
        return UsageEstimate(packageName, window, union(foreground), union(unknown), limitations)
    }

    /** Actual local hour boundaries, not 24 fixed UTC hours. Repeated hours retain two UTC
     * intervals; the UI may combine them under one hour label and must show their total length.
     */
    fun hours(date: LocalDate, zone: ZoneId): List<UsageSpan> {
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val boundaries = buildList {
            add(dayStart)
            for (hour in 0..23) {
                val local = date.atTime(hour, 0)
                zone.rules.getValidOffsets(local).forEach { offset -> add(local.toInstant(offset).toEpochMilli()) }
            }
            add(dayEnd)
        }.filter { it in dayStart..dayEnd }.distinct().sorted()
        return boundaries.zipWithNext { from, to -> UsageSpan(from, to) }
    }

    fun hourly(estimate: UsageEstimate, date: LocalDate, zone: ZoneId, allowed: List<UsageSpan>): List<UsageHour> {
        val permitted = union(allowed.mapNotNull { it.intersect(estimate.window) })
        return hours(date, zone).map { bucket ->
            val parts = permitted.mapNotNull { it.intersect(bucket) }
            val confirmed = union(estimate.foreground.flatMap { span -> parts.mapNotNull(span::intersect) })
            val missing = union(estimate.unknown.flatMap { span -> parts.mapNotNull(span::intersect) })
            val observedMs = parts.sumOf { it.duration }
            // Consent gaps, future time, and unqueried time remain unknown, never known zero.
            val unknownMs = bucket.duration - observedMs + missing.sumOf { it.duration }
            val foregroundMs = confirmed.sumOf { it.duration }
            val state = when {
                observedMs == 0L || (unknownMs == bucket.duration && foregroundMs == 0L) -> UsageCompleteness.UNKNOWN
                unknownMs > 0 -> UsageCompleteness.PARTIAL
                else -> UsageCompleteness.ESTIMATED
            }
            UsageHour(date, Instant.ofEpochMilli(bucket.start).atZone(zone).hour, zone.id,
                bucket, parts, foregroundMs.takeUnless { state == UsageCompleteness.UNKNOWN }, unknownMs, state)
        }
    }

    private val ACTIVITY_KINDS = setOf(UsageEventKind.RESUMED, UsageEventKind.PAUSED, UsageEventKind.STOPPED)
}
