package app.pausecn.ai

import app.pausecn.data.*
import org.json.JSONArray
import org.json.JSONObject

data class CategoryAiApp(val id: Int, val packageName: String, val label: String, val current: String)
data class CategoryAiPlan(val apps: List<CategoryAiApp>, val categories: List<String>, val revision: Long) {
    val batches: List<List<CategoryAiApp>> get() = apps.chunked(50)
}
data class CategoryAiSuggestion(val app: CategoryAiApp, val category: String) {
    val changes: Boolean get() = category != AppCategories.UNCLASSIFIED && category != app.current
}
data class CategoryAiDelivery(val plan: CategoryAiPlan, val config: AiConfig,
    val suggestions: List<CategoryAiSuggestion>, val completed: Int, val notice: String,
    val createdAt: Long = System.currentTimeMillis())

/** Local package IDs never cross the network boundary; response IDs are request-local integers. */
object CategoryAiProtocol {
    const val PROMPT_ASSET = "prompts/app_categories.md"
    fun plan(apps: List<InstalledApp>, snapshot: AppCategorySnapshot, includeAutomatic: Boolean): CategoryAiPlan {
        val eligible = apps.distinctBy { it.packageName }.filter { it.isInstalled && !snapshot.manual(it.packageName) &&
            (includeAutomatic || snapshot.category(it.packageName) == AppCategories.UNCLASSIFIED) }
        require(eligible.size <= 2_000) { "应用过多，请先缩小筛选范围。" }
        val categories = snapshot.categories.toList()
        require(categories.size <= 200 && categories.all(AppCategories::valid)) { "分类列表过大或名称无效，请先整理分类。" }
        return CategoryAiPlan(eligible.mapIndexed { id, app -> CategoryAiApp(id, app.packageName,
            sanitizeInstalledAppLabel(app.label, app.packageName).takeUnless { it == app.packageName } ?: "未命名应用",
            snapshot.category(app.packageName)) }, categories, snapshot.settings.revision)
    }
    fun input(plan: CategoryAiPlan, batch: List<CategoryAiApp>): String = JSONObject()
        .put("categories", JSONArray(plan.categories))
        .put("apps", JSONArray().apply { batch.forEach { put(JSONArray().put(it.id).put(it.label)) } }).toString()

    fun parse(text: String, plan: CategoryAiPlan, batch: List<CategoryAiApp>): List<CategoryAiSuggestion> {
        require(text.length <= 16_000)
        val rows = JSONObject(text).getJSONArray("assignments")
        require(rows.length() == batch.size)
        val byId = batch.associateBy { it.id }
        val seen = hashSetOf<Int>()
        val suggestions = (0 until rows.length()).map { i ->
            val row = rows.getJSONArray(i)
            require(row.length() == 2 && row.get(0) is Int && row.get(1) is Int)
            val id = row.getInt(0); val category = row.getInt(1)
            require(id in byId && seen.add(id) && category in plan.categories.indices)
            CategoryAiSuggestion(requireNotNull(byId[id]), plan.categories[category])
        }
        return suggestions.sortedBy { it.app.id }
    }
    fun current(delivery: CategoryAiDelivery, config: AiConfig?, revision: Long, now: Long): Boolean =
        config != null && config.enabled && config.instanceId == delivery.config.instanceId &&
            config.privacyEpoch == delivery.config.privacyEpoch && config.model == delivery.config.model &&
            revision == delivery.plan.revision && now >= delivery.createdAt && now - delivery.createdAt < 600_000

    /** Fail as a whole on conflicts; never overwrite a newly manual category. */
    fun changes(delivery: CategoryAiDelivery, selected: Set<String>, snapshot: AppCategorySnapshot,
        installed: Set<String>): List<AppCategoryRow> {
        require(selected.isNotEmpty() && snapshot.settings.revision == delivery.plan.revision)
        val allowed = delivery.suggestions.filter { it.changes }.associateBy { it.app.packageName }
        require(selected.all { it in allowed && it in installed && !snapshot.manual(it) })
        val old = snapshot.rows.associateBy { it.packageName }
        return selected.sorted().map { pkg ->
            val suggestion = requireNotNull(allowed[pkg])
            require(suggestion.category in snapshot.categories && snapshot.category(pkg) == suggestion.app.current)
            (old[pkg] ?: AppCategoryRow(pkg, AppCategories.automatic(pkg))).copy(manual = suggestion.category)
        }
    }
}
