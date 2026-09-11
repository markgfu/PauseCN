package app.pausecn

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.pausecn.data.AppContainer
import app.pausecn.data.DatabaseFailureReason
import app.pausecn.data.InstalledApp
import app.pausecn.data.ServiceHeartbeatSnapshot
import app.pausecn.data.ServiceStartObservationSnapshot
import app.pausecn.data.SettingsFailureReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MainViewModel(
    private val container: AppContainer,
) : ViewModel() {
    val targets = container.repository.targets.retryAfterLocalDataRecovery(
        onFailure = { error ->
            Log.e(LOCAL_DATA_FLOW_TAG, "Target flow failed", error)
            container.databaseHealthStore.reportFailure(DatabaseFailureReason.OPEN_FAILED)
        },
        awaitRecovery = {
            container.databaseHealthStore.state.first { it == app.pausecn.data.DatabaseHealthState.Healthy }
        },
    ).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val settings = container.settingsStore.settings.retryAfterLocalDataRecovery(
        onFailure = { error ->
            Log.e(LOCAL_DATA_FLOW_TAG, "Settings flow failed", error)
            container.settingsHealthStore.reportUnavailable(SettingsFailureReason.READ_OR_WRITE_FAILED)
        },
        awaitRecovery = {
            container.settingsHealthStore.state.first { it == app.pausecn.data.SettingsHealthState.Healthy }
        },
    ).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        app.pausecn.data.SettingsSnapshot(),
    )
    val stats = container.repository.observeThisWeekStats().retryAfterLocalDataRecovery(
        onFailure = { error ->
            Log.e(LOCAL_DATA_FLOW_TAG, "Statistics flow failed", error)
            container.databaseHealthStore.reportFailure(DatabaseFailureReason.OPEN_FAILED)
        },
        awaitRecovery = {
            container.databaseHealthStore.state.first { it == app.pausecn.data.DatabaseHealthState.Healthy }
        },
    ).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        app.pausecn.data.StatsSnapshot(),
    )
    val recentEvents = container.repository.recentEvents.retryAfterLocalDataRecovery(
        onFailure = { error ->
            Log.e(LOCAL_DATA_FLOW_TAG, "History flow failed", error)
            container.databaseHealthStore.reportFailure(DatabaseFailureReason.OPEN_FAILED)
        },
        awaitRecovery = {
            container.databaseHealthStore.state.first { it == app.pausecn.data.DatabaseHealthState.Healthy }
        },
    ).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val databaseHealth = container.databaseHealthStore.state
    val appCategories = container.appCategories.state.retryAfterLocalDataRecovery(
        onFailure = { container.databaseHealthStore.reportFailure(DatabaseFailureReason.OPEN_FAILED) },
        awaitRecovery = { container.databaseHealthStore.state.first { it == app.pausecn.data.DatabaseHealthState.Healthy } },
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), app.pausecn.data.AppCategorySnapshot())
    val categoryCounts = container.repository.observeThisWeekCategoryCounts().retryAfterLocalDataRecovery(
        onFailure = { container.databaseHealthStore.reportFailure(DatabaseFailureReason.OPEN_FAILED) },
        awaitRecovery = { container.databaseHealthStore.state.first { it == app.pausecn.data.DatabaseHealthState.Healthy } },
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settingsHealth = container.settingsHealthStore.state

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps = _installedApps.asStateFlow()
    private val _loadingApps = MutableStateFlow(true)
    val loadingApps = _loadingApps.asStateFlow()
    private val _selectingAllTargets = MutableStateFlow(false)
    val selectingAllTargets = _selectingAllTargets.asStateFlow()
    val reasonMemories = container.reasonMemoryCache.state
    private val _appsLoadFailed = MutableStateFlow(false)
    val appsLoadFailed = _appsLoadFailed.asStateFlow()
    private val _hasLoadedAppsSuccessfully = MutableStateFlow(false)
    val hasLoadedAppsSuccessfully = _hasLoadedAppsSuccessfully.asStateFlow()
    private val _exportState = MutableStateFlow(ExportUiState())
    val exportState = _exportState.asStateFlow()
    private val _localDataActionState = MutableStateFlow(LocalDataActionUiState())
    val localDataActionState = _localDataActionState.asStateFlow()
    private val pendingExportPassphrase = SensitiveCharArrayBuffer()
    private var pendingExportOptions = app.pausecn.data.LocalExportOptions()
    private val localDataOperationGate = LocalDataOperationGate()
    private var appsLoadJob: Job? = null
    private val appIconCache = object : LruCache<String, Bitmap>(APP_ICON_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    init {
        refreshInstalledApps()
    }

    fun refreshInstalledApps() {
        if (appsLoadJob?.isActive == true) return
        appsLoadJob = viewModelScope.launch {
            _loadingApps.value = true
            _appsLoadFailed.value = false
            try {
                _installedApps.value = container.repository.loadLaunchableApps()
                _hasLoadedAppsSuccessfully.value = true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _appsLoadFailed.value = true
            } finally {
                _loadingApps.value = false
            }
        }
    }

    suspend fun loadAppIcon(packageName: String): Bitmap? {
        synchronized(appIconCache) { appIconCache.get(packageName) }?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                container.applicationContext.packageManager
                    .getApplicationIcon(packageName)
                    .toBitmap(width = APP_ICON_SIZE_PX, height = APP_ICON_SIZE_PX)
            }.getOrNull()?.also { icon ->
                synchronized(appIconCache) { appIconCache.put(packageName, icon) }
            }
        }
    }

    fun setTarget(app: InstalledApp, enabled: Boolean) = viewModelScope.launch {
        serializeUserMutation(Unit) {
            performDatabaseMutation { container.repository.setTarget(app, enabled) }
        }
    }

    fun forgetReason(row: app.pausecn.data.ReasonMemory) = viewModelScope.launch {
        serializeUserMutation(Unit) {
            performDatabaseMutation {
                container.repository.forgetReason(row.packageName, row.text)
                container.reasonMemoryCache.replace(container.reasonMemoryCache.state.value.filterNot {
                    it.packageName == row.packageName && it.text == row.text
                })
            }
        }
    }

    fun forgetAllReasons() = viewModelScope.launch {
        serializeUserMutation(Unit) {
            performDatabaseMutation {
                container.repository.forgetAllReasons()
                container.reasonMemoryCache.clear()
            }
        }
    }

    fun selectTargets(apps: List<InstalledApp>) = setTargetsEnabled(apps, enabled = true)

    fun deselectTargets(apps: List<InstalledApp>) = setTargetsEnabled(apps, enabled = false)

    private fun setTargetsEnabled(apps: List<InstalledApp>, enabled: Boolean) {
        if (_selectingAllTargets.value || _loadingApps.value || _appsLoadFailed.value) return
        _selectingAllTargets.value = true
        viewModelScope.launch {
            try {
                serializeUserMutation(Unit) {
                    val requested = apps.mapTo(hashSetOf()) { it.packageName }
                    if (enabled) {
                        val current = _installedApps.value.filter { it.isInstalled && it.packageName in requested }
                        performDatabaseMutation { container.repository.selectTargets(current) }
                    } else {
                        // Removing an unavailable selected target must remain possible.
                        val known = _installedApps.value.mapTo(hashSetOf()) { it.packageName }
                        targets.value.mapTo(known) { it.packageName }
                        val current = apps.filter { it.packageName in known }
                        performDatabaseMutation { container.repository.deselectTargets(current) }
                    }
                }
            } finally {
                _selectingAllTargets.value = false
            }
        }
    }

    suspend fun acceptDisclosureAndAgeEligibility(): Boolean =
        serializeUserMutation(false) {
            performSettingsMutation { container.settingsStore.acceptDisclosureAndAgeEligibility() }
        }

    fun completeOnboardingPreview() = viewModelScope.launch {
        serializeUserMutation(Unit) {
            performSettingsMutation { container.settingsStore.completeOnboardingPreview() }
        }
    }
    fun pauseFor15Minutes() = viewModelScope.launch {
        serializeUserMutation(Unit) {
            performSettingsMutation { container.settingsStore.pauseFor(15 * 60_000L) }
        }
    }
    fun resumeNow() = viewModelScope.launch {
        serializeUserMutation(Unit) {
            performSettingsMutation { container.settingsStore.resumeNow() }
        }
    }
    fun setScheduleEnabled(enabled: Boolean) =
        viewModelScope.launch {
            serializeUserMutation(Unit) {
                performSettingsMutation { container.settingsStore.setScheduleEnabled(enabled) }
            }
        }

    fun setScheduleWindow(startMinutes: Int, endMinutes: Int) =
        viewModelScope.launch {
            serializeUserMutation(Unit) {
                performSettingsMutation {
                    container.settingsStore.setScheduleWindow(startMinutes, endMinutes)
                }
            }
        }

    fun setActiveDays(mask: Int) = viewModelScope.launch {
        serializeUserMutation(Unit) {
            performSettingsMutation { container.settingsStore.setActiveDays(mask) }
        }
    }
    fun setInterventionSeconds(seconds: Int) =
        viewModelScope.launch {
            serializeUserMutation(Unit) {
                performSettingsMutation { container.settingsStore.setInterventionSeconds(seconds) }
            }
        }

    fun setTemporaryPassMinutes(minutes: Int) =
        viewModelScope.launch {
            serializeUserMutation(Unit) {
                performSettingsMutation { container.settingsStore.setTemporaryPassMinutes(minutes) }
            }
        }

    fun setHistoryRetentionDays(days: Int) = viewModelScope.launch {
        serializeUserMutation(Unit) {
            if (performSettingsMutation { container.settingsStore.setHistoryRetentionDays(days) }) {
                performDatabaseMutation { container.repository.pruneHistory(days) }
            }
        }
    }

    fun clearHistory() = runLocalDataAction(
        successMessage = "干预历史已清除",
        failureMessage = "未能确认干预历史已完全清除，请重试",
    ) {
        container.localDataResetter.clearHistory()
    }

    fun resetAllLocalData() = runLocalDataAction(
        successMessage = "全部本地数据已重置",
        failureMessage = "重置未完整完成，请保留应用并重试",
    ) {
        container.localDataResetter.resetAll()
    }

    fun exportLocalData(uri: Uri, passphrase: CharArray, options: app.pausecn.data.LocalExportOptions = app.pausecn.data.LocalExportOptions()) = viewModelScope.launch {
        if (!localDataOperationGate.tryStartLongOperation()) {
            passphrase.fill('\u0000')
            if (!_exportState.value.inProgress && _localDataActionState.value.inProgress) {
                _exportState.value = ExportUiState(message = "本地数据正在处理，请稍后再导出")
            }
            return@launch
        }
        _exportState.value = ExportUiState(inProgress = true)
        try {
            localDataOperationGate.runStartedLongOperation {
                container.localDataExporter.export(uri, passphrase, BuildConfig.VERSION_NAME, options)
            }
            _exportState.value = ExportUiState(message = "加密导出完成")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _exportState.value = ExportUiState(
                message = "导出未完成，本机原始数据未改变；请删除所选文件后重试",
            )
        } finally {
            passphrase.fill('\u0000')
            if (_exportState.value.inProgress) _exportState.value = ExportUiState()
        }
    }

    fun queuePendingExportPassphrase(passphrase: CharArray, options: app.pausecn.data.LocalExportOptions = app.pausecn.data.LocalExportOptions()) {
        pendingExportPassphrase.replace(passphrase)
        pendingExportOptions = options
    }

    fun takePendingExportPassphrase(): CharArray? = pendingExportPassphrase.take()
    fun takePendingExportOptions(): app.pausecn.data.LocalExportOptions = pendingExportOptions.also {
        pendingExportOptions = app.pausecn.data.LocalExportOptions()
    }

    fun discardPendingExportPassphrase() {
        pendingExportPassphrase.clear()
        pendingExportOptions = app.pausecn.data.LocalExportOptions()
    }

    fun consumeExportMessage() {
        _exportState.value = ExportUiState()
    }

    fun consumeLocalDataActionMessage() {
        _localDataActionState.value = LocalDataActionUiState()
    }

    fun recheckDatabase() = viewModelScope.launch {
        performDatabaseMutation { container.databaseHealthMonitor.checkDatabase() }
    }

    fun recheckSettings() = viewModelScope.launch {
        performSettingsMutation { container.settingsHealthMonitor.checkSettings() }
    }

    fun confirmRecoveredSettings() = viewModelScope.launch {
        performSettingsMutation { container.settingsHealthMonitor.confirmSafeDefaults() }
    }

    fun lastServiceConnection(): Long = container.healthStore.lastConnectedAt()
    fun lastWindowEvent(): Long = container.healthStore.lastWindowEventAt()
    fun lastServiceHeartbeat(): ServiceHeartbeatSnapshot = container.healthStore.lastHeartbeat()
    fun serviceStartObservation(): ServiceStartObservationSnapshot =
        container.healthStore.serviceStartObservation()
    fun observeAccessibilityState(
        enabled: Boolean,
        nowEpochMs: Long,
        nowElapsedMs: Long,
        bootCount: Int,
    ) = container.healthStore.observeAccessibilityState(enabled, nowEpochMs, nowElapsedMs, bootCount)
    fun currentBootCount(): Int = container.healthStore.currentBootCount()

    private fun runLocalDataAction(
        successMessage: String,
        failureMessage: String,
        action: suspend () -> Unit,
    ) = viewModelScope.launch {
        if (!localDataOperationGate.tryStartLongOperation()) return@launch
        _localDataActionState.value = LocalDataActionUiState(inProgress = true)
        _localDataActionState.value = try {
            localDataOperationGate.runStartedLongOperation { action() }
            LocalDataActionUiState(message = successMessage)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            LocalDataActionUiState(message = failureMessage)
        } finally {
            if (_localDataActionState.value.inProgress) {
                _localDataActionState.value = LocalDataActionUiState()
            }
        }
    }

    private suspend fun performSettingsMutation(action: suspend () -> Unit): Boolean =
        runFailClosedMutation(
            onFailure = { error ->
                Log.e(MUTATION_TAG, "Settings mutation failed", error)
                container.settingsHealthStore.reportUnavailable(SettingsFailureReason.READ_OR_WRITE_FAILED)
            },
            action = action,
        )

    private suspend fun <T> serializeUserMutation(
        blockedValue: T,
        action: suspend () -> T,
    ): T = localDataOperationGate.serializeMutation(blockedValue, action)

    private suspend fun performDatabaseMutation(action: suspend () -> Unit): Boolean =
        runFailClosedMutation(
            onFailure = { error ->
                Log.e(MUTATION_TAG, "Database mutation failed", error)
                container.databaseHealthStore.reportFailure(DatabaseFailureReason.OPEN_FAILED)
            },
            action = action,
        )

    override fun onCleared() {
        pendingExportPassphrase.clear()
        super.onCleared()
    }

    companion object {
        private const val APP_ICON_CACHE_KB = 4 * 1024
        private const val APP_ICON_SIZE_PX = 96
        private const val MUTATION_TAG = "PauseMutation"
        private const val LOCAL_DATA_FLOW_TAG = "PauseLocalDataFlow"

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(container) as T
                }
            }
    }
}

