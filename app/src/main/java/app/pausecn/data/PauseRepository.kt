package app.pausecn.data

import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import android.provider.Telephony
import androidx.room.withTransaction
import app.pausecn.usage.syncUsageTargets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isInstalled: Boolean = true,
    val platformCategory: Int = -1,
)

data class StatsSnapshot(
    val total: Int = 0,
    val exited: Int = 0,
    val continued: Int = 0,
    val dismissed: Int = 0,
    val displayFailed: Int = 0,
) {
    val completedDecisions: Int get() = exited + continued
    val triggerSuccesses: Int get() = (total - displayFailed).coerceAtLeast(0)
    val triggerSuccessRate: Int
        get() = if (total == 0) 0 else ((triggerSuccesses * 100f) / total).toInt()
    val exitRate: Int
        get() = if (completedDecisions == 0) 0 else ((exited * 100f) / completedDecisions).toInt()
}

internal data class StatsWindow(
    val startEpochMsInclusive: Long,
    val endEpochMsExclusive: Long,
)

data class HistoryPruneResult(
    val expiredRows: Int,
    val overflowRows: Int,
)

data class InterventionStartResult(
    val eventId: Long,
    val pruneResult: HistoryPruneResult,
)

data class LocalRepositoryExportData(
    val targets: List<TargetRuleEntity>,
    val events: List<InterventionEventEntity>,
    val serviceSessions: List<ServiceSessionEntity>,
    val ai: AiExportData? = null,
    val usage: app.pausecn.usage.UsageExportData? = null,
    val reports: app.pausecn.reports.ReportExportData? = null,
    val appCategories: List<AppCategoryRow> = emptyList(),
)

internal data class CapturedLocalExport(val data: LocalRepositoryExportData, val stamp: ExportStamp,
    val reports: app.pausecn.reports.ReportExportCapture, val options: LocalExportOptions, val retentionDays: Int)

