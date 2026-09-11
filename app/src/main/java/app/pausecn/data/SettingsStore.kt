package app.pausecn.data

import android.content.Context
import android.os.SystemClock
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.pausecn.domain.ScheduleSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.io.IOException

data class SettingsSnapshot(
    val disclosureAccepted: Boolean = false,
    val ageEligibilityConfirmed: Boolean = false,
    val onboardingPreviewCompleted: Boolean = false,
    val schedule: ScheduleSpec = ScheduleSpec(),
    val globallyPausedUntilEpochMs: Long = 0,
    val globallyPausedAtEpochMs: Long = 0,
    val globallyPausedAtElapsedMs: Long = 0,
    val globallyPausedUntilElapsedMs: Long = 0,
    val interventionSeconds: Int = 6,
    val temporaryPassMinutes: Int = 5,
    val historyRetentionDays: Int = DEFAULT_HISTORY_RETENTION_DAYS,
) {
    fun isGloballyPaused(
        nowEpochMs: Long,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean = isExpiringWindowActive(
        issuedEpochMs = globallyPausedAtEpochMs,
        issuedElapsedMs = globallyPausedAtElapsedMs,
        untilEpochMs = globallyPausedUntilEpochMs,
        untilElapsedMs = globallyPausedUntilElapsedMs,
        nowEpochMs = nowEpochMs,
        nowElapsedMs = nowElapsedMs,
    )
}

class SettingsStore internal constructor(
    private val context: Context,
    dataStoreName: String = DEFAULT_DATA_STORE_NAME,
    private val healthStore: SettingsHealthStore? = null,
) {
    private val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val dataFile = context.dataStoreFile("$dataStoreName.preferences_pb")
    internal val recoveryManager = healthStore?.let {
        SettingsRecoveryManager(context, dataFile, it, "$dataStoreName-recovery")
    }
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = recoveryManager?.let { manager ->
            ReplaceFileCorruptionHandler {
                try {
                    manager.preserveCorruptedSettings()
                    emptyPreferences()
                } catch (backupError: Exception) {
                    healthStore?.reportUnavailable(SettingsFailureReason.RECOVERY_COPY_FAILED)
                    throw CorruptionException(
                        "The corrupted settings file could not be preserved; refusing replacement.",
                        backupError,
                    )
                }
            }
        },
        scope = dataStoreScope,
        produceFile = { dataFile },
    )

    val settings: Flow<SettingsSnapshot> = dataStore.data
        .catch { error ->
            reportFailure(error)
            throw error
        }
        .map { prefs ->
            sanitizedSettingsSnapshot(
                disclosureAccepted = prefs[DISCLOSURE_ACCEPTED] ?: false,
                ageEligibilityConfirmed = prefs[AGE_ELIGIBILITY_CONFIRMED] ?: false,
                onboardingPreviewCompleted = prefs[ONBOARDING_PREVIEW_COMPLETED] ?: false,
                scheduleEnabled = prefs[SCHEDULE_ENABLED] ?: true,
                startMinutes = prefs[START_MINUTES] ?: 0,
                endMinutes = prefs[END_MINUTES] ?: 0,
                activeDaysMask = prefs[ACTIVE_DAYS] ?: ScheduleSpec.ALL_DAYS,
                globallyPausedUntilEpochMs = prefs[PAUSED_UNTIL] ?: 0,
                globallyPausedAtEpochMs = prefs[PAUSED_AT_EPOCH] ?: 0,
                globallyPausedAtElapsedMs = prefs[PAUSED_AT_ELAPSED] ?: 0,
                globallyPausedUntilElapsedMs = prefs[PAUSED_UNTIL_ELAPSED] ?: 0,
                interventionSeconds = prefs[INTERVENTION_SECONDS] ?: 6,
                temporaryPassMinutes = prefs[PASS_MINUTES] ?: 5,
                historyRetentionDays = prefs[HISTORY_RETENTION_DAYS] ?: DEFAULT_HISTORY_RETENTION_DAYS,
            )
        }

    suspend fun acceptDisclosureAndAgeEligibility() = editSettings {
        it[DISCLOSURE_ACCEPTED] = true
        it[AGE_ELIGIBILITY_CONFIRMED] = true
    }

    suspend fun completeOnboardingPreview() = editSettings {
        it[ONBOARDING_PREVIEW_COMPLETED] = true
    }

    suspend fun setScheduleEnabled(enabled: Boolean) =
        editSettings { changeRules(it) { value -> value.copy(schedule = value.schedule.copy(enabled = enabled)) } }

    suspend fun setScheduleWindow(startMinutes: Int, endMinutes: Int) =
        editSettings {
            changeRules(it) { value -> value.copy(schedule = value.schedule.copy(
                startMinutes = startMinutes.coerceIn(0, 1439), endMinutes = endMinutes.coerceIn(0, 1439))) }
        }

    suspend fun setActiveDays(mask: Int) =
        editSettings { changeRules(it) { value -> value.copy(schedule = value.schedule.copy(activeDaysMask = mask.coerceIn(0, ScheduleSpec.ALL_DAYS))) } }

    suspend fun pauseFor(durationMs: Long) {
        val nowEpochMs = System.currentTimeMillis()
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val safeDurationMs = durationMs.coerceIn(0, MAX_GLOBAL_PAUSE_DURATION_MS)
        editSettings {
            it[PAUSED_AT_EPOCH] = nowEpochMs
            it[PAUSED_AT_ELAPSED] = nowElapsedMs
            it[PAUSED_UNTIL] = nowEpochMs + safeDurationMs
            it[PAUSED_UNTIL_ELAPSED] = nowElapsedMs + safeDurationMs
        }
    }

    suspend fun resumeNow() = editSettings {
        it.remove(PAUSED_AT_EPOCH)
        it.remove(PAUSED_AT_ELAPSED)
        it.remove(PAUSED_UNTIL)
        it.remove(PAUSED_UNTIL_ELAPSED)
    }

    suspend fun setInterventionSeconds(seconds: Int) =
        editSettings { changeRules(it) { value -> value.copy(waitSeconds = seconds.coerceIn(3, 15)) } }

    suspend fun setTemporaryPassMinutes(minutes: Int) =
        editSettings { changeRules(it) { value -> value.copy(passMinutes = minutes.coerceIn(1, 30)) } }

    suspend fun setHistoryRetentionDays(days: Int) = editSettings {
        it[HISTORY_RETENTION_DAYS] = sanitizeHistoryRetentionDays(days)
    }

    suspend fun resetAll() = editSettings { it.clear(); it[RULE_REVISION] = UUID.randomUUID().toString() }

    val ruleAdjustments: Flow<RuleAdjustmentState> = dataStore.data.catch { reportFailure(it); throw it }.map { prefs ->
        RuleAdjustmentState(readRules(prefs), prefs[RULE_UNDO]?.let { runCatching { RuleUndoCodec.decode(it) }.getOrNull() })
    }

    suspend fun applyRuleChange(expected: RuleState, patch: RulePatch, sourceStillValid: () -> Boolean = { true }) {
        requireRuleHealth()
        var written: RuleState? = null
        editSettings { prefs ->
            requireRuleHealth()
            if (!sourceStillValid()) throw RuleConflictException("建议来源已失效，未修改设置。")
            val undo = RuleChangePlanner.apply(readRules(prefs), expected, patch, UUID.randomUUID().toString())
            writeRules(prefs, undo.after)
            prefs[RULE_UNDO] = RuleUndoCodec.encode(undo)
            written = undo.after
        }
        verifyRuleWrite(requireNotNull(written))
    }

    suspend fun undoRuleChange(expectedUndoId: String) {
        requireRuleHealth()
        var written: RuleState? = null
        editSettings { prefs ->
            requireRuleHealth()
            val undo = prefs[RULE_UNDO]?.let { runCatching { RuleUndoCodec.decode(it) }.getOrNull() }
            if (undo == null || undo.id != expectedUndoId) throw RuleConflictException("可撤销的修改已经变化，请重新查看。")
            val restored = RuleChangePlanner.undo(readRules(prefs), undo, UUID.randomUUID().toString())
            writeRules(prefs, restored); prefs.remove(RULE_UNDO); written = restored
        }
        verifyRuleWrite(requireNotNull(written))
    }

    private suspend fun verifyRuleWrite(expected: RuleState) {
        requireRuleHealth()
        if (readRules(dataStore.data.first()) != expected) throw RuleConflictException("保存后设置再次变化，请查看当前值；未回滚其他修改。")
    }

    private fun requireRuleHealth() {
        check(healthStore == null || healthStore.state.value == SettingsHealthState.Healthy) { "设置存储尚未就绪，请先处理设置恢复。" }
    }

    private fun readRules(prefs: Preferences) = RuleState(RuleValues(
        (prefs[INTERVENTION_SECONDS] ?: 6).coerceIn(3, 15), (prefs[PASS_MINUTES] ?: 5).coerceIn(1, 30),
        ScheduleSpec(prefs[SCHEDULE_ENABLED] ?: true, (prefs[START_MINUTES] ?: 0).coerceIn(0, 1439),
            (prefs[END_MINUTES] ?: 0).coerceIn(0, 1439), (prefs[ACTIVE_DAYS] ?: ScheduleSpec.ALL_DAYS).coerceIn(0, ScheduleSpec.ALL_DAYS))),
        prefs[RULE_REVISION].orEmpty(), prefs[WAIT_REVISION].orEmpty(), prefs[PASS_REVISION].orEmpty(), prefs[SCHEDULE_REVISION].orEmpty())

    private fun changeRules(prefs: MutablePreferences, transform: (RuleValues) -> RuleValues) {
        val current = readRules(prefs)
        writeRules(prefs, RuleChangePlanner.changed(current, transform(current.values), UUID.randomUUID().toString()))
    }

    private fun writeRules(prefs: MutablePreferences, state: RuleState) {
        val value = state.values
        prefs[INTERVENTION_SECONDS] = value.waitSeconds; prefs[PASS_MINUTES] = value.passMinutes
        prefs[SCHEDULE_ENABLED] = value.schedule.enabled; prefs[START_MINUTES] = value.schedule.startMinutes
        prefs[END_MINUTES] = value.schedule.endMinutes; prefs[ACTIVE_DAYS] = value.schedule.activeDaysMask
        prefs[RULE_REVISION] = state.revision; prefs[WAIT_REVISION] = state.waitRevision
        prefs[PASS_REVISION] = state.passRevision; prefs[SCHEDULE_REVISION] = state.scheduleRevision
    }

    internal fun close() = dataStoreScope.cancel()

    private suspend fun editSettings(transform: suspend (MutablePreferences) -> Unit) {
        try {
            dataStore.edit(transform)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            reportFailure(error)
            throw error
        }
    }

    private fun reportFailure(error: Throwable) {
        val reason = if (error is CorruptionException) {
            SettingsFailureReason.RECOVERY_COPY_FAILED
        } else if (error is IOException) {
            SettingsFailureReason.READ_OR_WRITE_FAILED
        } else {
            return
        }
        healthStore?.reportUnavailable(reason)
    }

    companion object {
        private const val DEFAULT_DATA_STORE_NAME = "pause_settings"
        private val DISCLOSURE_ACCEPTED = booleanPreferencesKey("disclosure_accepted")
        private val AGE_ELIGIBILITY_CONFIRMED = booleanPreferencesKey("age_eligibility_confirmed")
        private val ONBOARDING_PREVIEW_COMPLETED = booleanPreferencesKey("onboarding_preview_completed")
        private val SCHEDULE_ENABLED = booleanPreferencesKey("schedule_enabled")
        private val START_MINUTES = intPreferencesKey("start_minutes")
        private val END_MINUTES = intPreferencesKey("end_minutes")
        private val ACTIVE_DAYS = intPreferencesKey("active_days")
        private val PAUSED_AT_EPOCH = longPreferencesKey("paused_at_epoch")
        private val PAUSED_AT_ELAPSED = longPreferencesKey("paused_at_elapsed")
        private val PAUSED_UNTIL = longPreferencesKey("paused_until")
        private val PAUSED_UNTIL_ELAPSED = longPreferencesKey("paused_until_elapsed")
        private val INTERVENTION_SECONDS = intPreferencesKey("intervention_seconds")
        private val PASS_MINUTES = intPreferencesKey("pass_minutes")
        private val HISTORY_RETENTION_DAYS = intPreferencesKey("history_retention_days")
        private val RULE_REVISION = stringPreferencesKey("rule_revision")
        private val WAIT_REVISION = stringPreferencesKey("rule_wait_revision")
        private val PASS_REVISION = stringPreferencesKey("rule_pass_revision")
        private val SCHEDULE_REVISION = stringPreferencesKey("rule_schedule_revision")
        private val RULE_UNDO = stringPreferencesKey("rule_last_undo")
    }
}