internal fun <T> Flow<T>.retryAfterLocalDataRecovery(
    onFailure: (Throwable) -> Unit,
    awaitRecovery: suspend () -> Unit,
): Flow<T> = retryWhen { error, _ ->
    if (error is CancellationException || error !is Exception) {
        false
    } else {
        onFailure(error)
        awaitRecovery()
        true
    }
}

internal suspend fun runFailClosedMutation(
    onFailure: (Exception) -> Unit,
    action: suspend () -> Unit,
): Boolean = try {
    action()
    true
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    onFailure(error)
    false
}

/**
 * Serializes user data mutations and gives a pending clear/reset/export exclusive priority.
 * The long-operation flag is set synchronously before it waits for the mutex, so new mutations
 * cannot join the queue behind an already requested destructive or export operation.
 */
internal class LocalDataOperationGate {
    private val mutex = Mutex()
    private val stateLock = Any()

    @Volatile
    private var longOperationInProgress = false

    fun tryStartLongOperation(): Boolean = synchronized(stateLock) {
        if (longOperationInProgress) {
            false
        } else {
            longOperationInProgress = true
            true
        }
    }

    suspend fun <T> runStartedLongOperation(action: suspend () -> T): T {
        check(longOperationInProgress) { "A long local-data operation must be started first." }
        return try {
            mutex.withLock { action() }
        } finally {
            synchronized(stateLock) { longOperationInProgress = false }
        }
    }

    suspend fun <T> serializeMutation(
        blockedValue: T,
        action: suspend () -> T,
    ): T {
        if (longOperationInProgress) return blockedValue
        return mutex.withLock {
            if (longOperationInProgress) blockedValue else action()
        }
    }
}

data class ExportUiState(
    val inProgress: Boolean = false,
    val message: String? = null,
)

data class LocalDataActionUiState(
    val inProgress: Boolean = false,
    val message: String? = null,
)

internal class SensitiveCharArrayBuffer {
    private var value: CharArray? = null

    @Synchronized
    fun replace(replacement: CharArray) {
        clearLocked()
        value = replacement
    }

    @Synchronized
    fun take(): CharArray? = value.also { value = null }

    @Synchronized
    fun clear() {
        clearLocked()
    }

    private fun clearLocked() {
        value?.fill('\u0000')
        value = null
    }
}
