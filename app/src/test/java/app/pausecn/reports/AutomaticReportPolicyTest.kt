package app.pausecn.reports

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticReportPolicyTest {
    @Test
    fun `automatic reports default off and cannot claim`() {
        val state = AutomaticReportState()

        assertFalse(state.enabled)
        assertNull(AutomaticReportPolicy.claim(state, instant("2024-03-11T12:00:00Z"), UTC, "claim"))
    }

    @Test
    fun `first claim advances durable date and same day concurrent or restarted unknown cannot claim`() {
        val (claimed, ticket) = requireNotNull(AutomaticReportPolicy.claim(
            AutomaticReportState(enabled = true, epoch = 4),
            instant("2024-03-11T12:00:00Z"), UTC, "first",
        ))

        assertEquals("2024-03-10", claimed.lastDate)
        assertEquals("UNKNOWN", claimed.status)
        assertTrue(AutomaticReportPolicy.current(claimed, ticket))
        assertNull(AutomaticReportPolicy.claim(claimed, instant("2024-03-11T23:59:00Z"), UTC, "concurrent"))
        assertNull(AutomaticReportPolicy.claim(claimed.copy(status = "UNKNOWN"), instant("2024-03-11T13:00:00Z"), UTC, "restart"))
    }

    @Test
    fun `next date is allowed while wall rollback and timezone change cannot repeat high water date`() {
        val first = requireNotNull(AutomaticReportPolicy.claim(
            AutomaticReportState(enabled = true), instant("2024-03-11T12:00:00Z"), UTC, "first",
        )).first

        assertNotNull(AutomaticReportPolicy.claim(first, instant("2024-03-12T12:00:00Z"), UTC, "next"))
        assertNull(AutomaticReportPolicy.claim(first, instant("2024-03-10T12:00:00Z"), UTC, "rollback"))
        assertNull(AutomaticReportPolicy.claim(first, instant("2024-03-11T01:00:00Z"), ZoneId.of("America/Los_Angeles"), "zone"))
    }

    @Test
    fun `epoch disable or replacement claim invalidates ticket`() {
        val (claimed, ticket) = requireNotNull(AutomaticReportPolicy.claim(
            AutomaticReportState(enabled = true, epoch = 8), instant("2024-03-11T12:00:00Z"), UTC, "original",
        ))

        assertFalse(AutomaticReportPolicy.current(claimed.copy(epoch = 9), ticket))
        assertFalse(AutomaticReportPolicy.current(claimed.copy(enabled = false), ticket))
        assertFalse(AutomaticReportPolicy.current(claimed.copy(claimId = "replacement"), ticket))
        assertFalse(AutomaticReportPolicy.current(claimed.copy(status = "DONE"), ticket))
    }

    @Test
    fun `DST yesterday matches only the complete claimed daily window`() {
        val zone = ZoneId.of("America/New_York")
        val now = instant("2024-03-11T16:00:00Z")
        val (_, ticket) = requireNotNull(AutomaticReportPolicy.claim(
            AutomaticReportState(enabled = true), now, zone, "dst",
        ))
        val yesterday = ReportWindow.at(ReportPeriod.YESTERDAY, now, zone)
        val facts = facts(yesterday)

        assertEquals(23 * 60 * 60_000L, yesterday.end - yesterday.start)
        assertTrue(AutomaticReportPolicy.matches(ticket, facts))
        assertFalse(AutomaticReportPolicy.matches(ticket, facts(ReportWindow.at(ReportPeriod.TODAY, now, zone))))
        assertFalse(AutomaticReportPolicy.matches(ticket, facts(ReportWindow.at(ReportPeriod.LAST_WEEK, now, zone))))
        assertFalse(AutomaticReportPolicy.matches(ticket, facts(yesterday.copy(cutoff = yesterday.end - 1))))
        assertFalse(AutomaticReportPolicy.matches(ticket, facts(yesterday.copy(endDateExclusive = yesterday.endDateExclusive.plusDays(1)))))
    }

    private fun facts(window: ReportWindow) = ReportFacts(
        window = window,
        createdAt = window.end,
        targets = emptyList(),
        counts = ReportCounts(0, 0, 0, 0, 0),
        foregroundMs = null,
        usagePartial = false,
        usageCapturedAt = null,
        pauseCells = emptyList(),
        usageCells = emptyList(),
        notes = emptyList(),
        fingerprint = "facts",
        sourceFingerprint = "source",
        validUntil = Long.MAX_VALUE,
    )

    private fun instant(value: String) = Instant.parse(value).toEpochMilli()

    private companion object {
        val UTC: ZoneId = ZoneId.of("UTC")
    }
}
