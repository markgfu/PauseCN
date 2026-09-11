package app.pausecn.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

enum class SettingsFailureReason(val diagnosticCode: String) {
    READ_OR_WRITE_FAILED("SETTINGS-IO"),
    RECOVERY_COPY_FAILED("SETTINGS-BACKUP"),
    RECOVERY_DELETE_FAILED("SETTINGS-DELETE"),
}

data class SettingsRecoveryEvidence(
    val fileName: String,
    val sha256: String,
    val byteCount: Long,
    val detectedAtEpochMs: Long,
)

sealed interface SettingsHealthState {
    data class Checking(val previousProblem: Problem? = null) : SettingsHealthState
    data object Healthy : SettingsHealthState

    sealed interface Problem : SettingsHealthState {
        val detectedAtEpochMs: Long
    }

    data class Unavailable(
        val reason: SettingsFailureReason,
        override val detectedAtEpochMs: Long,
    ) : Problem

    data class RecoveryRequired(
        val evidence: SettingsRecoveryEvidence,
    ) : Problem {
        override val detectedAtEpochMs: Long get() = evidence.detectedAtEpochMs
    }
}

class SettingsHealthStore internal constructor(
    context: Context,
    preferenceName: String = PREFERENCE_NAME,
) {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val stateLock = Any()
    private val _state = MutableStateFlow<SettingsHealthState>(
        SettingsHealthState.Checking(loadPersistedProblem()),
    )
    val state = _state.asStateFlow()

    internal fun markChecking() = synchronized(stateLock) {
        _state.value = SettingsHealthState.Checking(currentProblem())
    }

    internal fun markHealthy() = synchronized(stateLock) {
        preferences.edit(commit = true) {
            remove(KEY_ISSUE_KIND)
            remove(KEY_FAILURE_REASON)
            remove(KEY_DETECTED_AT)
        }
        _state.value = SettingsHealthState.Healthy
    }

    internal fun reportUnavailable(
        reason: SettingsFailureReason,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) = synchronized(stateLock) {
        val prior = currentProblem() as? SettingsHealthState.Unavailable
        val detectedAt = prior
            ?.takeIf { it.reason == reason }
            ?.detectedAtEpochMs
            ?: nowEpochMs
        val problem = SettingsHealthState.Unavailable(reason, detectedAt)
        preferences.edit(commit = true) {
            putString(KEY_ISSUE_KIND, ISSUE_UNAVAILABLE)
            putString(KEY_FAILURE_REASON, reason.name)
            putLong(KEY_DETECTED_AT, detectedAt)
        }
        _state.value = problem
    }

    internal fun reportRecoveryRequired(evidence: SettingsRecoveryEvidence): Boolean = synchronized(stateLock) {
        val persisted = preferences.edit()
            .putString(KEY_ISSUE_KIND, ISSUE_RECOVERY)
            .putLong(KEY_DETECTED_AT, evidence.detectedAtEpochMs)
            .putString(KEY_RECOVERY_FILE, evidence.fileName)
            .putString(KEY_RECOVERY_SHA256, evidence.sha256)
            .putLong(KEY_RECOVERY_BYTES, evidence.byteCount)
            .commit()
        if (persisted) _state.value = SettingsHealthState.RecoveryRequired(evidence)
        persisted
    }

    internal fun restoreProblem(problem: SettingsHealthState.Problem) = synchronized(stateLock) {
        _state.value = problem
    }

    internal fun persistedRecoveryEvidence(): SettingsRecoveryEvidence? = synchronized(stateLock) {
        loadRecoveryEvidence()
    }

    internal fun clearRecoveryEvidence(): Boolean = synchronized(stateLock) {
        val recoveryIssueActive = preferences.getString(KEY_ISSUE_KIND, null) == ISSUE_RECOVERY
        val editor = preferences.edit()
            .remove(KEY_RECOVERY_FILE)
            .remove(KEY_RECOVERY_SHA256)
            .remove(KEY_RECOVERY_BYTES)
        if (recoveryIssueActive) {
            editor.remove(KEY_ISSUE_KIND)
                .remove(KEY_DETECTED_AT)
        }
        val persisted = editor.commit()
        if (persisted && _state.value is SettingsHealthState.RecoveryRequired) {
            _state.value = SettingsHealthState.Healthy
        }
        persisted
    }

    private fun currentProblem(): SettingsHealthState.Problem? = when (val current = _state.value) {
        is SettingsHealthState.Checking -> current.previousProblem
        is SettingsHealthState.Problem -> current
        SettingsHealthState.Healthy -> null
    }

    private fun loadPersistedProblem(): SettingsHealthState.Problem? = when (
        preferences.getString(KEY_ISSUE_KIND, null)
    ) {
        ISSUE_RECOVERY -> loadRecoveryEvidence()?.let(SettingsHealthState::RecoveryRequired)
        ISSUE_UNAVAILABLE -> {
            val reason = preferences.getString(KEY_FAILURE_REASON, null)
                ?.let { stored -> SettingsFailureReason.entries.firstOrNull { it.name == stored } }
                ?: return null
            val detectedAt = preferences.getLong(KEY_DETECTED_AT, 0L).takeIf { it > 0 } ?: return null
            SettingsHealthState.Unavailable(reason, detectedAt)
        }
        else -> null
    }

    private fun loadRecoveryEvidence(): SettingsRecoveryEvidence? {
        val fileName = preferences.getString(KEY_RECOVERY_FILE, null)
            ?.takeIf { it.isNotBlank() && '/' !in it && '\\' !in it }
            ?: return null
        val sha256 = preferences.getString(KEY_RECOVERY_SHA256, null)
            ?.takeIf { it.matches(Regex("[0-9A-F]{64}")) }
            ?: return null
        val byteCount = preferences.getLong(KEY_RECOVERY_BYTES, -1L).takeIf { it >= 0 } ?: return null
        val detectedAt = preferences.getLong(KEY_DETECTED_AT, 0L).takeIf { it > 0 } ?: return null
        return SettingsRecoveryEvidence(fileName, sha256, byteCount, detectedAt)
    }

    private companion object {
        const val PREFERENCE_NAME = "settings_health"
        const val KEY_ISSUE_KIND = "issue_kind"
        const val KEY_FAILURE_REASON = "failure_reason"
        const val KEY_DETECTED_AT = "detected_at"
        const val KEY_RECOVERY_FILE = "recovery_file"
        const val KEY_RECOVERY_SHA256 = "recovery_sha256"
        const val KEY_RECOVERY_BYTES = "recovery_bytes"
        const val ISSUE_UNAVAILABLE = "unavailable"
        const val ISSUE_RECOVERY = "recovery_required"
    }
}

