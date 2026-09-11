package app.pausecn.reports

import app.pausecn.usage.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class ReportPeriod(val label: String) { TODAY("今日"), YESTERDAY("昨日"), THIS_WEEK("本周"), LAST_WEEK("上周") }
enum class ReportTemplate(val label: String) { SIMPLE("简洁数据"), REFLECTION("温和复盘"), HEATMAP("时段热力") }

data class ReportWindow(val period: ReportPeriod, val startDate: LocalDate, val endDateExclusive: LocalDate,
    val zoneId: String, val start: Long, val end: Long, val cutoff: Long) {
    val ongoing: Boolean get() = cutoff < end
    val slot: String get() = "${if (period == ReportPeriod.TODAY || period == ReportPeriod.YESTERDAY) "DAY" else "WEEK"}|$start|$end|$zoneId"

    companion object {
        fun at(period: ReportPeriod, now: Long, zone: ZoneId): ReportWindow {
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val first = when (period) {
                ReportPeriod.TODAY -> today
                ReportPeriod.YESTERDAY -> today.minusDays(1)
                ReportPeriod.THIS_WEEK -> monday
                ReportPeriod.LAST_WEEK -> monday.minusWeeks(1)
            }
            val last = first.plusDays(if (period == ReportPeriod.TODAY || period == ReportPeriod.YESTERDAY) 1 else 7)
            val start = first.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = last.atStartOfDay(zone).toInstant().toEpochMilli()
            return ReportWindow(period, first, last, zone.id, start, end, minOf(now, end))
        }
    }
}

/** Minimal Room projection: reasons, labels from old events and chat text never enter report facts. */
data class ReportEvent(val id: Long, val packageName: String, val occurredAtEpochMs: Long, val outcome: String)
data class ReportTarget(val packageName: String, val label: String, val selectedAt: Long,
    val category: String = app.pausecn.data.AppCategories.UNCLASSIFIED)
data class ReportCategorySummary(val category: String, val counts: ReportCounts, val foregroundMs: Long?, val usagePartial: Boolean)
data class ReportCounts(val exited: Int, val continued: Int, val interrupted: Int, val pending: Int, val displayFailed: Int) {
    val recorded: Int get() = exited + continued + interrupted + pending
    val exitPercent: Int? get() = (exited + continued).takeIf { it > 0 }?.let { (exited * 100L / it).toInt() }
}
data class ReportFacts(val window: ReportWindow, val createdAt: Long, val targets: List<ReportTarget>,
    val counts: ReportCounts, val foregroundMs: Long?, val usagePartial: Boolean,
    val usageCapturedAt: Long?, val pauseCells: List<HeatmapCell>, val usageCells: List<HeatmapCell>,
    val notes: List<String>, val fingerprint: String, val sourceFingerprint: String, val validUntil: Long,
    val categories: List<ReportCategorySummary> = emptyList())

