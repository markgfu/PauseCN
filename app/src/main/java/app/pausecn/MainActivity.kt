package app.pausecn

import android.app.TimePickerDialog
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.pausecn.data.InstalledApp
import app.pausecn.data.InterventionEventEntity
import app.pausecn.data.InterventionOutcome
import app.pausecn.data.hasMinimumExportPassphraseLength
import app.pausecn.data.DatabaseFailureReason
import app.pausecn.data.DatabaseHealthState
import app.pausecn.data.SettingsSnapshot
import app.pausecn.data.ServiceHeartbeatSnapshot
import app.pausecn.data.SettingsFailureReason
import app.pausecn.data.SettingsHealthState
import app.pausecn.data.StatsSnapshot
import app.pausecn.data.TargetRuleEntity
import app.pausecn.domain.INTERVENTION_PURPOSES
import app.pausecn.domain.InterventionAvailability
import app.pausecn.domain.ServiceHealth
import app.pausecn.domain.evaluateInterventionAvailability
import app.pausecn.domain.nextScheduleActivationEpochMs
import app.pausecn.domain.evaluateServiceHealth
import app.pausecn.platform.DeviceStatus
import app.pausecn.platform.ExternalUriOpenResult
import app.pausecn.platform.currentOemGuide
import app.pausecn.platform.OemSettingsOpenResult
import app.pausecn.platform.SystemSettingsOpenResult
import app.pausecn.platform.openAccessibilitySettings
import app.pausecn.platform.openAppDetails
import app.pausecn.platform.openBatteryOptimizationSettings
import app.pausecn.platform.openOemBackgroundSettings
import app.pausecn.platform.openHttpsUri
import app.pausecn.platform.readDeviceStatus
import app.pausecn.ui.Clay
import app.pausecn.ui.Ink
import app.pausecn.ui.Line
import app.pausecn.ui.Muted
import app.pausecn.ui.Paper
import app.pausecn.ui.PauseTheme
import app.pausecn.ui.Sage
import app.pausecn.ui.SageSoft
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory((application as PauseApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PauseTheme {
                PauseApp(viewModel)
            }
        }
    }
}

private enum class MainTab(val label: String, val glyph: String) {
    TODAY("今天", "◉"),
    TARGETS("目标", "◎"),
    AI("AI陪伴", "✦"),
    STATS("记录", "▥"),
    SETTINGS("设置", "◇"),
}

