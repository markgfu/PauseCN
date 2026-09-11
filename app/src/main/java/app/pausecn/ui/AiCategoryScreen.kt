package app.pausecn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.pausecn.ai.*
import app.pausecn.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Suggestions are page-local. Leaving never applies them or continues a background upload. */
@Composable
fun AiCategoryScreen(repository: AiRepository, apps: List<InstalledApp>, snapshot: AppCategorySnapshot, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var includeAutomatic by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf<CategoryAiPlan?>(null) }
    var delivery by remember { mutableStateOf<CategoryAiDelivery?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var working by remember { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val planResult = remember(apps, snapshot, includeAutomatic) { runCatching { CategoryAiProtocol.plan(apps, snapshot, includeAutomatic) } }
    val plan = planResult.getOrNull()
    val currentApps = rememberUpdatedState(apps)
    val currentJob = rememberUpdatedState(job)
    val currentlyRequesting = rememberUpdatedState(requesting)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && currentlyRequesting.value) currentJob.value?.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val suggestions = delivery?.suggestions.orEmpty().filter { it.changes }

    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            BackButton(onClick = onBack, label = "返回分类")
            Text("AI一键分类", style = MaterialTheme.typography.headlineMedium)
            Text("AI提建议，你决定是否应用。只处理进入此页前的筛选列表，不改变停顿开关。")
            Text("离开页面会取消未完成请求，未应用的建议不保存。取消前的请求可能已计费。", style = MaterialTheme.typography.bodySmall)
            if (message.isNotBlank()) Text(message)
        }
        if (working) item {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(progress)
            if (requesting) TextButton(onClick = { job?.cancel() }) { Text("取消请求（可能已计费）") }
        }
        if (delivery == null && !working) {
            item {
                Row {
                    Checkbox(includeAutomatic, { includeAutomatic = it })
                    Text("也整理已有的自动分类（手动分类始终保留）")
                }
                Text("默认只整理未分类应用。只有本次明确确认的应用名称和可选分类名称会发送给DeepSeek；不附包名、使用记录、画像、记忆或个性化风格。")
                Text("这与“允许AI参考应用分类”是两个独立功能，不会自动开启那个选项。", style = MaterialTheme.typography.bodySmall)
                if (plan == null) Text("当前范围过大或分类名称无效，请返回整理后再试。")
                else {
                    Text("待整理 ${plan.apps.size} 个 · 预计 ${plan.batches.size} 次请求（每批最多50个）", style = MaterialTheme.typography.titleMedium)
                    Text("可选分类（自定义名称也会发送）：${plan.categories.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { consent = plan }, enabled = plan.apps.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text("生成分类建议")
                    }
                    if (plan.apps.isEmpty()) Text("当前范围没有待整理应用。可切换上方选项，或返回选择其他分类。")
                    Text("本次将发送的应用名称", style = MaterialTheme.typography.titleMedium)
                }
            }
            items(plan?.apps.orEmpty(), key = { it.id }) { app -> Text("${app.label} · 当前：${app.current}") }
        }
        delivery?.let { result ->
            item {
                Text("已处理 ${result.completed} / ${result.plan.apps.size} 个应用", style = MaterialTheme.typography.titleLarge)
                Text(result.notice)
                Text("${suggestions.size} 个有可用变更；不确定或无变化的应用保留原分类。勾选后一次应用，确认结果会作为你的分类选择保存，自动扫描不会覆盖。")
                if (result.plan.revision != snapshot.settings.revision) Text("分类已变化，这份建议已失效，请返回重新整理。")
                Row {
                    TextButton(enabled = !working && suggestions.isNotEmpty(), onClick = {
                        val all = suggestions.map { it.app.packageName }.toSet()
                        selected = if (selected.containsAll(all)) emptySet() else all
                    }) { Text(if (suggestions.isNotEmpty() && selected.size == suggestions.size) "取消全选建议" else "全选建议") }
                    TextButton(enabled = !working, onClick = { delivery = null; selected = emptySet(); message = "" }) { Text("重新选择范围") }
                }
                Button(enabled = !working && selected.isNotEmpty() && result.plan.revision == snapshot.settings.revision,
                    modifier = Modifier.fillMaxWidth(), onClick = {
                        val chosen = selected.toSet()
                        working = true; progress = "正在保存分类，不调用AI…"
                        job = scope.launch {
                            try {
                                repository.applyCategories(result, chosen, currentApps.value.filter { it.isInstalled }.map { it.packageName }.toSet())
                                delivery = null; selected = emptySet(); message = "已应用 ${chosen.size} 个分类；各页面统一生效，停顿开关未改变。"
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { message = "未应用：分类、授权或应用范围可能已变化，请重新整理。" }
                            finally { working = false; job = null }
                        }
                    }) { Text("确认应用 ${selected.size} 个分类") }
            }
            items(suggestions, key = { it.app.packageName }) { suggestion ->
                Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp)) {
                    Checkbox(suggestion.app.packageName in selected, enabled = !working, onCheckedChange = { checked ->
                        selected = if (checked) selected + suggestion.app.packageName else selected - suggestion.app.packageName
                    })
                    Column(Modifier.weight(1f)) {
                        Text(suggestion.app.label, style = MaterialTheme.typography.titleMedium)
                        Text("${suggestion.app.current} → ${suggestion.category}")
                    }
                } }
            }
        }
    }
    consent?.let { captured -> AlertDialog(onDismissRequest = { consent = null }, title = { Text("发送应用名称给DeepSeek？") },
        text = { Text("本次只发送上页列出的${captured.apps.size}个应用名称和${captured.categories.size}个可选分类名称（含自定义名称）。预计${captured.batches.size}次请求，可能收费。不会发送使用记录、包名、画像、对话或Key给其他地址；失败后停止，不自动重试。生成后还需确认才会改分类。请先在AI陪伴→连接与风格中启用AI并保存Key。") },
        confirmButton = { TextButton(enabled = !working, onClick = {
            consent = null; message = ""; working = true; requesting = true; progress = "正在准备本次请求…"
            job = scope.launch {
                try {
                    val result = repository.generateCategories(captured) { batch, total -> progress = "正在整理第 $batch / $total 批…" }
                    delivery = result
                    selected = result.suggestions.filter { it.changes }.map { it.app.packageName }.toSet()
                } catch (cancelled: CancellationException) { message = "已取消，未改动分类；已发出的请求可能计费。"; throw cancelled }
                catch (error: Exception) { message = (error as? AiRequestException)?.publicMessage ?: "未完成分类，未改动应用，也不会自动重试。" }
                finally { working = false; requesting = false; job = null }
            }
        }) { Text("同意并生成建议") } }, dismissButton = { TextButton(onClick = { consent = null }) { Text("取消") } }) }
}
