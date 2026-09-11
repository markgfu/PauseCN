package app.pausecn.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A secondary settings page: credential input never enters saved instance state. */
@Composable
fun AiSettingsScreen(repository: AiRepository, onBack: () -> Unit, onPreview: () -> Unit, backLabel: String = "返回设置") {
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var style by remember(state.config.styleVersion, state.loaded) { mutableStateOf(state.config.style) }
    var manual by remember(state.config.styleVersion, state.loaded) { mutableStateOf(state.config.manualPhrase) }
    var model by remember(state.config.styleVersion, state.loaded) { mutableStateOf(state.config.model) }
    var message by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var showConsent by remember { mutableStateOf(false) }
    var deleteKey by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    if (showProfile) {
        UserProfileScreen(repository = repository, onBack = { showProfile = false })
        return
    }
    BackHandler(onBack = onBack)
    LaunchedEffect(repository) {
        try { repository.load() } catch (_: Exception) { message = "AI 设置读取失败，基础停顿仍可使用。请返回后重试。" }
    }
    val action: (suspend () -> String) -> Unit = { work ->
        scope.launch {
            saving = true
            try { message = work() }
            catch (error: CancellationException) { message = "请求已取消；可能已产生费用，不会自动重试。" }
            catch (_: Exception) { message = "操作未完成，请检查输入或重试。原有离线停顿不受影响。" }
            finally { saving = false }
        }
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { app.pausecn.ui.BackButton(onClick = onBack, label = backLabel) }
        item { Text("AI 提醒", style = MaterialTheme.typography.headlineLarge) }
        item { Text("基础停顿无需联网。此页使用你自己的DeepSeek账户，调用可能收费。下方普通句库只发送风格；个性化提醒另行开启，发送范围见“我的背景与下次提醒”。交流、使用解读和分类也各有发送说明。") }
        item { Text("Key：${if (state.hasKey) "已加密保存（不显示原文）" else "未填写"} · AI ${if (state.config.enabled) "已启用" else "已关闭"}") }
        item { OutlinedButton(onClick = { showKey = true }, enabled = state.loaded && !saving) { Text(if (state.hasKey) "更换 Key" else "在手机内填写 Key") } }
        if (state.hasKey) item { TextButton(onClick = { deleteKey = true }, enabled = !saving) { Text("删除 Key 并关闭 AI") } }
        item {
            Button(onClick = {
                if (state.config.enabled) action { repository.setEnabled(false); "已关闭 AI，停止新请求；已有内容保留供管理。" }
                else showConsent = true
            }, enabled = state.loaded && state.hasKey && (!saving || state.busy)) {
                Text(if (state.config.enabled) "关闭 AI" else "查看说明并启用 AI")
            }
        }
        item { HorizontalDivider() }
        item { OutlinedButton(onClick = { showProfile = true }, enabled = state.loaded && !saving) {
            Text("我的背景与下次提醒")
        } }
        item { OutlinedTextField(value = style, onValueChange = { if (it.length <= 300) style = it },
            label = { Text("提醒风格（最多300字）") }, modifier = Modifier.fillMaxWidth(), enabled = !saving) }
        item { Text("模型（以账户可用模型为准）") }
        items(DeepSeekClient.MODELS.toList()) { name ->
            FilterChip(selected = model == name, onClick = { model = name }, label = { Text(name) }, enabled = !saving)
        }
        item { OutlinedTextField(value = manual, onValueChange = { if (it.length <= 100) manual = it },
            label = { Text("手写短句：优先于 AI，可离线使用") }, supportingText = { Text("最多36个Unicode码点；留空则使用已确认的AI文案或默认提醒。") },
            modifier = Modifier.fillMaxWidth(), enabled = !saving) }
        item { Button(onClick = { action {
            repository.saveStyle(style.trim(), model, manual.trim()); "已保存；旧生成文案已清除。手写短句无需联网。"
        } }, enabled = state.loaded && !saving && style.isNotBlank() && (manual.isBlank() || PromptSelector.validShortText(manual.trim()))) {
            Text("保存风格与手写短句")
        } }
        item { Text("本页生成普通句库时，会先发送完整要求：每句最多36个Unicode码点，目标12条，单批最多接收36条。可按相同风格再生成，不设每日总量上限。成功后替换候选，失败保留旧句；先保存风格，再生成并确认应用。此句库用于普通打开与连续继续场景，个性化预生成在背景页单独管理。") }
        item { Button(onClick = { action { repository.generatePhrases() } }, enabled = state.loaded && state.config.enabled && !saving && !state.busy
            && style.trim() == state.config.style && model == state.config.model && manual.trim() == state.config.manualPhrase) {
            Text(when {
                state.busy -> "正在生成…"
                state.phrases.isNotEmpty() -> "再生成一组（调用一次 API）"
                else -> "生成候选短句（调用一次 API）"
            })
        } }
        if (state.busy) item { TextButton(onClick = { scope.launch { repository.cancelRequest() } }) { Text("取消请求，不自动重试") } }
        item { Text("今日已尝试 ${state.attempts} 次；服务端已返回的总 token：${state.tokens}。不设每日生成或请求次数上限；统计不代表金额。每次生成均可能收费，失败和超时也可能收费；不会自动重试。") }
        if (message.isNotEmpty()) item { Text(message, color = MaterialTheme.colorScheme.primary) }
        items(state.phrases, key = { it.id }) { phrase ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("${sceneLabel(phrase.scene)} · ${if (phrase.approved) "已确认" else "待检查"}", style = MaterialTheme.typography.labelMedium)
                Text(phrase.text)
            }
        }
        if (state.phrases.any { !it.approved }) item {
            Text("模型仍可能生成不合适的话。请检查所有句子；有问题时可直接再生成或修改风格，不必应用。新候选确认前使用手写或默认提醒。")
            Button(onClick = { action { repository.approvePhrases(); "已应用。停顿只读本地缓存，不等待联网。" } }, enabled = !saving && state.config.enabled) { Text("确认这些句子合适，应用到停顿") }
        }
        item { OutlinedButton(onClick = onPreview) { Text("预览当前停顿（不联网、不记录）") } }
        item { Text("Key 不进入导出或系统备份。加密导出的基础范围含历史理由原文；画像、记忆、交流、反馈、AI内容、时长、报告和分类需另行勾选，详见“设置 → 加密导出本地数据”。", style = MaterialTheme.typography.bodySmall) }
        item { Text("清除历史会清理生成内容，但保留独立背景和长期偏好；重置全部数据才会一并删除背景、Key和AI配置。本地删除无法撤回服务端已收到的数据或已导出文件。", style = MaterialTheme.typography.bodySmall) }
    }
    if (showKey) KeyDialog(onDismiss = { showKey = false }, onSave = { key ->
        showKey = false
        action { repository.saveKey(key); "Key 已加密保存。请阅读说明后单独启用 AI。" }
    })
    if (showConsent) AlertDialog(onDismissRequest = { showConsent = false }, title = { Text("允许调用 DeepSeek？") },
        text = { Text("普通生成仅在主动点击时发送固定要求与风格描述至DeepSeek官方HTTPS API。个性化预生成另需单独开启和授权；曾授权的预生成设置保留，重新启用AI后会按该设置工作，可在背景页关闭。不会读取其他应用聊天或页面。调用由你的账户付费，无每日次数上限，同一时间一个请求，不自动重试。关闭不能撤回远端已收到的内容或费用。", modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { showConsent = false; action { repository.setEnabled(true); "AI 已启用；尚未发送请求。" } }) { Text("同意并启用") } },
        dismissButton = { TextButton(onClick = { showConsent = false }) { Text("暂不启用") } })
    if (deleteKey) AlertDialog(onDismissRequest = { deleteKey = false }, title = { Text("删除本机 Key？") },
        text = { Text("关闭 AI 并删除加密凭据。候选句保留，但不再用于停顿；手写句仍可用。不会撤销服务端 Key，需要时请在 DeepSeek 账户中撤销。") },
        confirmButton = { TextButton(onClick = { deleteKey = false; action { repository.deleteKey(); "本机 Key 已删除，AI 已关闭。" } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleteKey = false }) { Text("取消") } })
}

@Composable
private fun KeyDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { key = "" } }
    AlertDialog(onDismissRequest = { key = ""; onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("仅在本机填写 API Key") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { OutlinedTextField(value = key, onValueChange = { if (it.length <= 256) key = it },
            label = { Text("DeepSeek API Key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = { Text("请使用可单独撤销的测试 Key，不要发到聊天或写入代码。") }) } },
        confirmButton = { TextButton(onClick = { val value = key; key = ""; onSave(value) },
            enabled = key.trim().length in 8..256 && key.trim().all { it.code in 33..126 }) { Text("加密保存") } },
        dismissButton = { TextButton(onClick = { key = ""; onDismiss() }) { Text("取消") } })
}

private fun sceneLabel(scene: String) = when (scene) {
    "ORDINARY" -> "普通打开"
    "REPEATED" -> "连续选择继续"
    "GOAL" -> "目标提醒（待接入）"
    "CONTEXT" -> "用途询问（待接入）"
    "PURPOSE" -> "明确用途（待接入）"
    else -> "中性反馈（待接入）"
}