private enum class DemoStep {
    WAITING,
    PURPOSE,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PauseApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(MainTab.TODAY) }
    var targetSearchQuery by rememberSaveable { mutableStateOf("") }
    val databaseHealth by viewModel.databaseHealth.collectAsStateWithLifecycle()
    if (databaseHealth != DatabaseHealthState.Healthy) {
        DatabaseRecoveryScreen(
            state = databaseHealth,
            onRetry = viewModel::recheckDatabase,
        )
        return
    }
    val settingsHealth by viewModel.settingsHealth.collectAsStateWithLifecycle()
    if (settingsHealth != SettingsHealthState.Healthy) {
        SettingsRecoveryScreen(
            state = settingsHealth,
            onRetry = viewModel::recheckSettings,
            onConfirmSafeDefaults = viewModel::confirmRecoveredSettings,
        )
        return
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val targets by viewModel.targets.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val appCategories by viewModel.appCategories.collectAsStateWithLifecycle()
    val categoryCounts by viewModel.categoryCounts.collectAsStateWithLifecycle()
    val loadingApps by viewModel.loadingApps.collectAsStateWithLifecycle()
    val selectingAllTargets by viewModel.selectingAllTargets.collectAsStateWithLifecycle()
    val reasonMemories by viewModel.reasonMemories.collectAsStateWithLifecycle()
    val appsLoadFailed by viewModel.appsLoadFailed.collectAsStateWithLifecycle()
    val hasLoadedAppsSuccessfully by viewModel.hasLoadedAppsSuccessfully.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val recentEvents by viewModel.recentEvents.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val localDataActionState by viewModel.localDataActionState.collectAsStateWithLifecycle()
    var showDisclosure by remember { mutableStateOf(false) }
    var showPermissions by rememberSaveable { mutableStateOf(false) }
    var disclosureSaving by remember { mutableStateOf(false) }
    var showDemo by remember { mutableStateOf(false) }
    var showAiSettings by remember { mutableStateOf(false) }
    var showAppCategories by remember { mutableStateOf(false) }
    var showReasonMemory by remember { mutableStateOf(false) }
    var aiNavigationGuard by remember { mutableStateOf<app.pausecn.ai.AiNavigationGuard?>(null) }
    val registerAiNavigationGuard = remember { { guard: app.pausecn.ai.AiNavigationGuard? -> aiNavigationGuard = guard } }
    var showUsage by remember { mutableStateOf(false) }
    var showReports by remember { mutableStateOf(false) }
    var showReportShare by remember { mutableStateOf(false) }
    var showRuleAdjustment by remember { mutableStateOf(false) }
    val aiRepository = remember(context) { (context.applicationContext as PauseApplication).container.aiRepository }
    val uiScope = rememberCoroutineScope()
    var deviceStatus by remember { mutableStateOf(readDeviceStatus(context)) }
    var lastHeartbeat by remember { mutableStateOf(viewModel.lastServiceHeartbeat()) }
    var serviceStartObservation by remember { mutableStateOf(viewModel.serviceStartObservation()) }
    var nowEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var nowElapsedMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var currentBootCount by remember { mutableIntStateOf(viewModel.currentBootCount()) }
    val serviceHealth = evaluateServiceHealth(
        accessibilityEnabled = deviceStatus.accessibilityEnabled,
        lastHeartbeatEpochMs = lastHeartbeat.epochMs,
        lastHeartbeatElapsedMs = lastHeartbeat.elapsedMs,
        lastHeartbeatBootCount = lastHeartbeat.bootCount,
        enabledObservedEpochMs = serviceStartObservation.epochMs,
        enabledObservedElapsedMs = serviceStartObservation.elapsedMs,
        enabledObservedBootCount = serviceStartObservation.bootCount,
        nowEpochMs = nowEpochMs,
        nowElapsedMs = nowElapsedMs,
        currentBootCount = currentBootCount,
    )
    val eligibilityAndDisclosureAccepted = settings.disclosureAccepted && settings.ageEligibilityConfirmed
    val installedPackages = remember(installedApps) { installedApps.mapTo(hashSetOf()) { it.packageName } }
    val effectiveTargetCount = remember(targets, installedPackages, hasLoadedAppsSuccessfully) {
        if (hasLoadedAppsSuccessfully) {
            targets.count { it.packageName in installedPackages }
        } else {
            targets.size
        }
    }
    val currentZoneId = ZoneId.systemDefault()
    val now = remember(nowEpochMs, currentZoneId) {
        Instant.ofEpochMilli(nowEpochMs).atZone(currentZoneId)
    }
    val availability = evaluateInterventionAvailability(
        disclosureAccepted = settings.disclosureAccepted,
        ageEligibilityConfirmed = settings.ageEligibilityConfirmed,
        onboardingPreviewCompleted = settings.onboardingPreviewCompleted,
        accessibilityEnabled = deviceStatus.accessibilityEnabled,
        targetCount = effectiveTargetCount,
        serviceHealth = serviceHealth,
        globallyPaused = settings.isGloballyPaused(nowEpochMs),
        schedule = settings.schedule,
        dayOfWeek = now.dayOfWeek,
        minuteOfDay = now.hour * 60 + now.minute,
    )
    val scheduleClockMinute = Math.floorDiv(nowEpochMs, 60_000L)
    val nextActivationEpochMs = remember(
        settings.schedule,
        scheduleClockMinute,
        currentZoneId,
        availability,
    ) {
        if (availability == InterventionAvailability.OUTSIDE_SCHEDULE) {
            nextScheduleActivationEpochMs(
                schedule = settings.schedule,
                nowEpochMs = nowEpochMs,
                zoneId = currentZoneId,
            )
        } else {
            null
        }
    }
    val nextScheduleActivationLabel = remember(
        nextActivationEpochMs,
        nowEpochMs,
        currentZoneId,
    ) {
        nextActivationEpochMs?.let {
            formatNextScheduleActivation(it, nowEpochMs, currentZoneId)
        }
    }
    val serviceStatusLabel = when (serviceHealth) {
        ServiceHealth.HEALTHY -> "服务正常"
        ServiceHealth.STARTING -> "连接中"
        ServiceHealth.DISABLED -> "未开启"
        ServiceHealth.STALE -> "需修复"
    }
    val serviceStatusDescription = when (serviceHealth) {
        ServiceHealth.HEALTHY -> "打开前干预服务正常"
        ServiceHealth.STARTING -> "打开前干预服务正在连接"
        ServiceHealth.DISABLED -> "打开前干预服务未开启"
        ServiceHealth.STALE -> "打开前干预服务需要修复"
    }
    val serviceStatusColor = when (serviceHealth) {
        ServiceHealth.HEALTHY -> Sage
        ServiceHealth.STARTING -> Color(0xFFA66B00)
        ServiceHealth.DISABLED, ServiceHealth.STALE -> Clay
    }
    val serviceStatusBackground = when (serviceHealth) {
        ServiceHealth.HEALTHY -> SageSoft
        ServiceHealth.STARTING -> Color(0xFFFFF4D8)
        ServiceHealth.DISABLED, ServiceHealth.STALE -> Color(0xFFFFE9E1)
    }
    val openOemSettingsWithFeedback = {
        val message = when (openOemBackgroundSettings(context)) {
            OemSettingsOpenResult.OEM_PANEL -> "已打开厂商后台管理，请按页面步骤完成设置"
            OemSettingsOpenResult.BATTERY_OPTIMIZATION_LIST ->
                "未找到厂商专用入口，已打开系统电池优化列表"
            OemSettingsOpenResult.APP_DETAILS ->
                "未找到后台管理入口，已打开应用系统设置"
            OemSettingsOpenResult.GENERAL_SETTINGS ->
                "未找到专用入口，已打开系统设置"
            OemSettingsOpenResult.UNAVAILABLE ->
                "这台设备无法打开后台设置，请按页面步骤手动查找"
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    val openAccessibilitySettingsWithFeedback = {
        when (openAccessibilitySettings(context)) {
            SystemSettingsOpenResult.REQUESTED_PAGE -> Unit
            SystemSettingsOpenResult.APP_DETAILS ->
                Toast.makeText(context, "未找到无障碍页面，已打开应用系统设置", Toast.LENGTH_LONG).show()
            SystemSettingsOpenResult.GENERAL_SETTINGS ->
                Toast.makeText(context, "未找到无障碍专用页面，已打开系统设置，请搜索“无障碍”", Toast.LENGTH_LONG).show()
            SystemSettingsOpenResult.UNAVAILABLE ->
                Toast.makeText(context, "无法打开系统设置，请手动进入设置并搜索“无障碍”", Toast.LENGTH_LONG).show()
        }
    }
    val openBatterySettingsWithFeedback = {
        when (openBatteryOptimizationSettings(context)) {
            SystemSettingsOpenResult.REQUESTED_PAGE -> Unit
            SystemSettingsOpenResult.APP_DETAILS ->
                Toast.makeText(context, "未找到电池优化列表，已打开应用系统设置", Toast.LENGTH_LONG).show()
            SystemSettingsOpenResult.GENERAL_SETTINGS ->
                Toast.makeText(context, "未找到电池优化入口，已打开系统设置", Toast.LENGTH_LONG).show()
            SystemSettingsOpenResult.UNAVAILABLE ->
                Toast.makeText(context, "无法打开电池设置，请按页面步骤手动查找", Toast.LENGTH_LONG).show()
        }
    }
    val openAppDetailsWithFeedback = {
        when (openAppDetails(context)) {
            SystemSettingsOpenResult.REQUESTED_PAGE -> Unit
            SystemSettingsOpenResult.APP_DETAILS -> Unit
            SystemSettingsOpenResult.GENERAL_SETTINGS ->
                Toast.makeText(context, "未找到应用详情页，已打开系统设置", Toast.LENGTH_LONG).show()
            SystemSettingsOpenResult.UNAVAILABLE ->
                Toast.makeText(context, "无法打开应用系统设置，请手动进入系统设置查找“停一下”", Toast.LENGTH_LONG).show()
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val passphrase = viewModel.takePendingExportPassphrase()
        val exportOptions = viewModel.takePendingExportOptions()
        if (uri != null && passphrase != null) {
            viewModel.exportLocalData(uri, passphrase, exportOptions)
        } else {
            passphrase?.fill('\u0000')
            if (uri != null) {
                Toast.makeText(context, "导出已取消，请重新输入密码", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(exportState.message) {
        exportState.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeExportMessage()
        }
    }

    LaunchedEffect(localDataActionState.message) {
        localDataActionState.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeLocalDataActionMessage()
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refreshInstalledApps()
            awaitCancellation()
        }
    }

    LaunchedEffect(lifecycleOwner, context, settings) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                nowEpochMs = System.currentTimeMillis()
                nowElapsedMs = SystemClock.elapsedRealtime()
                currentBootCount = viewModel.currentBootCount()
                deviceStatus = readDeviceStatus(context)
                viewModel.observeAccessibilityState(
                    enabled = deviceStatus.accessibilityEnabled,
                    nowEpochMs = nowEpochMs,
                    nowElapsedMs = nowElapsedMs,
                    bootCount = currentBootCount,
                )
                lastHeartbeat = viewModel.lastServiceHeartbeat()
                serviceStartObservation = viewModel.serviceStartObservation()
                delay(nextUiStatusRefreshDelayMs(settings, nowEpochMs, nowElapsedMs))
            }
        }
    }

    Scaffold(
        containerColor = Paper,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "停一下",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    Row(
                        Modifier
                            .padding(end = 12.dp)
                            .heightIn(min = 32.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(serviceStatusBackground)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .semantics(mergeDescendants = true) {
                                contentDescription = serviceStatusDescription
                            },
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(serviceStatusColor),
                        )
                        Text(
                            serviceStatusLabel,
                            color = Ink,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper),
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFFFFFDF8),
                tonalElevation = 0.dp,
                modifier = Modifier.navigationBarsPadding(),
            ) {
                MainTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = {
                            if (tab != item) {
                                showPermissions = false
                                val navigate = { tab = item }
                                if (tab == MainTab.AI) aiNavigationGuard?.invoke(navigate) ?: navigate()
                                else navigate()
                            }
                        },
                        icon = { Text(item.glyph, fontSize = 18.sp) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (showPermissions) app.pausecn.ui.PermissionSetupScreen(
                (context.applicationContext as PauseApplication).container,
                eligibilityAndDisclosureAccepted, deviceStatus.accessibilityEnabled, settings.historyRetentionDays,
                onAccessibility = {
                    if (!eligibilityAndDisclosureAccepted) showDisclosure = true
                    else openAccessibilitySettingsWithFeedback()
                }, onBack = { showPermissions = false }) else when (tab) {
                MainTab.TODAY -> TodayScreen(
                    status = deviceStatus,
                    settings = settings,
                    targetCount = effectiveTargetCount,
                    stats = stats,
                    lastConnection = viewModel.lastServiceConnection(),
                    availability = availability,
                    nextScheduleActivationLabel = nextScheduleActivationLabel,
                    onSetup = {
                        if (!eligibilityAndDisclosureAccepted) showDisclosure = true
                        else showPermissions = true
                    },
                    onChooseTargets = { tab = MainTab.TARGETS },
                    onPause = viewModel::pauseFor15Minutes,
                    onResume = viewModel::resumeNow,
                    onDemo = { showDemo = true },
                    onRepair = openAccessibilitySettingsWithFeedback,
                    onRepairBackground = openOemSettingsWithFeedback,
                    onSchedule = { tab = MainTab.SETTINGS },
                )
                MainTab.TARGETS -> if (showAppCategories) app.pausecn.ui.AppCategoryScreen(
                    (context.applicationContext as PauseApplication).container.appCategories,
                    (installedApps + targets.filter { target -> installedApps.none { it.packageName == target.packageName } }
                        .map { InstalledApp(it.packageName, it.label, false) }).distinctBy { it.packageName },
                    appCategories, (context.applicationContext as PauseApplication).container.aiRepository,
                    onBack = { showAppCategories = false }) else TargetsScreen(
                    installedApps = installedApps,
                    targets = targets,
                    query = targetSearchQuery,
                    loading = loadingApps,
                    loadFailed = appsLoadFailed,
                    catalogAuthoritative = hasLoadedAppsSuccessfully,
                    loadIcon = viewModel::loadAppIcon,
                    onToggle = viewModel::setTarget,
                    onSelectAll = viewModel::selectTargets,
                    onDeselectAll = viewModel::deselectTargets,
                    selectingAll = selectingAllTargets,
                    categories = appCategories,
                    onCategories = { showAppCategories = true },
                    onQueryChanged = { targetSearchQuery = it },
                    onRefresh = viewModel::refreshInstalledApps,
                )
                MainTab.AI -> app.pausecn.ai.AiCompanionScreen(
                    (context.applicationContext as PauseApplication).container, settings.historyRetentionDays,
                    onBack = { tab = MainTab.TODAY }, onPreview = { showDemo = true },
                    onNavigationGuard = registerAiNavigationGuard)
                MainTab.STATS -> if (showReportShare) app.pausecn.reports.ReportShareEntryScreen(
                    (context.applicationContext as PauseApplication).container, settings.historyRetentionDays,
                    onBack = { showReportShare = false }) else if (showReports) app.pausecn.reports.ReportScreen(
                    (context.applicationContext as PauseApplication).container, settings.historyRetentionDays,
                    onBack = { showReports = false }) else if (showUsage) app.pausecn.usage.UsageScreen(
                    (context.applicationContext as PauseApplication).container, targets, settings.historyRetentionDays,
                    onBack = { showUsage = false }) else StatsScreen(stats, recentEvents,
                    onConversation = { tab = MainTab.AI }, onUsage = { showUsage = true }, onReports = { showReports = true },
                    onShare = { showReportShare = true }, categoryCounts = categoryCounts)
                MainTab.SETTINGS -> if (showRuleAdjustment) app.pausecn.ui.RuleAdjustmentScreen(
                    (context.applicationContext as PauseApplication).container.settingsStore,
                    onBack = { showRuleAdjustment = false }) else if (showReasonMemory) app.pausecn.ui.ReasonMemoryScreen(
                    rows = reasonMemories,
                    retentionDays = settings.historyRetentionDays,
                    onBack = { showReasonMemory = false },
                    onForget = viewModel::forgetReason,
                    onForgetAll = viewModel::forgetAllReasons,
                ) else if (showAiSettings) app.pausecn.ai.AiSettingsScreen(
                    repository = aiRepository,
                    onBack = { showAiSettings = false },
                    onPreview = { showDemo = true },
                ) else SettingsScreen(
                    onRuleAdjustment = { showRuleAdjustment = true },
                    onUsage = { tab = MainTab.STATS; showUsage = true; showReports = false; showReportShare = false },
                    onAiSettings = { showAiSettings = true },
                    onReasonMemory = { showReasonMemory = true },
                    settings = settings,
                    status = deviceStatus,
                    onScheduleEnabled = viewModel::setScheduleEnabled,
                    onWindowChanged = viewModel::setScheduleWindow,
                    onDaysChanged = viewModel::setActiveDays,
                    onSecondsChanged = viewModel::setInterventionSeconds,
                    onPassChanged = viewModel::setTemporaryPassMinutes,
                    onRetentionChanged = viewModel::setHistoryRetentionDays,
                    onAccessibility = {
                        if (!eligibilityAndDisclosureAccepted) showDisclosure = true
                        else showPermissions = true
                    },
                    onBattery = openBatterySettingsWithFeedback,
                    onOemSettings = openOemSettingsWithFeedback,
                    onAppDetails = openAppDetailsWithFeedback,
                    onClearHistory = viewModel::clearHistory,
                    localDataActionInProgress = localDataActionState.inProgress,
                    exportInProgress = exportState.inProgress,
                    onExportData = { passphrase, options ->
                        viewModel.queuePendingExportPassphrase(passphrase, options)
                        try {
                            exportLauncher.launch("停一下-加密数据-${LocalDate.now()}.pausecn.json")
                        } catch (_: Exception) {
                            viewModel.discardPendingExportPassphrase()
                            Toast.makeText(context, "无法打开保存位置，请稍后重试", Toast.LENGTH_LONG).show()
                        }
                    },
                    onResetAllData = {
                        lastHeartbeat = ServiceHeartbeatSnapshot()
                        viewModel.resetAllLocalData()
                    },
                )
            }
        }
    }

    if (showDisclosure) {
        AccessibilityDisclosureDialog(
            saving = disclosureSaving,
            onDismiss = {
                if (!disclosureSaving) showDisclosure = false
            },
            onAccept = {
                if (!disclosureSaving) {
                    disclosureSaving = true
                    uiScope.launch {
                        if (viewModel.acceptDisclosureAndAgeEligibility()) {
                            disclosureSaving = false
                            showDisclosure = false
                            showPermissions = true
                        } else {
                            disclosureSaving = false
                            Toast.makeText(
                                context,
                                "未能保存确认，尚未打开系统权限设置，请重试",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            },
        )
    }
    if (showDemo) {
        DemoIntervention(
            waitSeconds = settings.interventionSeconds,
            promptText = remember { aiRepository.choose("preview", System.currentTimeMillis(), SystemClock.elapsedRealtime()).text },
            onDismiss = { showDemo = false },
            onComplete = {
                showDemo = false
                viewModel.completeOnboardingPreview()
            },
        )
    }
    if (localDataActionState.inProgress || exportState.inProgress) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(if (exportState.inProgress) "正在加密导出" else "正在处理本地数据")
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (exportState.inProgress) {
                            "正在生成并核对加密文件，请勿离开。"
                        } else {
                            "正在完成删除并核对结果，请勿离开。"
                        },
                    )
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun DatabaseRecoveryScreen(
    state: DatabaseHealthState,
    onRetry: () -> Unit,
) {
    val checking = state is DatabaseHealthState.Checking
    val failure = when (state) {
        is DatabaseHealthState.Checking -> state.previousFailure
        is DatabaseHealthState.Unavailable -> state
        DatabaseHealthState.Healthy -> null
    }
    Scaffold(
        containerColor = Paper,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "停一下",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Sage,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = if (checking) "正在检查本地数据…" else "本地数据需要保护",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Ink,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (checking) {
                    "完整性确认完成前，目标规则和干预记录暂不读取，无障碍停顿也不会运行。"
                } else {
                    when (failure?.reason) {
                        DatabaseFailureReason.CORRUPTION_DETECTED ->
                            "系统报告本地数据库可能损坏。应用已停止使用该文件，并保留原文件，没有自动重置或清空。"
                        DatabaseFailureReason.INTEGRITY_CHECK_FAILED ->
                            "本地数据库没有通过完整性检查。应用已停止读取规则和记录，并保留原文件。"
                        DatabaseFailureReason.RECOVERY_FAILED ->
                            "数据库完整性检查已通过，但未能安全收尾上次中断的记录。规则保持停用，原数据没有清空。"
                        DatabaseFailureReason.OPEN_FAILED ->
                            "暂时无法安全打开本地数据库。可能与存储空间、文件系统或升级状态有关，原文件仍保留。"
                        null -> "本地数据库暂时不可用，原文件仍保留。"
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
                color = Muted,
            )
            if (!checking && failure != null) {
                Spacer(Modifier.height(20.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = SageSoft),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text(
                            "请勿卸载应用或清除应用数据",
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "先确认设备仍有可用存储空间，再重新检查。如果问题持续，请保留现场并联系发布者；清除数据会删除可能仍可恢复的文件。",
                            color = Muted,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("重新检查本地数据")
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "诊断编号：${failure.reason.diagnosticCode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
    }
}

@Composable
private fun SettingsRecoveryScreen(
    state: SettingsHealthState,
    onRetry: () -> Unit,
    onConfirmSafeDefaults: () -> Unit,
) {
    val checking = state is SettingsHealthState.Checking
    val problem = when (state) {
        is SettingsHealthState.Checking -> state.previousProblem
        is SettingsHealthState.Problem -> state
        SettingsHealthState.Healthy -> null
    }
    Scaffold(
        containerColor = Paper,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "停一下",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Sage,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = when {
                    checking -> "正在检查本地设置…"
                    problem is SettingsHealthState.RecoveryRequired -> "设置文件已安全保全"
                    else -> "本地设置暂时不可用"
                },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Ink,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = when {
                    checking -> "确认完成前，目标规则和干预记录暂不读取，无障碍停顿也不会运行。"
                    problem is SettingsHealthState.RecoveryRequired ->
                        "原设置文件无法解析。应用已先保存并校验原始副本，再写入不会启动干预的安全默认设置。Room 中的目标应用和历史没有删除。"
                    problem is SettingsHealthState.Unavailable &&
                        problem.reason == SettingsFailureReason.RECOVERY_COPY_FAILED ->
                        "检测到设置文件异常，但无法生成并校验保全副本，因此没有替换原文件。请先释放存储空间后重新检查。"
                    problem is SettingsHealthState.Unavailable &&
                        problem.reason == SettingsFailureReason.RECOVERY_DELETE_FAILED ->
                        "完整重置未能确认恢复副本已经删除。应用保留删除记录并停止实时干预；重新检查会再次尝试删除。"
                    else ->
                        "暂时无法安全读取或写入设置。应用已停止实时干预，目标和历史仍保留在本机。"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = Muted,
            )
            if (!checking && problem != null) {
                Spacer(Modifier.height(20.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = SageSoft),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text(
                            if (problem is SettingsHealthState.RecoveryRequired) {
                                "确认后需要重新完成用途说明和首次预览"
                            } else {
                                "请勿卸载应用或清除应用数据"
                            },
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (problem is SettingsHealthState.RecoveryRequired) {
                                "保全副本继续留在仅本应用可访问的 noBackup 区域，直到你执行“重置全部本地数据”。"
                            } else {
                                "先确认设备仍有可用存储空间，再重新检查。清除应用数据会同时删除设置、目标、历史和恢复副本。"
                            },
                            color = Muted,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = if (problem is SettingsHealthState.RecoveryRequired) {
                        onConfirmSafeDefaults
                    } else {
                        onRetry
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (problem is SettingsHealthState.RecoveryRequired) {
                            "使用安全默认设置继续"
                        } else {
                            "重新检查本地设置"
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                val diagnosticCode = when (problem) {
                    is SettingsHealthState.RecoveryRequired -> "SETTINGS-RECOVERED"
                    is SettingsHealthState.Unavailable -> problem.reason.diagnosticCode
                }
                Text(
                    text = "诊断编号：$diagnosticCode",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
    }
}

@Composable
private fun TodayScreen(
    status: DeviceStatus,
    settings: SettingsSnapshot,
    targetCount: Int,
    stats: StatsSnapshot,
    lastConnection: Long,
    availability: InterventionAvailability,
    nextScheduleActivationLabel: String?,
    onSetup: () -> Unit,
    onChooseTargets: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDemo: () -> Unit,
    onRepair: () -> Unit,
    onRepairBackground: () -> Unit,
    onSchedule: () -> Unit,
) {
    val paused = availability == InterventionAvailability.PAUSED
    val disclosureAccepted = settings.disclosureAccepted && settings.ageEligibilityConfirmed
    val setupComplete = disclosureAccepted &&
        status.accessibilityEnabled &&
        targetCount > 0 &&
        settings.onboardingPreviewCompleted
    val showPauseControls = availability == InterventionAvailability.ACTIVE ||
        availability == InterventionAvailability.OUTSIDE_SCHEDULE ||
        availability == InterventionAvailability.PAUSED
    LazyColumn(
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("少一点惯性，\n多一点自己。", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(10.dp))
            Text(
                when (availability) {
                    InterventionAvailability.SERVICE_STALE -> "服务可能已停止，修复后规则会继续生效。"
                    InterventionAvailability.SERVICE_STARTING -> "正在确认打开前干预服务。"
                    InterventionAvailability.PAUSED -> "你主动留出的暂停时间还在继续。"
                    InterventionAvailability.SCHEDULE_DISABLED ->
                        "干预计划已关闭，需要手动开启后才会恢复。"
                    InterventionAvailability.OUTSIDE_SCHEDULE -> {
                        if (nextScheduleActivationLabel != null) {
                            "当前不在计划时段，将于 $nextScheduleActivationLabel 自动恢复。"
                        } else {
                            "当前不在计划时段，到了时间会自动恢复。"
                        }
                    }
                    InterventionAvailability.ACTIVE -> "干预正在守护你的注意力。"
                    InterventionAvailability.SETUP_REQUIRED -> "只需几步，就能开始第一次有意识的打开。"
                },
                color = Muted,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        item {
            HeroCard(stats = stats, availability = availability)
        }
        if (availability == InterventionAvailability.SERVICE_STALE) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE9E1)),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text("打开前干预需要修复", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "系统仍显示权限已开启，但服务未能持续报告运行状态。请检查无障碍与后台运行设置。",
                            color = Muted,
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = onRepair, modifier = Modifier.fillMaxWidth()) {
                            Text("检查无障碍设置")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onRepairBackground, modifier = Modifier.fillMaxWidth()) {
                            Text("检查后台运行设置")
                        }
                    }
                }
            }
        }
        if (availability == InterventionAvailability.SCHEDULE_DISABLED) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4D8)),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text("干预计划没有生效", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "“启用干预计划”已关闭，或没有选择任何生效日期。此状态不会自动恢复。",
                            color = Muted,
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = onSchedule, modifier = Modifier.fillMaxWidth()) {
                            Text("检查生效时间")
                        }
                    }
                }
            }
        }
        if (!setupComplete) {
            item {
                SetupCard(
                    disclosureAccepted = disclosureAccepted,
                    accessibilityEnabled = status.accessibilityEnabled,
                    targetCount = targetCount,
                    previewCompleted = settings.onboardingPreviewCompleted,
                    onSetup = onSetup,
                    onChooseTargets = onChooseTargets,
                    onPreview = onDemo,
                )
            }
        }
        item {
            TextButton(onClick = onSetup) { Text("权限与数据用途 · 使用时长设置") }
        }
        if (showPauseControls) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFDF8)),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(if (paused) "干预已暂停" else "需要喘口气？", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (paused) "你可以随时恢复，不需要为暂停解释。" else "临时暂停 15 分钟，不会改变任何规则。",
                            color = Muted,
                        )
                        Spacer(Modifier.height(16.dp))
                        if (paused) {
                            Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text("现在恢复") }
                            Text(
                                "将于 ${formatClockTime(settings.globallyPausedUntilEpochMs)} 自动恢复",
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            OutlinedButton(onClick = onPause, modifier = Modifier.fillMaxWidth()) { Text("暂停 15 分钟") }
                        }
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = onDemo, modifier = Modifier.fillMaxWidth()) {
                Text("预览一次干预")
            }
        }
        if (lastConnection > 0 && status.accessibilityEnabled) {
            item {
                Text(
                    "服务最近连接于 ${formatRelativeTime(lastConnection)} · 所有记录默认留在本机",
                    color = Muted,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun HeroCard(stats: StatsSnapshot, availability: InterventionAvailability) {
    val active = availability == InterventionAvailability.ACTIVE
    val statusLabel = when (availability) {
        InterventionAvailability.ACTIVE -> "本周的选择"
        InterventionAvailability.PAUSED -> "已暂停 · 本周"
        InterventionAvailability.SCHEDULE_DISABLED -> "计划已关闭"
        InterventionAvailability.OUTSIDE_SCHEDULE -> "当前休息 · 本周"
        InterventionAvailability.SERVICE_STARTING -> "服务连接中"
        InterventionAvailability.SERVICE_STALE -> "服务需要修复"
        InterventionAvailability.SETUP_REQUIRED -> "等待启用"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = if (active) Ink else SageSoft),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(
                statusLabel,
                color = if (active) Color.White.copy(alpha = .75f) else Muted,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (stats.completedDecisions > 0) "${stats.exited} 次" else "—",
                color = if (active) Color.White else Ink,
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                if (stats.completedDecisions > 0) {
                    "在已完成选择中占 ${stats.exitRate}%"
                } else {
                    "完成设置后，从第一次停顿开始"
                },
                color = if (active) Color.White.copy(alpha = .8f) else Muted,
            )
        }
    }
}

@Composable
private fun SetupCard(
    disclosureAccepted: Boolean,
    accessibilityEnabled: Boolean,
    targetCount: Int,
    previewCompleted: Boolean,
    onSetup: () -> Unit,
    onChooseTargets: () -> Unit,
    onPreview: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFDF8)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("开始使用", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            SetupRow("确认年龄与权限用途", disclosureAccepted)
            SetupRow("开启打开前干预", accessibilityEnabled)
            SetupRow("选择目标应用", targetCount > 0)
            SetupRow("预览并确认干预方式", previewCompleted)
            Text("使用时长分析可在权限确认页单独开启，也可跳过。", color = Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            val nextAction = when {
                !disclosureAccepted || !accessibilityEnabled -> onSetup
                targetCount == 0 -> onChooseTargets
                else -> onPreview
            }
            val nextLabel = when {
                !disclosureAccepted || !accessibilityEnabled -> "继续设置"
                targetCount == 0 -> "选择目标应用"
                else -> "预览并正式启用"
            }
            Button(
                onClick = nextAction,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(nextLabel)
            }
        }
    }
}

@Composable
private fun SetupRow(label: String, done: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (done) Sage else Line),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (done) "✓" else "", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (done) Muted else Ink)
    }
}

