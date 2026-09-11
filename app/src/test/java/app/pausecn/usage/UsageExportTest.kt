package app.pausecn.usage

import app.pausecn.data.LocalDataExportSnapshot
import app.pausecn.data.LocalExportOptions
import app.pausecn.data.SettingsSnapshot
import app.pausecn.data.encodeLocalDataExport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageExportTest {
    @Test
    fun `default local export omits the entire populated usage object`() {
        val json = encodeLocalDataExport(
            LocalDataExportSnapshot(
                exportedAtEpochMs = 1,
                appVersion = "test",
                settings = SettingsSnapshot(),
                targets = emptyList(),
                events = emptyList(),
                usage = privateUsageData(),
            ),
        )

        val root = JSONObject(json)
        assertFalse(root.has("usage"))
        assertFalse(root.getJSONArray("includedCategories").toString().contains("usage"))
        assertFalse(json.contains("app.private"))
        assertFalse(json.contains("private-display-id"))
    }

    @Test
    fun `selected usage export exposes projection without internal ids clocks or raw events`() {
        val json = encodeUsageExport(privateUsageData(), LocalExportOptions(usage = true))
        val root = JSONObject(json)
        val hour = root.getJSONArray("hours").getJSONObject(0)
        val display = root.getJSONArray("pauseDisplays").getJSONObject(0)

        assertEquals("app.private", hour.getString("packageName"))
        assertEquals(5_000L, display.getLong("durationMs"))
        listOf("periodId", "id", "bootId", "startedElapsed", "endedElapsed", "events")
            .forEach { field ->
                assertFalse(hour.has(field))
                assertFalse(display.has(field))
            }
        assertFalse(json.contains("private-period-id"))
        assertFalse(json.contains("private-display-id"))
        assertFalse(json.contains("private-boot-id"))
        assertFalse(json.contains("\"events\":"))
    }

    private fun privateUsageData() = UsageExportData(
        hours = listOf(
            UsageHourEntity(
                periodId = "private-period-id",
                packageName = "app.private",
                zoneId = "UTC",
                start = 100_000,
                end = 200_000,
                localDate = "2026-09-08",
                localHour = 8,
                evaluatedFrom = 100_000,
                evaluatedTo = 200_000,
                foregroundMs = 10_000,
                unknownMs = 90_000,
                completeness = "PARTIAL",
                limitations = "fixture",
                capturedAt = 200_000,
            ),
        ),
        displays = listOf(
            PauseDisplay(
                id = "private-display-id",
                packageName = "app.private",
                startedAt = 120_000,
                startedElapsed = 10_000,
                bootId = "private-boot-id",
                zoneId = "UTC",
                endedAt = 125_000,
                endedElapsed = 15_000,
                status = "CONFIRMED",
            ),
        ),
    )
}
