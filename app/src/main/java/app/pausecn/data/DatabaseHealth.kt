package app.pausecn.data

import android.content.Context
import androidx.core.content.edit
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class DatabaseFailureReason(val diagnosticCode: String) {
    CORRUPTION_DETECTED("DB-CORRUPTION"),
    INTEGRITY_CHECK_FAILED("DB-INTEGRITY"),
    RECOVERY_FAILED("DB-RECOVERY"),
    OPEN_FAILED("DB-OPEN"),
}

sealed interface DatabaseHealthState {
    data class Checking(val previousFailure: Unavailable? = null) : DatabaseHealthState
    data object Healthy : DatabaseHealthState
    data class Unavailable(
        val reason: DatabaseFailureReason,
        val detectedAtEpochMs: Long,
    ) : DatabaseHealthState
}

class DatabaseHealthStore internal constructor(
    context: Context,
    preferenceName: String = PREFERENCE_NAME,
) {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val stateLock = Any()
    private val _state = MutableStateFlow<DatabaseHealthState>(
        DatabaseHealthState.Checking(loadPersistedFailure()),
    )
    val state = _state.asStateFlow()

    internal fun markChecking() = synchronized(stateLock) {
        val previous = when (val current = _state.value) {
            is DatabaseHealthState.Checking -> current.previousFailure
            is DatabaseHealthState.Unavailable -> current
            DatabaseHealthState.Healthy -> null
        }
        _state.value = DatabaseHealthState.Checking(previous)
    }

    internal fun markHealthy() = synchronized(stateLock) {
        preferences.edit(commit = true) { clear() }
        _state.value = DatabaseHealthState.Healthy
    }

    internal fun reportFailure(
        reason: DatabaseFailureReason,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) = synchronized(stateLock) {
        val priorFailure = when (val current = _state.value) {
            is DatabaseHealthState.Checking -> current.previousFailure
            is DatabaseHealthState.Unavailable -> current
            DatabaseHealthState.Healthy -> null
        }
        val detectedAt = priorFailure
            ?.takeIf { it.reason == reason }
            ?.detectedAtEpochMs
            ?: nowEpochMs
        val failure = DatabaseHealthState.Unavailable(reason, detectedAt)
        preferences.edit(commit = true) {
            putString(KEY_REASON, reason.name)
            putLong(KEY_DETECTED_AT, detectedAt)
        }
        _state.value = failure
    }

    private fun loadPersistedFailure(): DatabaseHealthState.Unavailable? {
        val reason = preferences.getString(KEY_REASON, null)
            ?.let { stored -> DatabaseFailureReason.entries.firstOrNull { it.name == stored } }
            ?: return null
        val detectedAt = preferences.getLong(KEY_DETECTED_AT, 0L)
            .takeIf { it > 0L }
            ?: return null
        return DatabaseHealthState.Unavailable(reason, detectedAt)
    }

    private companion object {
        const val PREFERENCE_NAME = "database_health"
        const val KEY_REASON = "failure_reason"
        const val KEY_DETECTED_AT = "detected_at"
    }
}

class DatabaseHealthMonitor internal constructor(
    private val database: RoomDatabase,
    private val healthStore: DatabaseHealthStore,
    private val onIntegrityConfirmed: suspend () -> Unit = {},
) {
    private val checkMutex = Mutex()

    suspend fun checkDatabase(): Boolean = checkMutex.withLock {
        healthStore.markChecking()
        withContext(Dispatchers.IO) {
            try {
                val result = database.openHelper.writableDatabase
                    .query("PRAGMA quick_check(1)")
                    .use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
                }
                if (result.equals("ok", ignoreCase = true)) {
                    try {
                        onIntegrityConfirmed()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        healthStore.reportFailure(DatabaseFailureReason.RECOVERY_FAILED)
                        return@withContext false
                    }
                    healthStore.markHealthy()
                    true
                } else {
                    healthStore.reportFailure(DatabaseFailureReason.INTEGRITY_CHECK_FAILED)
                    false
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (healthStore.state.value !is DatabaseHealthState.Unavailable) {
                    healthStore.reportFailure(DatabaseFailureReason.OPEN_FAILED)
                }
                false
            }
        }
    }
}

/**
 * Room's framework callback deletes a database after SQLite reports corruption. This wrapper
 * deliberately suppresses that destructive default, records the failure, and closes the active
 * handle so the rest of the app can fail closed while preserving the original files for recovery.
 */
internal class PreservingCorruptionOpenHelperFactory(
    private val healthStore: DatabaseHealthStore,
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val roomCallback = configuration.callback
        val preservingCallback = object : SupportSQLiteOpenHelper.Callback(roomCallback.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = roomCallback.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = roomCallback.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                roomCallback.onUpgrade(db, oldVersion, newVersion)

            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                roomCallback.onDowngrade(db, oldVersion, newVersion)

            override fun onOpen(db: SupportSQLiteDatabase) = roomCallback.onOpen(db)

            override fun onCorruption(db: SupportSQLiteDatabase) {
                healthStore.reportFailure(DatabaseFailureReason.CORRUPTION_DETECTED)
                runCatching { db.close() }
            }
        }
        val preservingConfiguration = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name)
            .callback(preservingCallback)
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .allowDataLossOnRecovery(false)
            .build()
        return delegate.create(preservingConfiguration)
    }
}
