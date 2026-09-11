package app.pausecn.usage

import app.pausecn.data.LocalExportOptions
import app.pausecn.data.PauseDatabase
import app.pausecn.data.sanitizeHistoryRetentionDays
import org.json.JSONArray
import org.json.JSONObject

/** Explicit export projection excludes boot identity, monotonic anchors, raw events and settings. */
data class UsageExportData(val hours: List<UsageHourEntity> = emptyList(),
    val displays: List<PauseDisplay> = emptyList(), val validUntil: Long = Long.MAX_VALUE)

internal suspend fun readUsageExport(database: PauseDatabase, options: LocalExportOptions,
    now: Long, retentionDays: Int): UsageExportData? {
    if (!options.usage) return null
    val retentionMs = sanitizeHistoryRetentionDays(retentionDays) * 86_400_000L
    val since = now - retentionMs
    val hours = database.usageDao().hours(since, now).filter {
        it.evaluatedFrom >= since && it.evaluatedTo <= now && it.capturedAt <= now
    }
    val displays = database.usageDao().displays(since, now)
    val deadline = minOf(hours.minOfOrNull { it.evaluatedFrom + retentionMs } ?: Long.MAX_VALUE,
        displays.minOfOrNull { it.startedAt + retentionMs } ?: Long.MAX_VALUE)
    return UsageExportData(hours, displays, deadline)
}

internal fun encodeUsageExport(data: UsageExportData, options: LocalExportOptions): String = JSONObject().apply {
    if (!options.usage) return@apply
    put("source", "android_usage_events_estimate")
    put("limitations", "所选目标前台时长估算；可重叠；未知不等于零；未减去停顿展示时长")
    put("hours", JSONArray().apply { data.hours.forEach {
        put(JSONObject().put("packageName", it.packageName).put("zoneId", it.zoneId)
            .put("start", it.start).put("end", it.end).put("algorithm", it.algorithm)
            .put("localDate", it.localDate).put("localHour", it.localHour)
            .put("evaluatedFrom", it.evaluatedFrom).put("evaluatedTo", it.evaluatedTo)
            .put("foregroundMs", it.foregroundMs ?: JSONObject.NULL).put("unknownMs", it.unknownMs)
            .put("completeness", it.completeness).put("limitations", it.limitations).put("capturedAt", it.capturedAt))
    } })
    put("pauseDisplays", JSONArray().apply { data.displays.forEach {
        put(JSONObject().put("packageName", it.packageName).put("zoneId", it.zoneId)
            .put("startedAt", it.startedAt).put("endedAt", it.endedAt ?: JSONObject.NULL)
            .put("durationMs", it.durationMs ?: JSONObject.NULL).put("status", it.status))
    } })
}.toString()
