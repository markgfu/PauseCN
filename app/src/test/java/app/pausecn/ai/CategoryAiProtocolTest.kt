package app.pausecn.ai

import app.pausecn.data.AppCategories
import app.pausecn.data.AppCategoryRow
import app.pausecn.data.AppCategorySettings
import app.pausecn.data.AppCategorySnapshot
import app.pausecn.data.InstalledApp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryAiProtocolTest {
    @Test
    fun `plan respects installation manual and automatic scope while input exposes only ids and sanitized labels`() {
        val snapshot = AppCategorySnapshot(
            rows = listOf(
                AppCategoryRow("manual.pkg", automatic = "工具", manual = "学习阅读"),
                AppCategoryRow("com.tencent.mm", automatic = "社交通讯"),
            ),
            settings = AppCategorySettings(revision = 7),
        )
        val apps = listOf(
            InstalledApp("unknown.pkg", "未知\n应用"),
            InstalledApp("unknown.pkg", "重复项"),
            InstalledApp("com.tencent.mm", "微信"),
            InstalledApp("manual.pkg", "手动应用"),
            InstalledApp("gone.pkg", "已卸载", isInstalled = false),
        )

        val unclassifiedOnly = CategoryAiProtocol.plan(apps, snapshot, includeAutomatic = false)
        assertEquals(listOf("unknown.pkg"), unclassifiedOnly.apps.map { it.packageName })
        assertEquals(7, unclassifiedOnly.revision)

        val includingAutomatic = CategoryAiProtocol.plan(apps, snapshot, includeAutomatic = true)
        assertEquals(listOf("unknown.pkg", "com.tencent.mm"), includingAutomatic.apps.map { it.packageName })
        val input = CategoryAiProtocol.input(includingAutomatic, includingAutomatic.batches.single())
        assertTrue(input.contains("未知 应用"))
        listOf("unknown.pkg", "com.tencent.mm", "manual.pkg", "gone.pkg", "packageName", "current", "style", "usage")
            .forEach { assertFalse(input.contains(it)) }
        assertEquals(listOf(0, 1), includingAutomatic.apps.map { it.id })
    }

    @Test
    fun `parse rejects unknown duplicate and noninteger or out of range assignments`() {
        val plan = plan()
        val batch = plan.apps
        fun response(vararg rows: Any) = JSONObject().put("assignments", JSONArray().apply { rows.forEach(::put) }).toString()
        fun row(id: Any, category: Any) = JSONArray().put(id).put(category)

        assertThrows(IllegalArgumentException::class.java) {
            CategoryAiProtocol.parse(response(row(99, 0), row(1, 0)), plan, batch)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CategoryAiProtocol.parse(response(row(0, 0), row(0, 1)), plan, batch)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CategoryAiProtocol.parse(response(row(0, plan.categories.size), row(1, 0)), plan, batch)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CategoryAiProtocol.parse("{\"assignments\":[[0.5,0],[1,0]]}", plan, batch)
        }
    }

    @Test
    fun `changes are atomic and never overwrite manual uninstalled unknown or stale current categories`() {
        val plan = plan()
        val delivery = delivery(plan, listOf(
            CategoryAiSuggestion(plan.apps[0], "学习阅读"),
            CategoryAiSuggestion(plan.apps[1], AppCategories.UNCLASSIFIED),
        ))
        val snapshot = AppCategorySnapshot(
            rows = listOf(AppCategoryRow("app.a", automatic = AppCategories.UNCLASSIFIED)),
            settings = AppCategorySettings(revision = plan.revision),
        )

        val accepted = CategoryAiProtocol.changes(delivery, setOf("app.a"), snapshot, setOf("app.a", "app.b"))
        assertEquals("学习阅读", accepted.single().manual)
        assertThrows(IllegalArgumentException::class.java) {
            CategoryAiProtocol.changes(delivery, setOf("app.b"), snapshot, setOf("app.a", "app.b"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CategoryAiProtocol.changes(delivery, setOf("app.a"), snapshot, setOf("app.b"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CategoryAiProtocol.changes(delivery, setOf("app.a"), snapshot.copy(rows = listOf(
                AppCategoryRow("app.a", automatic = AppCategories.UNCLASSIFIED, manual = "工具"),
            )), setOf("app.a"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CategoryAiProtocol.changes(delivery, setOf("app.a"), snapshot.copy(rows = listOf(
                AppCategoryRow("app.a", automatic = "社交通讯"),
            )), setOf("app.a"))
        }
    }

    @Test
    fun `delivery current requires unchanged enabled authorization revision and ten minute lifetime`() {
        val plan = plan()
        val config = AiConfig(instanceId = "instance", privacyEpoch = 3, enabled = true, model = "model")
        val delivery = CategoryAiDelivery(plan, config, emptyList(), 0, "fixture", createdAt = 1_000)

        assertTrue(CategoryAiProtocol.current(delivery, config, plan.revision, 600_999))
        assertFalse(CategoryAiProtocol.current(delivery, config.copy(enabled = false), plan.revision, 2_000))
        assertFalse(CategoryAiProtocol.current(delivery, config.copy(privacyEpoch = 4), plan.revision, 2_000))
        assertFalse(CategoryAiProtocol.current(delivery, config, plan.revision + 1, 2_000))
        assertFalse(CategoryAiProtocol.current(delivery, config, plan.revision, 601_000))
        assertFalse(CategoryAiProtocol.current(delivery, config, plan.revision, 999))
    }

    private fun plan() = CategoryAiPlan(
        apps = listOf(
            CategoryAiApp(0, "app.a", "应用甲", AppCategories.UNCLASSIFIED),
            CategoryAiApp(1, "app.b", "应用乙", AppCategories.UNCLASSIFIED),
        ),
        categories = AppCategories.builtIns,
        revision = 7,
    )

    private fun delivery(plan: CategoryAiPlan, suggestions: List<CategoryAiSuggestion>) = CategoryAiDelivery(
        plan, AiConfig(instanceId = "instance", enabled = true), suggestions, suggestions.size, "fixture", createdAt = 1_000,
    )
}
