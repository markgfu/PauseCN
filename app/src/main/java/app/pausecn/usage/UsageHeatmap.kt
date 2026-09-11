package app.pausecn.usage

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class HeatmapMetric { PAUSES, FOREGROUND }
data class HeatmapCell(val date: LocalDate, val hour: Int, val value: Long?, val partial: Boolean,
    val actualDurationMs: Long, val future: Boolean = false, val capturedAt: Long? = null)

/** Presentation-only merge of saved snapshots. Never reinterprets an old ZoneId as the current
 * one and never sums overlapping consent coverage as if it were extra known time.
 */
object UsageHeatmap {
    fun cells(today: LocalDate, zone: ZoneId, now: Long, packages: Set<String>, metric: HeatmapMetric,
        rows: List<UsageHourEntity>, pauses: List<UsagePausePoint>): List<HeatmapCell> =
        cellsBetween(today.minusDays(6), today.plusDays(1), zone, now, packages, metric, rows, pauses)

    /** Reports use their own calendar range. Capture time is independent of the fact cutoff:
     * yesterday's events may have been collected today. Never prorate an aggregate across a cutoff. */
    fun cellsBetween(startDate: LocalDate, endDateExclusive: LocalDate, zone: ZoneId, now: Long,
        packages: Set<String>, metric: HeatmapMetric, rows: List<UsageHourEntity>, pauses: List<UsagePausePoint>,
        observedAt: Long = now): List<HeatmapCell> {
        val days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDateExclusive)
        require(days in 1..7)
        val usable = rows.filter { it.packageName in packages && it.zoneId == zone.id &&
            it.algorithm == UsageAggregation.VERSION && it.evaluatedTo <= now && it.capturedAt <= observedAt }
        return (0 until days).flatMap { offset ->
            val date = startDate.plusDays(offset)
            val actualHours = UsageAggregation.hours(date, zone)
            (0..23).map { hour ->
                val spans = actualHours.filter { Instant.ofEpochMilli(it.start).atZone(zone).hour == hour }
                val duration = spans.sumOf { it.duration }
                val future = spans.isNotEmpty() && spans.all { it.start >= now }
                if (spans.isEmpty() || future || packages.isEmpty()) {
                    HeatmapCell(date, hour, null, false, duration, future)
                } else if (metric == HeatmapMetric.PAUSES) {
                    val count = pauses.count { point -> point.packageName in packages &&
                        spans.any { point.occurredAtEpochMs >= it.start && point.occurredAtEpochMs < minOf(it.end, now) } }
                    HeatmapCell(date, hour, count.toLong(), false, duration)
                } else {
                    val bucketRows = usable.filter { row -> spans.any { row.start == it.start && row.end == it.end } }
                    var value = 0L
                    var anyKnown = false
                    var partial = false
                    for (pkg in packages) {
                        val appRows = bucketRows.filter { it.packageName == pkg }
                        val evaluated = UsageAggregation.union(appRows.map { UsageSpan(it.evaluatedFrom, it.evaluatedTo) })
                        val uncertaintyWithinEvaluation = appRows.sumOf {
                            (it.unknownMs - ((it.end - it.start) - (it.evaluatedTo - it.evaluatedFrom))).coerceAtLeast(0)
                        }
                        val knownDuration = (evaluated.sumOf { it.duration } - uncertaintyWithinEvaluation).coerceAtLeast(0)
                        val appValue = appRows.sumOf { it.foregroundMs ?: 0L }
                        value += appValue
                        anyKnown = anyKnown || knownDuration > 0L || appValue > 0L
                        partial = partial || knownDuration < duration || appRows.any { it.completeness != UsageCompleteness.ESTIMATED.name }
                    }
                    HeatmapCell(date, hour, value.takeIf { anyKnown }, partial, duration,
                        capturedAt = bucketRows.minOfOrNull { it.capturedAt })
                }
            }
        }
    }
}
