package app.pausecn.reports

import app.pausecn.usage.HeatmapMetric
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneId

data class ReportShareOptions(val template: ReportTemplate = ReportTemplate.SIMPLE,
    val showAppNames: Boolean = false, val allowHourlyPattern: Boolean = false,
    val metric: HeatmapMetric = HeatmapMetric.PAUSES, val caption: String = "", val includeAi: Boolean = false,
    val includeCategories: Boolean = false)
data class SharedReportMetric(val label: String, val value: String)
data class SharedReportCell(val date: String, val hour: Int, val value: Long?, val partial: Boolean)

/** Deliberate export boundary. Only opted-in AI text, never source IDs, package IDs, profile,
 * reasons, chat, credential storage, boot identifiers or cache/request metadata. */
data class ReportShareModel(val template: ReportTemplate, val dates: String, val cutoff: String,
    val metrics: List<SharedReportMetric>, val neutralSummary: String?, val appNames: List<String>,
    val omittedAppNames: Int, val cells: List<SharedReportCell>, val heatmapUnit: String,
    val caption: String, val notes: List<String>, val aiObservations: List<String> = emptyList(), val aiSuggestion: String? = null,
    val categories: List<SharedReportMetric> = emptyList())

object ReportShareProjection {
    fun build(facts: ReportFacts, options: ReportShareOptions, interpretation: ReportInterpretation? = null): ReportShareModel {
        require(!options.includeAi || interpretation != null) { "本期暂无有效AI解读" }
        require(options.caption.codePointCount(0, options.caption.length) <= 160)
        require(options.caption.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() })
        require(options.template != ReportTemplate.HEATMAP || options.allowHourlyPattern) { "请确认公开时段分布" }
        val metrics = listOf(SharedReportMetric("已记录停顿", "${facts.counts.recorded} 次"),
            SharedReportMetric("主动离开", "${facts.counts.exited} 次"), SharedReportMetric("继续打开", "${facts.counts.continued} 次"),
            SharedReportMetric("目标前台时长之和", facts.foregroundMs?.let(::shareDuration) ?: "暂无可用数据"))
        val notes = buildList {
            add("仅所选应用的留存样本，不是全部打开次数。继续使用不是失败。")
            add("中断 ${facts.counts.interrupted} 次，未完成 ${facts.counts.pending} 次，显示失败 ${facts.counts.displayFailed} 次；不计为离开或继续。")
            add("删除、未采集或保留期可能造成缺口；未知不等于0。")
            if (facts.window.ongoing) add("此快照尚未覆盖完整周期。")
            if (facts.foregroundMs != null) add("时长为各目标前台估算之和，可能重叠，不是手机屏幕总时长。${if (facts.usagePartial) "仅部分区间可用。" else ""}")
            if (options.template == ReportTemplate.HEATMAP) add("热图公开精确小时分布；—未知，点标记为部分数据，颜色深浅不代表表现好坏。")
            if (facts.targets.any { it.selectedAt > facts.window.start }) add("部分目标在本期开始后加入，样本可能不完整。")
            add("应用名称${if (options.showAppNames) "由本人选择公开" else "已隐藏"}；这不保证匿名。")
        }
        val w = facts.window
        val names = if (options.showAppNames) facts.targets.take(8).map { it.label } else emptyList()
        val cells = if (options.template == ReportTemplate.HEATMAP) {
            (if (options.metric == HeatmapMetric.PAUSES) facts.pauseCells else facts.usageCells).map {
                SharedReportCell(it.date.toString(), it.hour, it.value, it.partial)
            }
        } else emptyList()
        return ReportShareModel(options.template, "${w.startDate} — ${w.endDateExclusive.minusDays(1)}",
            "截至 ${Instant.ofEpochMilli(w.cutoff).atZone(ZoneId.of(w.zoneId)).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}（不含截止瞬间） · ${w.zoneId}",
            metrics, if (options.template == ReportTemplate.REFLECTION) "记录是用来理解自己的，不是给自己打分。一次停顿，也可以只是想清楚再继续。" else null,
            names, if (options.showAppNames) (facts.targets.size - names.size).coerceAtLeast(0) else 0,
            cells, if (options.metric == HeatmapMetric.PAUSES) "次" else "分钟", options.caption.trim(), notes,
            if (options.includeAi) requireNotNull(interpretation).observations.map { it.text } else emptyList(),
            if (options.includeAi) requireNotNull(interpretation).suggestion else null,
            if (options.includeCategories) facts.categories.filter { it.counts.recorded > 0 || it.foregroundMs != null }.take(10)
                .map { SharedReportMetric(it.category, "${it.counts.recorded} 次停顿" + (it.foregroundMs?.let { ms -> " · 前台 ${shareDuration(ms)}${if (it.usagePartial) "（部分）" else ""}" } ?: "")) }
            else emptyList())
    }
}

internal fun shareDuration(ms: Long): String = if (ms < 60_000) "${ms / 1000} 秒" else if (ms < 3_600_000) "${ms / 60_000} 分钟"
    else "${ms / 3_600_000} 小时 ${ms / 60_000 % 60} 分钟"
