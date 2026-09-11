package app.pausecn.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/** User-authored background is not inferred from usage, and saving is not upload consent. */
@Composable
fun UserProfileScreen(repository: AiRepository, onBack: () -> Unit) {
    val state by repository.state.collectAsStateWithLifecycle()
    val profile = state.profile
    val scope = rememberCoroutineScope()
    var goal by remember(profile?.revision, state.loaded) { mutableStateOf(profile?.goal.orEmpty()) }
    var preferences by remember(profile?.revision, state.loaded) { mutableStateOf(profile?.preferences.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var showConsent by remember { mutableStateOf(false) }
    var useProfile by remember(state.personalization.epoch) { mutableStateOf(state.personalization.useProfile) }
    var useReasons by remember(state.personalization.epoch) { mutableStateOf(state.personalization.useReasons) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var explaining by remember { mutableStateOf<Pair<String, String>?>(null) }
    var targetQuery by rememberSaveable { mutableStateOf("") }
    val matchingTargets = remember(state.targets, targetQuery) {
        val query = targetQuery.trim()
        state.targets.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) ||
            it.packageName.contains(query, ignoreCase = true) }
    }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    LaunchedEffect(state.personalPhrases, state.personalization.epoch) { now = System.currentTimeMillis() }
    val dirty = goal != profile?.goal.orEmpty() || preferences != profile?.preferences.orEmpty()
    val leave: () -> Unit = { if (!saving) { if (dirty) confirmLeave = true else onBack() } }
    BackHandler(onBack = leave)
    LaunchedEffect(repository) {
        try { repository.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "本地背景读取失败，请返回后重试。" }
    }
    val action: (suspend () -> Unit) -> Unit = { work ->
        scope.launch {
            saving = true
            error = ""
            message = ""
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "操作未完成，请检查状态后重试；已发出的请求可能计费，不会自动重试。" }
            finally { saving = false }
        }
    }
    LazyColumn(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { app.pausecn.ui.BackButton(onClick = leave, enabled = !saving, label = "返回 AI 设置") }
        item { Text("我的背景与偏好", style = MaterialTheme.typography.headlineMedium) }
        item { Text("只记你主动填写的内容，不根据使用记录推断人格。这份背景面向所有目标应用；如果只与某个应用有关，请在内容里说明。") }
        item { Text("保存只写入本机，不会发起AI请求。只有下方单独开启预生成并勾选“使用这份背景”后，它才会进入后续请求；关闭或删除使旧个性化缓存失效。") }
        item {
            OutlinedTextField(value = goal, onValueChange = { if (it.length <= 1_200) goal = it },
                modifier = Modifier.fillMaxWidth().testTag("profile_goal"),
                label = { Text("我的目标或背景") }, minLines = 3, enabled = state.loaded && !saving,
                placeholder = { Text("例如：我想在睡前少刷小红书，但查资料时仍会继续。") },
                supportingText = { Text("${goal.codePointCount(0, goal.length)}/600；填写你愿意保存在本机的内容。") },
                isError = !ProfileText.valid(goal))
        }
        item {
            OutlinedTextField(value = preferences, onValueChange = { if (it.length <= 1_200) preferences = it },
                modifier = Modifier.fillMaxWidth().testTag("profile_preferences"),
                label = { Text("提醒偏好与不喜欢的方式") }, minLines = 3, enabled = state.loaded && !saving,
                placeholder = { Text("例如：可以幽默一些；不要把选择继续说成失败。") },
                supportingText = { Text("${preferences.codePointCount(0, preferences.length)}/600；可换行，不必写满。") },
                isError = !ProfileText.valid(preferences))
        }
        item {
            Button(onClick = { action {
                repository.saveProfile(goal, preferences)
                // Repository normalizes whitespace without silently truncating the user's text.
                goal = repository.state.value.profile?.goal.orEmpty()
                preferences = repository.state.value.profile?.preferences.orEmpty()
                message = "已保存到本机，未调用 AI。"
            } }, modifier = Modifier.testTag("save_profile"), enabled = state.loaded && !saving && dirty &&
                ProfileText.valid(goal) && ProfileText.valid(preferences) && (goal.isNotBlank() || preferences.isNotBlank())) {
                Text(if (saving) "正在保存…" else "保存本地背景")
            }
        }
        if (profile != null) item {
            TextButton(onClick = { confirmDelete = true }, enabled = !saving,
                modifier = Modifier.testTag("delete_profile")) { Text("删除这份背景") }
        }
        item { HorizontalDivider() }
        item { Text("为下次停顿提前准备", style = MaterialTheme.typography.titleLarge) }
        item { Text("开启后，在真实选择继续/离开结束时，根据背景变化和可用文案决定是否准备该应用下一次的提醒；也可在下方手动准备。新句子校验后自动采用，不逐批确认；停顿不会等待网络。") }
        item { Row { Checkbox(checked = useProfile, onCheckedChange = { useProfile = it }, enabled = !saving)
            Text("使用这份已保存的目标与偏好（可选）") } }
        item { Row { Checkbox(checked = useReasons, onCheckedChange = { useReasons = it }, enabled = !saving)
            Text("发送该应用最多3项常用理由及次数摘要（可选，非本次用途）") } }
        item { Text("基本背景包含当前应用名称、提醒风格与近期最多20条已完成停顿的次数汇总；不发送包名、原始事件或其他应用内容。以上两个选项单独决定是否附上画像/理由。") }
        item { Text("其他已授权背景：应用分类${if (state.categories.settings.sendToAi) "允许发送" else "不发送"}；交流记忆与反馈${if (state.conversation.useMemories) "允许发送" else "不发送"}。分别由分类管理和交流设置控制，本页不会替你开启。") }
        item { Button(onClick = { showConsent = true }, enabled = state.loaded && state.config.enabled && !saving && !dirty) {
            Text(if (state.personalization.enabled) "查看说明并更新授权" else "查看说明并开启预生成")
        } }
        if (!state.config.enabled) item { Text("请返回AI设置先启用AI；未启用时仍可在本地保存背景。") }
        if (dirty) item { Text("背景有未保存修改，请先保存；预生成只会使用已保存的内容。") }
        if (state.personalization.enabled) item {
            OutlinedButton(onClick = { action {
                repository.setPersonalization(false, false, false)
                message = "已关闭预生成，相关缓存失效；没有删除本地背景。"
            } }, enabled = !saving) { Text("关闭预生成并撤销发送授权") }
        }
        item { Text("状态：${if (state.personalization.enabled) "已开启" else "未开启"}。不设每日句子或请求总数上限；后台请求也可能收费。最多一个在途请求和3个待处理应用，同应用合并，不自动重试。") }
        if (state.busy) item { TextButton(onClick = { scope.launch { repository.cancelRequest() } }) { Text("取消当前请求及等待队列") } }
        if (state.personalStatus.isNotBlank()) item { Text(state.personalStatus) }
        if (state.config.manualPhrase.isNotBlank()) item { Text("当前手写短句优先显示；需要AI提醒时，请先返回设置清空手写短句并保存。") }
        item { Text("初次准备：发送应用名称与风格，按现有授权附上画像、分类、相关长期记忆及反馈；不带近期行为、继续理由或使用时长。") }
        item {
            OutlinedTextField(value = targetQuery, onValueChange = { if (it.length <= 100) targetQuery = it },
                label = { Text("查找要准备提醒的应用") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { if (targetQuery.isNotEmpty()) TextButton(onClick = { targetQuery = "" }) { Text("清除") } })
            if (state.targets.isNotEmpty() && matchingTargets.isEmpty()) Text("没有匹配的目标应用，请换个关键词或清除搜索。")
        }
        items(matchingTargets, key = { "prepare:${it.packageName}" }) { target ->
            Column {
                Text(target.label)
                Text("${state.categories.category(target.packageName)} · ${if (state.categories.settings.sendToAi) "允许AI参考分类" else "分类不发送"}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { action { message = repository.prepareNext(target.packageName) } },
                    enabled = state.personalization.enabled && state.config.enabled && !saving && !dirty &&
                        useProfile == state.personalization.useProfile && useReasons == state.personalization.useReasons &&
                        state.config.manualPhrase.isBlank()) { Text("准备这个应用的下次提醒") }
                val ready = repository.usablePersonalPhrases(target.packageName, now)
                ready.take(8).forEach { phrase ->
                    Text("${if (phrase.kind == PersonalizedCachePolicy.RECENT) "近期" else "背景"}：${phrase.text}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { explaining = target.packageName to phrase.text }) { Text("为什么这样提醒") }
                }
            }
        }
        if (state.targets.isEmpty()) item { Text("请先在应用选择页设置需要停一下的应用。") }
        if (message.isNotBlank()) item { Text(message, color = MaterialTheme.colorScheme.primary) }
        if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
        item { Text("这是你独立保存的偏好，保留到主动删除，不随“清除历史”消失。“重置全部数据”会删除它。加密导出默认不包含这份背景，需另行勾选；不会自动读取旧理由、对话或其他应用内容来补全它。", style = MaterialTheme.typography.bodySmall) }
    }
    explaining?.let { (pkg, text) -> ReminderContextDialog(repository.explanationFor(pkg, text, now), onDismiss = { explaining = null }) }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("删除本地背景？") },
        text = { Text("删除已保存的目标与偏好，同时放弃本页未保存修改，并使旧个性化短句失效；不删除继续理由、行为计数、手写句或Key。") },
        confirmButton = { TextButton(onClick = {
            confirmDelete = false
            action {
                repository.deleteProfile()
                goal = ""
                preferences = ""
                message = "本地背景已删除。"
            }
        }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false },
        title = { Text("放弃未保存修改？") }, text = { Text("已保存的背景不会改变。") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; onBack() }) { Text("放弃修改并返回") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("继续编辑") } })
    if (showConsent) AlertDialog(onDismissRequest = { showConsent = false },
        title = { Text("允许为下次停顿提前生成？") },
        text = { Text("将当前目标应用名称、风格和近期完成停顿的汇总发送至DeepSeek。" +
            "本次选择：画像${if (useProfile) "允许" else "不发送"}；常用理由摘要${if (useReasons) "允许" else "不发送"}。" +
            "其他现有授权：分类${if (state.categories.settings.sendToAi) "允许" else "不发送"}；交流记忆与反馈${if (state.conversation.useMemories) "允许" else "不发送"}，由各自设置独立控制。初次手动准备不带近期行为或继续理由，但可附已授权的长期记忆与反馈。" +
            "开启后真实选择结束时可能自动调用；合格新句直接用于下次。不限每日总量，调用由你的账户付费，失败也可能计费，不自动重试。" +
            "保存授权本身不发请求。可随时关闭或删除，但不能撤回远端已收到的数据和费用。",
            modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = {
            showConsent = false
            action { repository.setPersonalization(true, useProfile, useReasons); message = "授权已保存，尚未发起请求。" }
        }) { Text("同意并保存授权") } },
        dismissButton = { TextButton(onClick = { showConsent = false }) { Text("取消") } })
}
