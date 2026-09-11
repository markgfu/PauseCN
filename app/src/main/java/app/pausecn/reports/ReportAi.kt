package app.pausecn.reports

import androidx.room.*
import app.pausecn.ai.*
import app.pausecn.data.PauseDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

@Entity(tableName = "report_config")
data class ReportConfig(@PrimaryKey val id: Int = 1, val enabled: Boolean = false,
    val useUsage: Boolean = false, val useProfile: Boolean = false, val useMemories: Boolean = false,
    val useReasons: Boolean = false, val epoch: Long = 0,
    @ColumnInfo(defaultValue = "0") val useSettings: Boolean = false)

@Dao
interface ReportDao {
    @Query("SELECT * FROM report_cache ORDER BY createdAt DESC, slot LIMIT 4") suspend fun exportCandidates(): List<ReportCacheRow>
    @Query("SELECT * FROM report_cache WHERE slot = :slot") suspend fun cached(slot: String): ReportCacheRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveCache(row: ReportCacheRow)
    @Query("DELETE FROM report_cache WHERE slot = :slot") suspend fun deleteCache(slot: String)
    @Query("DELETE FROM report_cache") suspend fun clearCache()
    @Query("DELETE FROM report_cache WHERE expiresAt <= :now OR createdAt > :now OR slot NOT IN (SELECT slot FROM report_cache ORDER BY createdAt DESC LIMIT 4)") suspend fun pruneCache(now: Long)
    @Query("""UPDATE report_cache SET interpretationJson = :json, aiTag = :tag, consentEpoch = :epoch,
        backgroundHash = :background, ruleBasisJson = :ruleBasis, expiresAt = MIN(expiresAt, :expires)
        WHERE slot = :slot AND fingerprint = :fingerprint""")
    suspend fun saveInterpretation(slot: String, fingerprint: String, json: String, tag: String, epoch: Long, background: String, expires: Long, ruleBasis: String = ""): Int
    @Query("SELECT * FROM report_config WHERE id = 1") suspend fun config(): ReportConfig?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(config: ReportConfig)
    @Query("DELETE FROM report_config") suspend fun clearConfig()
}

data class ReportObservation(val text: String, val basedOn: List<String>)
data class ReportInterpretation(val observations: List<ReportObservation>, val suggestion: String, val rulePatch: app.pausecn.data.RulePatch? = null)
internal data class ReportBackground(val json: String, val ids: Set<String>, val expiresAt: Long, val allowCategories: Boolean = false)
internal data class ReportRequestSnapshot(val local: LocalReportSnapshot, val consent: ReportConfig,
    val ai: AiConfig, val background: ReportBackground, val userJson: String, val sourceIds: Set<String>, val expiresAt: Long,
    val ruleBasis: app.pausecn.data.RuleState? = null, val automatic: AutomaticReportTicket? = null)
internal data class ReportDelivery(val result: ReportInterpretation, val request: ReportRequestSnapshot)

/** Invoked in the caller's short guarded Room transaction. No raw chat or installed-app inventory. */
internal suspend fun reportBackground(db: PauseDatabase, facts: ReportFacts, consent: ReportConfig,
    now: Long, days: Int): ReportBackground {
    val items = JSONArray()
    val ids = linkedSetOf<String>()
    var expires = facts.validUntil
    fun add(id: String, text: String) { if (text.isNotBlank()) { ids.add(id); items.put(JSONObject().put("id", id).put("text", text)) } }
    if (consent.useProfile) db.aiDao().profile()?.let { add("profile_goal", it.goal); add("profile_preferences", it.preferences) }
    val packages = facts.targets.map { it.packageName }.toSet()
    val retentionMs = days.coerceIn(1, 30) * 86_400_000L
    if (consent.useMemories) db.conversationDao().memories(now, retentionMs)
        .filter { it.confirmed && it.createdAt <= now && (it.scopePackage.isEmpty() || it.scopePackage in packages) }
        .take(4).forEachIndexed { index, memory ->
            add("memory_$index", memory.text)
            expires = minOf(expires, memory.expiresAt)
        }
    if (consent.useReasons) facts.targets.take(3).forEachIndexed { appIndex, target ->
        db.interventionEventDao().reasonsForAi(target.packageName, maxOf(facts.window.start, now - retentionMs), facts.window.cutoff - 1)
            .take(2).forEachIndexed { index, reason ->
                add("reason_${appIndex}_$index", "${target.label}：${reason.text}（本期留存 ${reason.uses} 次；不代表本次目的）")
                expires = minOf(expires, reason.firstUsedAt + retentionMs)
            }
    }
    return ReportBackground(items.toString(), ids, expires, db.appCategoryDao().settings()?.sendToAi == true)
}

