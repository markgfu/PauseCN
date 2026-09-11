package app.pausecn.reports

import app.pausecn.data.LocalExportOptions
import app.pausecn.data.PauseDatabase
import app.pausecn.data.sanitizeHistoryRetentionDays
import app.pausecn.usage.HeatmapCell
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Backup projection, not a serialized cache or request. Private interpretation is independently selected. */
data class ExportedReportTarget(val packageName: String, val label: String, val category: String? = null)
data class ExportedReport(val startDate: String, val endDateExclusive: String, val zone: String,
    val cutoff: Long, val createdAt: Long, val expiresAt: Long, val counts: ReportCounts,
    val targets: List<ExportedReportTarget>, val pauses: List<HeatmapCell>,
    val foregroundMs: Long?, val usagePartial: Boolean, val usage: List<HeatmapCell>,
    val observations: List<String> = emptyList(), val suggestion: String? = null,
    val rulePatch: app.pausecn.data.RulePatch? = null)
data class ReportExportData(val reports: List<ExportedReport> = emptyList()) {
    val validUntil: Long get() = reports.minOfOrNull { it.expiresAt } ?: Long.MAX_VALUE
}
internal data class ReportExportCheckpoint(val row: ReportCacheRow)
internal data class ReportExportCapture(val data: ReportExportData, val checkpoints: List<ReportExportCheckpoint>)

internal fun projectReportExport(facts: ReportFacts, options: LocalExportOptions, expiresAt: Long,
    interpretation: ReportInterpretation? = null): ExportedReport {
    require(options.reports)
    val privateText = interpretation.takeIf { options.reportInterpretations }
    return ExportedReport(facts.window.startDate.toString(), facts.window.endDateExclusive.toString(),
        facts.window.zoneId, facts.window.cutoff, facts.createdAt, minOf(facts.validUntil, expiresAt), facts.counts,
        facts.targets.map { ExportedReportTarget(it.packageName, it.label, it.category.takeIf { options.appCategories }) }, facts.pauseCells,
        facts.foregroundMs.takeIf { options.usage }, options.usage && facts.usagePartial,
        if (options.usage) facts.usageCells else emptyList(),
        privateText?.observations?.map { it.text }.orEmpty(), privateText?.suggestion, privateText?.rulePatch)
}

/** Caller holds guard -> Room; read-only, no generation, pruning, credentials or network. */
private suspend fun currentProjection(database: PauseDatabase, reports: ReportRepository, row: ReportCacheRow,
    options: LocalExportOptions, now: Long, days: Int): ExportedReport? = try {
    val snapshot = ReportCacheCodec.decode(row.snapshotJson)
    check(snapshot.retentionDays == sanitizeHistoryRetentionDays(days) && row.expiresAt > now && row.createdAt <= now)
    check(row.createdAt == snapshot.facts.createdAt && row.slot == snapshot.facts.window.slot && row.fingerprint == snapshot.facts.fingerprint)
    check(reports.isCurrentInTransaction(snapshot))
    var interpretation: ReportInterpretation? = null
    var deadline = minOf(row.expiresAt, snapshot.facts.validUntil)
    if (options.reportInterpretations && row.interpretationJson.isNotBlank()) {
        val ai = database.aiDao().config() ?: error("AI授权已失效")
        val consent = database.reportDao().config() ?: error("报告授权已失效")
        check(ai.enabled && consent.enabled && row.aiTag == ReportCacheCodec.aiTag(ai) && row.consentEpoch == consent.epoch)
        val background = reportBackground(database, snapshot.facts, consent, now, days)
        check(row.backgroundHash == ReportCacheCodec.hash(background.json) && now < background.expiresAt)
        val basis = if (consent.useSettings) RuleSuggestionProtocol.decodeBasis(row.ruleBasisJson) else null
        val (_, ids) = ReportProtocol.input(snapshot.facts, consent, ai.style, background, basis)
        interpretation = ReportProtocol.parse(row.interpretationJson, ids, basis)
        deadline = minOf(deadline, background.expiresAt)
    }
    projectReportExport(snapshot.facts, options, deadline, interpretation)
} catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { null }

internal suspend fun readReportExport(database: PauseDatabase, reports: ReportRepository,
    options: LocalExportOptions, now: Long, days: Int): ReportExportCapture {
    if (!options.reports) return ReportExportCapture(ReportExportData(), emptyList())
    val data = mutableListOf<ExportedReport>()
    val checks = mutableListOf<ReportExportCheckpoint>()
    database.reportDao().exportCandidates().forEach { row ->
        currentProjection(database, reports, row, options, now, days)?.let {
            data.add(it); checks.add(ReportExportCheckpoint(row))
        }
    }
    return ReportExportCapture(ReportExportData(data), checks)
}

internal suspend fun validateReportExport(database: PauseDatabase, reports: ReportRepository,
    capture: ReportExportCapture, options: LocalExportOptions, now: Long, days: Int) {
    capture.checkpoints.forEachIndexed { index, checkpoint ->
        val row = database.reportDao().cached(checkpoint.row.slot)
        check(row == checkpoint.row && currentProjection(database, reports, checkpoint.row, options, now, days) == capture.data.reports[index]) {
            "导出期间报告、授权或来源已改变"
        }
    }
}

/** Rechecks options even if an upstream caller supplies populated unselected fields. */
internal fun encodeReportExport(data: ReportExportData, options: LocalExportOptions): String = JSONArray().apply {
    if (!options.reports) return@apply
    fun cells(values: List<HeatmapCell>) = JSONArray().apply { values.forEach {
        put(JSONObject().put("date", it.date.toString()).put("hour", it.hour).put("value", it.value ?: JSONObject.NULL)
            .put("partial", it.partial).put("actualDurationMs", it.actualDurationMs).put("future", it.future)
            .put("capturedAt", it.capturedAt ?: JSONObject.NULL))
    } }
    data.reports.forEach { report -> put(JSONObject().apply {
        put("startDate", report.startDate); put("endDateExclusive", report.endDateExclusive); put("zone", report.zone)
        put("cutoffExclusiveMs", report.cutoff); put("createdAt", report.createdAt); put("expiresAt", report.expiresAt)
        put("counts", JSONObject().put("recordedPauses", report.counts.recorded).put("exited", report.counts.exited)
            .put("continued", report.counts.continued).put("interrupted", report.counts.interrupted)
            .put("pending", report.counts.pending).put("displayFailed", report.counts.displayFailed))
        put("targets", JSONArray().apply { report.targets.forEach { target -> put(JSONObject().put("packageName", target.packageName).put("label", target.label).apply {
            if (options.appCategories) target.category?.let { put("category", it) }
        }) } })
        put("pauseHours", cells(report.pauses))
        put("limitations", "所选目标的留存快照，不是全部打开次数；删除、漏采和保留期可能造成缺口；继续不是失败。")
        if (options.usage) {
            put("foregroundMs", report.foregroundMs ?: JSONObject.NULL); put("usagePartial", report.usagePartial)
            put("usageHours", cells(report.usage))
            put("usageLimitations", "各目标前台估算之和，可能重叠；未知不等于零；未减停顿层时间。")
        }
        if (options.reportInterpretations && report.suggestion != null) put("privateAiInterpretation", JSONObject()
            .put("aiGenerated", true).put("observations", JSONArray(report.observations)).put("suggestion", report.suggestion)
            .put("interpretationNotice", "私人AI文字可能引用画像、记忆、理由、时长或设置；未附带完整请求背景。")
            .apply { report.rulePatch?.let { put("settingsPatch", RuleSuggestionProtocol.patch(it)) } })
    }) }
}.toString()