internal class SettingsRecoveryManager(
    context: Context,
    private val settingsFile: File,
    private val healthStore: SettingsHealthStore,
    recoveryDirectoryName: String = RECOVERY_DIRECTORY,
) {
    private val recoveryDirectory = File(context.noBackupFilesDir, recoveryDirectoryName)

    fun preserveCorruptedSettings(): SettingsRecoveryEvidence {
        if (!settingsFile.isFile) throw IOException("The corrupted settings file is missing.")
        if (!recoveryDirectory.exists() && !recoveryDirectory.mkdirs()) {
            throw IOException("Could not create the settings recovery directory.")
        }
        val detectedAt = System.currentTimeMillis()
        val token = UUID.randomUUID().toString().replace("-", "")
        val temporary = File(recoveryDirectory, ".pause-settings-$token.tmp")
        val preserved = File(recoveryDirectory, "pause-settings-$detectedAt-$token.preferences_pb")
        FileInputStream(settingsFile).use { input ->
            FileOutputStream(temporary).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
        val sourceHash = settingsFile.sha256()
        val copyHash = temporary.sha256()
        if (!MessageDigest.isEqual(sourceHash.hexToBytes(), copyHash.hexToBytes()) ||
            settingsFile.length() != temporary.length()
        ) {
            throw IOException("The settings recovery copy did not match its source.")
        }
        if (!temporary.renameTo(preserved)) {
            throw IOException("Could not finalize the settings recovery copy.")
        }
        val evidence = SettingsRecoveryEvidence(
            fileName = preserved.name,
            sha256 = copyHash,
            byteCount = preserved.length(),
            detectedAtEpochMs = detectedAt,
        )
        if (!healthStore.reportRecoveryRequired(evidence)) {
            throw IOException("Could not persist the settings recovery marker.")
        }
        return evidence
    }

    fun hasVerifiedRecoveryCopy(evidence: SettingsRecoveryEvidence): Boolean {
        val file = recoveryFile(evidence) ?: return false
        return file.isFile &&
            file.length() == evidence.byteCount &&
            file.sha256() == evidence.sha256
    }

    fun clearRecoveryCopies(): Boolean {
        if (!recoveryDirectory.exists()) return true
        if (!recoveryDirectory.isDirectory) return false
        val allFilesDeleted = recoveryDirectory.listFiles().orEmpty().all { candidate ->
            if (candidate.isFile && candidate.parentFile?.canonicalFile == recoveryDirectory.canonicalFile) {
                candidate.delete()
            } else {
                false
            }
        }
        return allFilesDeleted && recoveryDirectory.delete() && !recoveryDirectory.exists()
    }

    internal fun recoveryFile(evidence: SettingsRecoveryEvidence): File? {
        if ('/' in evidence.fileName || '\\' in evidence.fileName) return null
        val file = File(recoveryDirectory, evidence.fileName)
        return file.takeIf { it.parentFile?.canonicalFile == recoveryDirectory.canonicalFile }
    }

    private fun File.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(readBytes())
        .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xff) }

    private fun String.hexToBytes(): ByteArray = chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

    private companion object {
        const val RECOVERY_DIRECTORY = "settings-recovery"
    }
}

