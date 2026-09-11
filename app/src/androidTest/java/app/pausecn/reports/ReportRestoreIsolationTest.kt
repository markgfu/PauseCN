package app.pausecn.reports

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pausecn.ai.AiRepository
import app.pausecn.ai.AiTransport
import app.pausecn.ai.ApiCredentialStore
import app.pausecn.data.ExportStamp
import app.pausecn.data.PauseDatabase
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReportRestoreIsolationTest {
    @Test
    fun staleSnapshotCannotDeleteNewerCacheInSameSlot() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            PauseDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val ai = AiRepository(
                database = database,
                credentials = ForbiddenCredentials,
                transport = AiTransport { _, _, _, _ -> error("HTTP is forbidden") },
                phrasePrompt = { error("prompt is forbidden") },
                retentionDays = { 30 },
            )
            val reports = ReportRepository(database)
            val cases = listOf(
                Case("old-fingerprint", "new-fingerprint", oldCreatedAt = 100, freshCreatedAt = 100),
                Case("same-fingerprint", "same-fingerprint", oldCreatedAt = 100, freshCreatedAt = 200),
            )

            cases.forEach { case ->
                val old = snapshot(case.oldFingerprint, createdAt = case.oldCreatedAt)
                val fresh = snapshot(case.freshFingerprint, createdAt = case.freshCreatedAt)
                val row = ReportCacheRow(
                    slot = fresh.facts.window.slot,
                    fingerprint = fresh.facts.fingerprint,
                    snapshotJson = ReportCacheCodec.encode(fresh),
                    createdAt = fresh.facts.createdAt,
                    expiresAt = Long.MAX_VALUE,
                    interpretationJson = "{\"observations\":[],\"suggestion\":\"new interpretation\"}",
                    aiTag = "new-ai-tag",
                    consentEpoch = 9,
                    backgroundHash = "new-background-hash",
                    ruleBasisJson = "new-rule-basis",
                )
                database.reportDao().saveCache(row)

                assertNull(ai.restoreReport(old, reports))
                assertEquals(row, database.reportDao().cached(row.slot))
            }
        } finally {
            database.close()
        }
    }

    private data class Case(val oldFingerprint: String, val freshFingerprint: String,
        val oldCreatedAt: Long, val freshCreatedAt: Long)

    private fun snapshot(fingerprint: String, createdAt: Long): LocalReportSnapshot {
        val date = LocalDate.of(2026, 9, 8)
        val window = ReportWindow(
            period = ReportPeriod.YESTERDAY,
            startDate = date,
            endDateExclusive = date.plusDays(1),
            zoneId = "UTC",
            start = 0,
            end = 86_400_000,
            cutoff = 86_400_000,
        )
        val facts = ReportFacts(
            window = window,
            createdAt = createdAt,
            targets = emptyList(),
            counts = ReportCounts(0, 0, 0, 0, 0),
            foregroundMs = null,
            usagePartial = false,
            usageCapturedAt = null,
            pauseCells = emptyList(),
            usageCells = emptyList(),
            notes = emptyList(),
            fingerprint = fingerprint,
            sourceFingerprint = "source-$fingerprint",
            validUntil = Long.MAX_VALUE,
        )
        return LocalReportSnapshot(
            facts = facts,
            stamp = ExportStamp("instance", 1, 2, 3, null),
            eventIds = emptySet(),
            hourKeys = emptySet(),
            retentionDays = 30,
            elapsedAt = 0,
            systemUsagePermission = false,
            bootId = "boot",
        )
    }

    private object ForbiddenCredentials : ApiCredentialStore {
        override fun hasKey(): Boolean = error("credentials are forbidden")
        override fun read(): String = error("credentials are forbidden")
        override fun save(value: String): Unit = error("credentials are forbidden")
        override fun clear(): Unit = error("credentials are forbidden")
    }
}