@Composable
internal fun TargetsScreen(
    installedApps: List<InstalledApp>,
    targets: List<TargetRuleEntity>,
    query: String,
    loading: Boolean,
    loadFailed: Boolean,
    catalogAuthoritative: Boolean,
    loadIcon: suspend (String) -> Bitmap?,
    onToggle: (InstalledApp, Boolean) -> Unit,
    onSelectAll: (List<InstalledApp>) -> Unit,
    onDeselectAll: (List<InstalledApp>) -> Unit,
    onQueryChanged: (String) -> Unit,
    onRefresh: () -> Unit,
    selectingAll: Boolean = false,
    categories: app.pausecn.data.AppCategorySnapshot = app.pausecn.data.AppCategorySnapshot(),
    onCategories: () -> Unit = {},
) {
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    val selectedPackages = remember(targets) { targets.filter { it.enabled }.mapTo(hashSetOf()) { it.packageName } }
    val displayedApps = remember(installedApps, targets, catalogAuthoritative) {
        if (!catalogAuthoritative) {
            installedApps
        } else {
            val installedPackages = installedApps.mapTo(hashSetOf()) { it.packageName }
            buildList {
                addAll(installedApps)
                targets
                    .asSequence()
                    .filter { it.enabled && it.packageName !in installedPackages }
                    .mapTo(this) { InstalledApp(it.packageName, it.label, isInstalled = false) }
            }
        }
    }
    val unavailableTargetCount = remember(displayedApps) { displayedApps.count { !it.isInstalled } }
    val filtered = remember(displayedApps, query, selectedPackages, categories, selectedCategory) {
        displayedApps
            .filter { selectedCategory == null || categories.category(it.packageName) == selectedCategory }
            .filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
            .sortedWith(
                compareByDescending<InstalledApp> { it.packageName in selectedPackages }
                    .thenBy { it.isInstalled }
                    .thenBy { it.label.lowercase() },
            )
    }

    // Unavailable selected entries can still be removed, but never newly selected.
    val selectable = filtered.filter { it.isInstalled || it.packageName in selectedPackages }
    val remaining = selectable.filter { it.isInstalled && it.packageName !in selectedPackages }
    val selectedInScope = selectable.filter { it.packageName in selectedPackages }
    val allSelected = selectable.isNotEmpty() && remaining.isEmpty()

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("选择你想少打开的应用", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text("默认不显示电话、系统设置等关键应用。", color = Muted)
            TextButton(onClick = onCategories) { Text("管理应用分类（自动 / 手动）") }
            app.pausecn.ui.CategoryFilter(categories, selectedCategory) { selectedCategory = it }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { onQueryChanged(limitTargetSearchQuery(it)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索应用") },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        TextButton(onClick = { onQueryChanged("") }) {
                            Text("清除")
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "当前列表已选 ${selectedInScope.size} / ${selectable.size}",
                    color = Muted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        if (allSelected) onDeselectAll(selectedInScope) else onSelectAll(remaining)
                    },
                    enabled = !loading && !loadFailed && !selectingAll && selectable.isNotEmpty(),
                    modifier = Modifier.testTag("select_all_targets"),
                ) {
                    Text(when {
                        selectingAll -> "正在保存…"
                        allSelected && query.isNotBlank() -> "取消全选搜索结果"
                        allSelected -> "取消全选"
                        query.isNotBlank() -> "全选搜索结果"
                        else -> "全选"
                    })
                }
            }
        }
        if (loading && installedApps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("正在读取可启动应用…", color = Muted) }
        } else if (loadFailed && installedApps.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("暂时无法读取应用", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("系统没有返回可启动应用，请稍后重试。", color = Muted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onRefresh) { Text("重新读取") }
            }
        } else if (filtered.isEmpty() && query.isNotBlank()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("没有匹配的应用", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("试试应用名称或包名中的其他关键词。", color = Muted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { onQueryChanged("") }) { Text("清除搜索") }
            }
        } else if (filtered.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("没有找到应用", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("可以刷新列表，或检查系统的应用可见性设置。", color = Muted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onRefresh) { Text("刷新") }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (loadFailed) {
                    item(key = "load-error") {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE9E1)),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("刷新失败，当前显示上次结果", color = Clay, modifier = Modifier.weight(1f))
                                TextButton(onClick = onRefresh) { Text("重试") }
                            }
                        }
                    }
                }
                if (unavailableTargetCount > 0) {
                    item(key = "unavailable-targets") {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE9E1)),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text(
                                "$unavailableTargetCount 个已选应用当前不可用，已不计入正在生效的目标。关闭下方开关即可移除。",
                                color = Clay,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
                items(filtered, key = { it.packageName }) { app ->
                    val selected = app.packageName in selectedPackages
                    AppTargetRow(app, selected, loadIcon, categories.category(app.packageName)) { onToggle(app, it) }
                }
            }
        }
    }
}

