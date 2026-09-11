package app.pausecn.reports

import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportProtocolTest {
    @Test
    fun `report consent defaults every report and background permission off`() {
        val consent = ReportConfig()

        assertFalse(consent.enabled)
        assertFalse(consent.useUsage)
        assertFalse(consent.useProfile)
        assertFalse(consent.useMemories)
        assertFalse(consent.useReasons)
    }

    @Test
    fun `request projection follows consent and never invents missing duration`() {
        val privateBackground = ReportBackground(
            json = JSONArray().put(JSONObject().put("id", "memory_0").put("text", "私人背景")).toString(),
            ids = setOf("memory_0"),
            expiresAt = Long.MAX_VALUE,
        )
        val denied = ReportProtocol.input(
            facts(foregroundMs = 123_000),
            ReportConfig(enabled = true),
            "温和",
            privateBackground,
        )
        val deniedJson = JSONObject(denied.first)
        assertFalse(factIds(deniedJson).contains("foreground_ms"))
        assertFalse(factIds(deniedJson).contains("usage_partial"))
        assertEquals(0, deniedJson.getJSONArray("background").length())
        assertFalse("memory_0" in denied.second)

        val allowedConsent = ReportConfig(
            enabled = true,
            useUsage = true,
            useMemories = true,
        )
        val allowed = ReportProtocol.input(facts(foregroundMs = 123_000), allowedConsent, "温和", privateBackground)
        assertTrue(factIds(JSONObject(allowed.first)).containsAll(setOf("foreground_ms", "usage_partial")))
        assertEquals("memory_0", JSONObject(allowed.first).getJSONArray("background").getJSONObject(0).getString("id"))
        assertTrue("memory_0" in allowed.second)

        val missing = ReportProtocol.input(facts(foregroundMs = null), allowedConsent, "温和", privateBackground)
        val missingIds = factIds(JSONObject(missing.first))
        assertFalse("foreground_ms" in missingIds)
        assertFalse("usage_partial" in missingIds)
        assertFalse(missing.first.contains("\"foreground_ms\":0"))
    }

    @Test
    fun `category permission never bypasses duration consent`() {
        val categoryFacts = facts(123_000).copy(categories = listOf(
            ReportCategorySummary("学习阅读", ReportCounts(8, 2, 3, 1, 5), 123_000, true),
        ))
        val denied = JSONObject(ReportProtocol.input(categoryFacts, ReportConfig(enabled = true), "温和",
            ReportBackground("[]", emptySet(), Long.MAX_VALUE, allowCategories = false)).first)
        assertFalse(factIds(denied).any { it.startsWith("category_") })

        val countsOnly = JSONObject(ReportProtocol.input(categoryFacts, ReportConfig(enabled = true), "温和",
            ReportBackground("[]", emptySet(), Long.MAX_VALUE, allowCategories = true)).first)
        val categoryWithoutUsage = factValue(countsOnly, "category_0") as JSONObject
        assertEquals("学习阅读", categoryWithoutUsage.getString("category"))
        assertEquals(14, categoryWithoutUsage.getInt("recorded_pauses"))
        assertEquals(8, categoryWithoutUsage.getInt("exited"))
        assertEquals(2, categoryWithoutUsage.getInt("continued"))
        assertEquals(3, categoryWithoutUsage.getInt("interrupted"))
        assertEquals(1, categoryWithoutUsage.getInt("pending"))
        assertEquals(5, categoryWithoutUsage.getInt("display_failed"))
        assertFalse(categoryWithoutUsage.has("foreground_ms"))
        assertFalse(categoryWithoutUsage.has("usage_partial"))

        val withUsage = JSONObject(ReportProtocol.input(categoryFacts, ReportConfig(enabled = true, useUsage = true), "温和",
            ReportBackground("[]", emptySet(), Long.MAX_VALUE, allowCategories = true)).first)
        val categoryWithUsage = factValue(withUsage, "category_0") as JSONObject
        assertEquals(123_000, categoryWithUsage.getLong("foreground_ms"))
        assertTrue(categoryWithUsage.getBoolean("usage_partial"))
    }

    @Test
    fun `JSON fence accepts three observations with whitelisted source ids`() {
        val content = """
            ```json
            {"observations":[
              {"text":"观察一","based_on":["recorded_pauses"]},
              {"text":"观察二","based_on":["continued"]},
              {"text":"观察三","based_on":["recorded_pauses","continued"]}
            ],"suggestion":"可以先观察，再自行决定是否调整。"}
            ```""".trimIndent()

        val parsed = ReportProtocol.parse(content, setOf("recorded_pauses", "continued"))

        assertEquals(3, parsed.observations.size)
        assertEquals(listOf("recorded_pauses", "continued"), parsed.observations[2].basedOn)
    }

    @Test
    fun `protocol rejects too many observations long text and unknown sources`() {
        fun response(rows: JSONArray) = JSONObject()
            .put("observations", rows)
            .put("suggestion", "建议")
            .toString()
        fun row(text: String, source: String = "recorded_pauses") = JSONObject()
            .put("text", text)
            .put("based_on", JSONArray().put(source))

        assertThrows(IllegalArgumentException::class.java) {
            ReportProtocol.parse(response(JSONArray().apply { repeat(4) { put(row("观察$it")) } }), setOf("recorded_pauses"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReportProtocol.parse(response(JSONArray().put(row("文".repeat(301)))), setOf("recorded_pauses"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReportProtocol.parse(response(JSONArray().put(row("观察", "not-in-request"))), setOf("recorded_pauses"))
        }
    }

    @Test
    fun `format failures have stable codes without echoing rejected content`() {
        val secret = "PRIVATE_RESPONSE_MARKER"
        fun response(rows: Any, suggestion: Any = "建议") = JSONObject()
            .put("observations", rows)
            .put("suggestion", suggestion)
            .toString()
        fun row(text: String, source: Any = "recorded_pauses") = JSONObject()
            .put("text", text)
            .put("based_on", JSONArray().put(source))
        val cases = listOf(
            ReportFormatProblem.JSON to "$secret not-json",
            ReportFormatProblem.STRUCTURE to response(secret),
            ReportFormatProblem.COUNT to response(JSONArray()),
            ReportFormatProblem.TEXT_LENGTH to response(JSONArray().put(row(secret + "文".repeat(301)))),
            ReportFormatProblem.TEXT_FORMAT to response(JSONArray().put(row("$secret\u0001"))),
            ReportFormatProblem.SOURCES to response(JSONArray().put(row("观察", secret))),
            ReportFormatProblem.RESPONSE_LENGTH to secret.padEnd(16_001, 'x'),
        )

        cases.forEach { (expected, content) ->
            val failure = assertThrows(ReportFormatException::class.java) {
                ReportProtocol.parse(content, setOf("recorded_pauses"))
            }
            assertEquals(expected, failure.problem)
            assertFalse((failure.message.orEmpty() + failure.problem.description).contains(secret))
        }
    }

    private fun factIds(root: JSONObject): Set<String> {
        val facts = root.getJSONArray("facts")
        return (0 until facts.length()).map { facts.getJSONObject(it).getString("id") }.toSet()
    }

    private fun factValue(root: JSONObject, id: String): Any {
        val facts = root.getJSONArray("facts")
        return (0 until facts.length()).map { facts.getJSONObject(it) }.single { it.getString("id") == id }.get("value")
    }

    private fun facts(foregroundMs: Long?) = ReportFacts(
        window = ReportWindow(
            period = ReportPeriod.TODAY,
            startDate = LocalDate.of(2026, 9, 9),
            endDateExclusive = LocalDate.of(2026, 9, 10),
            zoneId = "Asia/Shanghai",
            start = 0,
            end = 86_400_000,
            cutoff = 43_200_000,
        ),
        createdAt = 43_200_000,
        targets = listOf(ReportTarget("app.a", "应用甲", 0)),
        counts = ReportCounts(exited = 1, continued = 1, interrupted = 0, pending = 0, displayFailed = 0),
        foregroundMs = foregroundMs,
        usagePartial = true,
        usageCapturedAt = null,
        pauseCells = emptyList(),
        usageCells = emptyList(),
        notes = emptyList(),
        fingerprint = "facts",
        sourceFingerprint = "source",
        validUntil = Long.MAX_VALUE,
    )
}
