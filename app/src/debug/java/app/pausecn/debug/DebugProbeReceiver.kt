package app.pausecn.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.pausecn.PauseApplication
import app.pausecn.data.InstalledApp
import app.pausecn.data.SettingsFailureReason
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DebugProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        when (intent.action) {
            ACTION_PREPARE_STRESS_ITERATION -> {
                val container = (context.applicationContext as PauseApplication).container
                container.sessionGate.clearAll()
                preferences.edit(commit = true) {
                    putInt(KEY_STRESS_ITERATION, intent.getIntExtra(EXTRA_STRESS_ITERATION, -1))
                }
                return
            }

            ACTION_DISMISS_STRESS_OVERLAY -> {
                context.sendBroadcast(
                    Intent(ACTION_INTERNAL_DISMISS_STRESS_OVERLAY)
                        .setPackage(context.packageName),
                )
                return
            }
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
            runCatching {
                val container = (context.applicationContext as PauseApplication).container
                when (intent.action) {
                    ACTION_SEED -> {
                        preferences.edit(commit = true) {
                            clear()
                            putBoolean(KEY_STRESS_OVERLAY_VISIBLE, false)
                        }
                        val packageName = requireNotNull(intent.getStringExtra(EXTRA_TARGET_PACKAGE))
                        val label = intent.getStringExtra(EXTRA_TARGET_LABEL).orEmpty().ifBlank { packageName }
                        container.repository.clearTargetsAndHistory()
                        container.settingsStore.resetAll()
                        container.sessionGate.clearAll()
                        container.healthStore.clearAll()
                        container.settingsStore.acceptDisclosureAndAgeEligibility()
                        container.settingsStore.completeOnboardingPreview()
                        container.repository.setTarget(InstalledApp(packageName, label), true)
                    }

                    ACTION_SNAPSHOT -> {
                        val recentEvents = container.repository.recentEvents.first()
                        val historyCount = recentEvents.size
                        val targets = container.repository.targets.first()
                        val settings = container.settingsStore.settings.first()
                        preferences.edit(commit = true) {
                            putInt(KEY_HISTORY_COUNT, historyCount)
                            putString(KEY_HISTORY_OUTCOMES, recentEvents.joinToString(",") { it.outcome })
                            putInt(KEY_TARGET_COUNT, targets.size)
                            putString(KEY_TARGET_PACKAGES, targets.joinToString(",") { it.packageName })
                            putBoolean(KEY_DISCLOSURE_ACCEPTED, settings.disclosureAccepted)
                            putBoolean(KEY_AGE_CONFIRMED, settings.ageEligibilityConfirmed)
                            putBoolean(KEY_PREVIEW_COMPLETED, settings.onboardingPreviewCompleted)
                        }
                    }

                    ACTION_RESET -> {
                        container.repository.clearTargetsAndHistory()
                        container.settingsStore.resetAll()
                        container.sessionGate.clearAll()
                        container.healthStore.clearAll()
                        preferences.edit(commit = true) {
                            remove(KEY_STRESS_ITERATION)
                            putBoolean(KEY_STRESS_OVERLAY_VISIBLE, false)
                        }
                    }

                    ACTION_INJECT_SETTINGS_FAILURE -> {
                        container.settingsHealthStore.reportUnavailable(
                            SettingsFailureReason.READ_OR_WRITE_FAILED,
                        )
                    }

                    ACTION_RESTORE_SETTINGS_HEALTH -> {
                        container.settingsHealthStore.markHealthy()
                    }

                    else -> error("Unsupported debug probe action: ${intent.action}")
                }
            }.onSuccess {
                preferences.edit(commit = true) {
                    putString(KEY_REQUEST_ID, requestId)
                    putBoolean(KEY_SUCCESS, true)
                    remove(KEY_ERROR)
                }
            }.onFailure { throwable ->
                preferences.edit(commit = true) {
                    putString(KEY_REQUEST_ID, requestId)
                    putBoolean(KEY_SUCCESS, false)
                    putString(KEY_ERROR, throwable.message ?: throwable.javaClass.name)
                }
            }
            pendingResult.finish()
        }
    }

    companion object {
        const val ACTION_SEED = "app.pausecn.debug.SEED_SAFETY_SCENARIO"
        const val ACTION_SNAPSHOT = "app.pausecn.debug.SNAPSHOT_SAFETY_SCENARIO"
        const val ACTION_PREPARE_STRESS_ITERATION =
            "app.pausecn.debug.PREPARE_STRESS_ITERATION"
        const val ACTION_DISMISS_STRESS_OVERLAY =
            "app.pausecn.debug.DISMISS_STRESS_OVERLAY"
        const val ACTION_INTERNAL_DISMISS_STRESS_OVERLAY =
            "app.pausecn.debug.INTERNAL_DISMISS_STRESS_OVERLAY"
        const val ACTION_RESET = "app.pausecn.debug.RESET_SAFETY_SCENARIO"
        const val ACTION_INJECT_SETTINGS_FAILURE =
            "app.pausecn.debug.INJECT_SETTINGS_FAILURE"
        const val ACTION_RESTORE_SETTINGS_HEALTH =
            "app.pausecn.debug.RESTORE_SETTINGS_HEALTH"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_TARGET_LABEL = "target_label"
        const val EXTRA_STRESS_ITERATION = "stress_iteration"
        const val PREFERENCES = "debug_probe"
        const val KEY_REQUEST_ID = "request_id"
        const val KEY_SUCCESS = "success"
        const val KEY_ERROR = "error"
        const val KEY_HISTORY_COUNT = "history_count"
        const val KEY_HISTORY_OUTCOMES = "history_outcomes"
        const val KEY_TARGET_COUNT = "target_count"
        const val KEY_TARGET_PACKAGES = "target_packages"
        const val KEY_DISCLOSURE_ACCEPTED = "disclosure_accepted"
        const val KEY_AGE_CONFIRMED = "age_confirmed"
        const val KEY_PREVIEW_COMPLETED = "preview_completed"
        const val KEY_STRESS_OVERLAY_VISIBLE = "stress_overlay_visible"
        const val KEY_STRESS_ITERATION = "stress_iteration"
    }
}
