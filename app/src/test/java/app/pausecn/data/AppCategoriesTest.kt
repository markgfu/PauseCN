package app.pausecn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCategoriesTest {
    @Test
    fun `manual category wins while exact and platform suggestions remain deterministic`() {
        val snapshot = AppCategorySnapshot(listOf(
            AppCategoryRow("com.tencent.mm", automatic = "社交通讯", manual = "专注联络"),
            AppCategoryRow("app.reader", automatic = "学习阅读"),
        ))

        assertEquals("专注联络", snapshot.category("com.tencent.mm"))
        assertTrue(snapshot.manual("com.tencent.mm"))
        assertEquals("学习阅读", snapshot.category("app.reader"))
        assertFalse(snapshot.manual("app.reader"))
        assertEquals("工作效率", AppCategories.automatic("com.openai.chatgpt"))
        assertEquals("学习阅读", AppCategories.automatic("unknown.package", platformCategory = 5))
        assertEquals(AppCategories.UNCLASSIFIED, AppCategories.automatic("unknown.package"))
    }

    @Test
    fun `custom names are canonical and package totals are counted once per input row`() {
        assertEquals("ABC", AppCategories.normalize("  ＡＢＣ  "))
        assertEquals("自定义 分类", AppCategories.normalize("  自定义 分类  "))
        assertTrue(AppCategories.valid(AppCategories.normalize("  阅读  ")))
        assertFalse(AppCategories.valid("坏\u200B名称"))
        assertFalse(AppCategories.valid("文".repeat(21)))

        val snapshot = AppCategorySnapshot(listOf(
            AppCategoryRow("app.a", manual = "学习阅读"),
            AppCategoryRow("app.b", automatic = "学习阅读"),
        ))
        assertEquals(
            listOf(CategoryPauseCount("学习阅读", 5), CategoryPauseCount(AppCategories.UNCLASSIFIED, 1)),
            categoryPauseCounts(listOf(PackagePauseCount("app.a", 2), PackagePauseCount("app.b", 3), PackagePauseCount("app.c", 1)), snapshot),
        )
    }
}
