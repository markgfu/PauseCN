package app.pausecn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pausecn.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun CategoryFilter(snapshot: AppCategorySnapshot, selected: String?, onSelect: (String?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected == null, { onSelect(null) }, label = { Text("全部分类") })
        snapshot.categories.forEach { name -> FilterChip(selected == name, { onSelect(name) }, label = { Text(name) }) }
    }
}

@Composable
fun AppCategoryScreen(repository: AppCategoryRepository, apps: List<InstalledApp>, snapshot: AppCategorySnapshot,
    aiRepository: app.pausecn.ai.AiRepository, onBack: () -> Unit) {
    var aiScope by remember { mutableStateOf<Set<String>?>(null) }
    aiScope?.let { packages ->
        AiCategoryScreen(aiRepository, apps.filter { it.packageName in packages }, snapshot, onBack = { aiScope = null })
        return
    }
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var editing by remember { mutableStateOf<Set<String>?>(null) }
    var custom by remember { mutableStateOf("") }
    var choice by remember { mutableStateOf<String?>(null) }
    var useCustom by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmAi by remember { mutableStateOf(false) }
    fun edit(packages: Set<String>) { editing = packages; choice = null; custom = ""; useCustom = false }
    fun action(block: suspend () -> Unit) { scope.launch {
        busy = true; message = ""
        try { block(); message = "已保存，分类在各页面统一生效。" }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "分类未保存，请稍后重试。" }
        finally { busy = false }
    } }
    val visible = apps.filter { (query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)) &&
        (filter == null || snapshot.category(it.packageName) == filter) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            BackButton(onClick = onBack, label = "返回目标")
            Text("统一应用分类", style = MaterialTheme.typography.headlineMedium)
            Text("每个应用一个主分类；手动优先，不确定时保留未分类。历史统计按当前分类重新分组。")
            OutlinedTextField(query, { query = it.take(100) }, Modifier.fillMaxWidth(), label = { Text("查找应用") }, singleLine = true)
            CategoryFilter(snapshot, filter) { filter = it }
            FilledTonalButton(enabled = !busy && visible.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
                onClick = { aiScope = visible.map { it.packageName }.toSet() }) { Text("AI一键分类 · 当前列表") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy && visible.isNotEmpty(), onClick = {
                    val packages = visible.map { it.packageName }.toSet()
                    selected = if (selected.containsAll(packages)) selected - packages else selected + packages
                }) { Text(if (visible.isNotEmpty() && selected.containsAll(visible.map { it.packageName })) "取消全选当前列表" else "全选当前列表") }
                FilledTonalButton(enabled = !busy && selected.isNotEmpty(), onClick = { edit(selected) }) { Text("分类已选 ${selected.size} 个") }
            }
            Text("自动结果来自本地规则和系统类别；选“恢复自动分类”不会改变是否停顿。", style = MaterialTheme.typography.bodySmall)
            Row {
                Checkbox(snapshot.settings.sendToAi, onCheckedChange = { if (it) confirmAi = true else action { repository.allowAi(false) } }, enabled = !busy)
                Text("允许AI参考应用分类（默认关闭）")
            }
            if (message.isNotBlank()) Text(message)
        }
        items(visible, key = { it.packageName }) { app ->
            Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp)) {
                Checkbox(app.packageName in selected, onCheckedChange = { checked -> selected = if (checked) selected + app.packageName else selected - app.packageName }, enabled = !busy)
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.titleMedium)
                    Text("${snapshot.category(app.packageName)} · ${if (snapshot.manual(app.packageName)) "手动" else "自动"}")
                    TextButton(enabled = !busy, onClick = { edit(setOf(app.packageName)) }) { Text("修改分类") }
                }
            } }
        }
        if (visible.isEmpty()) item { Text("没有匹配应用。试试其他分类或搜索词。") }
    }
    editing?.let { packages -> AlertDialog(onDismissRequest = { editing = null }, title = { Text("设置 ${packages.size} 个应用的分类") },
        text = { Column {
            Row { RadioButton(!useCustom && choice == null, { useCustom = false; choice = null }); Text("恢复自动分类") }
            // Dropdown keeps the editor usable with many custom categories and large fonts.
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text(if (useCustom) "选择已有分类" else choice ?: "选择已有分类") }
                DropdownMenu(expanded, { expanded = false }) {
                    snapshot.categories.forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { choice = name; useCustom = false; expanded = false }) }
                }
            }
            OutlinedTextField(custom, { custom = it.take(80); useCustom = true }, Modifier.fillMaxWidth(), label = { Text("或填写自定义分类（最多20字）") })
            Text("这只是用途分类，不代表本次使用目的；不会自动调整停顿。", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(enabled = !busy && (!useCustom || AppCategories.valid(AppCategories.normalize(custom))), onClick = {
            val category = if (useCustom) custom else choice
            editing = null
            action { repository.assign(packages, category); selected = selected - packages }
        }) { Text("保存分类") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }) }
    if (confirmAi) AlertDialog(onDismissRequest = { confirmAi = false }, title = { Text("允许AI使用分类？") },
        text = { Text("开启后，已授权的个性化提醒和单应用交流可附带该应用分类；已授权的使用解读可附带分类汇总。自定义名称也会发送给DeepSeek。不会发送完整安装列表，不会立即调用AI，也不会开启其他授权。分类不是用户本次目的。") },
        confirmButton = { TextButton(onClick = { confirmAi = false; action { repository.allowAi(true) } }) { Text("同意") } },
        dismissButton = { TextButton(onClick = { confirmAi = false }) { Text("取消") } })
}