@Composable
private fun AppTargetRow(
    app: InstalledApp,
    selected: Boolean,
    loadIcon: suspend (String) -> Bitmap?,
    category: String? = null,
    onToggle: (Boolean) -> Unit,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = app.packageName, key2 = app.isInstalled) {
        value = if (app.isInstalled) loadIcon(app.packageName) else null
    }
    val icon = remember(bitmap) { bitmap?.asImageBitmap() }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when {
                !app.isInstalled -> Color(0xFFFFE9E1)
                selected -> SageSoft
                else -> Color(0xFFFFFDF8)
            },
        ),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = selected,
                role = Role.Switch,
                onValueChange = onToggle,
            ),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) Sage else Line),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp),
                    )
                } else {
                    Text(
                        app.label.take(1).uppercase(),
                        color = if (selected) Color.White else Ink,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                category?.let { Text(it, color = Sage, style = MaterialTheme.typography.labelMedium) }
                Text(
                    if (app.isInstalled) app.packageName else "当前未安装 · ${app.packageName}",
                    color = if (app.isInstalled) Muted else Clay,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(checked = selected, onCheckedChange = null)
        }
    }
}

@Composable
private fun StatsScreen(stats: StatsSnapshot, recentEvents: List<InterventionEventEntity>, onConversation: () -> Unit, onUsage: () -> Unit, onReports: () -> Unit, onShare: () -> Unit,
    categoryCounts: List<app.pausecn.data.CategoryPauseCount> = emptyList()) {
    var showAllRecent by remember { mutableStateOf(false) }
    var showRunDetails by remember { mutableStateOf(false) }
    var showAllCategories by remember { mutableStateOf(false) }
    LazyColumn(
        contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("本周停顿", style = MaterialTheme.typography.headlineSmall)
                OutlinedButton(onClick = onShare) { Text("分享本周") }
            }
            Text("${stats.triggerSuccesses} 次", style = MaterialTheme.typography.displayMedium)
            Text("已记录的停顿，不是全部打开次数。", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("主动离开", stats.exited.toString(), Modifier.weight(1f))
                MetricCard("继续打开", stats.continued.toString(), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReports, modifier = Modifier.weight(1f)) { Text("日报周报") }
                OutlinedButton(onClick = onUsage, modifier = Modifier.weight(1f)) { Text("使用热力图") }
            }
        }
        item {
            Card(onClick = onConversation, modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SageSoft)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AI陪伴  →", style = MaterialTheme.typography.titleLarge)
                    Text("聊聊现在的想法，也听听对使用记录的解读。", color = Ink)
                }
            }
        }
        if (categoryCounts.isNotEmpty()) item {
            Text("本周分类分布", style = MaterialTheme.typography.titleMedium)
            (if (showAllCategories) categoryCounts else categoryCounts.take(4)).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(row.category, modifier = Modifier.weight(1f)); Text("${row.count} 次")
                }
            }
            if (categoryCounts.size > 4) TextButton(onClick = { showAllCategories = !showAllCategories }) { Text(if (showAllCategories) "收起分类" else "查看全部分类") }
            Text("按当前主分类汇总已记录停顿，不重复计数。", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (stats.displayFailed > 0 || stats.dismissed > 0) {
            item { TextButton(onClick = { showRunDetails = !showRunDetails }) {
                Text("中断 ${stats.dismissed} 次 · 未显示 ${stats.displayFailed} 次${if (showRunDetails) " · 收起" else " · 详情"}")
            } }
            if (showRunDetails) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4D8)),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text("运行说明", fontWeight = FontWeight.Bold, color = Ink)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "本周有 ${stats.displayFailed} 次停顿未能显示，" +
                                "${stats.dismissed} 次在选择前被系统、锁屏或服务中断。" +
                                "这些都不属于你的主动离开或继续选择。",
                            color = Muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            }
        }
        item { Text("最近记录", style = MaterialTheme.typography.headlineSmall) }
        if (recentEvents.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFDF8)),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text("第一条记录会在发生一次真实停顿尝试后出现。", color = Muted, modifier = Modifier.padding(20.dp))
                }
            }
        } else {
            items(if (showAllRecent) recentEvents else recentEvents.take(5), key = { it.id }) { event -> EventRow(event) }
            if (recentEvents.size > 5) item { TextButton(onClick = { showAllRecent = !showAllRecent }) {
                Text(if (showAllRecent) "收起最近记录" else "查看更多最近记录")
            } }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFDF8)),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(label, color = Muted, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineLarge)
        }
    }
}

