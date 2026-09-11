package app.pausecn

import android.app.UiAutomation
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.Lifecycle
import androidx.room.withTransaction
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pausecn.data.DatabaseFailureReason
import app.pausecn.data.DatabaseHealthState
import app.pausecn.data.InstalledApp
import app.pausecn.data.InterventionOutcome
import app.pausecn.data.SettingsFailureReason
import app.pausecn.data.SettingsHealthState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun waitForDatabaseIntegrityCheck() {
        val container = (composeRule.activity.application as PauseApplication).container
        composeRule.waitUntil(timeoutMillis = 10_000) {
            container.databaseHealthStore.state.value == DatabaseHealthState.Healthy &&
                container.settingsHealthStore.state.value == SettingsHealthState.Healthy
        }
    }

    @After
    fun restoreDatabaseHealthAfterTest() {
        val container = (composeRule.activity.application as PauseApplication).container
        container.databaseHealthStore.markHealthy()
        container.settingsHealthStore.markHealthy()
        runBlocking {
            container.settingsStore.setScheduleEnabled(true)
            container.settingsStore.setActiveDays(app.pausecn.domain.ScheduleSpec.ALL_DAYS)
        }
    }

    @Test
    fun bottomNavigation_reachesEveryPrimaryScreen() {
        val container = (composeRule.activity.application as PauseApplication).container
        runBlocking {
            container.repository.setTarget(
                InstalledApp("app.pausecn.missing.test", "已卸载示例"),
                true,
            )
        }
        composeRule.onNodeWithText("少一点惯性，\n多一点自己。")
            .assertIsDisplayed()
        composeRule.onNodeWithText("未开启").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("打开前干预服务未开启").assertIsDisplayed()

        composeRule.onNodeWithText("目标").performClick()
        composeRule.onNodeWithText("选择你想少打开的应用").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("当前未安装 · app.pausecn.missing.test")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("1 个已选应用当前不可用，已不计入正在生效的目标。关闭下方开关即可移除。")
            .assertIsDisplayed()

        runBlocking { container.settingsStore.setScheduleEnabled(false) }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            !runBlocking { container.settingsStore.settings.first().schedule.enabled }
        }
        composeRule.onNodeWithText("选择你想少打开的应用").assertIsDisplayed()
        composeRule.onNodeWithText("当前未安装 · app.pausecn.missing.test").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("正在读取可启动应用…").fetchSemanticsNodes().isEmpty())
        runBlocking { container.settingsStore.setScheduleEnabled(true) }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { container.settingsStore.settings.first().schedule.enabled }
        }

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("当前未安装 · app.pausecn.missing.test")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("选择你想少打开的应用").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("暂时无法读取应用").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithText("已卸载示例")
            .assertIsOn()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { container.repository.targets.first().isEmpty() }
        }
        assertTrue(composeRule.onAllNodesWithText("已卸载示例").fetchSemanticsNodes().isEmpty())

        val continuityQuery = "搜".repeat(99) + "😀"
        composeRule.onNode(hasSetTextAction()).performTextInput(continuityQuery)
        composeRule.onNode(hasSetTextAction()).assert(hasText(continuityQuery))
        composeRule.onNodeWithText("今天").performClick()
        composeRule.onNodeWithText("目标").performClick()
        composeRule.onNode(hasSetTextAction()).assert(hasText(continuityQuery))

        composeRule.activityRule.scenario.recreate()
        composeRule.onNodeWithText("选择你想少打开的应用").assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).assert(hasText(continuityQuery))

        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        composeRule.onNode(hasSetTextAction()).assert(hasText(continuityQuery))
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }
        composeRule.onNode(hasSetTextAction()).assert(hasText(continuityQuery))

        container.databaseHealthStore.reportFailure(
            DatabaseFailureReason.INTEGRITY_CHECK_FAILED,
            nowEpochMs = 789L,
        )
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("本地数据需要保护").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("重新检查本地数据").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            container.databaseHealthStore.state.value == DatabaseHealthState.Healthy
        }
        composeRule.onNodeWithText("选择你想少打开的应用").assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).assert(hasText(continuityQuery))
        composeRule.onNodeWithText("清除")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        composeRule.onNode(hasSetTextAction()).assert(hasText(""))

        composeRule.onNode(hasSetTextAction()).performTextInput("绝无匹配的应用关键词_xyz")
        composeRule.onNodeWithText("没有匹配的应用").assertIsDisplayed()
        composeRule.onNodeWithText("试试应用名称或包名中的其他关键词。").assertIsDisplayed()
        composeRule.onNodeWithText("清除搜索").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("没有匹配的应用").fetchSemanticsNodes().isEmpty()
        }

        runBlocking {
            container.repository.clearHistory()
            container.repository.recordIntervention(
                packageName = "app.pausecn.stats.failed",
                appLabel = "显示失败示例",
                outcome = InterventionOutcome.DISPLAY_FAILED,
            )
            container.repository.recordIntervention(
                packageName = "app.pausecn.stats.dismissed",
                appLabel = "系统中断示例",
                outcome = InterventionOutcome.DISMISSED,
            )
        }
        composeRule.onNodeWithText("记录").performClick()
        composeRule.onNodeWithText("你的选择，不是考核。").assertIsDisplayed()
        composeRule.onNodeWithText("停顿显示").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(
                "本周有 1 次停顿未能显示，1 次在选择前被系统、锁屏或服务中断。" +
                    "这些都不属于你的主动离开或继续选择。",
            ).fetchSemanticsNodes().isNotEmpty()
        }

        runBlocking {
            container.settingsStore.setScheduleEnabled(true)
            container.settingsStore.setActiveDays(app.pausecn.domain.ScheduleSpec.ALL_DAYS)
        }
        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNodeWithText("生效时间").assertIsDisplayed()
        composeRule.onNodeWithText("启用干预计划")
            .assertIsOn()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            !runBlocking { container.settingsStore.settings.first().schedule.enabled }
        }
        composeRule.onNodeWithText("启用干预计划").assertIsOff()
        composeRule.onNodeWithText("关闭后不会进行打开前干预，也不会自动恢复；需要你手动重新开启。")
            .assertIsDisplayed()
        composeRule.onNodeWithText("启用干预计划")
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { container.settingsStore.settings.first().schedule.enabled }
        }
        composeRule.onNodeWithText("启用干预计划").assertIsOn()
        runBlocking {
            container.settingsStore.setActiveDays(0)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("没有选择生效日期，干预计划不会运行，也不会自动恢复。")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("没有选择生效日期，干预计划不会运行，也不会自动恢复。")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("星期一")
            .assertIsDisplayed()
            .assertIsOff()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { container.settingsStore.settings.first().schedule.activeDaysMask } == 1
        }
        composeRule.onNodeWithContentDescription("星期一").assertIsOn()
        assertTrue(
            composeRule.onAllNodesWithText("没有选择生效日期，干预计划不会运行，也不会自动恢复。")
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("打开后台管理设置"))
        composeRule.onNodeWithText("打开后台管理设置").assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performScrollToNode(
            hasText("厂商页面可能随系统版本变化；找不到时会自动打开标准系统设置。"),
        )
        composeRule.onNodeWithText("厂商页面可能随系统版本变化；找不到时会自动打开标准系统设置。")
            .assertIsDisplayed()

        composeRule.onNodeWithText("今天").performClick()
        composeRule.onNodeWithText("少一点惯性，\n多一点自己。")
            .assertIsDisplayed()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val skipScheduleAccessibility = InstrumentationRegistry.getArguments()
            .getString("skipScheduleAccessibility") == "true"
        if (!skipScheduleAccessibility) {
        val uiAutomation = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )
        val runShellCommand = { command: String ->
            ParcelFileDescriptor.AutoCloseInputStream(
                uiAutomation.executeShellCommand(command),
            ).bufferedReader().use { it.readText() }
        }
        val originalEnabledServices = runShellCommand(
            "settings --user 0 get secure enabled_accessibility_services",
        ).trim()
        val originalAccessibilityEnabled = runShellCommand(
            "settings --user 0 get secure accessibility_enabled",
        ).trim()
        try {
        runBlocking {
            container.settingsStore.acceptDisclosureAndAgeEligibility()
            container.settingsStore.completeOnboardingPreview()
            container.repository.setTarget(InstalledApp("com.android.chrome", "Chrome"), true)
        }
        runShellCommand(
            "settings --user 0 put secure enabled_accessibility_services " +
                "app.pausecn/app.pausecn.accessibility.PauseAccessibilityService",
        )
        runShellCommand("settings --user 0 put secure accessibility_enabled 1")
        composeRule.waitUntil(timeoutMillis = 40_000) {
            composeRule.onAllNodesWithText("服务正常").fetchSemanticsNodes().isNotEmpty()
        }

        val now = ZonedDateTime.now()
        val currentMinute = now.hour * 60 + now.minute
        assertTrue("API 36 UI test needs two future minutes today", currentMinute <= 1437)
        val todayStart = currentMinute + 2
        val todayMask = 1 shl (now.dayOfWeek.value - 1)
        runBlocking {
            container.settingsStore.setScheduleEnabled(true)
            container.settingsStore.setScheduleWindow(todayStart, todayStart + 1)
            container.settingsStore.setActiveDays(todayMask)
        }
        val todayTime = "%02d:%02d".format(todayStart / 60, todayStart % 60)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(
                "当前不在计划时段，将于 今天 $todayTime 自动恢复。",
            ).fetchSemanticsNodes().isNotEmpty()
        }

        val tomorrow = now.plusDays(1)
        val tomorrowMask = 1 shl (tomorrow.dayOfWeek.value - 1)
        runBlocking {
            container.settingsStore.setScheduleWindow(9 * 60, 9 * 60 + 1)
            container.settingsStore.setActiveDays(tomorrowMask)
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(
                "当前不在计划时段，将于 明天 09:00 自动恢复。",
            ).fetchSemanticsNodes().isNotEmpty()
        }

        val later = now.plusDays(3)
        val laterMask = 1 shl (later.dayOfWeek.value - 1)
        val laterDayLabel = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            .get(later.dayOfWeek.value - 1)
        runBlocking {
            container.settingsStore.setScheduleWindow(10 * 60, 10 * 60 + 1)
            container.settingsStore.setActiveDays(laterMask)
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(
                "当前不在计划时段，将于 $laterDayLabel 10:00 自动恢复。",
            ).fetchSemanticsNodes().isNotEmpty()
        }

        runBlocking { container.settingsStore.setScheduleEnabled(false) }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("干预计划已关闭，需要手动开启后才会恢复。")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("“启用干预计划”已关闭，或没有选择任何生效日期。此状态不会自动恢复。")
            .assertIsDisplayed()
        runBlocking {
            container.settingsStore.setScheduleEnabled(true)
            container.settingsStore.setScheduleWindow(0, 0)
            container.settingsStore.setActiveDays(app.pausecn.domain.ScheduleSpec.ALL_DAYS)
            container.repository.setTarget(InstalledApp("com.android.chrome", "Chrome"), false)
        }
        } finally {
            if (originalEnabledServices.isBlank() || originalEnabledServices == "null") {
                runShellCommand("settings --user 0 delete secure enabled_accessibility_services")
            } else {
                runShellCommand(
                    "settings --user 0 put secure enabled_accessibility_services " +
                        "'$originalEnabledServices'",
                )
            }
            if (originalAccessibilityEnabled.isBlank() || originalAccessibilityEnabled == "null") {
                runShellCommand("settings --user 0 delete secure accessibility_enabled")
            } else {
                runShellCommand(
                    "settings --user 0 put secure accessibility_enabled " +
                        "'$originalAccessibilityEnabled'",
                )
            }
        }
        }

        runBlocking { container.settingsStore.setActiveDays(app.pausecn.domain.ScheduleSpec.ALL_DAYS) }
        runBlocking { container.repository.setTarget(InstalledApp("app.pausecn.missing.test", "已卸载示例"), false) }

        val interruptedEventId = runBlocking {
            container.repository.beginIntervention(
                packageName = "app.pausecn.recovery.test",
                appLabel = "恢复示例",
                nowEpochMs = System.currentTimeMillis(),
            ).eventId
        }
        container.databaseHealthStore.reportFailure(
            DatabaseFailureReason.INTEGRITY_CHECK_FAILED,
            nowEpochMs = 123L,
        )
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("本地数据需要保护").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("请勿卸载应用或清除应用数据").assertIsDisplayed()
        composeRule.onNodeWithText("诊断编号：DB-INTEGRITY").performScrollTo().assertIsDisplayed()
        runBlocking { delay(6_000) }
        composeRule.onNodeWithText("重新检查本地数据").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            container.databaseHealthStore.state.value == DatabaseHealthState.Healthy
        }
        assertEquals(
            InterventionOutcome.DISMISSED.name,
            runBlocking {
                container.repository.loadExportData().events
                    .first { it.id == interruptedEventId }
                    .outcome
            },
        )
        composeRule.onNodeWithText("记录").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(
                "本周有 1 次停顿未能显示，2 次在选择前被系统、锁屏或服务中断。" +
                    "这些都不属于你的主动离开或继续选择。",
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("恢复示例"))
        composeRule.onNodeWithText("恢复示例").assertIsDisplayed()
        composeRule.onNodeWithText("今天").performClick()
        runBlocking { container.repository.clearHistory() }
        composeRule.onNodeWithText("少一点惯性，\n多一点自己。").assertIsDisplayed()

        container.settingsHealthStore.reportUnavailable(
            SettingsFailureReason.READ_OR_WRITE_FAILED,
            nowEpochMs = 456L,
        )
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("本地设置暂时不可用").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("诊断编号：SETTINGS-IO").performScrollTo().assertIsDisplayed()
        runBlocking {
            delay(6_000)
            container.settingsStore.setScheduleEnabled(false)
        }
        composeRule.onNodeWithText("重新检查本地设置").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            container.settingsHealthStore.state.value == SettingsHealthState.Healthy
        }
        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNodeWithText("启用干预计划").assertIsOff()
        runBlocking { container.settingsStore.setScheduleEnabled(true) }
        composeRule.onNodeWithText("今天").performClick()
        composeRule.onNodeWithText("少一点惯性，\n多一点自己。").assertIsDisplayed()
    }

    @Test
    fun aiSettings_manualPhraseSavesAndPreviewsOfflineAtLargeFont() {
        val container = (composeRule.activity.application as PauseApplication).container
        val aiDao = container.database.aiDao()
        val originalConfig = runBlocking {
            container.aiRepository.load()
            requireNotNull(aiDao.config())
        }
        val originalPhrases = runBlocking { aiDao.phrases() }
        val originalAttempts = container.aiRepository.state.value.attempts
        val manualPhrase = "先问自己现在要做什么？"
        assertTrue(composeRule.activity.resources.configuration.fontScale >= 1.99f)
        assertTrue(!container.aiRepository.state.value.hasKey)

        try {
            composeRule.onNodeWithText("设置").performClick()
            composeRule.onNode(hasScrollAction())
                .performScrollToNode(hasText("AI 提醒与手写短句"))
            composeRule.onNodeWithText("AI 提醒与手写短句")
                .assertIsDisplayed()
                .performClick()

            composeRule.onNodeWithText("Key：未填写 · AI 已关闭").assertIsDisplayed()
            composeRule.onNodeWithText("在手机内填写 Key")
                .assertIsDisplayed()
                .assertIsEnabled()
                .performClick()
            composeRule.onNodeWithText("仅在本机填写 API Key").assertIsDisplayed()
            composeRule.onNodeWithText("DeepSeek API Key").assertIsDisplayed()
            composeRule.onNodeWithText("取消").assertIsDisplayed().performClick()
            composeRule.onNodeWithText("查看说明并启用 AI").assertIsNotEnabled()

            composeRule.onNode(hasScrollAction()).performScrollToNode(
                hasText("手写短句：优先于 AI，可离线使用"),
            )
            val editableFields = composeRule.onAllNodes(hasSetTextAction())
            val manualField = editableFields[editableFields.fetchSemanticsNodes().lastIndex]
            manualField.performScrollTo().performTextInput(manualPhrase)
            composeRule.onNodeWithText("保存风格与手写短句")
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsEnabled()
                .performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                container.aiRepository.state.value.config.manualPhrase == manualPhrase
            }

            composeRule.onNode(hasScrollAction()).performScrollToNode(
                hasText("生成候选短句（调用一次 API）"),
            )
            composeRule.onNodeWithText("生成候选短句（调用一次 API）")
                .assertIsNotEnabled()
            composeRule.onNode(hasScrollAction()).performScrollToNode(
                hasText("预览当前停顿（不联网、不记录）"),
            )
            composeRule.onNodeWithText("预览当前停顿（不联网、不记录）")
                .assertIsDisplayed()
                .assertIsEnabled()
                .performClick()
            val manualMatches = composeRule.onAllNodesWithText(manualPhrase)
            manualMatches[manualMatches.fetchSemanticsNodes().lastIndex].assertIsDisplayed()
            assertEquals(originalAttempts, container.aiRepository.state.value.attempts)
        } finally {
            runBlocking {
                container.database.withTransaction {
                    aiDao.clearPhrases()
                    aiDao.save(originalConfig)
                    if (originalPhrases.isNotEmpty()) aiDao.insertPhrases(originalPhrases)
                }
                container.aiRepository.selector.clear()
                container.aiRepository.load()
            }
        }
    }

    @Test
    fun interventionPreview_canBeExited() {
        val container = (composeRule.activity.application as PauseApplication).container
        runBlocking { container.settingsStore.resetAll() }
        composeRule.onNodeWithText("开始使用").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("暂停 15 分钟").fetchSemanticsNodes().isEmpty())
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("预览一次干预"))
        composeRule.onNodeWithText("预览一次干预").performClick()
        composeRule.onNodeWithText("你正要打开一个应用").assertIsDisplayed()
        composeRule.onNodeWithText("先不打开").performClick()
        assertTrue(
            composeRule.onAllNodesWithText("你正要打开一个应用")
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { container.settingsStore.settings.first().onboardingPreviewCompleted }
        }
    }

    @Test
    fun interventionPreview_keepsExitAvailableAndConfirmsCasualBrowsing() {
        val container = (composeRule.activity.application as PauseApplication).container
        val eventCountBefore = runBlocking { container.database.interventionEventDao().getAllForExport().size }
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("预览一次干预"))
        composeRule.onNodeWithText("预览一次干预").performClick()
        composeRule.onNodeWithText("你正要打开一个应用").assertIsDisplayed()
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(7_000)
        composeRule.mainClock.autoAdvance = true

        composeRule.onNodeWithText("带着目的继续").assertIsEnabled().performClick()
        composeRule.onNodeWithText("先不打开").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("随便看看").performScrollTo().performClick()
        composeRule.onNodeWithText("没有明确目的，也可以继续。请再确认这是现在想做的事。").assertIsDisplayed()
        composeRule.onNodeWithText("仍然继续").assertIsDisplayed()
        composeRule.onNodeWithText("先不打开").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("搜资料").performScrollTo().performClick()
        composeRule.onNodeWithText("确认理由并继续").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("这次打开，是为了什么？").fetchSemanticsNodes().isEmpty())
        assertEquals(eventCountBefore, runBlocking { container.database.interventionEventDao().getAllForExport().size })
    }

    @Test
    fun privacyResetRequiresExplicitConfirmation() {
        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("重置全部本地数据"))
        composeRule.onNodeWithText("重置全部本地数据").performClick()

        composeRule.onNodeWithText("重置全部本地数据？").assertIsDisplayed()
        composeRule.onNodeWithText("取消").performClick()
        assertTrue(
            composeRule.onAllNodesWithText("重置全部本地数据？")
                .fetchSemanticsNodes()
                .isEmpty(),
        )
    }

    @Test
    fun encryptedExportRequiresMatchingTwelveCharacterPassword() {
        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("加密导出本地数据"))
        composeRule.onNodeWithText("加密导出本地数据").performClick()

        composeRule.onNodeWithText("导出包含目标应用、规则、设置、干预历史、停顿延迟和服务连接/心跳。文件只写入你选择的位置，不会上传。")
            .assertIsDisplayed()
        composeRule.onNodeWithText("选择保存位置").assertIsNotEnabled()
        composeRule.onAllNodes(hasSetTextAction())[0].assert(hasImeAction(ImeAction.Next))
        composeRule.onAllNodes(hasSetTextAction())[1].assert(hasImeAction(ImeAction.Done))

        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("12345678")
        composeRule.onAllNodes(hasSetTextAction())[1].performTextInput("12345678")
        composeRule.onNodeWithText("至少输入 12 个 Unicode 码点，且代理对必须完整").assertIsDisplayed()
        composeRule.onNodeWithText("选择保存位置").assertIsNotEnabled()

        composeRule.onAllNodes(hasSetTextAction())[0].performTextClearance()
        composeRule.onAllNodes(hasSetTextAction())[1].performTextClearance()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("😀😀😀😀😀😀")
        composeRule.onAllNodes(hasSetTextAction())[1].performTextInput("😀😀😀😀😀😀")
        composeRule.onNodeWithText("至少输入 12 个 Unicode 码点，且代理对必须完整").assertIsDisplayed()
        composeRule.onNodeWithText("选择保存位置").assertIsNotEnabled()

        composeRule.onAllNodes(hasSetTextAction())[0].performTextClearance()
        composeRule.onAllNodes(hasSetTextAction())[1].performTextClearance()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("😀😁😂😃😄😅😆😉😊😋😎😍")
        composeRule.onAllNodes(hasSetTextAction())[1].performTextInput("😀😁😂😃😄😅😆😉😊😋😎😍")
        composeRule.onNodeWithText("选择保存位置").assertIsEnabled()

        composeRule.onAllNodes(hasSetTextAction())[0].performTextClearance()
        composeRule.onAllNodes(hasSetTextAction())[1].performTextClearance()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("123456789012")
        composeRule.onAllNodes(hasSetTextAction())[1].performTextInput("210987654321")
        composeRule.onNodeWithText("两次输入不一致").assertIsDisplayed()
        composeRule.onNodeWithText("选择保存位置").assertIsNotEnabled()

        composeRule.onAllNodes(hasSetTextAction())[1].performTextClearance()
        composeRule.onAllNodes(hasSetTextAction())[1].performTextInput("123456789012")
        composeRule.onNodeWithText("选择保存位置").assertIsEnabled()
        composeRule.onNodeWithText("取消").performClick()
    }

    @Test
    fun historyRetentionChoicePersists() {
        val container = (composeRule.activity.application as PauseApplication).container
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60 * 60_000
        runBlocking {
            container.repository.clearTargetsAndHistory()
            container.settingsStore.setHistoryRetentionDays(90)
            container.settingsStore.setInterventionSeconds(10)
            container.settingsStore.setTemporaryPassMinutes(15)
            container.repository.setTarget(InstalledApp("app.pausecn.retention.target", "保留目标"), true)
            container.repository.recordIntervention(
                packageName = "app.pausecn.retention.old",
                appLabel = "旧记录",
                outcome = InterventionOutcome.CONTINUED,
                purpose = "expired-after-shrink",
                retentionDays = 365,
                nowEpochMs = now - 60 * dayMs,
            )
            container.repository.recordIntervention(
                packageName = "app.pausecn.retention.recent",
                appLabel = "近期记录",
                outcome = InterventionOutcome.EXITED,
                purpose = "inside-retention",
                retentionDays = 365,
                nowEpochMs = now - 10 * dayMs,
            )
            container.repository.beginServiceSession(
                retentionDays = 365,
                nowEpochMs = now - 60 * dayMs,
                nowElapsedMs = 100,
            )
            container.repository.beginServiceSession(
                retentionDays = 365,
                nowEpochMs = now - 10 * dayMs,
                nowElapsedMs = 200,
            )
        }

        fun assertSeedDataUnchanged() {
            val export = runBlocking { container.repository.loadExportData() }
            assertEquals(
                setOf("expired-after-shrink", "inside-retention"),
                export.events.mapNotNull { it.purpose }.toSet(),
            )
            assertEquals(2, export.serviceSessions.size)
            assertEquals(listOf("app.pausecn.retention.target"), export.targets.map { it.packageName })
        }

        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("历史保存期限"))
        composeRule.onNodeWithText("90 天").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("365 天").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("30 天").performScrollTo().performClick()
        composeRule.onNodeWithText("缩短历史保存期限？").assertIsDisplayed()
        composeRule.onNodeWithText(
            "改为 30 天后，超过 30 天的干预历史、停顿延迟和服务连接诊断将立即永久删除，无法恢复。目标应用和其他设置不受影响。",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("缩短并删除旧记录").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("取消").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(90, runBlocking { container.settingsStore.settings.first().historyRetentionDays })
        assertSeedDataUnchanged()

        composeRule.onNodeWithText("30 天").performScrollTo().performClick()
        composeRule.onNodeWithText("缩短历史保存期限？").assertIsDisplayed()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("设置").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeRule.onAllNodesWithText("缩短历史保存期限？").fetchSemanticsNodes().isEmpty())
        assertEquals(90, runBlocking { container.settingsStore.settings.first().historyRetentionDays })
        assertSeedDataUnchanged()

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("历史保存期限"))
        composeRule.onNodeWithText("30 天").performScrollTo().performClick()
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        assertTrue(composeRule.onAllNodesWithText("缩短历史保存期限？").fetchSemanticsNodes().isEmpty())
        assertEquals(90, runBlocking { container.settingsStore.settings.first().historyRetentionDays })
        assertSeedDataUnchanged()
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("历史保存期限"))
        composeRule.onNodeWithText("30 天").performScrollTo().performClick()
        composeRule.onNodeWithText("缩短历史保存期限？").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("清除本地记录？").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("重置全部本地数据？").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("缩短并删除旧记录").assertIsDisplayed().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            val settings = runBlocking { container.settingsStore.settings.first() }
            val export = runBlocking { container.repository.loadExportData() }
            settings.historyRetentionDays == 30 &&
                export.events.mapNotNull { it.purpose } == listOf("inside-retention") &&
                export.serviceSessions.size == 1
        }
        val afterShrinkSettings = runBlocking { container.settingsStore.settings.first() }
        assertEquals(10, afterShrinkSettings.interventionSeconds)
        assertEquals(15, afterShrinkSettings.temporaryPassMinutes)
        assertEquals(
            listOf("app.pausecn.retention.target"),
            runBlocking { container.repository.loadExportData() }.targets.map { it.packageName },
        )

        runBlocking {
            container.repository.recordIntervention(
                packageName = "app.pausecn.retention.redundant",
                appLabel = "防重复清理",
                outcome = InterventionOutcome.CONTINUED,
                purpose = "must-survive-selected-chip",
                retentionDays = 365,
                nowEpochMs = now - 60 * dayMs,
            )
        }
        composeRule.onNodeWithText("30 天").performScrollTo().performClick()
        composeRule.waitForIdle()
        runBlocking { delay(500) }
        assertTrue(composeRule.onAllNodesWithText("缩短历史保存期限？").fetchSemanticsNodes().isEmpty())
        assertTrue(
            runBlocking { container.repository.loadExportData() }.events
                .any { it.purpose == "must-survive-selected-chip" },
        )

        composeRule.onNodeWithText("90 天").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { container.settingsStore.settings.first().historyRetentionDays } == 90
        }
        assertTrue(composeRule.onAllNodesWithText("缩短历史保存期限？").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("365 天").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { container.settingsStore.settings.first().historyRetentionDays } == 365
        }
        assertTrue(composeRule.onAllNodesWithText("缩短历史保存期限？").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithText("90 天").performScrollTo().performClick()
        composeRule.onNodeWithText("缩短历史保存期限？").assertIsDisplayed()
        composeRule.onNodeWithText(
            "改为 90 天后，超过 90 天的干预历史、停顿延迟和服务连接诊断将立即永久删除，无法恢复。目标应用和其他设置不受影响。",
        ).assertIsDisplayed()
        assertEquals(365, runBlocking { container.settingsStore.settings.first().historyRetentionDays })
        composeRule.onNodeWithText("取消").assertIsDisplayed().performClick()
        assertEquals(365, runBlocking { container.settingsStore.settings.first().historyRetentionDays })

        runBlocking {
            container.repository.clearTargetsAndHistory()
            container.settingsStore.setHistoryRetentionDays(90)
            container.settingsStore.setInterventionSeconds(6)
            container.settingsStore.setTemporaryPassMinutes(5)
        }
    }

    @Test
    fun internalBuildCannotMasqueradeAsPublicRelease() {
        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNode(hasScrollAction()).performScrollToNode(
            hasText("内部验证构建：正式隐私政策尚未配置，禁止公开分发。"),
        )

        composeRule.onNodeWithText("运营者：内部测试版本（禁止公开分发）")
            .assertIsDisplayed()
        composeRule.onNodeWithText("内部验证构建：正式隐私政策尚未配置，禁止公开分发。")
            .assertIsDisplayed()
    }

    @Test
    fun confirmedPrivacyResetClearsTargetsSettingsAndDisclosure() {
        val container = (composeRule.activity.application as PauseApplication).container
        runBlocking {
            container.repository.setTarget(InstalledApp("example.target", "Example"), true)
            container.settingsStore.acceptDisclosureAndAgeEligibility()
            container.repository.recordIntervention(
                packageName = "example.target",
                appLabel = "Example",
                outcome = app.pausecn.data.InterventionOutcome.CONTINUED,
                purpose = "测试",
            )
            container.repository.beginServiceSession(nowEpochMs = 123L)
        }

        val databaseBlockEntered = CompletableDeferred<Unit>()
        val releaseDatabaseBlock = CompletableDeferred<Unit>()
        val databaseBlocker = CoroutineScope(Dispatchers.IO).launch {
            container.database.withTransaction {
                databaseBlockEntered.complete(Unit)
                releaseDatabaseBlock.await()
            }
        }
        runBlocking { databaseBlockEntered.await() }

        try {
            composeRule.onNodeWithText("设置").performClick()
            composeRule.onNode(hasScrollAction())
                .performScrollToNode(hasText("重置全部本地数据"))
            composeRule.onNodeWithText("重置全部本地数据").performClick()
            composeRule.onNodeWithText("确认重置").performClick()

            composeRule.onNodeWithText("正在处理本地数据").assertIsDisplayed()
            composeRule.onNodeWithText("正在完成删除并核对结果，请勿离开。")
                .assertIsDisplayed()

            val todayBounds = composeRule.onNodeWithText("今天")
                .fetchSemanticsNode().boundsInRoot
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "input tap ${todayBounds.center.x.toInt()} ${todayBounds.center.y.toInt()}",
                ),
            ).use { it.readBytes() }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("正在处理本地数据").assertIsDisplayed()

            pressBack()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("正在处理本地数据").assertIsDisplayed()
        } finally {
            releaseDatabaseBlock.complete(Unit)
            runBlocking { databaseBlocker.join() }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                    container.repository.targets.first().isEmpty() &&
                    container.repository.recentEvents.first().isEmpty() &&
                    container.repository.loadExportData().serviceSessions.isEmpty() &&
                    !container.settingsStore.settings.first().disclosureAccepted &&
                    !container.settingsStore.settings.first().ageEligibilityConfirmed &&
                    !container.settingsStore.settings.first().onboardingPreviewCompleted
            }
        }
    }

    @Test
    fun accessibilityDisclosureRequiresFourteenPlusConfirmation() {
        val container = (composeRule.activity.application as PauseApplication).container
        runBlocking { container.settingsStore.resetAll() }

        composeRule.onNodeWithText("今天").performClick()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("继续设置"))
        composeRule.onNodeWithText("继续设置").performClick()

        composeRule.onNodeWithText("向下阅读并确认年龄").assertIsNotEnabled()
        composeRule.onNodeWithTag("ageEligibilityCheckbox")
            .performScrollTo()
            .assertIsOff()
            .performClick()
            .assertIsOn()
        composeRule.onNodeWithText("我理解并确认，继续开启").assertIsEnabled()
        composeRule.onNodeWithText("暂时不用").performClick()

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("继续设置"))
        composeRule.onNodeWithText("继续设置").performClick()
        composeRule.onNodeWithTag("ageEligibilityCheckbox")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("我理解并确认，继续开启").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                val settings = container.settingsStore.settings.first()
                settings.disclosureAccepted && settings.ageEligibilityConfirmed
            }
        }
    }
}
