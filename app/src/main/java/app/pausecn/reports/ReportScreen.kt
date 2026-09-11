package app.pausecn.reports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.pausecn.data.AppContainer
import app.pausecn.usage.HeatmapCell
import app.pausecn.usage.HeatmapMetric
import app.pausecn.usage.formatUsageDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportScreen(container: AppContainer, retentionDays: Int, onBack: () -> Unit, aiFirst: Boolean = false) {
    val repository = remember(container) {
        val source = app.pausecn.usage.AndroidUsageSource(container.applicationContext)
        ReportRepository(container.database, usagePermission = source::hasPermission,
            bootId = { app.pausecn.usage.UsageClock.bootId(container.applicationContext) })
    }
    val lifecycle = LocalLifecycleOwner.current
    val categories by container.appCategories.state.collectAsStateWithLifecycle(initialValue = app.pausecn.data.AppCategorySnapshot())
    var showAllCategories by remember { mutableStateOf(false) }
    var period by remember { mutableStateOf(ReportPeriod.TODAY) }
    var template by remember { mutableStateOf(ReportTemplate.SIMPLE) }
    var metric by remember { mutableStateOf(HeatmapMetric.PAUSES) }
    var snapshot by remember { mutableStateOf<LocalReportSnapshot?>(null) }
    var updateAvailable by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var forceRefresh by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    var consent by remember { mutableStateOf(ReportConfig()) }
    var consentDraft by remember { mutableStateOf<ReportConfig?>(null) }
    var savingConsent by remember { mutableStateOf(false) }
    var delivery by remember { mutableStateOf<ReportDelivery?>(null) }
    var requestJob by remember { mutableStateOf<Job?>(null) }
    var sending by remember { mutableStateOf(false) }
    var aiMessage by remember { mutableStateOf<String?>(null) }
    var showNotes by remember { mutableStateOf(false) }
    var showAiOptions by remember { mutableStateOf(aiFirst) }
    var showSources by remember { mutableStateOf(false) }
    var cellDetail by remember { mutableStateOf<String?>(null) }
    var shareSource by remember { mutableStateOf<LocalReportSnapshot?>(null) }
    var showRuleAdjustment by remember { mutableStateOf(false) }
    var ruleDelivery by remember { mutableStateOf<ReportDelivery?>(null) }
    var loadedPeriod by remember { mutableStateOf(period) }
    var generationDraft by remember { mutableStateOf<LocalReportSnapshot?>(null) }
    var generationConsent by remember { mutableStateOf<ReportConfig?>(null) }
    val scrollState = rememberScrollState()
    // Only the visible level owns system back; nested share/settings pages handle theirs.
    BackHandler(enabled = !showRuleAdjustment && shareSource == null, onBack = onBack)
    if (showRuleAdjustment) {
        val selected = ruleDelivery
        val validity = remember(selected) { selected?.let { result ->
            merge(repository.changes, container.database.personalizationGuard.revisions.map { Unit }, flow {
                emit(Unit); delay((result.request.expiresAt - System.currentTimeMillis()).coerceAtLeast(1)); emit(Unit)
            }).map {
                try { container.aiRepository.reportProposalCurrent(result, repository) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            }
        } }
        app.pausecn.ui.RuleAdjustmentScreen(container.settingsStore, onBack = { showRuleAdjustment = false; ruleDelivery = null },
            initialProposal = selected?.let { RuleProposal(requireNotNull(it.request.ruleBasis), requireNotNull(it.result.rulePatch)) },
            proposalValidity = validity,
            applyProposal = selected?.let { result -> { expected, patch ->
                container.aiRepository.applyReportRuleProposal(result, repository, container.settingsStore, expected, patch)
            } })
        return
    }
    shareSource?.let { source ->
        ReportShareScreen(container, source, onBack = { shareSource = null }, backLabel = if (aiFirst) "返回解读" else "返回报告")
        return
    }

    LaunchedEffect(delivery) {
        val result = delivery ?: return@LaunchedEffect
        delay((result.request.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L))
        if (delivery === result) delivery = null
    }

    LaunchedEffect(period, retentionDays, refresh, lifecycle) {
        loading = true; updateAvailable = false; error = null
        if (loadedPeriod != period) {
            snapshot = null; delivery = null; generationDraft = null; loadedPeriod = period
            scrollState.scrollTo(0)
        }
        cellDetail = null
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val expiryTicks = flow {
                while (true) {
                    emit(Unit)
                    val untilExpiry = snapshot?.facts?.validUntil?.let { (it - System.currentTimeMillis()).coerceAtLeast(1L) } ?: 60_000L
                    delay(minOf(60_000L, untilExpiry))
                }
            }
            merge(repository.changes, container.database.personalizationGuard.revisions.map { Unit }, expiryTicks).collect {
                // Hide before revalidation; failed reads must not leave old private facts visible.
                loading = true
                cellDetail = null
                try {
                    val (current, hasUpdates) = repository.load(period, retentionDays, forceRefresh)
                    forceRefresh = false
                    consent = container.database.reportDao().config() ?: ReportConfig()
                    snapshot = current; updateAvailable = hasUpdates
                    delivery = container.aiRepository.restoreReport(current, repository)
                    error = null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    snapshot = null; delivery = null; updateAvailable = false
                    error = "本地报告暂时不可用或来源正在变化，旧快照已隐藏。可稍后刷新，不会发起AI请求。"
                } finally {
                    // A cancelled validation must not reveal the unvalidated body on pause.
                    if (currentCoroutineContext().isActive) loading = false
                }
            }
        }
    }

    val facts = snapshot?.facts.takeUnless { error != null }
    Column(Modifier.verticalScroll(scrollState).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column {
            if (!aiFirst) {
                app.pausecn.ui.BackButton(onClick = onBack, label = "返回记录")
                Spacer(Modifier.height(12.dp))
                Text("日报与周报", style = MaterialTheme.typography.headlineSmall)
            } else Text("随时解读已有记录，不必等到一天结束。", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ReportPeriod.entries.forEach { option ->
                FilterChip(selected = period == option, onClick = {
                    if (period != option) { loading = true; generationDraft = null; period = option }
                }, label = { Text(option.label) })
            } }
            if (!aiFirst) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ReportTemplate.entries.forEach { option ->
                FilterChip(selected = template == option, onClick = { template = option }, label = { Text(option.label) })
            } }
            TextButton(onClick = { forceRefresh = true; refresh++ }, enabled = !loading) { Text(if (aiFirst) {
                if (updateAvailable) "更新分析依据 · 有新记录" else "更新分析依据（本地）"
            } else if (updateAvailable) "刷新报告 · 有更新" else "刷新报告") }
            Box(Modifier.heightIn(min = 24.dp)) {
                if (loading) Text("正在核对本地事实…")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        ReportValidatedBody(visible = !loading && error == null) {
        if (facts != null) {
            Column {
                Text("${facts.window.startDate} 至 ${facts.window.endDateExclusive.minusDays(1)}", style = MaterialTheme.typography.labelLarge)
                Text("截至 ${reportTime(facts.window.cutoff, facts.window.zoneId).takeLast(11)} · ${facts.targets.size} 个应用${if (facts.window.ongoing) " · 未结束" else ""}", style = MaterialTheme.typography.bodySmall)
            }
            if (!aiFirst) ReportMetrics(facts)
            if (!aiFirst) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !sending, onClick = { shareSource = snapshot }) { Text("分享图片") }
                OutlinedButton(enabled = !sending, onClick = { showRuleAdjustment = true }) { Text("调整停顿设置") }
            }
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (aiFirst) "看懂当下的使用节奏" else "私人AI解读", style = MaterialTheme.typography.titleLarge)
                    if (!showAiOptions && delivery == null && !sending) {
                        TextButton(onClick = { showAiOptions = true }) { Text("查看AI解读选项") }
                    } else {
                    Text(if (consent.enabled) "使用记录解读授权已开启。" else "发送前请开启使用记录解读授权，不会自动生成。")
                    Text("可选发送：时长${if (consent.useUsage) "开" else "关"}、画像${if (consent.useProfile) "开" else "关"}、记忆${if (consent.useMemories) "开" else "关"}、理由${if (consent.useReasons) "开" else "关"}、设置建议${if (consent.useSettings) "开" else "关"}。")
                    Text("应用分类${if (categories.settings.sendToAi) "允许发送" else "不发送"} · 在目标页的统一分类中管理", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !savingConsent, onClick = { consentDraft = consent }) { Text(if (aiFirst) "选择解读可用的背景" else "报告发送授权") }
                    Button(enabled = consent.enabled && !sending && !loading && !savingConsent,
                        onClick = { generationConsent = consent; generationDraft = snapshot },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (sending) "正在生成…" else if (delivery != null) "重新生成AI解读（可能计费）" else "生成AI解读（可能计费）") }
                    if (sending) TextButton(onClick = { requestJob?.cancel() }) { Text("取消本次等待") }
                    aiMessage?.let { Text(it) }
                    delivery?.let { result ->
                        Text("AI生成 · 非事实核验或诊断；不会自动修改设置。", style = MaterialTheme.typography.bodySmall)
                        result.result.observations.forEach { observation ->
                            Text(observation.text)
                            if (!aiFirst || showSources) Text("所引来源：${observation.basedOn.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("可选建议：${result.result.suggestion}")
                        if (aiFirst) TextButton(onClick = { showSources = !showSources }) { Text(if (showSources) "收起依据" else "查看解读依据") }
                        if (result.result.rulePatch != null && result.request.ruleBasis != null) {
                            OutlinedButton(onClick = { ruleDelivery = result; showRuleAdjustment = true }) { Text("查看AI设置建议（未应用）") }
                        }
                    }
                    Text(if (aiFirst) "只分析已提供的记录，不等于全部手机使用；建议由你决定是否采用。" else "本地保存至多4份近期报告与有效私人解读；返回或换模板不调用AI。更新事实后需主动重生成解读。删除、撤权或到期清旧缓存；分享时可自行选择加入AI解读。", style = MaterialTheme.typography.bodySmall)
                    }
                } }
            if (aiFirst) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本次分析依据", style = MaterialTheme.typography.titleMedium)
                ReportMetrics(facts)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !sending, onClick = { shareSource = snapshot }) { Text("分享解读卡片") }
                    OutlinedButton(enabled = !sending, onClick = { showRuleAdjustment = true }) { Text("调整停顿设置") }
                }
            }
            if (facts.categories.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("分类使用概览", style = MaterialTheme.typography.titleMedium)
                val rows = facts.categories.filter { it.counts.recorded > 0 || it.foregroundMs != null }
                (if (showAllCategories) rows else rows.take(5)).forEach { row ->
                    Text("${row.category} · 停顿 ${row.counts.recorded} 次 · 继续 ${row.counts.continued} 次")
                    row.foregroundMs?.let { Text("前台估算 ${formatUsageDuration(it)}${if (row.usagePartial) " · 部分数据" else ""}", style = MaterialTheme.typography.bodySmall) }
                }
                if (rows.size > 5) TextButton(onClick = { showAllCategories = !showAllCategories }) { Text(if (showAllCategories) "收起分类" else "查看全部分类") }
                Text("按当前主分类整理，同一应用不重复计数。", style = MaterialTheme.typography.bodySmall)
            }
            if (template == ReportTemplate.REFLECTION) {
                Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本地复盘", style = MaterialTheme.typography.titleMedium)
                    Text(neutralReportSummary(facts.counts))
                    Text("可以回想：这些停顿是否帮你想清了这次打开的目的？暂时无需根据单份报告改变设置。")
                    Text("本段是固定本地文案，不是AI分析；授权后的私人AI解读单独展示在上方。", style = MaterialTheme.typography.bodySmall)
                } }
            }
            if (template == ReportTemplate.HEATMAP) {
                Column {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = metric == HeatmapMetric.PAUSES, onClick = { metric = HeatmapMetric.PAUSES }, label = { Text("停顿次数") })
                        FilterChip(selected = metric == HeatmapMetric.FOREGROUND, onClick = { metric = HeatmapMetric.FOREGROUND }, label = { Text("前台时长估算") })
                    }
                    ReportHeatmap(if (metric == HeatmapMetric.PAUSES) facts.pauseCells else facts.usageCells, metric) {
                        cellDetail = reportCellDescription(it, metric)
                    }
                }
            }
            TextButton(onClick = { showNotes = !showNotes }) { Text(if (showNotes) "收起统计说明" else "统计口径与数据缺口") }
            if (showNotes) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本地事实无需Key或联网。本页不采新系统事件；手动生成或另行开启的自动昨日复盘，才会按报告授权发送摘要。")
                Text("时区：${facts.window.zoneId}。统计截至 ${reportTime(facts.window.cutoff, facts.window.zoneId)}（不含截止瞬间）。")
                Text("快照生成于 ${reportTime(facts.createdAt, facts.window.zoneId)}。模板切换不会改变数字；仅含所选应用的留存记录。")
                Text("选择前中断 ${facts.counts.interrupted} 次 · 尚未完成 ${facts.counts.pending} 次 · 显示失败 ${facts.counts.displayFailed} 次")
                Text("已完成选择中的离开比例：${facts.counts.exitPercent?.let { "$it%" } ?: "—（尚无已完成选择）"}")
                facts.usageCapturedAt?.let { Text("所用时长数据最早采集于 ${reportTime(it, facts.window.zoneId)}，不是实时计时。") }
            }
            if (showNotes) facts.notes.forEach { note -> Text(note, style = MaterialTheme.typography.bodySmall) }
        }
        }
        AutomaticReportControls(container) { period = ReportPeriod.YESTERDAY; refresh++ }
    }
    generationDraft?.let { source ->
        val current = !loading && error == null && consent.enabled && !sending && !savingConsent &&
            consent == generationConsent &&
            snapshot?.facts?.fingerprint == source.facts.fingerprint
        ReportGenerationConfirmation(enabled = current, onDismiss = { generationDraft = null }, onConfirm = {
            if (!current) return@ReportGenerationConfirmation
            generationDraft = null
            sending = true; aiMessage = null
            requestJob = scope.launch {
                try {
                    val result = container.aiRepository.generateReport(source, repository)
                    if (snapshot?.facts?.fingerprint == source.facts.fingerprint && container.aiRepository.reportDeliveryCurrent(result, repository)) {
                        delivery = result; aiMessage = "解读已生成；请结合实际情况核对。"
                    } else aiMessage = "报告已经更新，未展示旧解读。"
                } catch (cancelled: CancellationException) { aiMessage = "已取消等待；请求可能已计费，不会自动重试。"; throw cancelled }
                catch (failure: Exception) { aiMessage = (failure as? app.pausecn.ai.AiRequestException)?.publicMessage ?: "本次解读未完成，没有自动重试。" }
                finally { sending = false; requestJob = null }
            }
        })
    }
    cellDetail?.let { detail -> AlertDialog(onDismissRequest = { cellDetail = null }, title = { Text("时段详情") },
        text = { Text(detail) }, confirmButton = { TextButton(onClick = { cellDetail = null }) { Text("知道了") } }) }
    consentDraft?.let { draft -> AlertDialog(onDismissRequest = { consentDraft = null },
        title = { Text("选择报告发送内容") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Text("发送给DeepSeek：日期/时区/截止、所选目标数量、停顿/离开/继续等汇总和提醒风格。保存授权不会直接调用AI；手动生成或另行开启的自动昨日复盘可能计费，均使用这些选项。撤销可阻止后续发送，不能撤回已接收的数据。")
            ReportConsentRow("允许发送报告事实", draft.enabled) { consentDraft = draft.copy(enabled = it) }
            ReportConsentRow("额外发送前台时长汇总", draft.useUsage) { consentDraft = draft.copy(useUsage = it) }
            ReportConsentRow("额外发送本人填写的画像/偏好", draft.useProfile) { consentDraft = draft.copy(useProfile = it) }
            ReportConsentRow("额外发送最多4条已确认记忆", draft.useMemories) { consentDraft = draft.copy(useMemories = it) }
            ReportConsentRow("额外发送最多3个目标的各2条常用理由（含应用名）", draft.useReasons) { consentDraft = draft.copy(useReasons = it) }
            ReportConsentRow("发送当前等待/通行/生效计划，附上可选设置建议", draft.useSettings) { consentDraft = draft.copy(useSettings = it) }
            Text("不发送原始聊天、逐条系统事件、完整应用列表或Key正文。历史理由不是本次目的；私人解读不作为默认分享内容。")
        } },
        confirmButton = { TextButton(onClick = {
            consentDraft = null; delivery = null; generationDraft = null; savingConsent = true
            scope.launch { try { container.aiRepository.setReportConsent(draft); consent = draft }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { aiMessage = "授权未保存，请重试。" }
                finally { savingConsent = false } }
        }) { Text("保存选择（不联网）") } },
        dismissButton = { TextButton(onClick = { consentDraft = null }) { Text("取消") } }) }
}