class PauseRepository(
    private val context: Context,
    private val database: PauseDatabase,
    private val maxHistoryRows: Int = MAX_HISTORY_ROWS,
) {
    init {
        require(maxHistoryRows > 0) { "maxHistoryRows must be positive" }
    }

    val targets: Flow<List<TargetRuleEntity>> = database.targetRuleDao().observeAll()
        .map { rules -> rules.map(::sanitizeTargetRule) }
    val enabledTargets: Flow<List<TargetRuleEntity>> = database.targetRuleDao().observeEnabled()
        .map { rules -> rules.map(::sanitizeTargetRule) }
    val recentEvents: Flow<List<InterventionEventEntity>> = database.interventionEventDao().observeRecent()
        .map { events -> events.map(::sanitizeInterventionEvent) }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeReasonMemories(retentionDays: Int): Flow<List<ReasonMemory>> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(now - minOf(sanitizeHistoryRetentionDays(retentionDays), 30) * DAY_MS to
                (now / 60_000L + 1) * 60_000L)
            delay(millisUntilNextStatsMinute(now))
        }
    }.flatMapLatest { (since, until) -> database.interventionEventDao().observeReasonMemories(since, until) }
        .map { rows -> rows.filter { app.pausecn.domain.ContinueReason.isValid(it.text) }
            .map { it.copy(appLabel = sanitizeInstalledAppLabel(it.appLabel, it.packageName)) } }

    private suspend fun invalidatePersonalizedSources() {
        database.aiDao().invalidatePersonalization()
        database.aiDao().clearPersonalized()
        database.conversationDao().invalidate()
        database.conversationDao().clearReplies()
        database.conversationDao().clearCandidates()
    }

    private suspend fun <T> personalSourceMutation(invalidate: Boolean = true, block: suspend () -> T): T =
        database.personalizationGuard.mutate { database.withTransaction {
            if (invalidate) invalidatePersonalizedSources()
            block()
        } }

    suspend fun forgetReason(packageName: String, text: String) = personalSourceMutation {
        database.interventionEventDao().forgetReason(packageName, text)
    }
    suspend fun forgetAllReasons() = personalSourceMutation { database.interventionEventDao().forgetAllReasons() }

    suspend fun setTarget(app: InstalledApp, enabled: Boolean) = personalSourceMutation {
        if (enabled) {
            database.targetRuleDao().upsert(
                TargetRuleEntity(
                    packageName = app.packageName,
                    label = sanitizeInstalledAppLabel(app.label, app.packageName),
                ),
            )
        } else {
            database.targetRuleDao().delete(app.packageName)
        }
        syncUsageTargets(database, System.currentTimeMillis())
    }

    /** One commit for bulk selection; keep existing rules and never add safety exclusions. */
    suspend fun selectTargets(apps: List<InstalledApp>) = withContext(Dispatchers.IO) {
        val protected = protectedPackages(context)
        val candidates = apps.filter { it.isInstalled && it.packageName !in protected }
            .distinctBy { it.packageName }
        personalSourceMutation {
            val dao = database.targetRuleDao()
            val existing = dao.getAllForExport().associateBy { it.packageName }
            candidates.forEach { app ->
                val old = existing[app.packageName]
                if (old?.enabled != true) {
                    dao.upsert(old?.copy(enabled = true) ?: TargetRuleEntity(
                        packageName = app.packageName,
                        label = sanitizeInstalledAppLabel(app.label, app.packageName),
                    ))
                }
            }
            app.pausecn.usage.syncUsageTargets(database, System.currentTimeMillis())
        }
    }

    /** Match single-item removal: clear only these rules, never their history or reasons. */
    suspend fun deselectTargets(apps: List<InstalledApp>) = withContext(Dispatchers.IO) {
        val packageNames = apps.map { it.packageName }.distinct()
        personalSourceMutation {
            val dao = database.targetRuleDao()
            packageNames.forEach { dao.delete(it) }
            app.pausecn.usage.syncUsageTargets(database, System.currentTimeMillis())
        }
    }

    suspend fun recordIntervention(
        packageName: String,
        appLabel: String,
        outcome: InterventionOutcome,
        purpose: String? = null,
        retentionDays: Int = DEFAULT_HISTORY_RETENTION_DAYS,
        nowEpochMs: Long = System.currentTimeMillis(),
        triggerLatencyMs: Long? = null,
    ): HistoryPruneResult = insertIntervention(
        packageName = packageName,
        appLabel = appLabel,
        outcome = outcome,
        purpose = purpose,
        retentionDays = retentionDays,
        nowEpochMs = nowEpochMs,
        triggerLatencyMs = triggerLatencyMs,
    ).pruneResult

    suspend fun beginIntervention(
        packageName: String,
        appLabel: String,
        retentionDays: Int = DEFAULT_HISTORY_RETENTION_DAYS,
        nowEpochMs: Long = System.currentTimeMillis(),
        triggerLatencyMs: Long? = null,
    ): InterventionStartResult = insertIntervention(
        packageName = packageName,
        appLabel = appLabel,
        outcome = InterventionOutcome.SHOWN,
        retentionDays = retentionDays,
        nowEpochMs = nowEpochMs,
        triggerLatencyMs = triggerLatencyMs,
    )

    suspend fun completeIntervention(
        eventId: Long,
        outcome: InterventionOutcome,
        purpose: String? = null,
    ): Boolean {
        require(outcome in COMPLETION_OUTCOMES) { "Unsupported completion outcome: $outcome" }
        require(purpose == null || app.pausecn.domain.ContinueReason.isValid(purpose)) { "Invalid continue reason" }
        return database.interventionEventDao().updateOutcomeIfCurrent(
            id = eventId,
            expectedOutcome = InterventionOutcome.SHOWN.name,
            outcome = outcome.name,
            purpose = purpose.takeIf { outcome == InterventionOutcome.CONTINUED },
        ) == 1
    }

    suspend fun discardIntervention(eventId: Long): Boolean =
        database.interventionEventDao().deleteIfCurrent(
            id = eventId,
            expectedOutcome = InterventionOutcome.SHOWN.name,
        ) == 1

    /**
     * A new process cannot own an overlay created by a previous process. This runs while database
     * health is still Checking, before the service is allowed to load targets or show a new one.
     */
    suspend fun recoverInterruptedInterventions(): Int = database.withTransaction {
        database.usageDao().recoverDisplays()
        database.interventionEventDao().dismissInterrupted(
            shownOutcome = InterventionOutcome.SHOWN.name,
            dismissedOutcome = InterventionOutcome.DISMISSED.name,
        )
    }

    private suspend fun insertIntervention(
        packageName: String,
        appLabel: String,
        outcome: InterventionOutcome,
        purpose: String? = null,
        retentionDays: Int,
        nowEpochMs: Long,
        triggerLatencyMs: Long? = null,
    ): InterventionStartResult = personalSourceMutation(invalidate = false) {
        val dao = database.interventionEventDao()
        val eventId = dao.insert(
            InterventionEventEntity(
                packageName = packageName,
                appLabel = sanitizeInstalledAppLabel(appLabel, packageName),
                occurredAtEpochMs = nowEpochMs,
                outcome = outcome.name,
                purpose = purpose,
                triggerLatencyMs = triggerLatencyMs?.coerceAtLeast(0),
            ),
        )
        val sanitizedDays = sanitizeHistoryRetentionDays(retentionDays)
        val expired = dao.deleteOlderThan(nowEpochMs - sanitizedDays * DAY_MS)
        val overflow = dao.trimToNewest(maxHistoryRows)
        if (expired > 0 || overflow > 0) invalidatePersonalizedSources()
        InterventionStartResult(
            eventId = eventId,
            pruneResult = HistoryPruneResult(expiredRows = expired, overflowRows = overflow),
        )
    }

    fun observeStatsSince(
        epochMs: Long,
        untilEpochMsExclusive: Long = Long.MAX_VALUE,
    ): Flow<StatsSnapshot> = database.interventionEventDao().observeStatsBetween(
        sinceEpochMs = epochMs,
        untilEpochMsExclusive = untilEpochMsExclusive,
        exitedOutcome = InterventionOutcome.EXITED.name,
        continuedOutcome = InterventionOutcome.CONTINUED.name,
        dismissedOutcome = InterventionOutcome.DISMISSED.name,
        displayFailedOutcome = InterventionOutcome.DISPLAY_FAILED.name,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeThisWeekStats(): Flow<StatsSnapshot> = flow {
        while (true) {
            val nowEpochMs = System.currentTimeMillis()
            emit(currentWeekStatsWindow(nowEpochMs, ZoneId.systemDefault()))
            delay(millisUntilNextStatsMinute(nowEpochMs))
        }
    }.distinctUntilChanged().flatMapLatest { window ->
        observeStatsSince(window.startEpochMsInclusive, window.endEpochMsExclusive)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeThisWeekCategoryCounts(): Flow<List<CategoryPauseCount>> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(currentWeekStatsWindow(now, ZoneId.systemDefault()))
            delay(millisUntilNextStatsMinute(now))
        }
    }.distinctUntilChanged().flatMapLatest { window ->
        kotlinx.coroutines.flow.combine(database.interventionEventDao().observeCategoryCounts(window.startEpochMsInclusive, window.endEpochMsExclusive),
            AppCategoryRepository(database).state, ::categoryPauseCounts)
    }

    suspend fun clearHistory() = personalSourceMutation {
        database.reportDao().clearCache()
        app.pausecn.usage.clearUsageData(database, System.currentTimeMillis(), reset = false)
        database.interventionEventDao().deleteAll()
        database.serviceSessionDao().deleteAll()
        database.conversationDao().apply { clearMessages(); clearDerivedMemories(); clearFeedback() }
    }

    suspend fun clearTargetsAndHistory() = personalSourceMutation {
        database.appCategoryDao().clear()
        database.appCategoryDao().clearSettings()
        database.automaticReportDao().clear()
        database.reportDao().clearConfig()
        database.reportDao().clearCache()
        app.pausecn.usage.clearUsageData(database, System.currentTimeMillis(), reset = true)
        database.interventionEventDao().deleteAll()
        database.serviceSessionDao().deleteAll()
        database.targetRuleDao().deleteAll()
        database.conversationDao().apply { clearMessages(); clearMemories(); clearFeedback(); clearConfig() }
    }

    suspend fun loadExportData(options: LocalExportOptions = LocalExportOptions(), retentionDays: Int = 30): LocalRepositoryExportData =
        database.withTransaction {
            LocalRepositoryExportData(
                targets = database.targetRuleDao().getAllForExport().map(::sanitizeTargetRule),
                events = database.interventionEventDao().getAllForExport().map(::sanitizeInterventionEvent),
                serviceSessions = database.serviceSessionDao().getAllForExport(),
                ai = readAiExport(database, options, System.currentTimeMillis(), retentionDays),
                usage = app.pausecn.usage.readUsageExport(database, options, System.currentTimeMillis(), retentionDays),
                reports = if (options.reports) app.pausecn.reports.readReportExport(database, exportReports(), options,
                    System.currentTimeMillis(), retentionDays).data else null,
                appCategories = if (options.appCategories) database.appCategoryDao().all() else emptyList(),
            )
        }

    private fun exportReports() = app.pausecn.reports.ReportRepository(database,
        usagePermission = app.pausecn.usage.AndroidUsageSource(context)::hasPermission,
        bootId = { app.pausecn.usage.UsageClock.bootId(context) })

    internal suspend fun captureExport(options: LocalExportOptions, retentionDays: Int): CapturedLocalExport =
        database.personalizationGuard.readStable { database.withTransaction {
            check(!database.personalizationGuard.changing) { "本地数据正在变更" }
            val reports = app.pausecn.reports.readReportExport(database, exportReports(), options, System.currentTimeMillis(), retentionDays)
            CapturedLocalExport(loadExportData(options.copy(reports = false), retentionDays).copy(reports = reports.data.takeIf { options.reports }),
                exportStamp(database, options.usage || options.reports), reports, options, retentionDays)
        } }

    internal suspend fun validateExport(capture: CapturedLocalExport, validUntil: Long) = database.personalizationGuard.readStable { database.withTransaction {
        val stamp = capture.stamp
        check(!database.personalizationGuard.changing && exportStamp(database, stamp.usageRevision != null) == stamp && System.currentTimeMillis() < validUntil) {
            "导出期间隐私、来源或有效期已改变"
        }
        app.pausecn.reports.validateReportExport(database, exportReports(), capture.reports, capture.options,
            System.currentTimeMillis(), capture.retentionDays)
    } }

    suspend fun beginServiceSession(
        retentionDays: Int = DEFAULT_HISTORY_RETENTION_DAYS,
        nowEpochMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = android.os.SystemClock.elapsedRealtime(),
    ): Long = database.withTransaction {
        val dao = database.serviceSessionDao()
        val sessionId = dao.insert(
            ServiceSessionEntity(
                connectedAtEpochMs = nowEpochMs,
                connectedAtElapsedMs = nowElapsedMs,
                lastHeartbeatAtEpochMs = nowEpochMs,
                lastHeartbeatAtElapsedMs = nowElapsedMs,
            ),
        )
        val sanitizedDays = sanitizeHistoryRetentionDays(retentionDays)
        dao.deleteOlderThan(nowEpochMs - sanitizedDays * DAY_MS)
        dao.trimToNewest(MAX_SERVICE_SESSION_ROWS)
        sessionId
    }

    suspend fun recordServiceHeartbeat(
        sessionId: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
        nowElapsedMs: Long = android.os.SystemClock.elapsedRealtime(),
    ): Boolean = database.serviceSessionDao().updateHeartbeat(
        sessionId,
        nowEpochMs,
        nowElapsedMs,
    ) == 1

    suspend fun pruneHistory(
        retentionDays: Int = DEFAULT_HISTORY_RETENTION_DAYS,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): HistoryPruneResult =
        personalSourceMutation(invalidate = false) {
            val dao = database.interventionEventDao()
            val sanitizedDays = sanitizeHistoryRetentionDays(retentionDays)
            val expired = dao.deleteOlderThan(nowEpochMs - sanitizedDays * DAY_MS)
            val overflow = dao.trimToNewest(maxHistoryRows)
            if (expired > 0 || overflow > 0) invalidatePersonalizedSources()
            app.pausecn.ai.pruneConversationData(database, nowEpochMs, sanitizedDays)
            app.pausecn.usage.pruneUsageData(database, nowEpochMs - sanitizedDays * DAY_MS)
            database.serviceSessionDao().apply {
                deleteOlderThan(nowEpochMs - sanitizedDays * DAY_MS)
                trimToNewest(MAX_SERVICE_SESSION_ROWS)
            }
            HistoryPruneResult(expiredRows = expired, overflowRows = overflow)
        }

    suspend fun loadLaunchableApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val protected = protectedPackages(context)
        context.packageManager.queryIntentActivities(intent, 0)
            .asSequence()
            .filterNot { isSafetyCriticalApplication(it.activityInfo.packageName) }
            .map { info ->
                InstalledApp(
                    packageName = info.activityInfo.packageName,
                    platformCategory = info.activityInfo.applicationInfo.category,
                    label = sanitizeInstalledAppLabel(
                        info.loadLabel(context.packageManager),
                        info.activityInfo.packageName,
                    ),
                )
            }
            .filterNot { it.packageName in protected }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
            .also { AppCategoryRepository(database).updateAutomatic(it) }
    }

    companion object {
        const val MAX_HISTORY_ROWS = 10_000
        const val MAX_SERVICE_SESSION_ROWS = 500
        private val COMPLETION_OUTCOMES = setOf(
            InterventionOutcome.EXITED,
            InterventionOutcome.CONTINUED,
            InterventionOutcome.DISMISSED,
        )
        private const val DAY_MS = 24L * 60 * 60_000
    }
}

