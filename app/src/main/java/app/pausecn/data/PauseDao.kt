package app.pausecn.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TargetRuleDao {
    @Query("SELECT * FROM target_rules ORDER BY label COLLATE NOCASE")
    fun observeAll(): Flow<List<TargetRuleEntity>>

    @Query("SELECT * FROM target_rules WHERE enabled = 1")
    fun observeEnabled(): Flow<List<TargetRuleEntity>>

    @Query("SELECT * FROM target_rules ORDER BY label COLLATE NOCASE, packageName")
    suspend fun getAllForExport(): List<TargetRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: TargetRuleEntity)

    @Query("DELETE FROM target_rules WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("DELETE FROM target_rules")
    suspend fun deleteAll()
}

@Dao
interface InterventionEventDao {
    @Query("""SELECT id, packageName, occurredAtEpochMs, outcome FROM intervention_events
        WHERE occurredAtEpochMs >= :since AND occurredAtEpochMs < :until ORDER BY id""")
    suspend fun reportEvents(since: Long, until: Long): List<app.pausecn.reports.ReportEvent>
    @Query("SELECT * FROM intervention_events WHERE id = :id") suspend fun byId(id: Long): InterventionEventEntity?
    @Query("""SELECT * FROM intervention_events WHERE packageName = :pkg AND
        outcome IN ('CONTINUED', 'EXITED') AND occurredAtEpochMs >= :since AND occurredAtEpochMs <= :until
        ORDER BY occurredAtEpochMs DESC, id DESC LIMIT 20""")
    suspend fun recentCompletedForAi(pkg: String, since: Long, until: Long): List<InterventionEventEntity>
    @Query("""SELECT purpose AS text, COUNT(*) AS uses, MIN(occurredAtEpochMs) AS firstUsedAt,
        MAX(occurredAtEpochMs) AS lastUsedAt FROM intervention_events
        WHERE packageName = :pkg AND outcome = 'CONTINUED' AND purpose IS NOT NULL AND purpose != ''
        AND occurredAtEpochMs >= :since AND occurredAtEpochMs <= :until
        GROUP BY purpose ORDER BY uses DESC, lastUsedAt DESC, purpose LIMIT 3""")
    suspend fun reasonsForAi(pkg: String, since: Long, until: Long): List<AiReasonSummary>
    @Insert
    suspend fun insert(event: InterventionEventEntity): Long

    @Query(
        """UPDATE intervention_events
            SET outcome = :outcome, purpose = :purpose
            WHERE id = :id AND outcome = :expectedOutcome""",
    )
    suspend fun updateOutcomeIfCurrent(
        id: Long,
        expectedOutcome: String,
        outcome: String,
        purpose: String?,
    ): Int

    @Query("DELETE FROM intervention_events WHERE id = :id AND outcome = :expectedOutcome")
    suspend fun deleteIfCurrent(id: Long, expectedOutcome: String): Int

    @Query(
        """UPDATE intervention_events
            SET outcome = :dismissedOutcome, purpose = NULL
            WHERE outcome = :shownOutcome""",
    )
    suspend fun dismissInterrupted(shownOutcome: String, dismissedOutcome: String): Int

    @Query(
        """SELECT
                COUNT(*) AS total,
                COUNT(CASE WHEN outcome = :exitedOutcome THEN 1 END) AS exited,
                COUNT(CASE WHEN outcome = :continuedOutcome THEN 1 END) AS continued,
                COUNT(CASE WHEN outcome = :dismissedOutcome THEN 1 END) AS dismissed,
                COUNT(CASE WHEN outcome = :displayFailedOutcome THEN 1 END) AS displayFailed
            FROM intervention_events
            WHERE occurredAtEpochMs >= :sinceEpochMs
              AND occurredAtEpochMs < :untilEpochMsExclusive""",
    )
    fun observeStatsBetween(
        sinceEpochMs: Long,
        untilEpochMsExclusive: Long,
        exitedOutcome: String,
        continuedOutcome: String,
        dismissedOutcome: String,
        displayFailedOutcome: String,
    ): Flow<StatsSnapshot>

