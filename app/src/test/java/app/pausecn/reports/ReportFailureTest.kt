package app.pausecn.reports

import app.pausecn.ai.AiRequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportFailureTest {
    @Test
    fun `request stages map to stable diagnostics and preparation stays not sent after reservation`() {
        data class Case(val stage: ReportRequestStage, val error: Exception, val reserved: Boolean, val code: String)
        val cases = listOf(
            Case(ReportRequestStage.PREPARING, IllegalStateException("prepare"), false, "REPORT_NOT_SENT"),
            Case(ReportRequestStage.PREPARING, IllegalStateException("reserved-before-send"), true, "REPORT_NOT_SENT"),
            Case(ReportRequestStage.REQUESTING, AiRequestException("safe request failure"), true, "REPORT_REQUEST_FAILED"),
            Case(ReportRequestStage.PARSING, IllegalStateException("parse"), true, "REPORT_PARSE_FAILED"),
            Case(ReportRequestStage.REVALIDATING, IllegalStateException("check"), true, "REPORT_SOURCE_CHECK_FAILED"),
            Case(ReportRequestStage.SAVING, IllegalStateException("save"), true, "REPORT_SAVE_FAILED"),
            Case(ReportRequestStage.PARSING, ReportFormatException(ReportFormatProblem.SOURCES), true, "REPORT_FORMAT_SOURCES"),
            Case(ReportRequestStage.REVALIDATING, ReportSourceChangedException(), true, "REPORT_SOURCE_CHANGED"),
        )

        cases.forEach { case ->
            assertEquals(case.code, reportFailure(case.stage, case.error, case.reserved).code)
        }
    }

    @Test
    fun `charge wording follows send boundary and unknown exception text never enters diagnostics`() {
        val secret = "PRIVATE_EXCEPTION_BODY"
        val prepared = reportFailure(ReportRequestStage.PREPARING, IllegalStateException(secret), reserved = true)
        val requested = reportFailure(ReportRequestStage.REQUESTING, IllegalStateException(secret), reserved = true)

        assertEquals("REPORT_NOT_SENT", prepared.code)
        assertFalse(prepared.message.contains("可能已计费"))
        assertTrue(requested.message.contains("可能已计费"))
        assertFalse(prepared.message.contains(secret))
        assertFalse(requested.message.contains(secret))
    }
}
