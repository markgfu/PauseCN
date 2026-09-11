package app.pausecn

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pausecn.data.InstalledApp
import app.pausecn.data.InterventionEventEntity
import app.pausecn.data.InterventionOutcome
import app.pausecn.data.PauseDatabase
import app.pausecn.data.PauseRepository
import app.pausecn.data.TargetRuleEntity
import app.pausecn.ui.PauseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TargetsSelectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun bulkButtonSelectsRemainingInstalledAppsThenDeselectsTheWholeCurrentList() {
        val selected = TargetRuleEntity("example.chat.selected", "聊天乙")
        val unavailable = TargetRuleEntity("example.chat.unavailable", "聊天旧版")
        val expected = InstalledApp("example.chat.new", "聊天甲")
        val apps = listOf(
            expected,
            InstalledApp(selected.packageName, selected.label),
            InstalledApp("example.chat.unavailable", "聊天旧版", isInstalled = false),
            InstalledApp("example.browser", "浏览器"),
        )
        var selectedCallback: List<InstalledApp>? = null
        var deselectedCallback: List<InstalledApp>? = null
        composeRule.setContent {
            var targetRows by remember { mutableStateOf(listOf(selected, unavailable)) }
            PauseTheme {
                TargetsScreen(
                    installedApps = apps,
                    targets = targetRows,
                    query = "",
                    loading = false,
                    loadFailed = false,
                    catalogAuthoritative = true,
                    loadIcon = { null },
                    onToggle = { _, _ -> },
                    onSelectAll = { chosen ->
                        selectedCallback = chosen
                        targetRows = targetRows + chosen.map { TargetRuleEntity(it.packageName, it.label) }
                    },
                    onDeselectAll = { chosen ->
                        deselectedCallback = chosen
                        val removed = chosen.mapTo(hashSetOf()) { it.packageName }
                        targetRows = targetRows.filterNot { it.packageName in removed }
                    },
                    onQueryChanged = {},
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText("全选").assertIsDisplayed()
        composeRule.onNodeWithTag("select_all_targets").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(setOf(expected, apps[3]), requireNotNull(selectedCallback).toSet())
        }
        composeRule.onNodeWithText("取消全选").assertIsDisplayed()
        composeRule.onNodeWithTag("select_all_targets").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(apps.toSet(), requireNotNull(deselectedCallback).toSet()) }
        composeRule.onNodeWithText("全选").assertIsDisplayed()
    }

    @Test
    fun deselectAllSearchResultsDoesNotTouchSelectedAppsOutsideTheSearch() {
        val chatInstalled = InstalledApp("example.chat.installed", "聊天")
        val chatUnavailable = InstalledApp("example.chat.unavailable", "聊天旧版", isInstalled = false)
        val browser = InstalledApp("example.browser", "浏览器")
        val apps = listOf(chatInstalled, chatUnavailable, browser)
        val targets = apps.map { TargetRuleEntity(it.packageName, it.label) }
        var received: List<InstalledApp>? = null
        composeRule.setContent {
            PauseTheme {
                TargetsScreen(
                    installedApps = apps,
                    targets = targets,
                    query = "聊天",
                    loading = false,
                    loadFailed = false,
                    catalogAuthoritative = true,
                    loadIcon = { null },
                    onToggle = { _, _ -> },
                    onSelectAll = {},
                    onDeselectAll = { received = it },
                    onQueryChanged = {},
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText("取消全选搜索结果").assertIsDisplayed()
        composeRule.onNodeWithTag("select_all_targets").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals(setOf(chatInstalled, chatUnavailable), requireNotNull(received).toSet())
            assertFalse(requireNotNull(received).contains(browser))
        }
    }

    @Test
    fun bulkButtonIsDisabledForEmptyLoadingFailureAndSavingStates() {
        val app = InstalledApp("example.selected", "已选择")
        var mode by mutableStateOf(0)
        var callbackCount = 0
        composeRule.setContent {
            val apps = if (mode == 0) emptyList() else listOf(app)
            TargetsScreen(
                installedApps = apps,
                targets = emptyList(),
                query = "",
                loading = mode == 1,
                loadFailed = mode == 2,
                catalogAuthoritative = true,
                loadIcon = { null },
                onToggle = { _, _ -> },
                onSelectAll = { callbackCount += 1 },
                onDeselectAll = { callbackCount += 1 },
                onQueryChanged = {},
                onRefresh = {},
                selectingAll = mode == 3,
            )
        }

        composeRule.onNodeWithText("全选").assertIsDisplayed()
        composeRule.onNodeWithTag("select_all_targets").assertIsNotEnabled()

        composeRule.runOnIdle { mode = 1 }
        composeRule.onNodeWithTag("select_all_targets").assertIsNotEnabled()

        composeRule.runOnIdle { mode = 2 }
        composeRule.onNodeWithTag("select_all_targets").assertIsNotEnabled()

        composeRule.runOnIdle { mode = 3 }
        composeRule.onNodeWithText("正在保存…").assertIsDisplayed()
        composeRule.onNodeWithTag("select_all_targets").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, callbackCount) }
    }

    @Test
    fun repositoryBulkSelectionAndDeselectionAreSafeIdempotentAndKeepHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PauseDatabase::class.java).build()
        try {
            val disabledCreatedAt = 111L
            val enabledCreatedAt = 222L
            database.targetRuleDao().upsert(
                TargetRuleEntity("example.disabled", "保留旧标签", enabled = false, createdAtEpochMs = disabledCreatedAt),
            )
            database.targetRuleDao().upsert(
                TargetRuleEntity("example.enabled", "已启用旧标签", enabled = true, createdAtEpochMs = enabledCreatedAt),
            )
            val repository = PauseRepository(context, database)
            val requested = listOf(
                InstalledApp("example.new", "新应用"),
                InstalledApp("example.disabled", "不应覆盖标签"),
                InstalledApp("example.enabled", "同样不应覆盖"),
                InstalledApp("example.uninstalled", "已卸载", isInstalled = false),
                InstalledApp("com.android.settings", "系统设置"),
                InstalledApp("example.new", "重复项"),
            )

            repository.selectTargets(requested)
            repository.selectTargets(requested)

            val selectedRows = database.targetRuleDao().getAllForExport().associateBy { it.packageName }
            assertEquals(setOf("example.new", "example.disabled", "example.enabled"), selectedRows.keys)
            assertTrue(requireNotNull(selectedRows["example.disabled"]).enabled)
            assertEquals("保留旧标签", selectedRows["example.disabled"]?.label)
            assertEquals(disabledCreatedAt, selectedRows["example.disabled"]?.createdAtEpochMs)
            assertEquals("已启用旧标签", selectedRows["example.enabled"]?.label)
            assertEquals(enabledCreatedAt, selectedRows["example.enabled"]?.createdAtEpochMs)
            assertTrue(requireNotNull(selectedRows["example.new"]).enabled)
            assertFalse(selectedRows.containsKey("example.uninstalled"))
            assertFalse(selectedRows.containsKey("com.android.settings"))

            database.interventionEventDao().insert(
                InterventionEventEntity(
                    packageName = "example.new",
                    appLabel = "新应用",
                    occurredAtEpochMs = 333L,
                    outcome = InterventionOutcome.CONTINUED.name,
                    purpose = "保留理由",
                ),
            )

            val deselected = listOf(
                InstalledApp("example.new", "新应用"),
                InstalledApp("example.disabled", "当前不可用", isInstalled = false),
                InstalledApp("example.missing", "不存在", isInstalled = false),
                InstalledApp("example.new", "重复项"),
            )
            repository.deselectTargets(deselected)
            repository.deselectTargets(deselected)

            val rows = database.targetRuleDao().getAllForExport().associateBy { it.packageName }
            assertEquals(setOf("example.enabled"), rows.keys)
            assertEquals("已启用旧标签", rows["example.enabled"]?.label)
            assertEquals(enabledCreatedAt, rows["example.enabled"]?.createdAtEpochMs)
            assertFalse(rows.containsKey("example.uninstalled"))
            assertFalse(rows.containsKey("com.android.settings"))
            val history = database.interventionEventDao().getAllForExport().single()
            assertEquals(InterventionOutcome.CONTINUED.name, history.outcome)
            assertEquals("保留理由", history.purpose)
        } finally {
            database.close()
        }
    }
}