    @Query("SELECT * FROM intervention_events ORDER BY occurredAtEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<InterventionEventEntity>>

    @Query("SELECT packageName, COUNT(*) AS count FROM intervention_events WHERE occurredAtEpochMs >= :since AND occurredAtEpochMs < :until AND outcome != 'DISPLAY_FAILED' GROUP BY packageName")
    fun observeCategoryCounts(since: Long, until: Long): Flow<List<PackagePauseCount>>

    @Query("""SELECT packageName, MAX(appLabel) AS appLabel, purpose AS text,
        COUNT(*) AS uses, MAX(occurredAtEpochMs) AS lastUsedAt
        FROM intervention_events
        WHERE outcome = 'CONTINUED' AND purpose IS NOT NULL AND purpose != ''
          AND occurredAtEpochMs >= :since AND occurredAtEpochMs < :until
        GROUP BY packageName, purpose ORDER BY uses DESC, lastUsedAt DESC, packageName, purpose""")
    fun observeReasonMemories(since: Long, until: Long): Flow<List<ReasonMemory>>

    @Query("UPDATE intervention_events SET purpose = NULL WHERE packageName = :packageName AND purpose = :text")
    suspend fun forgetReason(packageName: String, text: String)

    @Query("UPDATE intervention_events SET purpose = NULL WHERE purpose IS NOT NULL")
    suspend fun forgetAllReasons()

    @Query("SELECT * FROM intervention_events ORDER BY occurredAtEpochMs, id")
    suspend fun getAllForExport(): List<InterventionEventEntity>

    @Query("DELETE FROM intervention_events")
    suspend fun deleteAll()

    @Query("DELETE FROM intervention_events WHERE occurredAtEpochMs < :cutoffEpochMs")
    suspend fun deleteOlderThan(cutoffEpochMs: Long): Int

    @Query(
        """DELETE FROM intervention_events
            WHERE id NOT IN (
                SELECT id FROM intervention_events
                ORDER BY occurredAtEpochMs DESC, id DESC
                LIMIT :maxRows
            )""",
    )
    suspend fun trimToNewest(maxRows: Int): Int
}

data class AiReasonSummary(val text: String, val uses: Int, val firstUsedAt: Long, val lastUsedAt: Long)

@Dao
interface ServiceSessionDao {
    @Insert
    suspend fun insert(session: ServiceSessionEntity): Long

    @Query(
        """UPDATE service_sessions
            SET lastHeartbeatAtEpochMs = :heartbeatAtEpochMs,
                lastHeartbeatAtElapsedMs = :heartbeatAtElapsedMs,
                heartbeatCount = heartbeatCount + 1,
                maxHeartbeatGapMs = MAX(
                    maxHeartbeatGapMs,
                    :heartbeatAtElapsedMs - lastHeartbeatAtElapsedMs
                )
            WHERE id = :id AND lastHeartbeatAtElapsedMs <= :heartbeatAtElapsedMs""",
    )
    suspend fun updateHeartbeat(
        id: Long,
        heartbeatAtEpochMs: Long,
        heartbeatAtElapsedMs: Long,
    ): Int

    @Query("SELECT * FROM service_sessions ORDER BY connectedAtEpochMs, id")
    suspend fun getAllForExport(): List<ServiceSessionEntity>

    @Query("DELETE FROM service_sessions")
    suspend fun deleteAll()

    @Query("DELETE FROM service_sessions WHERE lastHeartbeatAtEpochMs < :cutoffEpochMs")
    suspend fun deleteOlderThan(cutoffEpochMs: Long): Int

    @Query(
        """DELETE FROM service_sessions
            WHERE id NOT IN (
                SELECT id FROM service_sessions
                ORDER BY connectedAtEpochMs DESC, id DESC
                LIMIT :maxRows
            )""",
    )
    suspend fun trimToNewest(maxRows: Int): Int
}