internal fun currentWeekStartEpochMs(nowEpochMs: Long, zoneId: ZoneId): Long =
    Instant.ofEpochMilli(nowEpochMs)
        .atZone(zoneId)
        .toLocalDate()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()

internal fun currentWeekStatsWindow(nowEpochMs: Long, zoneId: ZoneId): StatsWindow {
    val currentMinuteStart = Math.floorDiv(nowEpochMs, STATS_MINUTE_MS) * STATS_MINUTE_MS
    return StatsWindow(
        startEpochMsInclusive = currentWeekStartEpochMs(nowEpochMs, zoneId),
        endEpochMsExclusive = currentMinuteStart + STATS_MINUTE_MS,
    )
}

internal fun millisUntilNextStatsMinute(nowEpochMs: Long): Long =
    STATS_MINUTE_MS - Math.floorMod(nowEpochMs, STATS_MINUTE_MS)

/**
 * App labels are untrusted text supplied by other APKs. Keep them single-line, remove invisible
 * direction-changing controls, and cap their size before they reach overlays, history or exports.
 */
internal fun sanitizeInstalledAppLabel(label: CharSequence?, packageName: String): String {
    val sanitizedLabel = normalizeExternalDisplayText(label?.toString().orEmpty())
    if (sanitizedLabel.isNotEmpty()) return sanitizedLabel

    return normalizeExternalDisplayText(packageName).ifEmpty { UNNAMED_APP_LABEL }
}