@Composable
private fun EventRow(event: InterventionEventEntity) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFFDF8), RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(
                    when (event.outcome) {
                        InterventionOutcome.EXITED.name -> Sage
                        InterventionOutcome.CONTINUED.name -> Clay
                        InterventionOutcome.DISPLAY_FAILED.name -> Color(0xFFB35C4B)
                        else -> Muted
                    },
                ),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(event.appLabel, fontWeight = FontWeight.SemiBold)
            Text(
                when (event.outcome) {
                    InterventionOutcome.EXITED.name -> "选择了离开"
                    InterventionOutcome.CONTINUED.name -> "继续 · ${event.purpose ?: "理由未记录或已删除"}"
                    InterventionOutcome.DISPLAY_FAILED.name -> "停顿未能显示"
                    InterventionOutcome.DISMISSED.name -> "停顿被锁屏、系统或服务中断"
                    else -> "停顿已出现 · 未完成选择"
                },
                color = Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(formatEventTime(event.occurredAtEpochMs), color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun SettingsScreen(
    onRuleAdjustment: () -> Unit,
    onUsage: () -> Unit,
    onReasonMemory: () -> Unit,
    onAiSettings: () -> Unit,
    settings: SettingsSnapshot,
    status: DeviceStatus,
    onScheduleEnabled: (Boolean) -> Unit,
    onWindowChanged: (Int, Int) -> Unit,
    onDaysChanged: (Int) -> Unit,
    onSecondsChanged: (Int) -> Unit,
    onPassChanged: (Int) -> Unit,
    onRetentionChanged: (Int) -> Unit,
    onAccessibility: () -> Unit,
    onBattery: () -> Unit,
    onOemSettings: () -> Unit,
    onAppDetails: () -> Unit,
    onClearHistory: () -> Unit,
    localDataActionInProgress: Boolean,
    exportInProgress: Boolean,
    onExportData: (CharArray, app.pausecn.data.LocalExportOptions) -> Unit,
    onResetAllData: () -> Unit,
) {
    val context = LocalContext.current
    val guide = remember { currentOemGuide() }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var pendingRetentionDays by remember { mutableStateOf<Int?>(null) }
    var showExport by remember { mutableStateOf(false) }
    LazyColumn(
        contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("设置", style = MaterialTheme.typography.headlineLarge) }
        item {
            SettingsCard("本地使用时长") {
                Text("可选系统使用情况访问，仅统计所选目标；与AI联网授权独立。")
                OutlinedButton(onClick = onUsage) { Text("使用时长分析与热力图") }
            }
        }
        item {
            SettingsCard("个性化提醒") {
                Text("手写短句可离线使用；可选自带 Key 生成 AI 风格文案。默认关闭，未经启用不会发起 AI 请求。")
                OutlinedButton(onClick = onAiSettings) { Text("AI 提醒与手写短句") }
                OutlinedButton(onClick = onReasonMemory) { Text("继续理由与本地记忆") }
            }
        }
        item {
            SettingsCard("生效时间") {
                OutlinedButton(onClick = onRuleAdjustment) { Text("批量调整与撤销") }
                SettingSwitchRow("启用干预计划", settings.schedule.enabled, onScheduleEnabled)
                Text(
                    if (settings.schedule.enabled) {
                        "只在下面选择的日期和时段进行打开前干预。"
                    } else {
                        "关闭后不会进行打开前干预，也不会自动恢复；需要你手动重新开启。"
                    },
                    color = if (settings.schedule.enabled) Muted else Clay,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                AnimatedVisibility(settings.schedule.enabled) {
                    Column {
                        HorizontalDivider(color = Line)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            TimeButton("开始", settings.schedule.startMinutes, Modifier.weight(1f)) {
                                showTimePicker(context, settings.schedule.startMinutes) {
                                    onWindowChanged(it, settings.schedule.endMinutes)
                                }
                            }
                            TimeButton("结束", settings.schedule.endMinutes, Modifier.weight(1f)) {
                                showTimePicker(context, settings.schedule.endMinutes) {
                                    onWindowChanged(settings.schedule.startMinutes, it)
                                }
                            }
                        }
                        Text(
                            if (settings.schedule.startMinutes == settings.schedule.endMinutes) "开始与结束相同时表示全天" else "跨越午夜的时间段也支持",
                            color = Muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        DaySelector(settings.schedule.activeDaysMask, onDaysChanged)
                        if (settings.schedule.activeDaysMask == 0) {
                            Text(
                                "没有选择生效日期，干预计划不会运行，也不会自动恢复。",
                                color = Clay,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                }
            }
        }
        item {
            SettingsCard("干预方式") {
                Text("等待秒数", color = Muted)
                Spacer(Modifier.height(8.dp))
                ChoiceChips(app.pausecn.data.RulePatch.WAIT_CHOICES, settings.interventionSeconds, { "$it 秒" }, onSecondsChanged)
                Spacer(Modifier.height(18.dp))
                Text("继续后的临时通行", color = Muted)
                Spacer(Modifier.height(8.dp))
                ChoiceChips(app.pausecn.data.RulePatch.PASS_CHOICES, settings.temporaryPassMinutes, { "$it 分钟" }, onPassChanged)
            }
        }
        item {
            SettingsCard("系统权限") {
                TextButton(onClick = onAccessibility) { Text("统一权限设置 · 使用时长分析") }
                PermissionRow("打开前干预", status.accessibilityEnabled, "核心功能", onAccessibility)
                HorizontalDivider(color = Line)
                PermissionRow("后台稳定性", status.ignoringBatteryOptimizations, "按机型设置", onBattery)
            }
        }
        item {
            SettingsCard(guide.title) {
                guide.steps.forEachIndexed { index, step ->
                    Text("${index + 1}. $step", modifier = Modifier.padding(vertical = 4.dp))
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onOemSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("打开后台管理设置")
                }
                Text(
                    "厂商页面可能随系统版本变化；找不到时会自动打开标准系统设置。",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onAppDetails, modifier = Modifier.fillMaxWidth()) { Text("打开应用系统设置") }
            }
        }
        item {
            SettingsCard("隐私") {
                Text("历史保存期限", color = Muted)
                Spacer(Modifier.height(8.dp))
                ChoiceChips(
                    listOf(30, 90, 365),
                    settings.historyRetentionDays,
                    { "$it 天" },
                    { days ->
                        if (days < settings.historyRetentionDays) {
                            pendingRetentionDays = days
                        } else {
                            onRetentionChanged(days)
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "目标包名、规则、干预结果、停顿显示延迟和服务连接/心跳默认只保存在本机。干预记录按所选期限清理，最多 10,000 条；服务连接最多 500 条。不会读取其他应用的聊天、输入、页面正文、通知、位置、通讯录或设备广告标识。你在本应用主动填写的内容及授权的汇总，可按各AI功能说明发送给DeepSeek。",
                    color = Muted,
                )
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = Line)
                Spacer(Modifier.height(14.dp))
                Text("运营者：${BuildConfig.OPERATOR_NAME}")
                Text("隐私与投诉联系：${BuildConfig.PRIVACY_CONTACT}", color = Muted)
                Text("APP 备案：${BuildConfig.APP_FILING_DISCLOSURE}", color = Muted)
                if (BuildConfig.PRIVACY_POLICY_URL.isNotBlank()) {
                    TextButton(
                        onClick = {
                            when (openHttpsUri(context, BuildConfig.PRIVACY_POLICY_URL)) {
                                ExternalUriOpenResult.OPENED -> Unit
                                ExternalUriOpenResult.INVALID_HTTPS_URL -> Toast.makeText(
                                    context,
                                    "隐私政策地址配置无效，当前构建禁止公开分发",
                                    Toast.LENGTH_LONG,
                                ).show()
                                ExternalUriOpenResult.UNAVAILABLE -> Toast.makeText(
                                    context,
                                    "无法打开隐私政策，请稍后重试或使用页面中的联系方式",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("查看完整隐私政策")
                    }
                } else {
                    Text(
                        "内部验证构建：正式隐私政策尚未配置，禁止公开分发。",
                        color = Clay,
                    )
                }
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = { showExport = true },
                    enabled = !exportInProgress && !localDataActionInProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (exportInProgress) "正在加密导出…" else "加密导出本地数据")
                }
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = { confirmClear = true },
                    enabled = !localDataActionInProgress && !exportInProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (localDataActionInProgress) "正在处理本地数据…" else "清除干预历史", color = Clay)
                }
                HorizontalDivider(color = Line)
                TextButton(
                    onClick = { confirmReset = true },
                    enabled = !localDataActionInProgress && !exportInProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("重置全部本地数据", color = Clay)
                }
            }
        }
        item {
            Text(
                "停一下 ${BuildConfig.VERSION_NAME} · 产品代号，正式品牌上线前需完成商标检索",
                color = Muted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除本地记录？") },
            text = { Text("目标应用和设置会保留；干预历史、使用时长汇总、停顿延迟、服务连接诊断、交流原文、普通派生记忆、提醒反馈和 AI 生成内容将永久删除。时长采集从清除后重新起算，不重新导入已删时段；不会删除Android系统自身记录。你明确独立保存的长期偏好、画像、手写短句、Key 和本地请求次数账本保留，可另行管理。无法撤回服务端已收到的数据或已导出文件。") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClearHistory() }) { Text("确认清除", color = Clay) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
    if (showExport) {
        EncryptedExportDialog(
            onDismiss = { showExport = false },
            onExport = { passphrase, options ->
                showExport = false
                onExportData(passphrase, options)
            },
        )
    }
    pendingRetentionDays?.let { days ->
        AlertDialog(
            onDismissRequest = { pendingRetentionDays = null },
            title = { Text("缩短历史保存期限？") },
            text = {
                Text("改为 $days 天后，超过 $days 天的干预历史、使用时长汇总、停顿延迟和服务连接诊断将立即永久删除，无法恢复。目标应用和其他设置不受影响。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRetentionDays = null
                        onRetentionChanged(days)
                    },
                ) { Text("缩短并删除旧记录", color = Clay) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRetentionDays = null }) { Text("取消") }
            },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("重置全部本地数据？") },
            text = {
                Text("目标应用、规则、设置、授权确认、干预历史、本机质量诊断、画像、交流、普通及独立长期记忆、反馈、AI 生成内容与请求账本、手写短句和本机 Key 都会删除。系统无障碍授权需要你另行关闭；已导出文件和服务端已收到的数据不会被同步删除。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        onResetAllData()
                    },
                ) { Text("确认重置", color = Clay) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun EncryptedExportDialog(onDismiss: () -> Unit, onExport: (CharArray, app.pausecn.data.LocalExportOptions) -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var exportOptions by remember { mutableStateOf(app.pausecn.data.LocalExportOptions()) }
    val confirmationFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val longEnough = hasMinimumExportPassphraseLength(passphrase)
    val matches = passphrase == confirmation
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加密导出本地数据") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("基础导出包含目标应用、规则、设置、干预历史及其中的理由原文、停顿延迟和服务连接/心跳。可另选下方私人内容，默认均不选；不导出 API Key。文件由系统保存到你选择的位置，本应用不主动上传；若选择云盘提供方，它可能同步文件。")
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(exportOptions.profile, { exportOptions = exportOptions.copy(profile = it) }); Text("独立画像、风格和手写短句") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(exportOptions.memories, { exportOptions = exportOptions.copy(memories = it) }); Text("普通/长期记忆摘要（不连带原对话）") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(exportOptions.appCategories, { exportOptions = exportOptions.copy(appCategories = it) }); Text("应用分类（自动结果与手动分类）") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(exportOptions.conversations, { exportOptions = exportOptions.copy(conversations = it) }); Text("保存的交流原文") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(exportOptions.feedback, { exportOptions = exportOptions.copy(feedback = it) }); Text("提醒反馈偏好") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(exportOptions.generated, { exportOptions = exportOptions.copy(generated = it) }); Text("AI短句；同时选交流时含AI回复") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(exportOptions.usage, { exportOptions = exportOptions.copy(usage = it) }); Text("目标应用时长汇总与独立停顿展示区间") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(exportOptions.reports, { exportOptions = exportOptions.copy(reports = it, reportInterpretations = false) }); Text("已保存的日报/周报事实") }
                if (exportOptions.reports) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(exportOptions.reportInterpretations, { exportOptions = exportOptions.copy(reportInterpretations = it) }); Text("同时包含报告的私人AI解读") }
                    Text("仅导出仍有效的至多4份已保存报告，不补生成、不联网；报告时长需另选时长分类。")
                    if (exportOptions.reportInterpretations) Text("解读文字本身可能提到画像、记忆、理由或时长，即使未选择相应分类；不会另附原始背景或请求。")
                }
                if (exportOptions.generated) Text("AI文字可能引用私人背景，即使未勾选画像或记忆，文字本身也可能含有这些内容。不会额外附带生成时完整背景快照。")
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("导出密码（至少 12 个 Unicode 码点）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { confirmationFocusRequester.requestFocus() },
                    ),
                    isError = passphrase.isNotEmpty() && !longEnough,
                    supportingText = if (passphrase.isNotEmpty() && !longEnough) {
                        { Text("至少输入 12 个 Unicode 码点，且代理对必须完整") }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text("再次输入密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() },
                    ),
                    isError = confirmation.isNotEmpty() && !matches,
                    supportingText = if (confirmation.isNotEmpty() && !matches) {
                        { Text("两次输入不一致") }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(confirmationFocusRequester),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "建议使用多个不相关词组成的长密码。密码不会保存，遗忘后无法恢复导出内容。",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = longEnough && matches,
                onClick = {
                    val exportPassphrase = passphrase.toCharArray()
                    passphrase = ""
                    confirmation = ""
                    onExport(exportPassphrase, exportOptions)
                },
            ) { Text("选择保存位置") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    )
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFDF8)),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onChecked,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun TimeButton(label: String, minutes: Int, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 64.dp), shape = RoundedCornerShape(16.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Muted, fontSize = 11.sp)
            Text(formatMinutes(minutes), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DaySelector(mask: Int, onChanged: (Int) -> Unit) {
    val days = listOf(
        "一" to "星期一",
        "二" to "星期二",
        "三" to "星期三",
        "四" to "星期四",
        "五" to "星期五",
        "六" to "星期六",
        "日" to "星期日",
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 4,
    ) {
        days.forEachIndexed { index, (label, fullLabel) ->
            val bit = 1 shl index
            val selected = mask and bit != 0
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (selected) Ink else SageSoft)
                    .toggleable(
                        value = selected,
                        role = Role.Checkbox,
                        onValueChange = { checked ->
                            onChanged(if (checked) mask or bit else mask and bit.inv())
                        },
                    )
                    .semantics { contentDescription = fullLabel },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (selected) Color.White else Ink, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun <T> ChoiceChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 3,
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { if (option != selected) onSelect(option) },
                label = { Text(label(option)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, detail: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        Text(if (granted) "已完成" else "去设置", color = if (granted) Sage else Clay, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AccessibilityDisclosureDialog(
    saving: Boolean,
    onDismiss: () -> Unit,
    onAccept: () -> Unit,
) {
    var ageEligibilityConfirmed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("先确认权限与数据用途") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("为了在你打开所选应用时及时暂停一下，系统需要允许“停一下”接收这些应用的窗口切换事件。来电、系统相机、支付等有限安全界面的包名事件只用于立即撤下已有停顿层，不会保存。")
                Spacer(Modifier.height(8.dp))
                Text("请向下阅读完整说明，并在底部确认适用年龄。", color = Clay, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                DisclosureLine("会使用", "你选择的应用包名、出现时间、停顿显示延迟，以及你在干预页做出的选择；另记录本服务自身的连接和心跳时间。")
                DisclosureLine("不会读取", "聊天内容、键盘输入、通知、页面正文、照片、位置和通讯录。")
                DisclosureLine("可选使用时长", "用于目标应用的前台时长和热力图，需要单独选择本地分析及系统使用情况访问。确认本页后可统一设置，也可跳过；此处同意不等于开启采集或发送给AI。")
                DisclosureLine("默认本地保存", "无障碍事件、目标应用清单和使用目的默认保存在本机。普通AI生成只发送风格；个性化预生成需单独授权当前应用名称和近期汇总，画像与常用理由摘要分别选择是否发送。不读取其他应用的页面或聊天。")
                Spacer(Modifier.height(10.dp))
                Text("你可以随时在系统设置中关闭，基础设置与历史查看仍可使用。", color = Muted)
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = ageEligibilityConfirmed,
                            enabled = !saving,
                            role = Role.Checkbox,
                            onValueChange = { ageEligibilityConfirmed = it },
                        )
                        .testTag("ageEligibilityCheckbox"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = ageEligibilityConfirmed,
                        onCheckedChange = null,
                    )
                    Text(
                        "我确认已满 14 周岁",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    "本版本面向 14 周岁及以上用户。未满 14 周岁请暂时不用，也不要继续开启系统权限。",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = onAccept, enabled = ageEligibilityConfirmed && !saving) {
                Text(
                    when {
                        saving -> "正在保存确认…"
                        ageEligibilityConfirmed -> "我理解并确认，选择权限"
                        else -> "向下阅读并确认年龄"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("暂时不用") }
        },
    )
}

@Composable
private fun DisclosureLine(title: String, body: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(title, fontWeight = FontWeight.Bold)
        Text(body, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DemoIntervention(
    waitSeconds: Int,
    promptText: String = app.pausecn.ai.PromptSelector.DEFAULT,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
) {
    var seconds by remember(waitSeconds) { mutableIntStateOf(waitSeconds) }
    var ready by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf(DemoStep.WAITING) }
    val transition = rememberInfiniteTransition(label = "breath")
    val scale by transition.animateFloat(
        initialValue = .82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3_000), repeatMode = RepeatMode.Reverse),
        label = "breathScale",
    )
    LaunchedEffect(waitSeconds) {
        while (seconds > 0) {
            delay(1_000)
            seconds--
        }
        ready = true
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Paper)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(28.dp, 64.dp, 28.dp, 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("给自己一点空间", color = Sage, fontWeight = FontWeight.Bold)
            if (step == DemoStep.WAITING) {
                Spacer(Modifier.height(46.dp))
                Box(
                    Modifier
                        .size(190.dp)
                        .scale(scale)
                        .clip(CircleShape)
                        .background(SageSoft),
                    contentAlignment = Alignment.Center,
                ) { Text(if (scale > .91f) "吸气" else "呼气", style = MaterialTheme.typography.headlineSmall) }
                Spacer(Modifier.height(40.dp))
                Text("你正要打开一个应用", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(if (ready) "现在，由你来决定。" else promptText, color = Muted)
                Spacer(Modifier.height(44.dp))
            } else {
                Spacer(Modifier.height(36.dp))
                Text(
                    "这次打开，是为了什么？",
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                app.pausecn.ui.ReasonPicker(onConfirm = { onComplete() })
                Spacer(Modifier.height(20.dp))
            }
            Button(onClick = onComplete, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("先不打开") }
            if (step == DemoStep.WAITING) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { step = DemoStep.PURPOSE },
                    enabled = ready,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                ) { Text(if (ready) "带着目的继续" else "再等 $seconds 秒") }
            }
            Spacer(Modifier.height(18.dp))
            Text("这是预览：不保存理由，不计使用次数，不联网。", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun showTimePicker(context: android.content.Context, minutes: Int, onChanged: (Int) -> Unit) {
    TimePickerDialog(
        context,
        { _, hour, minute -> onChanged(hour * 60 + minute) },
        minutes / 60,
        minutes % 60,
        true,
    ).show()
}

private fun formatMinutes(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

private fun formatEventTime(epochMs: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

private fun formatClockTime(epochMs: Long): String = DateTimeFormatter.ofPattern("HH:mm")
    .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

private fun formatNextScheduleActivation(epochMs: Long, nowEpochMs: Long, zoneId: ZoneId): String {
    val activation = Instant.ofEpochMilli(epochMs).atZone(zoneId)
    val today = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate()
    val dayLabel = when (activation.toLocalDate()) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        else -> when (activation.dayOfWeek) {
            java.time.DayOfWeek.MONDAY -> "周一"
            java.time.DayOfWeek.TUESDAY -> "周二"
            java.time.DayOfWeek.WEDNESDAY -> "周三"
            java.time.DayOfWeek.THURSDAY -> "周四"
            java.time.DayOfWeek.FRIDAY -> "周五"
            java.time.DayOfWeek.SATURDAY -> "周六"
            java.time.DayOfWeek.SUNDAY -> "周日"
        }
    }
    return "$dayLabel ${DateTimeFormatter.ofPattern("HH:mm").format(activation)}"
}

internal fun limitTargetSearchQuery(value: String): String {
    if (value.codePointCount(0, value.length) <= MAX_TARGET_SEARCH_QUERY_CODE_POINTS) return value
    val endIndex = value.offsetByCodePoints(0, MAX_TARGET_SEARCH_QUERY_CODE_POINTS)
    return value.substring(0, endIndex)
}

private const val MAX_TARGET_SEARCH_QUERY_CODE_POINTS = 100

private fun formatRelativeTime(epochMs: Long): String {
    val diffMinutes = ((System.currentTimeMillis() - epochMs) / 60_000).coerceAtLeast(0)
    return when {
        diffMinutes < 1 -> "刚刚"
        diffMinutes < 60 -> "${diffMinutes} 分钟前"
        diffMinutes < 24 * 60 -> "${diffMinutes / 60} 小时前"
        else -> "${diffMinutes / (24 * 60)} 天前"
    }
}

internal fun nextUiStatusRefreshDelayMs(
    settings: SettingsSnapshot,
    nowEpochMs: Long,
    nowElapsedMs: Long,
    regularRefreshMs: Long = STATUS_REFRESH_INTERVAL_MS,
): Long {
    require(regularRefreshMs > 0) { "regularRefreshMs must be positive" }
    val untilNextMinuteMs = MILLIS_PER_MINUTE - Math.floorMod(nowEpochMs, MILLIS_PER_MINUTE)
    val untilPauseEndsMs = if (settings.isGloballyPaused(nowEpochMs, nowElapsedMs)) {
        minOf(
            settings.globallyPausedUntilEpochMs - nowEpochMs,
            settings.globallyPausedUntilElapsedMs - nowElapsedMs,
        ).coerceAtLeast(1L)
    } else {
        Long.MAX_VALUE
    }
    return minOf(regularRefreshMs, untilNextMinuteMs, untilPauseEndsMs).coerceAtLeast(1L)
}

private const val STATUS_REFRESH_INTERVAL_MS = 30_000L
private const val MILLIS_PER_MINUTE = 60_000L
