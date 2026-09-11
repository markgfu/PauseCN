package app.pausecn.reports

import app.pausecn.data.*
import app.pausecn.domain.ScheduleSpec
import org.json.JSONArray
import org.json.JSONObject

data class RuleProposal(val basis: RuleState, val patch: RulePatch)

/** Wire values are separate from local revisions. Never trust an AI-supplied baseline. */
internal object RuleSuggestionProtocol {
    fun values(value: RuleValues) = JSONObject().put("wait_seconds", value.waitSeconds).put("pass_minutes", value.passMinutes)
        .put("schedule", schedule(value.schedule))
    private fun schedule(value: ScheduleSpec) = JSONObject().put("enabled", value.enabled).put("start_minutes", value.startMinutes)
        .put("end_minutes", value.endMinutes).put("days_mask", value.activeDaysMask)
    fun capabilities() = JSONObject().put("scope", "all_selected_apps_global_rules")
        .put("wait_seconds", JSONArray(RulePatch.WAIT_CHOICES)).put("pass_minutes", JSONArray(RulePatch.PASS_CHOICES))
        .put("schedule", "enabled=false或days_mask=0表示不干预；起止0..1439，相同表示全天；days_mask的bit0为周一，跨午夜按开始日星期。不能按应用/昼夜分别设置时长。")
    private fun keys(json: JSONObject, allowed: Set<String>) { require(json.keys().asSequence().all { it in allowed }) }
    private fun integer(json: JSONObject, key: String): Int {
        val value = json.get(key); require(value is Int || value is Long)
        val number = (value as Number).toLong(); require(number in Int.MIN_VALUE..Int.MAX_VALUE)
        return number.toInt()
    }
    private fun readSchedule(json: JSONObject): ScheduleSpec {
        keys(json, setOf("enabled", "start_minutes", "end_minutes", "days_mask"))
        val enabled = json.get("enabled"); require(enabled is Boolean)
        return ScheduleSpec(enabled, integer(json, "start_minutes"), integer(json, "end_minutes"), integer(json, "days_mask"))
    }
    fun parsePatch(json: JSONObject): RulePatch {
        keys(json, setOf("wait_seconds", "pass_minutes", "schedule"))
        return RulePatch(if (json.has("wait_seconds")) integer(json, "wait_seconds") else null,
            if (json.has("pass_minutes")) integer(json, "pass_minutes") else null,
            if (json.has("schedule")) readSchedule(json.getJSONObject("schedule")) else null).also { it.validate() }
    }
    fun patch(value: RulePatch) = JSONObject().apply {
        value.waitSeconds?.let { put("wait_seconds", it) }; value.passMinutes?.let { put("pass_minutes", it) }
        value.schedule?.let { put("schedule", schedule(it)) }
    }
    fun encodeBasis(state: RuleState): String = values(state.values).put("version", 1).put("revision", state.revision)
        .put("wait_revision", state.waitRevision).put("pass_revision", state.passRevision).put("schedule_revision", state.scheduleRevision).toString()
    fun decodeBasis(text: String): RuleState {
        require(text.length <= 2_000)
        val json = JSONObject(text); require(json.getInt("version") == 1)
        val value = RuleValues(integer(json, "wait_seconds"), integer(json, "pass_minutes"), readSchedule(json.getJSONObject("schedule")))
        require(value.waitSeconds in 3..15 && value.passMinutes in 1..30)
        return RuleState(value, json.getString("revision"), json.getString("wait_revision"), json.getString("pass_revision"), json.getString("schedule_revision"))
    }
}
