package app.pausecn.reports

import app.pausecn.data.ExportStamp
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReportSnapshotTest {
    @Test
    fun `deletion expiry and wall elapsed or zone changes invalidate a snapshot`() {
        val original = snapshot(createdAt = 1_000, elapsedAt = 500, eventIds = setOf(1), hourKeys = setOf("hour-a"))

        assertFalse(original.canKeepComparedWith(snapshot(
            createdAt = 1_100, elapsedAt = 600, eventIds = emptySet(), hourKeys = setOf("hour-a"),
        )))
        assertFalse(original.canKeepComparedWith(snapshot(
            createdAt = 1_100, elapsedAt = 600, eventIds = setOf(1), hourKeys = emptySet(),
        )))
        assertFalse(original.canKeepComparedWith(snapshot(
            createdAt = 1_100, elapsedAt = 600, eventIds = setOf(1), hourKeys = setOf("hour-a"),
            systemUsagePermission = true,
        )))
        assertFalse(original.timeValid(2_000, 1_500, UTC))
        assertFalse(original.timeValid(900, 600, UTC))
        assertFalse(original.timeValid(1_100, 400, UTC))
        assertFalse(original.timeValid(70_101, 600, UTC))
        assertFalse(original.timeValid(1_100, 600, ZoneId.of("Asia/Shanghai")))
    }

    @Test
    fun `ordinary appended source facts keep the immutable snapshot until refresh`() {
        val original = snapshot(createdAt = 1_000, elapsedAt = 500, eventIds = setOf(1), hourKeys = setOf("hour-a"))
        val appended = snapshot(
            createdAt = 1_100,
            elapsedAt = 600,
            eventIds = setOf(1, 2),
            hourKeys = setOf("hour-a", "hour-b"),
            sourceFingerprint = "new-source",
        )

        assertTrue(original.canKeepComparedWith(appended))
    }

    private fun snapshot(
        createdAt: Long,
        elapsedAt: Long,
        eventIds: Set<Long>,
        hourKeys: Set<String>,
        sourceFingerprint: String = "source",
        systemUsagePermission: Boolean = false,
    ): LocalReportSnapshot {
        val window = ReportWindow(
            ReportPeriod.TODAY,
            LocalDate.of(1970, 1, 1),
            LocalDate.of(1970, 1, 2),
            "UTC",
            0,
            86_400_000,
            1_500,
        )
        val facts = ReportFacts(
            window = window,
            createdAt = createdAt,
            targets = emptyList(),
            counts = ReportCounts(0, 0, 0, 0, 0),
            foregroundMs = null,
            usagePartial = true,
            usageCapturedAt = null,
            pauseCells = emptyList(),
            usageCells = emptyList(),
            notes = emptyList(),
            fingerprint = "fingerprint",
            sourceFingerprint = sourceFingerprint,
            validUntil = 2_000,
        )
        return LocalReportSnapshot(facts, STAMP, eventIds, hourKeys, 30, elapsedAt, systemUsagePermission)
    }

    private companion object {
        val UTC: ZoneId = ZoneId.of("UTC")
        val STAMP = ExportStamp("instance", 1, 2, 3, "usage")
    }
}