@Composable
private fun ReportConsentRow(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = checked, onCheckedChange = change); Text(label) }
}

@Composable
private fun ReportMetrics(facts: ReportFacts) {
    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("已记录停顿 ${facts.counts.recorded} 次", style = MaterialTheme.typography.titleLarge)
        Text("主动离开 ${facts.counts.exited} 次 · 继续打开 ${facts.counts.continued} 次")
        Text("所选应用前台时长之和：${facts.foregroundMs?.let(::formatUsageDuration) ?: "暂无可用数据"}${if (facts.foregroundMs != null && facts.usagePartial) "（部分区间估算）" else ""}")
    } }
}

@Composable
private fun ReportHeatmap(cells: List<HeatmapCell>, metric: HeatmapMetric, onCell: (HeatmapCell) -> Unit) {
    app.pausecn.ui.ContributionHeatmap(cells, metric, { reportCellDescription(it, metric) }, onCell)
}

internal fun neutralReportSummary(counts: ReportCounts): String = if (counts.recorded == 0)
    "本期尚无留存的停顿记录。这不等于没有使用手机，也无需给自己打分。"
else "这些记录呈现的是你做过的选择，不是表现评分。继续打开和主动离开，都可以是想清楚后的决定。"

internal fun reportTime(at: Long, zone: String): String = Instant.ofEpochMilli(at).atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
private fun reportCellDescription(cell: HeatmapCell, metric: HeatmapMetric): String {
    val value = when {
        cell.actualDurationMs == 0L -> "当地不存在的小时"
        cell.future -> "未来时段"
        cell.value == null -> "无可用数据"
        metric == HeatmapMetric.PAUSES -> "已记录 ${cell.value} 次"
        else -> "${formatUsageDuration(cell.value)}${if (cell.partial) "，部分区间" else ""}"
    }
    return "${cell.date} ${cell.hour}时：$value；该小时实际长度 ${cell.actualDurationMs / 60_000} 分钟。"
}
