package app.pausecn.usage

import androidx.room.withTransaction
import app.pausecn.data.PauseDatabase
import app.pausecn.data.sanitizeHistoryRetentionDays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Single local pipeline shared by foreground refresh and periodic work. It owns no credentials,
 * HTTP client or AI opt-in. No raw event is written to Room. UI/worker must gate database health.
 */
class UsageRepository(
    private val database: PauseDatabase,
    private val source: UsageSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val elapsed: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val bootId: () -> String = UsageClock::processBootId,
) {
    private val collector = Mutex()
    val config = database.usageDao().observeConfig()
    private fun time() = UsageTime(clock(), elapsed(), bootId(), zone().id)

    suspend fun setEnabled(enabled: Boolean) = database.personalizationGuard.mutate {
        database.withTransaction {
            val dao = database.usageDao()
            val old = dao.config() ?: UsageConfig()
            if (old.enabled == enabled) return@withTransaction
            val reading = time()
            val now = reading.wall
            val clockChanged = UsageClock.change(old.anchor(), reading) !in setOf(UsageClockChange.INITIAL, UsageClockChange.STABLE)
            dao.closeAll(if (clockChanged) old.lastChecked else now)
            // Enabling is not retroactive: even with pre-existing system permission, the first
            // observed/authorized refresh establishes a new per-target interval at that time.
            dao.save(old.copy(enabled = enabled, revision = UUID.randomUUID().toString(),
                permissionObserved = false, lastChecked = now,
                floor = if (clockChanged) maxOf(old.floor, old.lastChecked, now) else if (old.floor == 0L) now else old.floor,
                status = if (enabled) "AWAITING_REFRESH" else "DISABLED").at(reading))
        }
    }

    suspend fun refresh(retentionDays: Int): String = collector.withLock {
        withContext(Dispatchers.IO) {
            val reading = time()
            val now = reading.wall
            val granted = source.hasPermission()
            val snapshot = database.withTransaction {
                if (database.personalizationGuard.changing) return@withTransaction null
                val dao = database.usageDao()
                var config = dao.config() ?: return@withTransaction null
                if (!config.enabled) return@withTransaction null
                val clockChange = UsageClock.change(config.anchor(), reading)
                val changed = clockChange !in setOf(UsageClockChange.STABLE, UsageClockChange.INITIAL)
                if (!granted || changed) {
                    // If revocation time is unknown, close at the last observed checkpoint and
                    // never backfill the unobserved gap after permission is granted again.
                    dao.closeAll(config.lastChecked)
                    config = config.copy(revision = UUID.randomUUID().toString(),
                        permissionObserved = granted,
                        floor = if (changed) maxOf(config.floor, config.lastChecked, now) else config.floor,
                        status = if (!granted) "NOT_AUTHORIZED" else "CLOCK_CHANGED").at(reading)
                    dao.save(config)
                    if (granted) syncUsageTargets(database, maxOf(now, config.floor))
                    return@withTransaction null
                }
                if (!config.permissionObserved) {
                    dao.closeAll(config.lastChecked)
                    config = config.copy(permissionObserved = true, revision = UUID.randomUUID().toString()).at(reading)
                    dao.save(config)
                }
                syncUsageTargets(database, now)
                config = requireNotNull(dao.config())
                val packages = database.targetRuleDao().getAllForExport().filter { it.enabled }.map { it.packageName }.toSet()
                val currentZone = zone()
                val yesterday = Instant.ofEpochMilli(now).atZone(currentZone).toLocalDate().minusDays(1)
                    .atStartOfDay(currentZone).toInstant().toEpochMilli()
                val start = maxOf(config.floor, yesterday, now - AndroidUsageSource.MAX_QUERY_MS)
                if (packages.isEmpty() || start >= now) {
                    dao.save(config.copy(status = if (packages.isEmpty()) "NO_TARGETS" else "EMPTY").at(reading))
                    return@withTransaction null
                }
                CollectionSnapshot(config, UsageSpan(start, now), packages,
                    dao.periods(start, now).filter { it.packageName in packages }, currentZone)
            } ?: return@withContext database.usageDao().config()?.status ?: "DISABLED"

            val read = source.read(snapshot.window, snapshot.packages)
            val rows = if (read.status == UsageReadStatus.AVAILABLE) calculate(snapshot, read) else emptyList()
            // Permission and local revision are both checked after the system call. Mutations
            // never wait on that call; they invalidate its write-back instead.
            val stillGranted = source.hasPermission()
            database.withTransaction {
                val dao = database.usageDao()
                val current = dao.config() ?: return@withTransaction "STALE"
                if (database.personalizationGuard.changing || !current.enabled ||
                    current.revision != snapshot.config.revision) return@withTransaction "STALE"
                val after = time()
                if (!stillGranted || read.status == UsageReadStatus.NOT_AUTHORIZED || UsageClock.change(reading, after) != UsageClockChange.STABLE) {
                    dao.closeAll(current.lastChecked)
                    dao.save(current.copy(permissionObserved = false, revision = UUID.randomUUID().toString(),
                        floor = maxOf(current.floor, now, after.wall),
                        status = if (!stillGranted || read.status == UsageReadStatus.NOT_AUTHORIZED)
                            "NOT_AUTHORIZED" else "CLOCK_CHANGED").at(after))
                    return@withTransaction "STALE"
                }
                // Empty/failed queries do not overwrite a previously confirmed bucket with zero.
                if (read.status == UsageReadStatus.AVAILABLE) {
                    val previous = dao.hours(snapshot.window.start, snapshot.window.end)
                        .associateBy { listOf(it.periodId, it.zoneId, it.start.toString(), it.algorithm.toString()) }
                    dao.saveHours(rows.map { row ->
                        val old = previous[listOf(row.periodId, row.zoneId, row.start.toString(), row.algorithm.toString())]
                        if (old != null && old.foregroundMs != null &&
                            (row.foregroundMs == null || (row.unknownMs > old.unknownMs && row.foregroundMs < old.foregroundMs))) {
                            // A shortened/missing system history must not erase a formerly
                            // observed interval. Retain its original cutoff and mark it partial.
                            old.copy(completeness = UsageCompleteness.PARTIAL.name,
                                limitations = (old.limitations.split(',') + "SOURCE_GAP_RETAINED").filter { it.isNotBlank() }.distinct().joinToString(","))
                        } else row
                    })
                }
                val status = when {
                    read.status != UsageReadStatus.AVAILABLE -> read.status.name
                    rows.isEmpty() || rows.all { it.completeness == UsageCompleteness.UNKNOWN.name } -> "EMPTY"
                    rows.any { it.completeness != UsageCompleteness.ESTIMATED.name } -> "PARTIAL"
                    else -> "AVAILABLE"
                }
                dao.save(current.copy(lastSuccess = if (read.status == UsageReadStatus.AVAILABLE) now else current.lastSuccess, status = status).at(reading))
                val floor = now - sanitizeHistoryRetentionDays(retentionDays) * 86_400_000L
                pruneUsageData(database, floor)
                status
            }
        }
    }

    suspend fun hours(since: Long, until: Long): List<UsageHourEntity> = database.usageDao().hours(since, until)

    private fun calculate(snapshot: CollectionSnapshot, read: UsageRead): List<UsageHourEntity> {
        val estimates = snapshot.packages.associateWith { UsageAggregation.estimate(it, snapshot.window, read.signals) }
        return snapshot.periods.flatMap { period ->
            val allowed = UsageSpan(period.start, period.end ?: snapshot.window.end).intersect(snapshot.window)
                ?: return@flatMap emptyList()
            val estimate = requireNotNull(estimates[period.packageName])
            val first = Instant.ofEpochMilli(allowed.start).atZone(snapshot.zone).toLocalDate()
            val last = Instant.ofEpochMilli(allowed.end - 1).atZone(snapshot.zone).toLocalDate()
            generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.flatMap { day ->
                UsageAggregation.hourly(estimate, day, snapshot.zone, listOf(allowed)).asSequence()
            }.mapNotNull { hour ->
                // Each persisted row belongs to exactly one continuous consent period.
                val evaluated = hour.evaluatedParts.singleOrNull() ?: return@mapNotNull null
                UsageHourEntity(period.id, period.packageName, hour.zoneId, hour.interval.start, hour.interval.end,
                    localDate = hour.date.toString(), localHour = hour.hour,
                    evaluatedFrom = evaluated.start, evaluatedTo = evaluated.end,
                    foregroundMs = hour.foregroundMs, unknownMs = hour.unknownMs,
                    completeness = hour.completeness.name, limitations = estimate.limitations.sorted().joinToString(","),
                    capturedAt = snapshot.window.end)
            }.toList()
        }
    }

    private data class CollectionSnapshot(val config: UsageConfig, val window: UsageSpan,
        val packages: Set<String>, val periods: List<UsagePeriod>, val zone: ZoneId)

    companion object { const val MAX_HOURLY_ROWS = 100_000 }
}
