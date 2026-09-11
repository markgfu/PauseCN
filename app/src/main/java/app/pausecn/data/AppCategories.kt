package app.pausecn.data

import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.text.Normalizer

/** One primary category per package. Names are shared IDs, not independent labels per screen. */
object AppCategories {
    const val UNCLASSIFIED = "未分类"
    val builtIns = listOf("社交通讯", "内容社区", "影音娱乐", "游戏", "购物消费", "学习阅读", "工作效率", "出行生活", "工具", UNCLASSIFIED)
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
    fun valid(value: String): Boolean = value.isNotBlank() && value.codePointCount(0, value.length) <= 20 &&
        value.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }
    // Exact package matches only: never infer a category from arbitrary substrings in app names.
    fun automatic(pkg: String, platformCategory: Int = -1): String = when (pkg) {
        "com.tencent.mm", "com.tencent.mobileqq", "org.telegram.messenger" -> "社交通讯"
        "com.xingin.xhs", "com.sina.weibo", "com.zhihu.android", "gov.pianzong.androidnga", "com.donews.nga" -> "内容社区"
        "com.ss.android.ugc.aweme", "com.smile.gifmaker", "tv.danmaku.bili", "com.youku.phone", "com.qiyi.video", "com.tencent.qqlive", "com.netease.cloudmusic", "com.tencent.qqmusic" -> "影音娱乐"
        "cn.damai", "cn.damai.app", "com.taobao.taobao", "com.jingdong.app.mall", "com.xunmeng.pinduoduo" -> "购物消费"
        "com.tencent.weread", "com.dragon.read", "com.duokan.reader" -> "学习阅读"
        "com.openai.chatgpt", "com.deepseek.chat", "com.alibaba.android.rimet", "com.tencent.wework", "com.ss.android.lark", "cn.wps.moffice_eng" -> "工作效率"
        "com.eg.android.AlipayGphone", "com.sankuai.meituan", "me.ele", "com.autonavi.minimap", "com.baidu.BaiduMap", "com.MobileTicket" -> "出行生活"
        else -> when (platformCategory) {
            0 -> "游戏"
            1, 2 -> "影音娱乐"
            3, 8 -> "工具"
            4 -> "社交通讯"
            5 -> "学习阅读"
            6 -> "出行生活"
            7 -> "工作效率"
            else -> UNCLASSIFIED
        }
    }
}

@Entity(tableName = "app_categories")
data class AppCategoryRow(@PrimaryKey val packageName: String,
    val automatic: String = AppCategories.UNCLASSIFIED, val manual: String? = null) {
    val effective: String get() = manual ?: automatic
}

@Entity(tableName = "app_category_settings")
data class AppCategorySettings(@PrimaryKey val id: Int = 1, val sendToAi: Boolean = false, val revision: Long = 0)

@Dao
interface AppCategoryDao {
    @Query("SELECT * FROM app_categories ORDER BY packageName") suspend fun all(): List<AppCategoryRow>
    @Query("SELECT * FROM app_categories ORDER BY packageName") fun observe(): kotlinx.coroutines.flow.Flow<List<AppCategoryRow>>
    @Query("SELECT * FROM app_category_settings WHERE id = 1") suspend fun settings(): AppCategorySettings?
    @Query("SELECT * FROM app_category_settings WHERE id = 1") fun observeSettings(): kotlinx.coroutines.flow.Flow<AppCategorySettings?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(rows: List<AppCategoryRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSettings(settings: AppCategorySettings)
    @Query("DELETE FROM app_categories") suspend fun clear()
    @Query("DELETE FROM app_category_settings") suspend fun clearSettings()
}

data class AppCategorySnapshot(val rows: List<AppCategoryRow> = emptyList(), val settings: AppCategorySettings = AppCategorySettings()) {
    private val byPackage = rows.associateBy { it.packageName }
    fun category(pkg: String): String = byPackage[pkg]?.effective ?: AppCategories.automatic(pkg)
    fun manual(pkg: String): Boolean = byPackage[pkg]?.manual != null
    val categories: List<String> get() = (AppCategories.builtIns + rows.mapNotNull { it.manual }).distinct()
}

data class PackagePauseCount(val packageName: String, val count: Long)
data class CategoryPauseCount(val category: String, val count: Long)
fun categoryPauseCounts(rows: List<PackagePauseCount>, snapshot: AppCategorySnapshot): List<CategoryPauseCount> =
    rows.groupBy { snapshot.category(it.packageName) }.map { (name, items) -> CategoryPauseCount(name, items.sumOf { it.count }) }
        .sortedWith(compareByDescending<CategoryPauseCount> { it.count }.thenBy { it.category })

class AppCategoryRepository(private val database: PauseDatabase) {
    private val dao = database.appCategoryDao()
    val state = combine(dao.observe(), dao.observeSettings()) { rows, settings -> AppCategorySnapshot(rows, settings ?: AppCategorySettings()) }

