package app.pausecn.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.pausecn.BuildConfig
import app.pausecn.PauseApplication
import app.pausecn.data.InterventionOutcome
import app.pausecn.data.DatabaseHealthState
import app.pausecn.data.SettingsSnapshot
import app.pausecn.data.SettingsHealthState
import app.pausecn.data.TargetRuleEntity
import app.pausecn.data.accessibilityEventPackages
import app.pausecn.data.isSafetyDismissalEvent
import app.pausecn.data.launcherPackages
import app.pausecn.data.safetyCooldownPackages
import app.pausecn.domain.InterventionContext
import app.pausecn.domain.InterventionDecision
import app.pausecn.domain.InterventionPolicy
import app.pausecn.domain.isDeviceReadyForIntervention
import app.pausecn.domain.WindowTransitionGate
import app.pausecn.domain.WindowDismissal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicReference

class PauseAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val targets = AtomicReference<Map<String, TargetRuleEntity>>(emptyMap())
    private val safetyPackages = AtomicReference<Set<String>>(emptySet())
    private val cooldownPackages = AtomicReference<Set<String>>(emptySet())
    private val windowTransitions = WindowTransitionGate()
    private val settings = AtomicReference(SettingsSnapshot())
    private val policy = InterventionPolicy()
    private var configurationCollectionJob: Job? = null
    private var heartbeatJob: Job? = null
    private var serviceSessionId: Deferred<Long>? = null
    private var activeHistoryEvent: Deferred<Long>? = null
    private lateinit var overlayController: InterventionOverlayController
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private var screenReceiverRegistered = false
    private var debugStressDismissReceiverRegistered = false
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && ::overlayController.isInitialized) {
                windowTransitions.blockPendingEvents(SystemClock.uptimeMillis())
                dismissOverlayWithoutChoice()
            }
        }
    }
    private val debugStressDismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_INTERNAL_DISMISS_STRESS_OVERLAY) return
            if (!::overlayController.isInitialized || !overlayController.isShowing) return
            if (BuildConfig.DEBUG) {
                Log.d(DEBUG_TAG, "dismiss overlay for stress probe iteration=${debugStressIteration()}")
            }
            container.sessionGate.grantSafetyCooldown(SAFETY_COOLDOWN_MS)
            dismissOverlayWithoutChoice(discardHistory = true)
        }
    }

    private val container get() = (application as PauseApplication).container

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenReceiverRegistered = true
        if (BuildConfig.DEBUG) {
            ContextCompat.registerReceiver(
                this,
                debugStressDismissReceiver,
                IntentFilter(ACTION_INTERNAL_DISMISS_STRESS_OVERLAY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            debugStressDismissReceiverRegistered = true
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlayController = InterventionOverlayController(this)
        recordDebugOverlayState(false)
        container.healthStore.markConnected()
        val initialCooldownPackages = safetyCooldownPackages(applicationContext)
        val initialSafetyPackages = initialCooldownPackages + launcherPackages(applicationContext)
        cooldownPackages.set(initialCooldownPackages)
        safetyPackages.set(initialSafetyPackages)
        updatePackageFilter(emptySet(), initialSafetyPackages)
        serviceSessionId?.cancel()
        serviceSessionId = createServiceSession()

        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                val heartbeatAtEpochMs = System.currentTimeMillis()
                val heartbeatAtElapsedMs = SystemClock.elapsedRealtime()
                container.healthStore.markHeartbeat(heartbeatAtEpochMs, heartbeatAtElapsedMs)
                try {
                    val session = serviceSessionId ?: createServiceSession().also { serviceSessionId = it }
                    if (!container.repository.recordServiceHeartbeat(
                            session.await(),
                            heartbeatAtEpochMs,
                            heartbeatAtElapsedMs,
                        )
                    ) {
                        Log.w(DEBUG_TAG, "Service-session heartbeat row was unavailable")
                        serviceSessionId = null
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(DEBUG_TAG, "Unable to persist service-session heartbeat", error)
                    serviceSessionId = null
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }

        configurationCollectionJob?.cancel()
        configurationCollectionJob = serviceScope.launch {
            while (isActive) {
                try {
                    combine(
                        container.databaseHealthStore.state,
                        container.settingsHealthStore.state,
                    ) { databaseHealth, settingsHealth ->
                        databaseHealth == DatabaseHealthState.Healthy &&
                            settingsHealth == SettingsHealthState.Healthy
                    }.distinctUntilChanged().collectLatest { localDataHealthy ->
                        if (!localDataHealthy) {
                            clearRuntimeConfigurationForFailure()
                            return@collectLatest
                        }

                        combine(
                            container.repository.enabledTargets,
                            container.settingsStore.settings,
                        ) { rules, snapshot -> rules to snapshot }
                            .collectLatest { (rules, snapshot) ->
                                val ruleMap = rules.associateBy { it.packageName }
                                val refreshedCooldownPackages = safetyCooldownPackages(applicationContext)
                                val refreshedSafetyPackages = refreshedCooldownPackages + launcherPackages(applicationContext)

                                // Publish a complete configuration before subscribing to target
                                // events. A target must never become observable with default or
                                // stale settings during service startup/recovery.
                                settings.set(snapshot)
                                targets.set(ruleMap)
                                safetyPackages.set(refreshedSafetyPackages)
                                cooldownPackages.set(refreshedCooldownPackages)
                                updatePackageFilter(ruleMap.keys, refreshedSafetyPackages)
                                if (BuildConfig.DEBUG) {
                                    // Only report the three apps explicitly involved in this diagnostic.
                                    Log.d(DEBUG_TAG, "target configuration " + DIAGNOSTIC_TARGETS.joinToString { pkg ->
                                        "$pkg=${ruleMap[pkg]?.enabled == true}"
                                    })
                                }
                            }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(DEBUG_TAG, "Configuration stream failed; retrying from a safe state", error)
                    clearRuntimeConfigurationForFailure()
                    delay(CONFIGURATION_RETRY_DELAY_MS)
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return
        if (!::overlayController.isInitialized) return
        if (container.databaseHealthStore.state.value != DatabaseHealthState.Healthy ||
            container.settingsHealthStore.state.value != SettingsHealthState.Healthy
        ) {
            dismissOverlayForUnavailableData()
            return
        }

        val packageName = event.packageName?.toString() ?: return
        val nowEpochMs = System.currentTimeMillis()
        val sourceEventUptimeMs = event.eventTime
        val nowUptimeMs = SystemClock.uptimeMillis()
        container.healthStore.recordWindowEvent(nowEpochMs)

        val safetyDismissalEvent = isSafetyDismissalEvent(packageName, safetyPackages.get())
        if (BuildConfig.DEBUG) {
            Log.d(
                DEBUG_TAG,
                "window type=${event.eventType} package=$packageName " +
                    "safety=$safetyDismissalEvent overlay=${overlayController.isShowing}",
            )
        }
        if (safetyDismissalEvent) {
            val dismissal = windowTransitions.onDismissal(
                sourceEventUptimeMs, nowUptimeMs, critical = packageName in cooldownPackages.get(),
            )
            if (dismissal == WindowDismissal.IGNORE) {
                if (BuildConfig.DEBUG) Log.d(DEBUG_TAG, "ignore stale launcher package=$packageName")
                return
            }
            if (dismissal == WindowDismissal.DISMISS_WITH_COOLDOWN) {
                container.sessionGate.grantSafetyCooldown(SAFETY_COOLDOWN_MS, nowEpochMs)
            }
            if (BuildConfig.DEBUG) Log.d(DEBUG_TAG, "safety action=$dismissal package=$packageName")
            if (BuildConfig.DEBUG && overlayController.isShowing) {
                Log.d(DEBUG_TAG, "dismiss overlay for safety package=$packageName")
            }
            if (overlayController.isShowing) {
                dismissOverlayWithoutChoice(discardHistory = true)
            }
            return
        }

        if (!isDeviceReadyForIntervention(powerManager.isInteractive, keyguardManager.isKeyguardLocked)) {
            dismissOverlayWithoutChoice()
            return
        }

        if (overlayController.isShowing) return
        val target = targets.get()[packageName] ?: return
        if (!windowTransitions.shouldEvaluateTarget(sourceEventUptimeMs, nowUptimeMs)) {
            if (BuildConfig.DEBUG) Log.d(DEBUG_TAG, "allow package=$packageName reason=STALE_WINDOW_EVENT")
            return
        }
        val currentSettings = settings.get()

        val now = LocalDateTime.now()
        val decision = policy.decide(
            InterventionContext(
                targetEnabled = target.enabled,
                setupComplete = currentSettings.disclosureAccepted &&
                    currentSettings.ageEligibilityConfirmed &&
                    currentSettings.onboardingPreviewCompleted,
                schedule = currentSettings.schedule,
                dayOfWeek = now.dayOfWeek,
                minuteOfDay = now.hour * 60 + now.minute,
                globallyPaused = currentSettings.isGloballyPaused(nowEpochMs),
                passValid = container.sessionGate.hasValidPass(packageName, nowEpochMs),
                exitCooldownActive = container.sessionGate.hasActiveExitCooldown(packageName, nowEpochMs),
                safetyCooldownActive = container.sessionGate.hasActiveSafetyCooldown(nowEpochMs),
                duplicateEvent = container.sessionGate.isDuplicate(packageName, nowEpochMs),
                protectedPackage = packageName in safetyPackages.get(),
            ),
        )

        if (decision is InterventionDecision.Allow) {
            if (BuildConfig.DEBUG) {
                Log.d(DEBUG_TAG, "allow package=$packageName reason=${decision.reason}")
            }
            return
        }
        container.sessionGate.markShown(packageName, nowEpochMs)
        val selectedPrompt = container.aiRepository.choose(packageName, nowEpochMs, SystemClock.elapsedRealtime())
        var displayHandle: app.pausecn.usage.PauseDisplayRecorder.Handle? = null
        val shown = try {
            overlayController.show(
                onAttached = {
                    if (!isDebugStressProbeActive()) displayHandle = container.displayRecorder.attached(packageName)
                },
                onDetached = {
                    displayHandle?.let(container.displayRecorder::detached)
                    displayHandle = null
                },
                appLabel = target.label,
                waitSeconds = currentSettings.interventionSeconds,
                promptText = selectedPrompt.text,
                reasonChoices = container.reasonMemoryCache.choices(packageName, nowEpochMs, currentSettings.historyRetentionDays),
                onExit = {
                    if (!isDebugStressProbeActive()) container.aiRepository.selector.complete(
                        packageName, false, System.currentTimeMillis(), SystemClock.elapsedRealtime())
                    recordDebugOverlayState(false)
                    completeActiveHistory(InterventionOutcome.EXITED)
                    container.sessionGate.grantExitCooldown(packageName, EXIT_COOLDOWN_MS)
                    if (!performGlobalAction(GLOBAL_ACTION_HOME)) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                },
                onContinue = { purpose ->
                    if (!isDebugStressProbeActive()) container.aiRepository.selector.complete(
                        packageName, true, System.currentTimeMillis(), SystemClock.elapsedRealtime())
                    recordDebugOverlayState(false)
                    completeActiveHistory(InterventionOutcome.CONTINUED, purpose)
                    container.sessionGate.grantPass(
                        packageName,
                        currentSettings.temporaryPassMinutes * 60_000L,
                    )
                },
            )
        } catch (error: Exception) {
            Log.e(DEBUG_TAG, "Unable to display intervention package=$packageName", error)
            recordDisplayFailure(
                packageName,
                target.label,
                currentSettings.historyRetentionDays,
                triggerLatencyMs(sourceEventUptimeMs),
            )
            recordDebugOverlayState(false)
            return
        }
        if (!shown) return
        if (!isDebugStressProbeActive()) container.aiRepository.shown(
            packageName, selectedPrompt, nowEpochMs, SystemClock.elapsedRealtime())
        if (!isDebugStressProbeActive()) {
            activeHistoryEvent = (application as PauseApplication).asyncProcessTask {
                container.repository.beginIntervention(
                    packageName = packageName,
                    appLabel = target.label,
                    retentionDays = currentSettings.historyRetentionDays,
                    nowEpochMs = nowEpochMs,
                    triggerLatencyMs = triggerLatencyMs(sourceEventUptimeMs),
                ).eventId
            }
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                DEBUG_TAG,
                "overlay shown package=$packageName iteration=${debugStressIteration()}",
            )
        }
        recordDebugOverlayState(true)
    }

    override fun onInterrupt() {
        windowTransitions.blockPendingEvents(SystemClock.uptimeMillis())
        dismissOverlayWithoutChoice()
    }

    override fun onDestroy() {
        if (::overlayController.isInitialized) overlayController.dismiss()
        completeActiveHistory(InterventionOutcome.DISMISSED)
        recordDebugOverlayState(false)
        if (screenReceiverRegistered) {
            unregisterReceiver(screenOffReceiver)
            screenReceiverRegistered = false
        }
        if (debugStressDismissReceiverRegistered) {
            unregisterReceiver(debugStressDismissReceiver)
            debugStressDismissReceiverRegistered = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updatePackageFilter(packageNames: Set<String>, currentSafetyPackages: Set<String>) {
        val filter = accessibilityEventPackages(
            targetPackages = packageNames,
            safetyPackages = currentSafetyPackages,
            ownPackage = applicationContext.packageName,
        ).sorted().toTypedArray()
        serviceInfo = serviceInfo.apply { this.packageNames = filter }
    }

    private fun clearRuntimeConfigurationForFailure() {
        val currentCooldownPackages = runCatching {
            safetyCooldownPackages(applicationContext)
        }.getOrElse { error ->
            Log.e(DEBUG_TAG, "Unable to refresh safety packages while disabling rules", error)
            emptySet()
        }
        val currentSafetyPackages = currentCooldownPackages + runCatching {
            launcherPackages(applicationContext)
        }.getOrDefault(emptySet())
        settings.set(SettingsSnapshot())
        targets.set(emptyMap())
        safetyPackages.set(currentSafetyPackages)
        cooldownPackages.set(currentCooldownPackages)
        runCatching { updatePackageFilter(emptySet(), currentSafetyPackages) }
            .onFailure { error ->
                Log.e(DEBUG_TAG, "Unable to narrow the accessibility package filter", error)
            }
        dismissOverlayForUnavailableData()
    }

    private fun dismissOverlayWithoutChoice(discardHistory: Boolean = false) {
        if (!::overlayController.isInitialized || !overlayController.isShowing) return
        overlayController.dismiss()
        if (discardHistory) {
            discardActiveHistory()
        } else {
            completeActiveHistory(InterventionOutcome.DISMISSED)
        }
        recordDebugOverlayState(false)
    }

    private fun dismissOverlayForUnavailableData() {
        if (container.databaseHealthStore.state.value == DatabaseHealthState.Healthy) {
            // Settings can fail independently while Room remains writable. Finish the already
            // visible attempt instead of leaving a permanent SHOWN row in the current process.
            dismissOverlayWithoutChoice()
        } else {
            dismissOverlayForDatabaseFailure()
        }
    }

    private fun dismissOverlayForDatabaseFailure() {
        if (::overlayController.isInitialized && overlayController.isShowing) {
            overlayController.dismiss()
            recordDebugOverlayState(false)
        }
        activeHistoryEvent?.cancel()
        activeHistoryEvent = null
    }

    private fun discardActiveHistory() {
        val historyEvent = activeHistoryEvent ?: return
        activeHistoryEvent = null
        (application as PauseApplication).launchProcessTask {
            try {
                container.repository.discardIntervention(historyEvent.await())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(DEBUG_TAG, "Unable to discard safety-dismissed history session", error)
            }
        }
    }

    private fun completeActiveHistory(outcome: InterventionOutcome, purpose: String? = null) {
        val historyEvent = activeHistoryEvent ?: return
        activeHistoryEvent = null
        (application as PauseApplication).launchProcessTask {
            try {
                val eventId = historyEvent.await()
                val updated = container.repository.completeIntervention(eventId, outcome, purpose)
                if (!updated) Log.w(DEBUG_TAG, "History session was already completed outcome=$outcome")
                if (updated) {
                    try { container.aiRepository.onCompletedIntervention(eventId) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { Log.w(DEBUG_TAG, "Unable to queue optional personalized reminder") }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(DEBUG_TAG, "Unable to complete history session outcome=$outcome", error)
            }
        }
    }

    private fun recordDisplayFailure(
        packageName: String,
        appLabel: String,
        retentionDays: Int,
        triggerLatencyMs: Long?,
    ) {
        if (isDebugStressProbeActive()) return
        (application as PauseApplication).launchProcessTask {
            try {
                container.repository.recordIntervention(
                    packageName = packageName,
                    appLabel = appLabel,
                    outcome = InterventionOutcome.DISPLAY_FAILED,
                    retentionDays = retentionDays,
                    triggerLatencyMs = triggerLatencyMs,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(DEBUG_TAG, "Unable to record display failure package=$packageName", error)
            }
        }
    }

    private fun createServiceSession(): Deferred<Long> = serviceScope.async(Dispatchers.IO) {
        combine(
            container.databaseHealthStore.state,
            container.settingsHealthStore.state,
        ) { databaseHealth, settingsHealth ->
            databaseHealth == DatabaseHealthState.Healthy &&
                settingsHealth == SettingsHealthState.Healthy
        }.first { it }
        val retentionDays = container.settingsStore.settings.first().historyRetentionDays
        container.repository.beginServiceSession(
            retentionDays = retentionDays,
            nowEpochMs = System.currentTimeMillis(),
            nowElapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    private fun triggerLatencyMs(sourceEventUptimeMs: Long): Long? {
        if (sourceEventUptimeMs <= 0L) return null
        return (SystemClock.uptimeMillis() - sourceEventUptimeMs).takeIf { it >= 0L }
    }

    private fun isDebugStressProbeActive(): Boolean = BuildConfig.DEBUG && debugStressIteration() >= 0

    private fun recordDebugOverlayState(showing: Boolean) {
        if (!BuildConfig.DEBUG) return
        getSharedPreferences(DEBUG_PROBE_PREFERENCES, Context.MODE_PRIVATE).edit {
            putBoolean(DEBUG_KEY_OVERLAY_VISIBLE, showing)
        }
    }

    private fun debugStressIteration(): Int =
        getSharedPreferences(DEBUG_PROBE_PREFERENCES, Context.MODE_PRIVATE)
            .getInt(DEBUG_KEY_STRESS_ITERATION, -1)

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 15 * 60_000L
        private const val CONFIGURATION_RETRY_DELAY_MS = 1_000L
        private const val EXIT_COOLDOWN_MS = 8_000L
        private const val SAFETY_COOLDOWN_MS = 3_000L
        private const val DEBUG_TAG = "PauseSafetyProbe"
        private val DIAGNOSTIC_TARGETS = listOf("gov.pianzong.androidnga", "cn.damai", "com.xingin.xhs")
        private const val DEBUG_PROBE_PREFERENCES = "debug_probe"
        private const val DEBUG_KEY_OVERLAY_VISIBLE = "stress_overlay_visible"
        private const val DEBUG_KEY_STRESS_ITERATION = "stress_iteration"
        private const val ACTION_INTERNAL_DISMISS_STRESS_OVERLAY =
            "app.pausecn.debug.INTERNAL_DISMISS_STRESS_OVERLAY"
    }
}
