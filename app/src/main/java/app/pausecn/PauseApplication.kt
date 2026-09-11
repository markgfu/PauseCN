package app.pausecn

import android.app.Application
import android.util.Log
import app.pausecn.data.AppContainer
import app.pausecn.data.DatabaseHealthState
import app.pausecn.data.SettingsHealthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PauseApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Work launched here may finish after an AccessibilityService instance is destroyed, while
     * still remaining bounded by the application process lifetime.
     */
    internal fun launchProcessTask(block: suspend CoroutineScope.() -> Unit): Job =
        applicationScope.launch(block = block)

    internal fun <T> asyncProcessTask(block: suspend CoroutineScope.() -> T): Deferred<T> =
        applicationScope.async(block = block)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            combine(container.databaseHealthStore.state, container.settingsHealthStore.state) { db, prefs ->
                (db == DatabaseHealthState.Healthy && prefs == SettingsHealthState.Healthy) to
                    (db is DatabaseHealthState.Unavailable || prefs is SettingsHealthState.Problem)
            }.distinctUntilChanged().collectLatest { (healthy, failed) ->
                if (!healthy) {
                    // Do not cancel the persisted job that may have started this cold process while checks are pending.
                    if (failed) runCatching { app.pausecn.reports.AutomaticReportScheduling.reconcile(this@PauseApplication, false) }
                    return@collectLatest
                }
                app.pausecn.reports.AutomaticReportScheduling.enabled(container.database).retryAfterLocalDataRecovery(
                    onFailure = { container.databaseHealthStore.reportFailure(app.pausecn.data.DatabaseFailureReason.OPEN_FAILED) },
                    awaitRecovery = { container.databaseHealthStore.state.first { it == DatabaseHealthState.Healthy } },
                ).distinctUntilChanged().collect { enabled ->
                    try { app.pausecn.reports.AutomaticReportScheduling.reconcile(this@PauseApplication, enabled) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Foreground UI can re-request scheduling; never invoke the AI here. */ }
                }
            }
        }
        applicationScope.launch {
            container.databaseHealthMonitor.checkDatabase()
            if (BuildConfig.DEBUG) {
                Log.d("PauseHealthProbe", "databaseReady=${container.databaseHealthStore.state.value == DatabaseHealthState.Healthy}")
            }
        }
        applicationScope.launch {
            container.settingsHealthMonitor.checkSettings()
        }
        applicationScope.launch {
            combine(container.databaseHealthStore.state, container.settingsHealthStore.state) { db, prefs ->
                db == DatabaseHealthState.Healthy && prefs == SettingsHealthState.Healthy
            }.distinctUntilChanged().collectLatest { ready ->
                if (!ready) return@collectLatest
                container.usageRepository.config.retryAfterLocalDataRecovery(
                    onFailure = { container.databaseHealthStore.reportFailure(app.pausecn.data.DatabaseFailureReason.OPEN_FAILED) },
                    awaitRecovery = { container.databaseHealthStore.state.first { it == DatabaseHealthState.Healthy } },
                ).map { it?.enabled == true }.distinctUntilChanged().collect { enabled ->
                    try { app.pausecn.usage.UsageScheduling.reconcile(this@PauseApplication, enabled) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Foreground refresh remains available; never start a polling service. */ }
                }
            }
        }
        applicationScope.launch {
            container.database.personalizationGuard.revisions.collectLatest {
                if (!container.database.personalizationGuard.changing &&
                    container.databaseHealthStore.state.value == DatabaseHealthState.Healthy &&
                    container.settingsHealthStore.state.value == SettingsHealthState.Healthy) {
                    try { container.aiRepository.load() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* A stale revision cannot supply personalized overlay text. */ }
                }
            }
        }
        applicationScope.launch {
            combine(container.databaseHealthStore.state, container.settingsHealthStore.state) { db, prefs ->
                db == DatabaseHealthState.Healthy && prefs == SettingsHealthState.Healthy
            }.distinctUntilChanged().collectLatest { healthy ->
                container.reasonMemoryCache.clear()
                if (!healthy) return@collectLatest
                while (true) {
                    try {
                        container.settingsStore.settings.map { it.historyRetentionDays }.distinctUntilChanged()
                            .collectLatest { days ->
                                container.reasonMemoryCache.clear()
                                container.repository.observeReasonMemories(days).collect {
                                    container.reasonMemoryCache.replace(it)
                                }
                            }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        container.reasonMemoryCache.clear()
                        delay(5_000)
                    }
                }
            }
        }
        applicationScope.launch {
            combine(
                container.databaseHealthStore.state,
                container.settingsHealthStore.state,
            ) { databaseHealth, settingsHealth ->
                databaseHealth == DatabaseHealthState.Healthy &&
                    settingsHealth == SettingsHealthState.Healthy
            }.distinctUntilChanged().collectLatest { localDataHealthy ->
                if (!localDataHealthy) return@collectLatest
                try {
                    container.aiRepository.load()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // AI is optional. A failed load leaves the overlay on its in-memory defaults.
                }
                container.settingsStore.settings
                    .map { it.historyRetentionDays }
                    .distinctUntilChanged()
                    .collectLatest { retentionDays ->
                        while (true) {
                            try {
                                container.repository.pruneHistory(retentionDays)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                Log.w(MAINTENANCE_TAG, "History pruning failed", error)
                            }
                            delay(HISTORY_MAINTENANCE_INTERVAL_MS)
                        }
                    }
            }
        }
    }

    private companion object {
        const val HISTORY_MAINTENANCE_INTERVAL_MS = 24L * 60 * 60_000
        const val MAINTENANCE_TAG = "PauseMaintenance"
    }
}
