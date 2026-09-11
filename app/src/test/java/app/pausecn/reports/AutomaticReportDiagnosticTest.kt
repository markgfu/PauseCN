package app.pausecn.reports

import app.pausecn.ai.AiRequestException
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AutomaticReportDiagnosticTest {
    @Test
    fun `local failures are not sent and arbitrary exception content is never stored or shown`() {
        val secret = "PRIVATE_KEY_AND_RESPONSE"
        listOf(
            AutomaticReportStage.PREPARING_FACTS to "AUTO_FACTS_PREPARE_FAILED",
            AutomaticReportStage.LOADING_FACTS to "AUTO_FACTS_LOAD_FAILED",
            AutomaticReportStage.VALIDATING_FACTS to "AUTO_FACTS_DATE_CHANGED",
        ).forEach { (stage, expected) ->
            val code = AutomaticReportDiagnostic.failure(stage, IllegalStateException(secret))
            assertEquals(expected, code)
            assertTrue(AutomaticReportDiagnostic.message(code).contains("本次未发送"))
            assertFalse(AutomaticReportDiagnostic.message(code).contains("可能已计费"))
            assertFalse(AutomaticReportDiagnostic.message(code).contains(secret))
        }
        assertEquals("FAILED_OR_UNKNOWN", AutomaticReportDiagnostic.failure(
            AutomaticReportStage.GENERATING, AiRequestException(secret, secret)))
        assertFalse(AutomaticReportDiagnostic.message(secret).contains(secret))
    }

    @Test
    fun `report stage diagnostics cross exception boundary retaining charge uncertainty`() {
        ReportRequestStage.entries.forEach { stage ->
            val failure = reportFailure(stage, IllegalStateException("PRIVATE"), reserved = true)
            val code = AutomaticReportDiagnostic.failure(AutomaticReportStage.GENERATING,
                AiRequestException(failure.message, failure.code))
            assertEquals(failure.code, code)
            assertEquals(stage != ReportRequestStage.PREPARING,
                AutomaticReportDiagnostic.message(code).contains("可能已计费"))
        }
        ReportFormatProblem.entries.forEach { problem ->
            val failure = reportFailure(ReportRequestStage.PARSING, ReportFormatException(problem), true)
            val code = AutomaticReportDiagnostic.failure(AutomaticReportStage.GENERATING,
                AiRequestException(failure.message, failure.code))
            assertEquals(failure.code, code)
            assertTrue(AutomaticReportDiagnostic.message(code).contains(problem.description))
            assertTrue(AutomaticReportDiagnostic.message(code).contains("可能已计费"))
        }
        assertTrue(AutomaticReportDiagnostic.message("REPORT_BUSY_NOT_SENT").contains("本次未发送"))
        assertTrue(AutomaticReportDiagnostic.message("REPORT_STATE_REFRESH_FAILED").contains("先查看昨日"))
    }

    @Test
    fun `old failures stay unknown and new diagnostics never reopen a consumed date`() {
        assertTrue(AutomaticReportDiagnostic.message("FAILED_OR_UNKNOWN").contains("没有具体诊断"))
        assertTrue(AutomaticReportDiagnostic.message("CANCELLED_OR_UNKNOWN").contains("可能已计费"))
        val now = Instant.parse("2026-09-10T04:00:00Z").toEpochMilli()
        val zone = ZoneId.of("Asia/Shanghai")
        val (claimed, ticket) = requireNotNull(AutomaticReportPolicy.claim(
            AutomaticReportState(enabled = true), now, zone, "claim"))
        listOf("AUTO_FACTS_PREPARE_FAILED", "REPORT_NOT_SENT", "REPORT_REQUEST_FAILED",
            "REPORT_FORMAT_SOURCES", "REPORT_SAVE_FAILED", "FAILED_OR_UNKNOWN").forEach { code ->
            val completed = claimed.copy(status = code)
            assertFalse(AutomaticReportPolicy.current(completed, ticket))
            assertNull(AutomaticReportPolicy.claim(completed, now + 1_000, zone, "retry"))
            assertNull(AutomaticReportPolicy.claim(completed.copy(epoch = 2), now + 2_000, zone, "re-enabled"))
        }
    }
}
