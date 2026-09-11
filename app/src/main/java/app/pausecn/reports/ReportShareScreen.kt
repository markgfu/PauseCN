package app.pausecn.reports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import app.pausecn.ui.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.pausecn.data.AppContainer
import app.pausecn.usage.HeatmapMetric
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.collect

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReportShareScreen(container: AppContainer, snapshot: LocalReportSnapshot, onBack: () -> Unit, backLabel: String = "返回报告") {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val store = remember(container) { container.reportShareStore }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    var options by remember { mutableStateOf(ReportShareOptions()) }
    var preview by remember { mutableStateOf<ReportSharePreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var handedToChooser by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var fullPreview by remember { mutableStateOf(false) }
    var availableAi by remember(snapshot) { mutableStateOf<ReportDelivery?>(null) }
    LaunchedEffect(snapshot, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            store.changes.collect {
                availableAi = try { store.interpretation(snapshot) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
        }
    }
    fun change(next: ReportShareOptions) {
        preview?.let { store.discard(it.uri) }; preview = null; options = next; message = null; handedToChooser = false; fullPreview = false
    }
    suspend fun generatePreview() {
        if (busy || (options.template == ReportTemplate.HEATMAP && !options.allowHourlyPattern)) return
        busy = true; message = null
        preview?.let { store.discard(it.uri) }; preview = null; handedToChooser = false
        try { preview = store.create(snapshot, options) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "图片未生成，可能是报告已失效或内容过长。请返回刷新报告，或减少感想/隐藏应用名。" }
        finally { busy = false }
    }
    // Only local rendering. Typing or toggling private fields still requires an explicit preview update.
    LaunchedEffect(snapshot, options.template) {
        if (options.template != ReportTemplate.HEATMAP) generatePreview()
    }
    LaunchedEffect(preview, lifecycle) {
        val image = preview ?: return@LaunchedEffect
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val expiry = flow { emit(Unit); delay((image.expiresAt - System.currentTimeMillis()).coerceAtLeast(1L)); emit(Unit) }
            merge(store.changes, expiry).collect {
                if (!store.valid(image.uri)) { preview = null; message = "预览已过期或来源改变，请返回刷新报告。" }
            }
        }
    }
    val latestPreview by rememberUpdatedState(preview)
    val latestHanded by rememberUpdatedState(handedToChooser)
    DisposableEffect(store) { onDispose { if (!latestHanded) latestPreview?.let { store.discard(it.uri) } } }

    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            app.pausecn.ui.BackButton(onClick = onBack, label = backLabel)
            Spacer(Modifier.height(12.dp))
            Text("分享一张注意力手记", style = MaterialTheme.typography.headlineSmall)
            Text("本地生成 · 不调用AI · 私人内容默认隐藏", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = SageSoft, border = BorderStroke(1.dp, Line), modifier = Modifier.fillMaxWidth()) {
                val ready = preview
                if (ready != null) {
                    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(ready.bitmap.asImageBitmap(), contentDescription = "${options.template.label}真实分享预览，点击查看大图",
                            modifier = Modifier.fillMaxWidth().height(260.dp).clickable { fullPreview = true }, contentScale = ContentScale.Fit)
                        TextButton(onClick = { fullPreview = true }) { Text("查看大图") }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().height(260.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        if (busy) CircularProgressIndicator(color = Sage, modifier = Modifier.size(32.dp))
                        else Text("Ⅱ", style = MaterialTheme.typography.displayMedium, color = Sage)
                        Spacer(Modifier.height(14.dp))
                        Text(if (busy) "正在绘制你的卡片…" else if (options.template == ReportTemplate.HEATMAP && !options.allowHourlyPattern) "确认下方时段选项后预览" else "内容已调整，点下方更新预览", color = Ink)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReportTemplate.entries.forEach { template ->
                    ShareTemplateTile(template, options.template == template, !busy, Modifier.weight(1f)) {
                        if (template != options.template) change(options.copy(template = template, allowHourlyPattern = false))
                    }
                }
            }
        }
        if (options.template == ReportTemplate.HEATMAP) item {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(enabled = !busy, checked = options.allowHourlyPattern, onCheckedChange = { change(options.copy(allowHourlyPattern = it)) }); Text("允许图片展示具体小时分布", style = MaterialTheme.typography.bodyMedium) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { HeatmapMetric.entries.forEach { metric ->
                    FilterChip(enabled = !busy, selected = options.metric == metric, onClick = { change(options.copy(metric = metric)) },
                        label = { Text(if (metric == HeatmapMetric.PAUSES) "停顿次数" else "前台时长估算") })
                } }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(enabled = !busy && (availableAi != null || options.includeAi), checked = options.includeAi,
                    onCheckedChange = { change(options.copy(includeAi = it)) })
                Text("把AI解读放进卡片", style = MaterialTheme.typography.titleSmall)
            }
            Text(if (availableAi == null) "本期暂无有效解读；可到AI陪伴或报告页生成，分享页不会调用AI。"
                else "保留原来的个性化语气。解读可能提到私人背景或分类，即使相应分享选项关闭也可能出现在原文中；勾选后请更新并核对预览。",
                style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        item {
            TextButton(onClick = { showOptions = !showOptions }) { Text(if (showOptions) "收起个性化内容" else "添加感想 / 应用名称") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(enabled = !busy, checked = options.includeCategories, onCheckedChange = { change(options.copy(includeCategories = it)) })
                Text("公开应用分类汇总（含自定义名称）")
            }
            if (showOptions) {
            OutlinedTextField(enabled = !busy, value = options.caption, onValueChange = { text ->
                if (text.codePointCount(0, text.length) <= 160 && text.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }) change(options.copy(caption = text))
            }, label = { Text("我的感想（可空，最多160字）") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(enabled = !busy, checked = options.showAppNames, onCheckedChange = { change(options.copy(showAppNames = it)) }); Text("公开应用名称（最多列8个）") }
            }
        }
        item {
            if (preview == null) Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && (options.template != ReportTemplate.HEATMAP || options.allowHourlyPattern),
                onClick = { scope.launch { generatePreview() } }) { Text(if (busy) "正在绘制…" else "更新图片预览") }
            preview?.let { image -> Button(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                scope.launch { try { val intent = store.intent(image); context.startActivity(intent); handedToChooser = true; message = "已打开系统分享面板，由你选择接收应用并发送。" }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "未能打开分享面板或预览已失效，没有自动发送。" } }
            }) { Text("确认卡片 · 选择分享应用") } }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("请核对卡片，分享后的图片无法撤回。", style = MaterialTheme.typography.bodySmall, color = Muted)
            TextButton(onClick = { showPrivacy = !showPrivacy }) { Text(if (showPrivacy) "收起分享说明" else "隐私与预览说明") }
            if (showPrivacy) Text("AI解读默认隐藏，仅在本页勾选后加入观察与建议正文，不附来源ID、可执行设置或原始理由/画像/聊天。解读正文仍可能提及这些背景，应用名开关不会替你删改AI原文。进入页面或切换非热力模板只生成本地预览，不联网。预览最多10分钟，来源或授权失效后不可继续读取；已发出的图片无法撤回。", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (fullPreview) preview?.let { image ->
        Dialog(onDismissRequest = { fullPreview = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(shape = RoundedCornerShape(20.dp), color = Paper, modifier = Modifier.fillMaxWidth(.94f).fillMaxHeight(.88f)) {
                Column(Modifier.padding(12.dp)) {
                    TextButton(onClick = { fullPreview = false }) { Text("关闭大图") }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Image(image.bitmap.asImageBitmap(), contentDescription = "完整分享卡片：${options.template.label}", modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareTemplateTile(template: ReportTemplate, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(16.dp),
        color = if (selected) SageSoft else Color.White, border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Sage else Line)) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Abstract swatches, not mock data or a second unverified report.
            Canvas(Modifier.fillMaxWidth().height(56.dp)) {
                val fill = if (template == ReportTemplate.REFLECTION) SageSoft else Ink
                drawRoundRect(fill, Offset.Zero, Size(size.width, size.height * .58f), CornerRadius(8.dp.toPx()))
                if (template == ReportTemplate.HEATMAP) {
                    repeat(8) { col -> repeat(2) { row ->
                        drawRoundRect(Sage, Offset(col * size.width / 8, size.height * .67f + row * 9.dp.toPx()), Size(5.dp.toPx(), 5.dp.toPx()), CornerRadius(1.dp.toPx()))
                    } }
                } else {
                    drawCircle(if (template == ReportTemplate.REFLECTION) Sage else SageSoft, size.height * .16f,
                        Offset(size.width * .75f, size.height * .29f), style = Stroke(1.dp.toPx()))
                    drawRoundRect(Sage, Offset(0f, size.height * .72f), Size(size.width * .43f, size.height * .18f), CornerRadius(3.dp.toPx()))
                    drawRoundRect(SageSoft, Offset(size.width * .55f, size.height * .72f), Size(size.width * .45f, size.height * .18f), CornerRadius(3.dp.toPx()))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(template.label, style = MaterialTheme.typography.labelLarge, color = Ink)
        }
    }
}
