package app.pausecn.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Coordinates destructive local-data actions and verifies their observable postconditions before
 * the product tells the user they completed. The operation is intentionally idempotent so a
 * partially completed reset can be retried safely after an I/O failure.
 */
class LocalDataResetter internal constructor(
    private val repository: PauseRepository,
    private val settingsStore: SettingsStore,
    private val settingsHealthMonitor: SettingsHealthMonitor,
    private val sessionGate: SessionGate,
    private val healthStore: HealthStore,
    private val aiRepository: app.pausecn.ai.AiRepository,
) {
    suspend fun clearHistory() {
        aiRepository.clearGeneratedHistory()
        repository.clearHistory()
        val exportData = repository.loadExportData()
        if (exportData.events.isNotEmpty() || exportData.serviceSessions.isNotEmpty()) {
            throw IOException("History deletion could not be verified.")
        }
    }

    suspend fun resetAll() {
        aiRepository.beginReset()
        repository.clearTargetsAndHistory()
        settingsStore.resetAll()
        if (!settingsHealthMonitor.clearRecoveryCopies()) {
            throw IOException("Settings recovery-copy deletion could not be verified.")
        }
        val runtimeGateCleared = withContext(Dispatchers.IO) { sessionGate.clearAll() }
        val serviceHealthCleared = withContext(Dispatchers.IO) { healthStore.clearAll() }
        if (!runtimeGateCleared) {
            throw IOException("Runtime-gate deletion could not be verified.")
        }
        if (!serviceHealthCleared) {
            throw IOException("Service-health deletion could not be verified.")
        }

        val exportData = repository.loadExportData()
        if (exportData.targets.isNotEmpty() ||
            exportData.events.isNotEmpty() ||
            exportData.serviceSessions.isNotEmpty()
        ) {
            throw IOException("Room deletion could not be verified.")
        }
        if (settingsStore.settings.first() != SettingsSnapshot()) {
            throw IOException("Settings reset could not be verified.")
        }
        aiRepository.finishReset()
    }
}
