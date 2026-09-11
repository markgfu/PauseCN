package app.pausecn.reports

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.pausecn.data.ExportStamp
import app.pausecn.usage.HeatmapCell
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate

@Entity(tableName = "report_cache")
data class ReportCacheRow(@PrimaryKey val slot: String, val fingerprint: String, val snapshotJson: String,
    val createdAt: Long, val expiresAt: Long, val interpretationJson: String = "", val aiTag: String = "",
    val consentEpoch: Long = -1, val backgroundHash: String = "",
    @ColumnInfo(defaultValue = "''") val ruleBasisJson: String = "")

/** No raw request, credentials, profile, memories or reasons are serialized as cache metadata.
 * The private interpretation may quote that background and therefore shares its deletion/expiry. */
internal object ReportCacheCodec {
    private fun array(values: Iterable<Any?>) = JSONArray().apply { values.forEach { put(it ?: JSONObject.NULL) } }
    private fun JSONArray.items(): List<Any> = (0 until length()).map { get(it) }
    fun encode(snapshot: LocalReportSnapshot): String = JSONObject().apply {
        put("version", 2)
        val f = snapshot.facts; val w = f.window
        put("window", array(listOf(w.period.name, w.startDate.toString(), w.endDateExclusive.toString(), w.zoneId, w.start, w.end, w.cutoff)))
        put("created", f.createdAt)
        put("targets", array(f.targets.map { array(listOf(it.packageName, it.label, it.selectedAt, it.category)) }))
        put("categories", array(f.categories.map { array(listOf(it.category, it.counts.exited, it.counts.continued,
            it.counts.interrupted, it.counts.pending, it.counts.displayFailed, it.foregroundMs, it.usagePartial)) }))
        put("counts", array(listOf(f.counts.exited, f.counts.continued, f.counts.interrupted, f.counts.pending, f.counts.displayFailed)))
        put("foreground", f.foregroundMs ?: JSONObject.NULL); put("partial", f.usagePartial); put("captured", f.usageCapturedAt ?: JSONObject.NULL)
        fun cells(rows: List<HeatmapCell>) = array(rows.map {
            array(listOf(it.date.toString(), it.hour, it.value, it.partial, it.actualDurationMs, it.future, it.capturedAt))
        })
        put("pauses", cells(f.pauseCells)); put("usage", cells(f.usageCells)); put("notes", array(f.notes))
        put("fingerprint", f.fingerprint); put("sourceFingerprint", f.sourceFingerprint); put("validUntil", f.validUntil)
        val s = snapshot.stamp
        put("stamp", array(listOf(s.instanceId, s.privacyEpoch, s.personalEpoch, s.conversationEpoch, s.usageRevision, s.categoryRevision)))
        put("events", array(snapshot.eventIds.sorted())); put("hours", array(snapshot.hourKeys.sorted()))
        put("days", snapshot.retentionDays); put("elapsed", snapshot.elapsedAt)
        put("permission", snapshot.systemUsagePermission); put("boot", snapshot.bootId)
    }.toString().also { require(it.length <= MAX_JSON) }

