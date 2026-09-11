package app.pausecn.reports

import androidx.room.withTransaction
import app.pausecn.data.*
import app.pausecn.usage.UsageHourEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Local immutable fact cache. Reads and template changes never invoke a network dependency. */
class ReportRepository(private val database: PauseDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val usagePermission: () -> Boolean = { false },
    private val bootId: () -> String = { app.pausecn.usage.UsageClock.processBootId() }) {
    val changes: Flow<Unit> = database.invalidationTracker.createFlow("intervention_events", "target_rules", "usage_hours",
        "usage_config", "ai_config", "personalization_config", "conversation_config", "report_config", "report_cache", "app_category_settings", "app_categories").map { Unit }

    internal suspend fun capture(period: ReportPeriod, retentionDays: Int): LocalReportSnapshot {
        val now = clock()
        val elapsedAt = elapsed()
        val permissionAtCapture = usagePermission()
        val window = ReportWindow.at(period, now, zone())
        val days = sanitizeHistoryRetentionDays(retentionDays)
        val retentionMs = days * 86_400_000L
        val source = database.personalizationGuard.readStable { database.withTransaction {
            val categories = appCategorySnapshot(database)
            val targets = database.targetRuleDao().getAllForExport().filter { it.enabled }.map {
                ReportTarget(it.packageName, sanitizeInstalledAppLabel(it.label, it.packageName), it.createdAtEpochMs, categories.category(it.packageName))
            }
            val packages = targets.map { it.packageName }.toSet()
            Source(targets, database.interventionEventDao().reportEvents(maxOf(window.start, now - retentionMs), window.cutoff)
                .filter { it.packageName in packages },
                database.usageDao().hours(window.start, window.cutoff).filter { it.packageName in packages },
                database.usageDao().config(), exportStamp(database, includeUsage = true))
        } }
        val facts = withContext(Dispatchers.Default) {
            ReportFactBuilder.build(window, now, source.targets, source.events, source.hours, retentionMs,
                source.usage?.enabled == true, source.usage?.permissionObserved == true && permissionAtCapture)
        }
        val snapshot = LocalReportSnapshot(facts, source.stamp, source.events.map { it.id }.toSet(),
            source.hours.map(::hourKey).toSet(), days, elapsedAt, permissionAtCapture, bootId())
        check(isCurrent(snapshot)) { "数据或时钟正在变化，请重新打开报告" }
        return snapshot
    }

    internal suspend fun isCurrent(snapshot: LocalReportSnapshot): Boolean = database.personalizationGuard.readStable {
        database.withTransaction { isCurrentInTransaction(snapshot) }
    }

    /** Caller already holds guard → Room. This variant must not reacquire the non-reentrant guard. */
    internal fun quickContextCurrent(snapshot: LocalReportSnapshot): Boolean = snapshot.bootId == bootId() &&
        snapshot.timeValid(clock(), elapsed(), zone()) && usagePermission() == snapshot.systemUsagePermission

    internal suspend fun isCurrentInTransaction(snapshot: LocalReportSnapshot): Boolean =
            exportStamp(database, includeUsage = true) == snapshot.stamp && quickContextCurrent(snapshot) &&
                database.interventionEventDao().reportEvents(snapshot.facts.window.start, snapshot.facts.window.cutoff)
                    .map { it.id }.toSet().containsAll(snapshot.eventIds) &&
                database.usageDao().hours(snapshot.facts.window.start, snapshot.facts.window.cutoff)
                    .map(::hourKey).toSet().containsAll(snapshot.hourKeys)

    /** Reads are local only; restoring an old immutable snapshot never regenerates an AI reply. */
    internal suspend fun load(period: ReportPeriod, days: Int, force: Boolean = false): Pair<LocalReportSnapshot, Boolean> {
        val fresh = capture(period, days)
        return database.personalizationGuard.readStable { database.withTransaction {
            val dao = database.reportDao()
            dao.pruneCache(clock())
            val saved = dao.cached(fresh.facts.window.slot)
            val previous = saved?.let { runCatching { ReportCacheCodec.decode(it.snapshotJson) }.getOrNull() }
            check(isCurrentInTransaction(fresh))
            if (!force && previous != null && previous.bootId == bootId() && previous.canKeepComparedWith(fresh)) {
                previous to (previous.facts.sourceFingerprint != fresh.facts.sourceFingerprint ||
                    (previous.facts.window.ongoing && !fresh.facts.window.ongoing))
            } else {
                dao.saveCache(ReportCacheRow(fresh.facts.window.slot, fresh.facts.fingerprint, ReportCacheCodec.encode(fresh),
                    fresh.facts.createdAt, fresh.facts.validUntil))
                dao.pruneCache(clock())
                fresh to false
            }
        } }
    }

    private data class Source(val targets: List<ReportTarget>, val events: List<ReportEvent>, val hours: List<UsageHourEntity>,
        val usage: app.pausecn.usage.UsageConfig?, val stamp: ExportStamp)
}

internal data class LocalReportSnapshot(val facts: ReportFacts, val stamp: ExportStamp,
    val eventIds: Set<Long>, val hourKeys: Set<String>, val retentionDays: Int, val elapsedAt: Long,
    val systemUsagePermission: Boolean = false, val bootId: String = "") {
    fun timeValid(now: Long, elapsed: Long, zone: ZoneId): Boolean = now >= facts.createdAt && now < facts.validUntil &&
        elapsed >= elapsedAt && kotlin.math.abs((now - facts.createdAt) - (elapsed - elapsedAt)) <= 60_000L &&
        zone.id == facts.window.zoneId

    /** A source deletion must replace the private snapshot, never merely show an update badge.
     * Ordinary appended events / completed outcomes keep the old immutable facts until refresh. */
    fun canKeepComparedWith(new: LocalReportSnapshot): Boolean = facts.window.slot == new.facts.window.slot &&
        stamp == new.stamp && bootId == new.bootId && retentionDays == new.retentionDays && systemUsagePermission == new.systemUsagePermission &&
        timeValid(new.facts.createdAt, new.elapsedAt, ZoneId.of(new.facts.window.zoneId)) &&
        new.eventIds.containsAll(eventIds) && new.hourKeys.containsAll(hourKeys)
}

private fun hourKey(row: UsageHourEntity): String = "${row.periodId}|${row.packageName}|${row.zoneId}|${row.start}|${row.algorithm}"
