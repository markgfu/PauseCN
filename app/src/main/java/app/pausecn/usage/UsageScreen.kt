package app.pausecn.usage

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.room.withTransaction
import app.pausecn.data.AppContainer
import app.pausecn.data.TargetRuleEntity
import app.pausecn.retryAfterLocalDataRecovery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsageScreen(container: AppContainer, targets: List<TargetRuleEntity>, retentionDays: Int, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val repository = container.usageRepository
    val categories by container.appCategories.state.collectAsStateWithLifecycle(initialValue = app.pausecn.data.AppCategorySnapshot())
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    val safeConfig = remember(repository) { repository.config.retryAfterLocalDataRecovery(
        onFailure = { container.databaseHealthStore.reportFailure(app.pausecn.data.DatabaseFailureReason.OPEN_FAILED) },
        awaitRecovery = { container.databaseHealthStore.state.first { it == app.pausecn.data.DatabaseHealthState.Healthy } },
    ) }
    val config by safeConfig.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<UsageHourEntity>>(emptyList()) }
    var pauses by remember { mutableStateOf<List<UsagePausePoint>>(emptyList()) }
    var displays by remember { mutableStateOf<List<PauseDisplay>>(emptyList()) }
    var metric by remember { mutableStateOf(HeatmapMetric.PAUSES) }
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var confirmEnable by remember { mutableStateOf(false) }
    var selectedCell by remember { mutableStateOf<HeatmapCell?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var snapshotTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var snapshotZone by remember { mutableStateOf(ZoneId.systemDefault()) }
    var reload by remember { mutableIntStateOf(0) }
    val packages = targets.filter { it.enabled }.map { it.packageName }.toSet()
    val historyPackages = (rows.map { it.packageName } + pauses.map { it.packageName } + displays.map { it.packageName }).toSet() - packages
    val displayedPackages = selectedPackage?.takeIf { it in packages || it in historyPackages }?.let { setOf(it) }
        ?: packages.filter { selectedCategory == null || categories.category(it) == selectedCategory }.toSet()
    LaunchedEffect(selectedCategory, categories) { selectedCell = null }

    suspend fun refresh() {
        if (busy) return
        busy = true
        error = null
        try {
            repository.refresh(retentionDays)
            UsageScheduling.reconcile(context, container.database.usageDao().config()?.enabled == true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "读取或保存失败，未将缺失时长补为零；稍后可手动刷新。" }
        finally { busy = false; reload++ }
    }
    LaunchedEffect(lifecycle, retentionDays) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { refresh(); awaitCancellation() }
    }
    LaunchedEffect(config?.revision, config?.lastChecked, packages, reload) {
        rows = emptyList(); pauses = emptyList(); displays = emptyList(); selectedCell = null
        try {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val start = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(6)
                .atStartOfDay(zone).toInstant().toEpochMilli()
            val data = container.database.withUsageSnapshot(start, now)
            rows = data.first
            pauses = data.second
            displays = data.third
            snapshotTime = now
            snapshotZone = zone
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "本地统计暂时不可用；没有显示虚构数据。" }
    }
    val today = Instant.ofEpochMilli(snapshotTime).atZone(snapshotZone).toLocalDate()
    val cells = remember(rows, pauses, metric, displayedPackages, snapshotTime, snapshotZone) {
        UsageHeatmap.cells(today, snapshotZone, snapshotTime, displayedPackages, metric, rows, pauses)
    }
    fun describe(cell: HeatmapCell): String {
        val text = when {
            cell.actualDurationMs == 0L -> "当地不存在的小时"
            cell.future -> "未来时段"
            cell.value == null -> "无可用数据"
            metric == HeatmapMetric.PAUSES -> "已记录 ${cell.value} 次停顿"
            else -> "前台时长估算 ${formatUsageDuration(cell.value)}${if (cell.partial) "，部分数据" else ""}"
        }
        val captured = cell.capturedAt?.let { "；采用的最早采集快照 ${Instant.ofEpochMilli(it).atZone(snapshotZone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}" }.orEmpty()
        return "${cell.date} ${cell.hour}时：$text；该小时实际长度 ${cell.actualDurationMs / 60_000} 分钟$captured"
    }

    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            app.pausecn.ui.BackButton(onClick = onBack, label = "返回记录")
            Spacer(Modifier.height(12.dp))
            Text("近7日使用分布", style = MaterialTheme.typography.headlineSmall)
            Text("只看所选应用 · 点击格子查看该时段", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = { scope.launch { refresh() } }) { Text(if (busy) "刷新中" else "刷新数据") }
                TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "收起采集设置" else "采集设置") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (metric == HeatmapMetric.FOREGROUND && config?.enabled != true) Text("时长采集未开启，可在“采集设置”中启用。", style = MaterialTheme.typography.bodySmall)
        }
        if (showSettings) item {
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (config?.enabled == true) "本地时长分析已启用" else "本地时长分析未启用")
                Text("状态：${usageStatusLabel(config?.status)}。系统使用情况访问与本地开关均需你选择；本页不发送数据给AI。")
                Text("首次启用、新选应用、重新授权从当时开始，不导入之前的历史。关闭后已有记录保留为历史；清历史可删除。")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = {
                        if (config?.enabled != true) confirmEnable = true
                        else scope.launch { busy = true; try { repository.setEnabled(false) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { error = "关闭未保存，请重试" }
                            finally { busy = false; reload++ } }
                    }) { Text(if (config?.enabled == true) "暂停本地采集" else "开启本地分析") }
                    OutlinedButton(enabled = !busy && config?.enabled == true, onClick = {
                        try { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                        catch (_: Exception) { Toast.makeText(context, "请在系统设置中找到使用情况访问并选择停一下", Toast.LENGTH_LONG).show() }
                    }) { Text("系统使用情况访问") }
                    TextButton(enabled = !busy, onClick = { scope.launch { refresh() } }) { Text(if (busy) "刷新中" else "刷新") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (config?.enabled != true && rows.isNotEmpty()) Text("下方时长为关闭前的历史记录，不再采集。")
                Text("系统约每日安排一次本地采集，可能延迟；本页打开或手动刷新可补采近期可用数据。")
            } }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = metric == HeatmapMetric.PAUSES, onClick = { metric = HeatmapMetric.PAUSES; selectedCell = null }, label = { Text("停顿次数") })
                FilterChip(selected = metric == HeatmapMetric.FOREGROUND, onClick = { metric = HeatmapMetric.FOREGROUND; selectedCell = null }, label = { Text("前台时长估算") })
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selectedPackage == null && selectedCategory == null, onClick = { selectedPackage = null; selectedCategory = null }, label = { Text("全部所选目标") })
                targets.filter { it.enabled }.forEach { target ->
                    FilterChip(selected = selectedPackage == target.packageName, onClick = { selectedPackage = target.packageName; selectedCategory = null }, label = { Text("${target.label} · ${categories.category(target.packageName)}") })
                }
                historyPackages.sorted().forEach { pkg ->
                    FilterChip(selected = selectedPackage == pkg, onClick = { selectedPackage = pkg; selectedCategory = null },
                        label = { Text("历史：${app.pausecn.data.sanitizeInstalledAppLabel(pkg, pkg)}") })
                }
            }
            app.pausecn.ui.CategoryFilter(categories, selectedCategory) { selectedCategory = it; selectedPackage = null }
            selectedCategory?.let { Text("当前分类：$it · ${displayedPackages.size} 个所选应用", style = MaterialTheme.typography.bodySmall) }
            if (packages.isEmpty()) Text("当前没有目标，不查询系统使用记录；可选择历史应用查看本地记录。")
            val known = cells.mapNotNull { it.value }
            val total = known.takeIf { it.isNotEmpty() }?.sum()
            Text(if (metric == HeatmapMetric.PAUSES) "近7日已记录停顿" else "近7日目标前台时长之和", style = MaterialTheme.typography.titleMedium)
            Text(total?.let { if (metric == HeatmapMetric.PAUSES) "$it 次" else formatUsageDuration(it) } ?: "暂无数据", style = MaterialTheme.typography.headlineLarge)
            Text(if (metric == HeatmapMetric.PAUSES) "不是全部打开次数" else "仅可用区间估算，可能重叠；未知不等于0", style = MaterialTheme.typography.bodySmall)
        }
        item {
            app.pausecn.ui.ContributionHeatmap(cells, metric, ::describe) { selectedCell = it }
        }
        item { TextButton(onClick = { showNotes = !showNotes }) { Text(if (showNotes) "收起统计说明" else "统计口径与数据缺口") } }
        if (showNotes) item {
            Text("页面更新于 ${Instant.ofEpochMilli(snapshotTime).atZone(snapshotZone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))} · ${snapshotZone.id}；不是实时计时。")
            Text("次数只包含留下记录的停顿，0次不代表没有使用。前台时长由系统事件估算，只汇总所选目标，可能与停顿层或其他应用重叠，不是手机总屏幕时间。本页不向AI发送数据。")
            Text("未授权、过期、漏采或升级前没有记录的时段不补零。时长采集暂停后，旧数据仅作历史展示；首次开启/重新授权不导入之前的历史。")
            if (rows.any { it.zoneId != snapshotZone.id }) Text("旧时区汇总未混入当前图表，相应日期可能不完整。")
            val displayedIntervals = displays.filter { it.packageName in displayedPackages && it.zoneId == snapshotZone.id && (it.endedAt == null || it.endedAt <= snapshotTime) }
            Text("近7日已确认停顿展示 ${formatUsageDuration(displayedIntervals.sumOf { it.durationMs ?: 0L })}；${displayedIntervals.count { it.durationMs == null }}段缺边界或受校时影响。包含选择停留，未从前台时长自动扣除，升级前区间无法补算。")
        }
    }
    selectedCell?.let { cell -> AlertDialog(onDismissRequest = { selectedCell = null }, title = { Text("时段详情") },
        text = { Text(describe(cell)) }, confirmButton = { TextButton(onClick = { selectedCell = null }) { Text("知道了") } }) }
    if (confirmEnable) AlertDialog(onDismissRequest = { confirmEnable = false }, title = { Text("开启本地使用时长分析？") },
        text = { Text(USAGE_LOCAL_CONSENT) },
        confirmButton = { TextButton(onClick = { confirmEnable = false; scope.launch {
            busy = true
            try { repository.setEnabled(true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "启用未保存，请重试" }
            finally { busy = false }
            refresh()
        } }) { Text("确认开启本地分析") } },
        dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text("取消") } })
}

private suspend fun app.pausecn.data.PauseDatabase.withUsageSnapshot(since: Long, until: Long): Triple<List<UsageHourEntity>, List<UsagePausePoint>, List<PauseDisplay>> =
    withTransaction { Triple(usageDao().hours(since, until), usageDao().pauses(since, until), usageDao().displays(since, until)) }

internal fun formatUsageDuration(ms: Long): String = "${ms / 60_000}分${ms / 1_000 % 60}秒"

private fun usageStatusLabel(status: String?): String = when (status) {
    null, "DISABLED" -> "未开启/已暂停"
    "AWAITING_REFRESH" -> "等待刷新或系统授权"
    "NOT_AUTHORIZED" -> "未获得系统授权"
    "DEVICE_LOCKED" -> "用户存储尚未解锁"
    "FAILED", "TOO_MANY_EVENTS" -> "读取未完成"
    "NO_TARGETS" -> "未选择目标"
    "CLOCK_CHANGED" -> "时间或时区变化，已重新建立采集边界"
    "EMPTY" -> "无可用记录，不等于使用为零"
    "PARTIAL" -> "部分时段有数据"
    "AVAILABLE" -> "有可用估算"
    else -> "等待更新"
}