object ReportFactBuilder {
    fun build(window: ReportWindow, observedAt: Long, targets: List<ReportTarget>, events: List<ReportEvent>,
        hours: List<UsageHourEntity>, retentionMs: Long, usageEnabled: Boolean, permissionObserved: Boolean): ReportFacts {
        require(retentionMs > 0 && window.cutoff in window.start..window.end && observedAt >= window.cutoff)
        val packages = targets.map { it.packageName }.toSet()
        val retentionFloor = observedAt - retentionMs
        val selectedEvents = events.filter { it.packageName in packages && it.occurredAtEpochMs >= maxOf(window.start, retentionFloor) &&
            it.occurredAtEpochMs < window.cutoff }.sortedBy { it.id }
        // A saved hourly sum cannot be safely split at a retention boundary. Omit it, marking the gap.
        val selectedHours = hours.filter { it.packageName in packages && it.zoneId == window.zoneId &&
            it.algorithm == UsageAggregation.VERSION && it.start >= window.start && it.end <= window.end &&
            it.evaluatedFrom >= retentionFloor && it.evaluatedTo <= window.cutoff && it.capturedAt <= observedAt }
            .sortedWith(compareBy({ it.packageName }, { it.start }, { it.periodId }, { it.algorithm }))
        fun count(outcome: String) = selectedEvents.count { it.outcome == outcome }
        val counts = ReportCounts(count("EXITED"), count("CONTINUED"), count("DISMISSED"), count("SHOWN"), count("DISPLAY_FAILED"))
        val points = selectedEvents.filter { it.outcome in setOf("EXITED", "CONTINUED", "DISMISSED", "SHOWN") }
            .map { UsagePausePoint(it.packageName, it.occurredAtEpochMs) }
        fun cells(metric: HeatmapMetric) = UsageHeatmap.cellsBetween(window.startDate, window.endDateExclusive,
            ZoneId.of(window.zoneId), window.cutoff, packages, metric, selectedHours, points, observedAt)
        val pauseCells = cells(HeatmapMetric.PAUSES)
        val usageCells = cells(HeatmapMetric.FOREGROUND)
        val elapsedCells = usageCells.filter { !it.future && it.actualDurationMs > 0 }
        val known = elapsedCells.mapNotNull { it.value }
        val foreground = known.takeIf { it.isNotEmpty() }?.sum()
        val partial = elapsedCells.any { it.partial || it.value == null }
        val categorySummaries = targets.groupBy { it.category }.map { (category, apps) ->
            val scoped = apps.map { it.packageName }.toSet()
            val eventsInCategory = selectedEvents.filter { it.packageName in scoped }
            fun categoryCount(outcome: String) = eventsInCategory.count { it.outcome == outcome }
            val usageInCategory = UsageHeatmap.cellsBetween(window.startDate, window.endDateExclusive, ZoneId.of(window.zoneId),
                window.cutoff, scoped, HeatmapMetric.FOREGROUND, selectedHours, points, observedAt).filter { !it.future && it.actualDurationMs > 0 }
            val knownInCategory = usageInCategory.mapNotNull { it.value }
            ReportCategorySummary(category, ReportCounts(categoryCount("EXITED"), categoryCount("CONTINUED"), categoryCount("DISMISSED"),
                categoryCount("SHOWN"), categoryCount("DISPLAY_FAILED")), knownInCategory.takeIf { it.isNotEmpty() }?.sum(),
                usageInCategory.any { it.partial || it.value == null })
        }.sortedWith(compareByDescending<ReportCategorySummary> { it.counts.recorded }.thenBy { it.category })
        val notes = buildList {
            add("只包含当前所选目标在本期留下的记录；不代表全部打开次数，也不能证明服务全程正常。")
            add("继续使用不是失败。离开比例仅以已完成的离开与继续选择为分母。")
            add("删除、保留期和记录容量可能造成缺口；仅展示剩余样本，不推算被删除的数据。")
            if (targets.isEmpty()) add("当前未选择目标，本期无可汇总目标。")
            if (retentionFloor > window.start) add("保留期未覆盖完整报告区间。")
            if (targets.any { it.selectedAt > window.start }) add("部分目标在本期开始后加入，不能据此比较整个周期。")
            if (!usageEnabled) add("本地时长采集未开启或已暂停；已有估算仅作为历史数据。")
            else if (!permissionObserved) add("系统使用授权尚未确认或已撤销；不会补填缺失时长。")
            if (hours.any { it.packageName in packages && it.zoneId != window.zoneId }) add("旧时区的时长汇总未混入本报告。")
            if (foreground == null) add("前台时长暂无可用估算，未知不等于零。")
            else {
                add("前台时长为所选应用之和，分屏或停顿层可能重叠；不是手机屏幕总时长，未减去停顿时间。")
                if (partial) add("时长仅有部分可用区间，不代表整期使用总量。")
            }
        }
        val source = ReportDigest().apply {
            targets.sortedBy { it.packageName }.forEach { add(it.packageName); add(it.label); add(it.selectedAt); add(it.category) }
            selectedEvents.forEach { add(it.id); add(it.packageName); add(it.occurredAtEpochMs); add(it.outcome) }
            selectedHours.forEach { add(it.periodId); add(it.packageName); add(it.start); add(it.end); add(it.evaluatedFrom)
                add(it.evaluatedTo); add(it.foregroundMs); add(it.unknownMs); add(it.completeness); add(it.limitations); add(it.capturedAt) }
            add(usageEnabled); add(permissionObserved)
        }.finish()
        val fingerprint = ReportDigest().apply {
            add("report-facts-v1"); add(window.slot); add(window.cutoff); add(source)
        }.finish()
        val validUntil = minOf(selectedEvents.minOfOrNull { it.occurredAtEpochMs + retentionMs } ?: Long.MAX_VALUE,
            selectedHours.minOfOrNull { it.evaluatedFrom + retentionMs } ?: Long.MAX_VALUE)
        return ReportFacts(window, observedAt, targets.sortedBy { it.packageName }.toList(), counts, foreground, partial,
            selectedHours.minOfOrNull { it.capturedAt }, pauseCells, usageCells, notes, fingerprint, source, validUntil, categorySummaries)
    }
}

/** Length-prefixed fields avoid delimiter collisions in arbitrary app labels. */
private class ReportDigest {
    private val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: Any?) {
        val bytes = (value?.toString() ?: "<null>").toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
    }
    fun finish(): String = digest.digest().joinToString("") { "%02x".format(it) }
}