    fun decode(text: String): LocalReportSnapshot {
        require(text.length <= MAX_JSON)
        val root = JSONObject(text); require(root.getInt("version") in 1..2)
        val w = root.getJSONArray("window")
        val window = ReportWindow(ReportPeriod.valueOf(w.getString(0)), LocalDate.parse(w.getString(1)), LocalDate.parse(w.getString(2)),
            w.getString(3), w.getLong(4), w.getLong(5), w.getLong(6))
        require(window.cutoff in window.start..window.end)
        fun cells(name: String) = root.getJSONArray(name).also { require(it.length() in setOf(24, 168)) }.items().map { value ->
            val row = value as JSONArray
            HeatmapCell(LocalDate.parse(row.getString(0)), row.getInt(1), if (row.isNull(2)) null else row.getLong(2),
                row.getBoolean(3), row.getLong(4), row.getBoolean(5), if (row.isNull(6)) null else row.getLong(6))
        }
        val counts = root.getJSONArray("counts")
        val facts = ReportFacts(window, root.getLong("created"), root.getJSONArray("targets").items().map {
            val row = it as JSONArray; ReportTarget(row.getString(0), row.getString(1), row.getLong(2), row.optString(3, app.pausecn.data.AppCategories.UNCLASSIFIED))
        }, ReportCounts(counts.getInt(0), counts.getInt(1), counts.getInt(2), counts.getInt(3), counts.getInt(4)),
            if (root.isNull("foreground")) null else root.getLong("foreground"), root.getBoolean("partial"),
            if (root.isNull("captured")) null else root.getLong("captured"), cells("pauses"), cells("usage"),
            root.getJSONArray("notes").items().map { it as String }, root.getString("fingerprint"), root.getString("sourceFingerprint"), root.getLong("validUntil"),
            (root.optJSONArray("categories") ?: JSONArray()).items().map {
                val row = it as JSONArray
                ReportCategorySummary(row.getString(0), ReportCounts(row.getInt(1), row.getInt(2), row.getInt(3), row.getInt(4), row.getInt(5)),
                    if (row.isNull(6)) null else row.getLong(6), row.getBoolean(7))
            })
        val s = root.getJSONArray("stamp")
        return LocalReportSnapshot(facts, ExportStamp(s.getString(0), s.getLong(1), s.getLong(2), s.getLong(3), if (s.isNull(4)) null else s.getString(4), s.optLong(5, 0)),
            root.getJSONArray("events").items().map { (it as Number).toLong() }.toSet(), root.getJSONArray("hours").items().map { it as String }.toSet(),
            root.getInt("days"), root.getLong("elapsed"), root.getBoolean("permission"), root.getString("boot"))
    }

    fun interpretation(result: ReportInterpretation): String = JSONObject().put("observations", array(result.observations.map {
        JSONObject().put("text", it.text).put("based_on", array(it.basedOn))
    })).put("suggestion", result.suggestion).apply { result.rulePatch?.let { put("settings_patch", RuleSuggestionProtocol.patch(it)) } }.toString()

    fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun aiTag(config: app.pausecn.ai.AiConfig): String = hash(JSONArray().put(config.instanceId).put(config.privacyEpoch)
        .put(config.styleVersion).put(config.enabled).put(config.model).toString())
    const val MAX_JSON = 2_000_000
}

/** Triggers make deletion atomic with clearing derived private reports, including paths that do
 * not go through the AI repository. Ordinary event append/completion and usage refresh keep cache. */
internal fun installReportCacheTriggers(db: SupportSQLiteDatabase) {
    val deleteSources = listOf("intervention_events", "target_rules", "user_profile", "user_memories", "conversation_messages",
        "phrase_feedback", "ai_config", "personalization_config", "conversation_config", "report_config", "usage_hours", "usage_periods", "usage_config")
    deleteSources.forEach { table -> db.execSQL("CREATE TRIGGER IF NOT EXISTS report_delete_$table AFTER DELETE ON $table BEGIN DELETE FROM report_cache; END") }
    val changedSources = listOf("target_rules", "user_profile", "user_memories", "phrase_feedback", "ai_config", "personalization_config", "conversation_config", "report_config")
    changedSources.forEach { table -> listOf("INSERT", "UPDATE").forEach { operation ->
        db.execSQL("CREATE TRIGGER IF NOT EXISTS report_${operation.lowercase()}_$table AFTER $operation ON $table BEGIN DELETE FROM report_cache; END")
    } }
    db.execSQL("CREATE TRIGGER IF NOT EXISTS report_usage_revision AFTER UPDATE ON usage_config WHEN OLD.revision != NEW.revision BEGIN DELETE FROM report_cache; END")
    db.execSQL("CREATE TRIGGER IF NOT EXISTS report_forget_reason AFTER UPDATE ON intervention_events WHEN OLD.purpose IS NOT NULL AND NEW.purpose IS NULL BEGIN DELETE FROM report_cache; END")
    db.execSQL("CREATE TRIGGER IF NOT EXISTS report_exclude_message AFTER UPDATE ON conversation_messages WHEN OLD.aiEligible != NEW.aiEligible BEGIN DELETE FROM report_cache; END")
}

internal val REPORT_CACHE_CALLBACK = object : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) = installReportCacheTriggers(db)
}