object ReportProtocol {
    const val PROMPT_ASSET = "prompts/report.md"
    internal fun input(facts: ReportFacts, consent: ReportConfig, style: String, background: ReportBackground,
        ruleBasis: app.pausecn.data.RuleState? = null): Pair<String, Set<String>> {
        require(consent.enabled)
        val ids = linkedSetOf<String>()
        val values = JSONArray()
        fun add(id: String, value: Any) { ids.add(id); values.put(JSONObject().put("id", id).put("value", value)) }
        add("recorded_pauses", facts.counts.recorded); add("exited", facts.counts.exited); add("continued", facts.counts.continued)
        add("interrupted", facts.counts.interrupted); add("pending", facts.counts.pending); add("display_failed", facts.counts.displayFailed)
        facts.counts.exitPercent?.let { add("exit_percent_of_completed", it) }
        if (consent.useUsage) facts.foregroundMs?.let { add("foreground_ms", it); add("usage_partial", facts.usagePartial) }
        if (background.allowCategories) facts.categories.filter { it.counts.recorded > 0 || (consent.useUsage && it.foregroundMs != null) }
            .take(10).forEachIndexed { index, row ->
                add("category_$index", JSONObject().put("category", row.category).put("recorded_pauses", row.counts.recorded)
                    .put("exited", row.counts.exited).put("continued", row.counts.continued)
                    .put("interrupted", row.counts.interrupted).put("pending", row.counts.pending)
                    .put("display_failed", row.counts.displayFailed).apply {
                        if (consent.useUsage) { put("foreground_ms", row.foregroundMs ?: JSONObject.NULL); put("usage_partial", row.usagePartial) }
                    })
            }
        // Duration-specific notes/cells are excluded when usage transmission has not been selected.
        val limitations = JSONArray().put("仅所选目标的留存样本，不是全部打开次数；删除、停用、漏采和保留期可能造成缺口。")
            .put("继续不是失败；没有节省时间、自律评分或可比较的历史基线。")
        if (consent.useUsage) limitations.put("前台估算为各目标之和，可能重叠，未知不是零，未减停顿时长。")
        val body = JSONObject().put("period", facts.window.period.name).put("start_date", facts.window.startDate.toString())
            .put("end_date_inclusive", facts.window.endDateExclusive.minusDays(1).toString()).put("zone", facts.window.zoneId)
            .put("cutoff_exclusive_ms", facts.window.cutoff).put("ongoing", facts.window.ongoing).put("target_count", facts.targets.size)
            .put("style", style).put("facts", values).put("limitations", limitations)
        val acceptedBackground = JSONArray()
        val candidates = JSONArray(background.json)
        for (index in 0 until candidates.length()) {
            val row = candidates.getJSONObject(index)
            val id = row.getString("id")
            val allowed = (consent.useProfile && id in setOf("profile_goal", "profile_preferences")) ||
                (consent.useMemories && id.startsWith("memory_")) || (consent.useReasons && id.startsWith("reason_"))
            if (allowed && id in background.ids) { acceptedBackground.put(row); ids.add(id) }
        }
        body.put("background", acceptedBackground)
        if (consent.useSettings) {
            require(ruleBasis != null)
            body.put("current_rules", RuleSuggestionProtocol.values(ruleBasis.values))
            body.put("rule_capabilities", RuleSuggestionProtocol.capabilities())
        }
        return body.toString() to ids
    }

    fun parse(content: String, sourceIds: Set<String>, ruleBasis: app.pausecn.data.RuleState? = null): ReportInterpretation {
        formatCheck(content.length <= 16_000, ReportFormatProblem.RESPONSE_LENGTH)
        val raw = content.trim().let { fence.matchEntire(it)?.groupValues?.get(1) ?: it }
        val root = try {
            val tokenizer = JSONTokener(raw)
            val value = tokenizer.nextValue() as? JSONObject ?: throw ReportFormatException(ReportFormatProblem.JSON)
            formatCheck(tokenizer.nextClean() == '\u0000', ReportFormatProblem.JSON)
            value
        } catch (_: org.json.JSONException) { throw ReportFormatException(ReportFormatProblem.JSON) }
        val rows = root.opt("observations") as? JSONArray ?: throw ReportFormatException(ReportFormatProblem.STRUCTURE)
        formatCheck(rows.length() in 1..3, ReportFormatProblem.COUNT)
        val observations = (0 until rows.length()).map { index ->
            val row = rows.optJSONObject(index) ?: throw ReportFormatException(ReportFormatProblem.STRUCTURE)
            val text = row.opt("text") as? String ?: throw ReportFormatException(ReportFormatProblem.STRUCTURE)
            checkText(text)
            val refs = row.opt("based_on") as? JSONArray ?: throw ReportFormatException(ReportFormatProblem.SOURCES)
            formatCheck(refs.length() in 1..6, ReportFormatProblem.SOURCES)
            val accepted = (0 until refs.length()).map { refs.opt(it) as? String ?: throw ReportFormatException(ReportFormatProblem.SOURCES) }
            formatCheck(accepted.all { it in sourceIds }, ReportFormatProblem.SOURCES)
            ReportObservation(text.trim(), accepted.distinct())
        }
        val suggestion = root.opt("suggestion") as? String ?: throw ReportFormatException(ReportFormatProblem.STRUCTURE)
        checkText(suggestion)
        // Invalid/unsolicited executable fields are discarded, without retrying or losing the valid prose.
        val patch = if (ruleBasis != null && !root.isNull("settings_patch")) runCatching {
            RuleSuggestionProtocol.parsePatch(root.getJSONObject("settings_patch")).takeIf { it.target(ruleBasis.values) != ruleBasis.values }
        }.getOrNull() else null
        return ReportInterpretation(observations, suggestion.trim(), patch)
    }
    private fun checkText(text: String) {
        formatCheck(text.codePointCount(0, text.length) <= 300, ReportFormatProblem.TEXT_LENGTH)
        formatCheck(text.isNotBlank() &&
        text.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() } &&
        !Regex("https?://|sk-[a-zA-Z0-9]{12,}", RegexOption.IGNORE_CASE).containsMatchIn(text), ReportFormatProblem.TEXT_FORMAT)
    }
    private fun formatCheck(accepted: Boolean, problem: ReportFormatProblem) {
        if (!accepted) throw ReportFormatException(problem)
    }
    private val fence = Regex("```(?:json)?[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n```", RegexOption.IGNORE_CASE)
}