private fun sanitizeTargetRule(rule: TargetRuleEntity): TargetRuleEntity =
    rule.copy(label = sanitizeInstalledAppLabel(rule.label, rule.packageName))

private fun sanitizeInterventionEvent(event: InterventionEventEntity): InterventionEventEntity =
    event.copy(appLabel = sanitizeInstalledAppLabel(event.appLabel, event.packageName))

private fun normalizeExternalDisplayText(value: String): String {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
    val result = StringBuilder()
    var index = 0
    var codePointCount = 0
    var pendingSpace = false

    while (index < normalized.length && codePointCount < MAX_APP_LABEL_CODE_POINTS) {
        val codePoint = Character.codePointAt(normalized, index)
        index += Character.charCount(codePoint)
        val type = Character.getType(codePoint)
        val unsafe = type == Character.CONTROL.toInt() ||
            type == Character.FORMAT.toInt() ||
            type == Character.LINE_SEPARATOR.toInt() ||
            type == Character.PARAGRAPH_SEPARATOR.toInt() ||
            type == Character.SURROGATE.toInt() ||
            type == Character.UNASSIGNED.toInt()
        if (unsafe || Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
            pendingSpace = result.isNotEmpty()
            continue
        }
        if (pendingSpace && codePointCount + 1 >= MAX_APP_LABEL_CODE_POINTS) {
            break
        }
        if (pendingSpace) {
            result.append(' ')
            codePointCount += 1
        }
        pendingSpace = false
        result.appendCodePoint(codePoint)
        codePointCount += 1
    }
    return result.toString()
}

