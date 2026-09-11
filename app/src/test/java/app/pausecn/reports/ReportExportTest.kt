package app.pausecn.reports

import app.pausecn.data.LocalDataExportSnapshot
import app.pausecn.data.LocalExportOptions
import app.pausecn.data.AppCategoryRow
import app.pausecn.data.RulePatch
import app.pausecn.data.SettingsSnapshot
import app.pausecn.data.encodeLocalDataExport
import app.pausecn.usage.HeatmapCell
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportExportTest {
    @Test
    fun `reports are absent by default even when populated data is supplied`() {
        val json = encodeReportExport(reportData(), LocalExportOptions())

        assertEquals(0, JSONArray(json).length())
        assertFalse(json.contains("settingsPatch"))
        assertFalse(localSnapshot(reports = reportData()).contains("\"reports\":"))
    }

    @Test
    fun `facts-only report excludes AI usage and internal snapshot metadata`() {
        val exported = projectReportExport(
            facts(),
            LocalExportOptions(reports = true),
            expiresAt = 8_000,
            interpretation = interpretation(),
        )
        val json = encodeReportExport(ReportExportData(listOf(exported)), LocalExportOptions(reports = true))
        val report = JSONArray(json).getJSONObject(0)

        assertTrue(report.has("counts"))
        assertTrue(report.has("pauseHours"))
        listOf("foregroundMs", "usagePartial", "usageHours", "privateAiInterpretation")
            .forEach { assertFalse(report.has(it)) }
        listOf("fingerprint", "sourceFingerprint", "stamp", "eventIds", "hourKeys", "elapsedAt", "bootId")
            .forEach { assertFalse(json.contains("\"$it\":")) }
        assertFalse(json.contains("private-note-secret"))
        assertFalse(json.contains("ai-observation-secret"))

        val populatedButUnselected = JSONArray(encodeReportExport(
            reportData(), LocalExportOptions(reports = true),
        )).getJSONObject(0)
        listOf("foregroundMs", "usagePartial", "usageHours", "privateAiInterpretation")
            .forEach { assertFalse(populatedButUnselected.has(it)) }
        assertFalse(populatedButUnselected.toString().contains("ai-observation-secret"))
    }

    @Test
    fun `usage and private interpretation require their independent switches`() {
        val usageOnly = LocalExportOptions(reports = true, usage = true)
        val usageJson = JSONArray(encodeReportExport(
            ReportExportData(listOf(projectReportExport(facts(), usageOnly, 8_000, interpretation()))), usageOnly,
        )).getJSONObject(0)
        assertTrue(usageJson.has("foregroundMs"))
        assertTrue(usageJson.has("usageHours"))
        assertFalse(usageJson.has("privateAiInterpretation"))
        assertFalse(usageJson.toString().contains("settingsPatch"))

        val aiOnly = LocalExportOptions(reports = true, reportInterpretations = true)
        val aiJson = JSONArray(encodeReportExport(
            ReportExportData(listOf(projectReportExport(facts(), aiOnly, 8_000, interpretation()))), aiOnly,
        )).getJSONObject(0)
        val privateAi = aiJson.getJSONObject("privateAiInterpretation")
        assertTrue(privateAi.has("settingsPatch"))
        assertEquals(10, privateAi.getJSONObject("settingsPatch").getInt("wait_seconds"))
        listOf("basis", "ruleBasis", "revision", "waitRevision", "passRevision", "scheduleRevision")
            .forEach { assertFalse(privateAi.toString().contains("\"$it\":")) }
        assertFalse(aiJson.has("foregroundMs"))
        assertFalse(aiJson.has("usageHours"))
    }

    @Test
    fun `unknown duration and cells remain null rather than zero`() {
        val options = LocalExportOptions(reports = true, usage = true)
        val report = projectReportExport(facts(foregroundMs = null), options, 8_000)
        val json = JSONArray(encodeReportExport(ReportExportData(listOf(report)), options)).getJSONObject(0)

        assertTrue(json.isNull("foregroundMs"))
        assertTrue(json.getJSONArray("usageHours").getJSONObject(1).isNull("value"))
        assertFalse(json.toString().contains("\"foregroundMs\":0"))
    }

    @Test
    fun `format five includes selected reports while format four ignores report options`() {
        val options = LocalExportOptions(reports = true, reportInterpretations = true)
        val versionFive = JSONObject(localSnapshot(options = options, reports = reportData()))
        val versionFour = JSONObject(localSnapshot(formatVersion = 4, options = options, reports = reportData()))

        assertEquals(5, versionFive.getInt("formatVersion"))
        assertTrue(versionFive.getJSONArray("includedCategories").toString().contains("reports"))
        assertTrue(versionFive.has("reports"))
        assertFalse(versionFour.getJSONArray("includedCategories").toString().contains("reports"))
        assertFalse(versionFour.has("reports"))
    }

    @Test
    fun `report and collection expiry use the earliest deadline`() {
        val options = LocalExportOptions(reports = true)
        val first = projectReportExport(facts(validUntil = 9_000), options, expiresAt = 8_000)
        val second = projectReportExport(facts(validUntil = 7_000), options, expiresAt = 10_000)

        assertEquals(8_000, first.expiresAt)
        assertEquals(7_000, ReportExportData(listOf(first, second)).validUntil)
    }

    @Test
    fun `application categories require their independent export selection`() {
        val rows = listOf(AppCategoryRow("app.a", automatic = "工具", manual = "学习阅读"))
        val fullyPopulated = ReportExportData(listOf(projectReportExport(facts(),
            LocalExportOptions(reports = true, usage = true, reportInterpretations = true, appCategories = true), 8_000, interpretation())))
        val denied = JSONObject(localSnapshot(formatVersion = 6, reports = fullyPopulated, appCategories = rows))
        assertFalse(denied.has("appCategories"))
        assertFalse(denied.toString().contains("学习阅读"))

        val options = LocalExportOptions(reports = true, appCategories = true)
        val allowed = JSONObject(localSnapshot(formatVersion = 6, options = options, reports = fullyPopulated, appCategories = rows))
        assertEquals("学习阅读", allowed.getJSONArray("appCategories").getJSONObject(0).getString("effective"))
        assertTrue(allowed.getJSONArray("includedCategories").toString().contains("app_categories"))
        assertEquals("学习阅读", allowed.getJSONArray("reports").getJSONObject(0)
            .getJSONArray("targets").getJSONObject(0).getString("category"))
    }

    private fun localSnapshot(
        formatVersion: Int = 5,
        options: LocalExportOptions = LocalExportOptions(),
        reports: ReportExportData? = null,
        appCategories: List<AppCategoryRow> = emptyList(),
    ) = encodeLocalDataExport(
        LocalDataExportSnapshot(
            formatVersion = formatVersion,
            exportedAtEpochMs = 1,
            appVersion = "test",
            settings = SettingsSnapshot(),
            targets = emptyList(),
            events = emptyList(),
            options = options,
            reports = reports,
            appCategories = appCategories,
        ),
    )

    private fun reportData(): ReportExportData {
        val options = LocalExportOptions(reports = true, usage = true, reportInterpretations = true)
        return ReportExportData(listOf(projectReportExport(facts(), options, 8_000, interpretation())))
    }

    private fun interpretation() = ReportInterpretation(
        listOf(ReportObservation("ai-observation-secret", listOf("recorded_pauses"))),
        "ai-suggestion-secret",
        RulePatch(waitSeconds = 10),
    )

    private fun facts(foregroundMs: Long? = 120_000, validUntil: Long = 9_000): ReportFacts {
        val date = LocalDate.of(2026, 9, 9)
        val cells = listOf(
            HeatmapCell(date, 0, 2, false, 3_600_000, capturedAt = 2_000),
            HeatmapCell(date, 1, null, true, 3_600_000, capturedAt = null),
        )
        return ReportFacts(
            ReportWindow(ReportPeriod.TODAY, date, date.plusDays(1), "UTC", 0, 86_400_000, 4_000),
            createdAt = 4_000,
            targets = listOf(ReportTarget("app.a", "应用甲", 0, "学习阅读")),
            counts = ReportCounts(1, 2, 0, 1, 0),
            foregroundMs = foregroundMs,
            usagePartial = true,
            usageCapturedAt = 2_000,
            pauseCells = cells,
            usageCells = cells,
            notes = listOf("private-note-secret"),
            fingerprint = "internal-fingerprint",
            sourceFingerprint = "internal-source-fingerprint",
            validUntil = validUntil,
        )
    }
}
