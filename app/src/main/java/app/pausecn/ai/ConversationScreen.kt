package app.pausecn.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Everyday conversation, also usable as a secondary page. Entry never calls the API. */
@Composable
fun ConversationScreen(repository: AiRepository, onBack: () -> Unit, embedded: Boolean = false,
    onNavigationGuard: ((AiNavigationGuard?) -> Unit)? = null) {
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val sessionId = remember { UUID.randomUUID().toString() }
    var pkg by remember { mutableStateOf("") }
    var page by remember { mutableStateOf("交流") }
    var input by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var consentDialog by remember { mutableStateOf(false) }
    var saveMessages by remember(state.conversation.epoch) { mutableStateOf(state.conversation.saveMessages) }
    var useMemories by remember(state.conversation.epoch) { mutableStateOf(state.conversation.useMemories) }
    var temporaryReply by remember(state.conversation.epoch, state.config.privacyEpoch, pkg) { mutableStateOf("") }
    var temporaryExpires by remember(state.conversation.epoch, state.config.privacyEpoch, pkg) { mutableStateOf(0L) }
    var correcting by remember { mutableStateOf<UserMemory?>(null) }
    var correction by remember { mutableStateOf("") }
    var forgetting by remember { mutableStateOf<UserMemory?>(null) }
    var feedbackPhrase by remember { mutableStateOf<String?>(null) }
    var feedbackText by remember { mutableStateOf("请更简短、中性，不要说教。") }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var leaveDialog by remember { mutableStateOf(false) }
    var selectingTarget by remember { mutableStateOf(false) }
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    var rememberDialog by remember { mutableStateOf(false) }
    var memoryKind by remember { mutableStateOf("PREFERENCE") }
    var keeping by remember { mutableStateOf<UserMemory?>(null) }
    var deletingMessage by remember { mutableStateOf<ConversationMessage?>(null) }
    var deleteIndependent by remember { mutableStateOf(false) }
    var explaining by remember { mutableStateOf<String?>(null) }
    var showPrivacy by remember { mutableStateOf(false) }
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    val requestLeave: AiNavigationGuard = { destination ->
        if (input.isNotBlank() || temporaryReply.isNotBlank() || working) {
            pendingLeave = destination; leaveDialog = true
        } else destination()
    }
    val latestLeave by rememberUpdatedState(requestLeave)
    DisposableEffect(onNavigationGuard) {
        onNavigationGuard?.invoke { destination -> latestLeave(destination) }
        onDispose { onNavigationGuard?.invoke(null) }
    }
    val leave = { requestLeave(onBack) }
    BackHandler(onBack = leave)
    LaunchedEffect(repository) {
        try { repository.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { status = "本地交流数据暂时不可用，请返回后重试。" }
    }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    LaunchedEffect(now, temporaryExpires) { if (now >= temporaryExpires) temporaryReply = "" }
    val action: (suspend () -> Unit) -> Unit = { block ->
        scope.launch {
            working = true
            status = ""
            try { block() }
            catch (cancelled: CancellationException) { status = "请求已取消，不会自动重试；已发出的请求仍可能计费。"; throw cancelled }
            catch (error: Exception) { status = if (error is AiRequestException) error.publicMessage else "操作未完成，请检查状态后再试。" }
            finally { working = false }
        }
    }
    val memories = state.memories.filter { it.expiresAt > now && (pkg.isEmpty() || it.scopePackage.isEmpty() || it.scopePackage == pkg) }
    val label: (String) -> String = { value -> if (value.isEmpty()) "整体手机使用" else state.targets.firstOrNull { it.packageName == value }?.label ?: "已移除的目标" }
    val messages = state.messages.filter { it.scopePackage == pkg && it.expiresAt > now }.take(30)
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().imePadding()) {
        if (!embedded) {
            item { app.pausecn.ui.BackButton(onClick = leave, label = "返回记录") }
            item { Text("交流与建议", style = MaterialTheme.typography.headlineMedium) }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("交流", "记忆", "反馈").forEach { name ->
                    FilterChip(selected = page == name, onClick = { page = name }, label = { Text(if (name == "交流") "聊聊现在" else name) })
                }
            }
        }
        item {
            Text("当前范围：${label(pkg)}")
            if (pkg.isNotEmpty()) Text("${state.categories.category(pkg)} · ${if (state.categories.settings.sendToAi) "分类允许供AI参考" else "分类仅本地显示"}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { selectingTarget = true }, enabled = !working && input.isBlank()) { Text("切换应用 / 整体范围") }
            if (input.isNotBlank()) Text("先发送或清空草稿，再切换范围。")
        }
        item {
            TextButton(onClick = { showPrivacy = !showPrivacy }) { Text(if (showPrivacy) "收起交流设置" else "交流设置与隐私") }
            if (showPrivacy) {
                Text("保存交流${if (state.conversation.saveMessages) "开" else "关"} · 使用记忆${if (state.conversation.useMemories) "开" else "关"}")
                Text("历史保留在本机，不会整段上传。关闭保存或使用记忆时不附旧消息；两者开启时最多附上本次会话、同范围的5条上下文。应用分类仅在统一分类页另行授权后附带。")
                OutlinedButton(onClick = { consentDialog = true }, enabled = !working) { Text("管理交流与记忆授权") }
            }
            if (!state.conversation.enabled) {
                Text("交流尚未开启。你可以先写下来，再决定是否发送给AI。")
                FilledTonalButton(onClick = { consentDialog = true }, enabled = !working) { Text("开启交流 · 查看发送说明") }
            }
            if (!state.config.enabled || !state.hasKey) Text("联网交流前请在设置 → AI 提醒中保存 Key 并启用 AI；本地管理不需要联网。")
            if (status.isNotBlank()) Text(status)
        }
        when (page) {
            "交流" -> {
                item {
                    if (temporaryReply.isNotBlank()) Text("本次回复（不保存）：\n$temporaryReply")
                    OutlinedTextField(input, { if (it.length <= 2_400) input = it }, Modifier.fillMaxWidth(),
                        label = { Text("现在有什么想聊的？") },
                        placeholder = { Text("比如：总想打开某个应用，或者想让停顿轻一点……") }, minLines = 3, enabled = !working,
                        supportingText = { Text("最多1200字。不要填写密码、Key或不愿发往 DeepSeek 的内容。") })
                    Button(enabled = state.loaded && state.config.enabled && state.conversation.enabled && !working &&
                        ConversationPolicy.textValid(input), onClick = {
                        val text = input
                        action {
                            val delivery = repository.sendConversation(sessionId, pkg, text)
                            val current = repository.state.value
                            if (current.conversation.epoch == delivery.conversationEpoch &&
                                current.config.privacyEpoch == delivery.privacyEpoch &&
                                current.config.instanceId == delivery.instanceId && System.currentTimeMillis() < delivery.expiresAt) {
                                if (!current.conversation.saveMessages) {
                                    temporaryExpires = delivery.expiresAt
                                    temporaryReply = delivery.result.reply
                                }
                                input = ""
                                status = if (delivery.result.candidates.isEmpty()) "已回复。" else "已回复；候选在“记忆”页，确认前不会用于提醒。"
                            } else status = "背景已改变或到期，旧回复不再显示。"
                        }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("聊一聊，听听建议（可能计费）") }
                    OutlinedButton(enabled = !working && ConversationPolicy.textValid(input, 160),
                        onClick = { rememberDialog = true }) { Text("直接记住这段话（仅本地）") }
                    if (working) TextButton(onClick = { scope.launch { repository.cancelRequest() } }) { Text("取消当前请求") }
                }
                if (messages.isNotEmpty()) item { Text("最近的交流 · 最新在前", style = MaterialTheme.typography.titleMedium) }
                items(messages, key = { "message:${it.id}" }) { message ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(if (message.role == "USER") "你${if (!message.aiEligible) "（不再用于 AI）" else ""}" else "AI 回复（不是事实认定）")
                            Text(message.text)
                            TextButton(onClick = { deletingMessage = message; deleteIndependent = false }, enabled = !working) { Text("删除此消息及派生记忆") }
                        }
                    }
                }
            }
            "记忆" -> {
                item { Text("AI 摘要先确认再使用。忘记/纠正会让旧原文退出后续上下文，并清理派生 AI 回复与个性化提醒；不会删除无关的停顿次数。") }
                if (memories.isEmpty()) item { Text("还没有有效记忆。开启保存交流后，可从 AI 回复中收到候选。独立长期目标仍可在 AI 设置中编辑。") }
                items(memories, key = { "memory:${it.id}" }) { memory ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${if (memory.confirmed) "已确认" else "待确认摘要"} · ${label(memory.scopePackage)}")
                            Text(memory.text)
                            val source = state.messages.firstOrNull { it.id == memory.sourceMessageId }
                            Text(if (source != null) "来源原话：${source.text}" else if (memory.independent) "原始交流已删除或到期，独立保存的内容仍保留。" else "来源已到期或不可用")
                            Text(if (memory.independent) "独立长期偏好：保留到你主动忘记，不随清历史删除。" else "有效至 ${DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(memory.expiresAt))}")
                            if (!memory.independent && memory.kind != "CONTEXT") TextButton(enabled = !working,
                                onClick = { keeping = memory }) { Text("独立长期保存") }
                            if (!memory.confirmed) Button(enabled = !working, onClick = { action {
                                repository.conversations.confirm(memory.id); status = "已确认；使用记忆开启后，后续生成可参考它，不会立即收费生成。"
                            } }) { Text("确认这条记忆") }
                            TextButton(enabled = !working, onClick = { correcting = memory; correction = memory.text }) { Text("纠正并确认") }
                            TextButton(enabled = !working, onClick = { forgetting = memory }) { Text(if (memory.confirmed) "忘记" else "不记住") }
                        }
                    }
                }
            }
            "反馈" -> {
                item { Text("对实际显示过或准备好的短句反馈，不增加等待时间。“不再用这句”仅保证本地排除相同短句，不保证识别所有同义说法；默认离线生效，开启使用记忆后才发送反馈偏好。全部离线备用句都被排除时，只保留原有倒计时和操作按钮。手写句由你自己编辑。") }
                if (pkg.isEmpty()) item { Text("请先选择一个应用，查看可反馈的提醒。") }
                else {
                    val phrases = (listOfNotNull(state.lastDisplayed[pkg]?.takeIf { it.expiresAt > now }?.text) + repository.usablePersonalPhrases(pkg, now).map { it.text }).distinct().take(9)
                    items(phrases, key = { "phrase:$it" }) { phrase ->
                        Column { Text(phrase)
                            TextButton(onClick = { explaining = phrase }) { Text("为什么这样提醒") }
                            TextButton(enabled = !working, onClick = { feedbackPhrase = phrase }) { Text("这句不合适 / 不再用这句") } }
                    }
                    if (phrases.isEmpty()) item { Text("暂无可反馈短句。下次实际停顿显示后，或准备好提醒后，可在这里反馈。") }
                }
                items(state.feedback.filter { it.expiresAt > now && (pkg.isEmpty() || it.scopePackage == pkg) }, key = { "feedback:${it.id}" }) { feedback ->
                    Column { Text("${label(feedback.scopePackage)}：${feedback.instruction}")
                        TextButton(enabled = !working, onClick = { action { repository.conversations.deleteFeedback(feedback.id); status = "已撤销反馈；后续生成可重新选择这类表达。" } }) { Text("撤销这条反馈") } }
                }
            }
        }
    }
    explaining?.let { ReminderContextDialog(repository.explanationFor(pkg, it, now), onDismiss = { explaining = null }) }
    keeping?.let { memory -> AlertDialog(onDismissRequest = { keeping = null }, title = { Text("独立长期保存？") },
        text = { Text("${memory.text}\n\n请确认它适合作为长期目标或偏好。将保留此摘要和应用范围，直到你主动忘记；原交流过期或清历史不会删掉它。使用开关不会自动开启，也不会立即联网。最多保存50条独立偏好。") },
        confirmButton = { TextButton(onClick = { keeping = null; action { repository.conversations.keepIndependently(memory.id); status = "已独立保存；可在这里纠正或忘记。" } }) { Text("确认长期保存") } },
        dismissButton = { TextButton(onClick = { keeping = null }) { Text("取消") } }) }
    deletingMessage?.let { message ->
        val linked = state.memories.filter { it.sourceMessageId == message.id && it.independent }
        AlertDialog(onDismissRequest = { deletingMessage = null }, title = { Text("删除原消息？") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("将删除消息及普通派生记忆，清理相关 AI 回复和提醒；不改变行为次数。")
                if (linked.isNotEmpty()) {
                    Text("此消息另有独立保存内容，默认保留：\n${linked.joinToString("\n") { it.text }}")
                    Row { Checkbox(deleteIndependent, { deleteIndependent = it }); Text("同时删除这些独立偏好") }
                }
            } }, confirmButton = { TextButton(onClick = { deletingMessage = null; action {
                repository.conversations.deleteMessage(message.id, deleteIndependent); status = "删除已完成，独立偏好按你的选择处理。"
            } }) { Text("确认删除") } }, dismissButton = { TextButton(onClick = { deletingMessage = null }) { Text("取消") } })
    }
    if (consentDialog) AlertDialog(onDismissRequest = { consentDialog = false }, title = { Text("交流与记忆授权") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("发送时将把当前文字、风格与所选应用显示名称交给 DeepSeek，可能计费。不开启使用记忆时，不附带旧消息或画像。此开关不自动开启短句预生成。")
            Row { Checkbox(saveMessages, { saveMessages = it }); Text("保存交流到本机：最多30天，且不超过历史保留期；保存后才能提取待确认记忆。") }
            Row { Checkbox(useMemories, { useMemories = it }); Text("使用已确认记忆和反馈偏好：允许用于交流及已开启的下一次提醒生成，最多5条相关记忆。") }
            Text("关闭不会删除原始历史，但会停止对应使用并清理派生回复/提醒。取消已发出的请求不能撤回远端收到的内容或费用。记忆摘要不代表 AI 已核实事实。")
        } }, confirmButton = { TextButton(enabled = !working, onClick = { consentDialog = false; action {
            repository.conversations.configure(true, saveMessages, useMemories); status = "授权已保存，未发起联网请求。"
        } }) { Text("同意并保存") } }, dismissButton = { TextButton(enabled = !working, onClick = { consentDialog = false; action {
            repository.conversations.configure(false, state.conversation.saveMessages, false); status = "已关闭交流和记忆使用，本地原始内容仍可管理。"
        } }) { Text("关闭交流和记忆使用") } })
    if (selectingTarget) AlertDialog(onDismissRequest = { selectingTarget = false }, title = { Text("交流与记忆的范围") },
        text = { LazyColumn(Modifier.heightIn(max = 320.dp)) {
            item { TextButton(onClick = { pkg = ""; selectingTarget = false }) { Text("整体手机使用") } }
            item { app.pausecn.ui.CategoryFilter(state.categories, categoryFilter) { categoryFilter = it } }
            items(state.targets.filter { categoryFilter == null || state.categories.category(it.packageName) == categoryFilter }, key = { it.packageName }) { target ->
                TextButton(onClick = { pkg = target.packageName; selectingTarget = false }) { Text("${target.label} · ${state.categories.category(target.packageName)}") }
            }
        } }, confirmButton = { TextButton(onClick = { selectingTarget = false }) { Text("取消") } })
    if (rememberDialog) AlertDialog(onDismissRequest = { rememberDialog = false }, title = { Text("明确记住这段话") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(input)
            Text("范围：${label(pkg)}。此次明确保存会保留原话及已确认记忆，即使自动保存交流关闭也会保存；不联网、不自动开启记忆使用。")
            listOf("PREFERENCE" to "提醒偏好", "GOAL" to "目标", "CONTEXT" to "仅今天的情境").forEach { (kind, title) ->
                FilterChip(selected = memoryKind == kind, onClick = { memoryKind = kind }, label = { Text(title) })
            }
            Text("目标和偏好最多30天；仅今天的情境最晚到今天结束且不超过24小时。都不超过历史保留期限，可随时忘记。")
        } }, confirmButton = { TextButton(onClick = { rememberDialog = false; action {
            repository.conversations.remember(pkg, input, memoryKind); input = ""; page = "记忆"; status = "已按你的明确选择记住，没有联网。"
        } }) { Text("确认本地保存") } }, dismissButton = { TextButton(onClick = { rememberDialog = false }) { Text("取消") } })
    correcting?.let { memory -> AlertDialog(onDismissRequest = { correcting = null }, title = { Text("纠正记忆") },
        text = { OutlinedTextField(correction, { if (it.length <= 320) correction = it }, label = { Text("最多160字；保存即确认新陈述") }) },
        confirmButton = { TextButton(enabled = ConversationPolicy.textValid(correction, 160), onClick = { correcting = null; action {
            repository.conversations.correct(memory.id, correction); status = "已停用旧来源并保存纠正内容。"
        } }) { Text("保存纠正") } }, dismissButton = { TextButton(onClick = { correcting = null }) { Text("取消") } }) }
    forgetting?.let { memory -> AlertDialog(onDismissRequest = { forgetting = null }, title = { Text("忘记这条记忆？") },
        text = { Text("会删除同一来源的候选/记忆，原消息保留但不再进入 AI 上下文，清理相关 AI 回复与提醒。原始停顿次数不受影响。") },
        confirmButton = { TextButton(onClick = { forgetting = null; action { repository.conversations.forget(memory.id); status = "已忘记，旧来源不会再次进入 AI。" } }) { Text("忘记") } },
        dismissButton = { TextButton(onClick = { forgetting = null }) { Text("取消") } }) }
    feedbackPhrase?.let { phrase -> AlertDialog(onDismissRequest = { feedbackPhrase = null }, title = { Text("这句不合适") },
        text = { Column { Text(phrase); OutlinedTextField(feedbackText, { if (it.length <= 320) feedbackText = it }, label = { Text("希望怎样提醒（最多160字）") }); Text("不屏蔽用户手写句；如需修改手写内容，请前往 AI 设置。") } },
        confirmButton = { TextButton(enabled = ConversationPolicy.textValid(feedbackText, 160), onClick = { feedbackPhrase = null; action {
            repository.conversations.rejectPhrase(pkg, phrase, feedbackText); status = "已保存本地反馈，未发起网络请求；相同 AI 短句不再采用。"
        } }) { Text("保存反馈并排除这句") } }, dismissButton = { TextButton(onClick = { feedbackPhrase = null }) { Text("取消") } }) }
    if (leaveDialog) AlertDialog(onDismissRequest = { leaveDialog = false; pendingLeave = null }, title = { Text("离开交流？") },
        text = { Text("未发送草稿与不保存的回复将清除；本页进行中的交流请求会取消，已发送请求可能计费。") },
        confirmButton = { TextButton(onClick = { val destination = pendingLeave; pendingLeave = null; leaveDialog = false; destination?.invoke() }) { Text("离开") } }, dismissButton = { TextButton(onClick = { leaveDialog = false; pendingLeave = null }) { Text("继续编辑") } })
}
