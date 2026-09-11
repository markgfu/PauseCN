package app.pausecn.reports

import app.pausecn.data.RulePatch
import app.pausecn.data.RuleState
import app.pausecn.data.RuleValues
import app.pausecn.domain.ScheduleSpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSuggestionProtocolTest {
    @Test
    fun `settings authorization defaults off and uploads neither rules nor revisions`() {
        val consent = ReportConfig(enabled = true)
        val basis = basis()

        val (text, _) = ReportProtocol.input(facts(), consent, "温和", emptyBackground(), basis)
        val root = JSONObject(text)

        assertFalse(ReportConfig().useSettings)
        assertFalse(root.has("current_rules"))
        assertFalse(root.has("rule_capabilities"))
        listOf(basis.revision, basis.waitRevision, basis.passRevision, basis.scheduleRevision)
            .forEach { assertFalse(text.contains(it)) }
    }

    @Test
    fun `authorized request contains values and capabilities but never local revisions`() {
        val basis = basis()

        val (text, _) = ReportProtocol.input(
            facts(), ReportConfig(enabled = true, useSettings = true), "温和", emptyBackground(), basis,
        )
        val root = JSONObject(text)

        assertEquals(6, root.getJSONObject("current_rules").getInt("wait_seconds"))
        assertEquals(5, root.getJSONObject("current_rules").getInt("pass_minutes"))
        assertEquals(listOf(3, 6, 10), ints(root.getJSONObject("rule_capabilities").getJSONArray("wait_seconds")))
        listOf(basis.revision, basis.waitRevision, basis.passRevision, basis.scheduleRevision)
            .forEach { assertFalse(text.contains(it)) }
    }

    @Test
    fun `patch requires strict integers whitelist and known fields`() {
        val valid = JSONObject()
            .put("wait_seconds", 10L)
            .put("pass_minutes", 15)
            .put("schedule", JSONObject().put("enabled", false).put("start_minutes", 480)
                .put("end_minutes", 1_020).put("days_mask", 31))
        assertEquals(RulePatch(10, 15, ScheduleSpec(false, 480, 1_020, 31)), RuleSuggestionProtocol.parsePatch(valid))

        listOf(
            JSONObject().put("wait_seconds", 6.0),
            JSONObject().put("wait_seconds", 4),
            JSONObject().put("pass_minutes", 2),
            JSONObject().put("wait_seconds", 6).put("unknown", true),
            JSONObject().put("schedule", JSONObject().put("enabled", true).put("start_minutes", 0)
                .put("end_minutes", 0).put("days_mask", 127).put("unknown", true)),
        ).forEach { invalid ->
            assertThrows(Exception::class.java) { RuleSuggestionProtocol.parsePatch(invalid) }
        }
    }

    @Test
    fun `unsolicited patch and AI supplied baseline are ignored while prose survives`() {
        val validPatch = JSONObject().put("wait_seconds", 10)
        val unsolicited = ReportProtocol.parse(response(validPatch), SOURCES, ruleBasis = null)
        assertNull(unsolicited.rulePatch)
        assertEquals("正文观察", unsolicited.observations.single().text)

        val untrustedBaseline = JSONObject().put("wait_seconds", 10)
            .put("basis", JSONObject().put("revision", "ai-invented"))
        val rejected = ReportProtocol.parse(response(untrustedBaseline), SOURCES, basis())
        assertNull(rejected.rulePatch)
        assertEquals("正文建议", rejected.suggestion)
    }

    @Test
    fun `cache basis and patch round trip while old reports remain compatible`() {
        val basis = basis()
        val patch = RulePatch(waitSeconds = 10)
        val result = ReportInterpretation(
            observations = listOf(ReportObservation("正文观察", listOf("recorded_pauses"))),
            suggestion = "正文建议",
            rulePatch = patch,
        )

        val restoredBasis = RuleSuggestionProtocol.decodeBasis(RuleSuggestionProtocol.encodeBasis(basis))
        val restored = ReportProtocol.parse(ReportCacheCodec.interpretation(result), SOURCES, restoredBasis)
        assertEquals(basis, restoredBasis)
        assertEquals(result, restored)

        val oldJson = JSONObject().put("observations", JSONArray().put(JSONObject()
            .put("text", "旧报告观察").put("based_on", JSONArray().put("recorded_pauses"))))
            .put("suggestion", "旧报告建议").toString()
        val old = ReportProtocol.parse(oldJson, SOURCES, restoredBasis)
        assertNull(old.rulePatch)
        assertEquals("旧报告建议", old.suggestion)
        assertEquals("", ReportCacheRow("slot", "fingerprint", "{}", 1, 2).ruleBasisJson)
    }

    private fun response(patch: JSONObject) = JSONObject()
        .put("observations", JSONArray().put(JSONObject()
            .put("text", "正文观察").put("based_on", JSONArray().put("recorded_pauses"))))
        .put("suggestion", "正文建议")
        .put("settings_patch", patch)
        .toString()

    private fun basis() = RuleState(
        RuleValues(6, 5, ScheduleSpec(enabled = true, startMinutes = 0, endMinutes = 0, activeDaysMask = 127)),
        revision = "local-root-revision",
        waitRevision = "local-wait-revision",
        passRevision = "local-pass-revision",
        scheduleRevision = "local-schedule-revision",
    )

    private fun emptyBackground() = ReportBackground("[]", emptySet(), Long.MAX_VALUE)

    private fun facts() = ReportFacts(
        ReportWindow(ReportPeriod.TODAY, java.time.LocalDate.of(2026, 9, 9), java.time.LocalDate.of(2026, 9, 10),
            "UTC", 0, 86_400_000, 43_200_000),
        43_200_000, emptyList(), ReportCounts(1, 0, 0, 0, 0), null, true, null,
        emptyList(), emptyList(), emptyList(), "facts", "source", Long.MAX_VALUE,
    )

    private fun ints(array: JSONArray) = (0 until array.length()).map { array.getInt(it) }

    private companion object {
        val SOURCES = setOf("recorded_pauses")
    }
}