private const val MAX_APP_LABEL_CODE_POINTS = 60
private const val UNNAMED_APP_LABEL = "未命名应用"
private const val STATS_MINUTE_MS = 60_000L

fun protectedPackages(context: Context): Set<String> = buildSet {
    addAll(safetyDismissalPackages(context))
    add(context.packageName)
    add("android")
    add("com.android.systemui")
    add("com.android.phone")
    add("com.android.emergency")
}

fun safetyDismissalPackages(context: Context): Set<String> =
    safetyCooldownPackages(context) + launcherPackages(context)

/** Launchers need dismissal, but must not grant a grace period on every normal app launch. */
fun launcherPackages(context: Context): Set<String> {
    val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    return context.packageManager.queryIntentActivities(homeIntent, 0)
        .mapNotNullTo(mutableSetOf()) { it.activityInfo?.packageName }
}

fun safetyCooldownPackages(context: Context): Set<String> = buildSet {
    add("com.android.settings")
    add("com.google.android.dialer")
    add("com.android.dialer")
    add("com.android.emergency")
    addAll(CRITICAL_SYSTEM_PACKAGES)

    context.getSystemService(TelecomManager::class.java)
        ?.defaultDialerPackage
        ?.takeIf { it.isNotBlank() }
        ?.let(::add)
    Telephony.Sms.getDefaultSmsPackage(context)
        ?.takeIf { it.isNotBlank() }
        ?.let(::add)

}

