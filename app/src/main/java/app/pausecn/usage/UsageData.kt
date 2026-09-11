package app.pausecn.usage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import app.pausecn.data.PauseDatabase
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "usage_config")
data class UsageConfig(
    @PrimaryKey val id: Int = 1,
    val enabled: Boolean = false,
    val revision: String = UUID.randomUUID().toString(),
    val floor: Long = 0,
    val permissionObserved: Boolean = false,
    val lastChecked: Long = 0,
    val lastSuccess: Long = 0,
    val status: String = "DISABLED",
    val lastElapsed: Long = -1,
    val lastBootId: String = "",
    val lastZoneId: String = "",
)

@Entity(tableName = "usage_periods")
data class UsagePeriod(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val packageName: String,
    val start: Long,
    val end: Long? = null,
)

/** One immutable UTC bucket in one consent period. Re-query replaces this row, not its total. */
@Entity(tableName = "usage_hours", primaryKeys = ["periodId", "zoneId", "start", "algorithm"])
data class UsageHourEntity(
    val periodId: String,
    val packageName: String,
    val zoneId: String,
    val start: Long,
    val end: Long,
    val algorithm: Int = UsageAggregation.VERSION,
    val localDate: String,
    val localHour: Int,
    val evaluatedFrom: Long,
    val evaluatedTo: Long,
    val foregroundMs: Long?,
    val unknownMs: Long,
    val completeness: String,
    val limitations: String,
    val capturedAt: Long,
)

@Dao
interface UsageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertDisplay(display: PauseDisplay)
    @Query("UPDATE pause_displays SET endedAt = :wall, endedElapsed = :elapsed, status = :status WHERE id = :id AND status = 'OPEN'")
    suspend fun finishDisplay(id: String, wall: Long, elapsed: Long, status: String): Int
    @Query("UPDATE pause_displays SET status = 'UNKNOWN_END' WHERE status = 'OPEN'") suspend fun recoverDisplays()
    @Query("SELECT * FROM pause_displays WHERE startedAt >= :since AND startedAt < :until ORDER BY startedAt")
    suspend fun displays(since: Long, until: Long): List<PauseDisplay>
    @Query("DELETE FROM pause_displays WHERE startedAt < :before") suspend fun pruneDisplays(before: Long): Int
    @Query("DELETE FROM pause_displays WHERE rowid NOT IN (SELECT rowid FROM pause_displays ORDER BY startedAt DESC LIMIT :maximum)")
    suspend fun capDisplays(maximum: Int)
    @Query("DELETE FROM pause_displays") suspend fun clearDisplays()
    @Query("SELECT COUNT(*) FROM pause_displays") suspend fun displayCount(): Int
    @Query("SELECT packageName, occurredAtEpochMs FROM intervention_events WHERE occurredAtEpochMs >= :since AND occurredAtEpochMs < :until AND outcome != 'DISPLAY_FAILED' ORDER BY occurredAtEpochMs")
    suspend fun pauses(since: Long, until: Long): List<UsagePausePoint>
    @Query("SELECT * FROM usage_config WHERE id = 1") suspend fun config(): UsageConfig?
    @Query("SELECT * FROM usage_config WHERE id = 1") fun observeConfig(): Flow<UsageConfig?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(config: UsageConfig)
    @Query("SELECT * FROM usage_periods WHERE `end` IS NULL") suspend fun openPeriods(): List<UsagePeriod>
    @Query("SELECT * FROM usage_periods WHERE start < :until AND (`end` IS NULL OR `end` > :since)")
    suspend fun periods(since: Long, until: Long): List<UsagePeriod>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(period: UsagePeriod)
    @Query("UPDATE usage_periods SET `end` = MAX(start, :at) WHERE `end` IS NULL") suspend fun closeAll(at: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveHours(rows: List<UsageHourEntity>)
    @Query("SELECT * FROM usage_hours WHERE `end` > :since AND start < :until ORDER BY start, packageName")
    suspend fun hours(since: Long, until: Long): List<UsageHourEntity>
    @Query("DELETE FROM usage_hours WHERE `end` <= :before") suspend fun pruneHours(before: Long): Int
    @Query("DELETE FROM usage_hours WHERE rowid NOT IN (SELECT rowid FROM usage_hours ORDER BY start DESC LIMIT :maximum)")
    suspend fun capHours(maximum: Int)
    @Query("DELETE FROM usage_periods WHERE `end` IS NOT NULL AND `end` <= :before") suspend fun prunePeriods(before: Long)
    @Query("DELETE FROM usage_hours") suspend fun clearHours()
    @Query("DELETE FROM usage_periods") suspend fun clearPeriods()
    @Query("SELECT COUNT(*) FROM usage_hours") suspend fun hourCount(): Int
    @Query("SELECT COUNT(*) FROM usage_periods") suspend fun periodCount(): Int
}

data class UsagePausePoint(val packageName: String, val occurredAtEpochMs: Long)

internal suspend fun pruneUsageData(database: PauseDatabase, before: Long) {
    val dao = database.usageDao()
    val deleted = dao.pruneHours(before)
    dao.pruneDisplays(before)
    dao.capDisplays(10_000)
    dao.prunePeriods(before)
    dao.capHours(UsageRepository.MAX_HOURLY_ROWS)
    if (deleted > 0) dao.config()?.let { config ->
        if (before > config.floor) dao.save(config.copy(floor = before, revision = UUID.randomUUID().toString()))
    }
}

/** Call inside the same transaction as target changes, so rapid remove/reselect cannot be lost
 * through a conflating Flow. This function does not read system usage data or request permission.
 */
internal suspend fun syncUsageTargets(database: PauseDatabase, now: Long) {
    val dao = database.usageDao()
    val config = dao.config() ?: return
    if (!config.enabled || !config.permissionObserved) return
    val selected = database.targetRuleDao().getAllForExport().filter { it.enabled }.map { it.packageName }.toSet()
    val open = dao.openPeriods()
    val removed = open.filter { it.packageName !in selected }
    val added = selected - open.map { it.packageName }.toSet()
    if (removed.isEmpty() && added.isEmpty()) return
    removed.forEach { dao.save(it.copy(end = maxOf(now, it.start))) }
    added.forEach { dao.save(UsagePeriod(packageName = it, start = maxOf(now, config.floor))) }
    dao.save(config.copy(revision = UUID.randomUUID().toString()))
}

/** Explicit history deletion advances the floor; a later system query cannot restore deleted
 * history. Reset retains a fresh disabled tombstone revision to reject already running queries.
 */
internal suspend fun clearUsageData(database: PauseDatabase, now: Long, reset: Boolean) {
    database.displayGeneration.incrementAndGet()
    val dao = database.usageDao()
    val old = dao.config()
    val nextFloor = maxOf(now, old?.floor ?: now, old?.lastChecked ?: now)
    dao.clearHours()
    dao.clearPeriods()
    dao.clearDisplays()
    run {
        dao.save(if (reset) UsageConfig(floor = nextFloor) else (old ?: UsageConfig()).copy(
            floor = nextFloor, revision = UUID.randomUUID().toString(), lastSuccess = 0,
            lastChecked = now, lastElapsed = -1, lastBootId = "", lastZoneId = "",
            permissionObserved = false, status = if (old?.enabled == true) "AWAITING_REFRESH" else "DISABLED"))
    }
    check(dao.hourCount() == 0 && dao.periodCount() == 0 && dao.displayCount() == 0) { "使用时长数据删除未完成" }
}