class SettingsHealthMonitor internal constructor(
    private val settingsStore: SettingsStore,
    private val healthStore: SettingsHealthStore,
    private val recoveryManager: SettingsRecoveryManager,
) {
    private val checkMutex = Mutex()

    suspend fun checkSettings(): Boolean = checkMutex.withLock {
        val previousProblem = when (val current = healthStore.state.value) {
            is SettingsHealthState.Checking -> current.previousProblem
            is SettingsHealthState.Problem -> current
            SettingsHealthState.Healthy -> null
        }
        healthStore.markChecking()
        withContext(Dispatchers.IO) {
            try {
                if (previousProblem is SettingsHealthState.Unavailable &&
                    previousProblem.reason == SettingsFailureReason.RECOVERY_DELETE_FAILED
                ) {
                    if (!recoveryManager.clearRecoveryCopies() || !healthStore.clearRecoveryEvidence()) {
                        healthStore.reportUnavailable(SettingsFailureReason.RECOVERY_DELETE_FAILED)
                        return@withContext false
                    }
                }
                settingsStore.settings.first()
                val current = healthStore.state.value
                when {
                    current is SettingsHealthState.RecoveryRequired -> false
                    previousProblem is SettingsHealthState.RecoveryRequired -> {
                        healthStore.restoreProblem(previousProblem)
                        false
                    }
                    else -> {
                        healthStore.markHealthy()
                        true
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (healthStore.state.value !is SettingsHealthState.Problem) {
                    healthStore.reportUnavailable(SettingsFailureReason.READ_OR_WRITE_FAILED)
                }
                false
            }
        }
    }

    suspend fun confirmSafeDefaults(): Boolean = checkMutex.withLock {
        withContext(Dispatchers.IO) {
            val recovery = when (val current = healthStore.state.value) {
                is SettingsHealthState.Checking -> current.previousProblem as? SettingsHealthState.RecoveryRequired
                is SettingsHealthState.RecoveryRequired -> current
                else -> null
            } ?: return@withContext false
            if (!recoveryManager.hasVerifiedRecoveryCopy(recovery.evidence)) return@withContext false
            try {
                if (settingsStore.settings.first() != SettingsSnapshot()) return@withContext false
                healthStore.markHealthy()
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                healthStore.reportUnavailable(SettingsFailureReason.READ_OR_WRITE_FAILED)
                false
            }
        }
    }

    suspend fun clearRecoveryCopies(): Boolean = withContext(Dispatchers.IO) {
        if (recoveryManager.clearRecoveryCopies() && healthStore.clearRecoveryEvidence()) {
            true
        } else {
            healthStore.reportUnavailable(SettingsFailureReason.RECOVERY_DELETE_FAILED)
            false
        }
    }
}
