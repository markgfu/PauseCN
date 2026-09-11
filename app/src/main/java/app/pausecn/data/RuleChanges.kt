package app.pausecn.data

import app.pausecn.domain.ScheduleSpec
import org.json.JSONObject

/** One global rule set; never represents app-specific or day/night-specific durations. */
data class RuleValues(val waitSeconds: Int = 6, val passMinutes: Int = 5, val schedule: ScheduleSpec = ScheduleSpec())
data class RuleState(val values: RuleValues = RuleValues(), val revision: String = "",
    val waitRevision: String = "", val passRevision: String = "", val scheduleRevision: String = "")
data class RulePatch(val waitSeconds: Int? = null, val passMinutes: Int? = null, val schedule: ScheduleSpec? = null) {
    fun validate() {
        require(waitSeconds == null || waitSeconds in WAIT_CHOICES) { "等待时间不在支持选项中" }
        require(passMinutes == null || passMinutes in PASS_CHOICES) { "临时通行不在支持选项中" }
        require(waitSeconds != null || passMinutes != null || schedule != null) { "没有要修改的项目" }
    }
    fun target(before: RuleValues): RuleValues = before.copy(waitSeconds = waitSeconds ?: before.waitSeconds,
        passMinutes = passMinutes ?: before.passMinutes, schedule = schedule ?: before.schedule)
    companion object {
        val WAIT_CHOICES = listOf(3, 6, 10)
        val PASS_CHOICES = listOf(1, 5, 15)
        fun between(before: RuleValues, after: RuleValues) = RulePatch(
            after.waitSeconds.takeIf { it != before.waitSeconds }, after.passMinutes.takeIf { it != before.passMinutes },
            after.schedule.takeIf { it != before.schedule })
    }
}
data class RuleUndo(val before: RuleValues, val after: RuleState) { val id: String get() = after.revision }
data class RuleAdjustmentState(val current: RuleState, val undo: RuleUndo?)
class RuleConflictException(message: String) : IllegalStateException(message)

/** Pure decisions used by the same atomic DataStore edit as the actual write. */
object RuleChangePlanner {
    fun changed(current: RuleState, values: RuleValues, token: String): RuleState {
        if (current.values == values) return current
        require(token.isNotBlank() && token != current.revision)
        return RuleState(values, token,
            if (current.values.waitSeconds != values.waitSeconds) token else current.waitRevision,
            if (current.values.passMinutes != values.passMinutes) token else current.passRevision,
            if (current.values.schedule != values.schedule) token else current.scheduleRevision)
    }
    fun apply(current: RuleState, expected: RuleState, patch: RulePatch, token: String): RuleUndo {
        if (current != expected) throw RuleConflictException("设置已被修改，请重新查看当前值并确认。")
        patch.validate()
        val target = patch.target(current.values)
        require(target != current.values) { "所选设置没有变化" }
        return RuleUndo(current.values, changed(current, target, token))
    }
    fun undo(current: RuleState, undo: RuleUndo, token: String): RuleState {
        val before = undo.before; val after = undo.after
        val wait = before.waitSeconds != after.values.waitSeconds
        val pass = before.passMinutes != after.values.passMinutes
        val schedule = before.schedule != after.values.schedule
        if ((wait && (current.waitRevision != after.waitRevision || current.values.waitSeconds != after.values.waitSeconds)) ||
            (pass && (current.passRevision != after.passRevision || current.values.passMinutes != after.values.passMinutes)) ||
            (schedule && (current.scheduleRevision != after.scheduleRevision || current.values.schedule != after.values.schedule))) {
            throw RuleConflictException("本次修改的项目已有后续调整，不能覆盖撤销。")
        }
        require(wait || pass || schedule)
        return changed(current, current.values.copy(waitSeconds = if (wait) before.waitSeconds else current.values.waitSeconds,
            passMinutes = if (pass) before.passMinutes else current.values.passMinutes,
            schedule = if (schedule) before.schedule else current.values.schedule), token)
    }
}

/** Local undo metadata only. No AI prose, behavior or credentials. */
internal object RuleUndoCodec {
    private fun values(value: RuleValues) = JSONObject().put("wait", value.waitSeconds).put("pass", value.passMinutes)
        .put("enabled", value.schedule.enabled).put("start", value.schedule.startMinutes)
        .put("end", value.schedule.endMinutes).put("days", value.schedule.activeDaysMask)
    private fun values(json: JSONObject): RuleValues = RuleValues(json.getInt("wait"), json.getInt("pass"),
        ScheduleSpec(json.getBoolean("enabled"), json.getInt("start"), json.getInt("end"), json.getInt("days"))).also {
        require(it.waitSeconds in 3..15 && it.passMinutes in 1..30)
    }
    fun encode(undo: RuleUndo): String = JSONObject().put("version", 1).put("before", values(undo.before))
        .put("after", values(undo.after.values)).put("revision", undo.after.revision)
        .put("waitRevision", undo.after.waitRevision).put("passRevision", undo.after.passRevision)
        .put("scheduleRevision", undo.after.scheduleRevision).toString()
    fun decode(text: String): RuleUndo {
        require(text.length <= 2_000)
        val json = JSONObject(text); require(json.getInt("version") == 1)
        val revision = json.getString("revision"); require(revision.isNotBlank())
        return RuleUndo(values(json.getJSONObject("before")), RuleState(values(json.getJSONObject("after")), revision,
            json.getString("waitRevision"), json.getString("passRevision"), json.getString("scheduleRevision"))).also {
            require(it.before != it.after.values)
        }
    }
}