internal fun sanitizedSettingsSnapshot(
    disclosureAccepted: Boolean,
    ageEligibilityConfirmed: Boolean,
    onboardingPreviewCompleted: Boolean,
    scheduleEnabled: Boolean,
    startMinutes: Int,
    endMinutes: Int,
    activeDaysMask: Int,
    globallyPausedUntilEpochMs: Long,
    interventionSeconds: Int,
    temporaryPassMinutes: Int,
    historyRetentionDays: Int,
    globallyPausedAtEpochMs: Long = 0,
    globallyPausedAtElapsedMs: Long = 0,
    globallyPausedUntilElapsedMs: Long = 0,
): SettingsSnapshot = SettingsSnapshot(
    disclosureAccepted = disclosureAccepted,
    ageEligibilityConfirmed = ageEligibilityConfirmed,
    onboardingPreviewCompleted = onboardingPreviewCompleted,
    schedule = ScheduleSpec(
        enabled = scheduleEnabled,
        startMinutes = startMinutes.coerceIn(0, 1439),
        endMinutes = endMinutes.coerceIn(0, 1439),
        activeDaysMask = activeDaysMask.coerceIn(0, ScheduleSpec.ALL_DAYS),
    ),
    globallyPausedUntilEpochMs = globallyPausedUntilEpochMs.coerceAtLeast(0),
    globallyPausedAtEpochMs = globallyPausedAtEpochMs.coerceAtLeast(0),
    globallyPausedAtElapsedMs = globallyPausedAtElapsedMs.coerceAtLeast(0),
    globallyPausedUntilElapsedMs = globallyPausedUntilElapsedMs.coerceAtLeast(0),
    interventionSeconds = interventionSeconds.coerceIn(3, 15),
    temporaryPassMinutes = temporaryPassMinutes.coerceIn(1, 30),
    historyRetentionDays = sanitizeHistoryRetentionDays(historyRetentionDays),
)

internal const val DEFAULT_HISTORY_RETENTION_DAYS = 90
internal val VALID_HISTORY_RETENTION_DAYS = setOf(30, 90, 365)
internal fun sanitizeHistoryRetentionDays(days: Int): Int =
    days.takeIf { it in VALID_HISTORY_RETENTION_DAYS } ?: DEFAULT_HISTORY_RETENTION_DAYS

private const val MAX_GLOBAL_PAUSE_DURATION_MS = 24L * 60 * 60_000