    private suspend fun mutate(block: suspend () -> Boolean) = withContext(Dispatchers.IO) {
        database.personalizationGuard.mutate { database.withTransaction {
            if (block()) {
                val previous = dao.settings() ?: AppCategorySettings()
                dao.saveSettings(previous.copy(revision = previous.revision + 1))
                database.reportDao().clearCache()
                database.aiDao().invalidatePersonalization()
                database.aiDao().clearPersonalized()
                database.conversationDao().invalidate()
            }
        } }
    }

    /** Updating automatic suggestions never overwrites manual choices; absent apps retain history labels. */
    suspend fun updateAutomatic(apps: List<InstalledApp>) = withContext(Dispatchers.IO) {
        // A foreground catalog refresh is normally read-only. Do not invalidate the overlay's
        // in-memory revision when every automatic suggestion is already current.
        val existing = dao.all().associateBy { it.packageName }
        if (apps.all { existing[it.packageName]?.automatic == AppCategories.automatic(it.packageName, it.platformCategory) }) {
            return@withContext
        }
        mutate {
        val old = dao.all().associateBy { it.packageName }
        val changed = apps.distinctBy { it.packageName }.map { app ->
            AppCategoryRow(app.packageName, AppCategories.automatic(app.packageName, app.platformCategory), old[app.packageName]?.manual)
        }.filter { it != old[it.packageName] }
        if (changed.isNotEmpty()) dao.save(changed)
        changed.isNotEmpty()
        }
    }

    suspend fun assign(packages: Set<String>, category: String?) = mutate {
        require(packages.isNotEmpty() && packages.size <= 2_000)
        val name = category?.let(AppCategories::normalize)
        require(name == null || AppCategories.valid(name))
        val old = dao.all().associateBy { it.packageName }
        val changed = packages.map { pkg ->
            require(pkg.isNotBlank())
            (old[pkg] ?: AppCategoryRow(pkg, AppCategories.automatic(pkg))).copy(manual = name)
        }.filter { it != old[it.packageName] }
        if (changed.isNotEmpty()) dao.save(changed)
        changed.isNotEmpty()
    }

    suspend fun allowAi(enabled: Boolean) = mutate {
        val previous = dao.settings() ?: AppCategorySettings()
        if (previous.sendToAi != enabled) dao.saveSettings(previous.copy(sendToAi = enabled))
        previous.sendToAi != enabled
    }

    internal suspend fun applyAi(delivery: app.pausecn.ai.CategoryAiDelivery, selected: Set<String>, installed: Set<String>) = mutate {
        val snapshot = appCategorySnapshot(database)
        check(app.pausecn.ai.CategoryAiProtocol.current(delivery, database.aiDao().config(), snapshot.settings.revision,
            System.currentTimeMillis())) { "分类或AI授权已变化，建议已失效。" }
        val rows = app.pausecn.ai.CategoryAiProtocol.changes(delivery, selected, snapshot, installed)
        dao.save(rows)
        true
    }
}

internal suspend fun appCategorySnapshot(db: PauseDatabase) = AppCategorySnapshot(db.appCategoryDao().all(), db.appCategoryDao().settings() ?: AppCategorySettings())