internal fun accessibilityEventPackages(
    targetPackages: Set<String>,
    safetyPackages: Set<String>,
    ownPackage: String,
): Set<String> = buildSet {
    addAll(targetPackages)
    addAll(safetyPackages)
    add(ownPackage)
}

internal fun isSafetyDismissalEvent(packageName: String, safetyPackages: Set<String>): Boolean =
    packageName in safetyPackages

private val CRITICAL_SYSTEM_PACKAGES = setOf(
    // Incoming-call and emergency surfaces must be able to dismiss an active overlay.
    "com.android.incallui",
    "com.miui.incallui",
    "com.coloros.incallui",
    "com.oplus.incallui",
    "com.samsung.android.incallui",
    "com.vivo.dialer",
    "com.huawei.contacts",
    "com.hihonor.contacts",
    "com.google.android.emergency",
    // Camera shortcuts must remain immediately reachable.
    "com.android.camera",
    "com.android.camera2",
    "com.google.android.GoogleCamera",
    "com.huawei.camera",
    "com.hihonor.camera",
    "com.miui.camera",
    "com.oppo.camera",
    "com.coloros.camera",
    "com.oplus.camera",
    "com.oneplus.camera",
    "com.vivo.camera",
    "com.sec.android.app.camera",
    "com.samsung.android.app.camera",
    // Alarm and clock surfaces must never be covered.
    "com.android.deskclock",
    "com.google.android.deskclock",
    "com.miui.clock",
    "com.huawei.deskclock",
    "com.hihonor.deskclock",
    "com.coloros.alarmclock",
    "com.oplus.alarmclock",
    "com.vivo.alarmclock",
    "com.sec.android.app.clockpackage",
    // Package installation and permission confirmation are system safety flows.
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
    "com.miui.packageinstaller",
    "com.android.permissioncontroller",
    "com.google.android.permissioncontroller",
    // Whole-app interception is unsafe for payment and super-app critical flows.
    "com.eg.android.AlipayGphone",
    "com.tencent.mm",
    "com.unionpay",
    "com.unionpay.tsmservice",
    "com.google.android.apps.walletnfcrel",
    "com.huawei.wallet",
    "com.mipay.wallet",
    "com.samsung.android.spay",
)

internal fun isSafetyCriticalApplication(packageName: String): Boolean =
    packageName in CRITICAL_SYSTEM_PACKAGES
