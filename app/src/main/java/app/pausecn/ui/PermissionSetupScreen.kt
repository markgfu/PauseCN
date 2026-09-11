package app.pausecn.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.pausecn.data.*
import app.pausecn.retryAfterLocalDataRecovery
import app.pausecn.usage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Optional usage consent stays separate from the age/accessibility disclosure and core gate. */
@Composable
fun PermissionSetupScreen(container: AppContainer, eligible: Boolean, accessibilityEnabled: Boolean,
    retentionDays: Int, onAccessibility: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val source = remember(container) { AndroidUsageSource(container.applicationContext) }
    val repository = container.usageRepository
    val safeConfig = remember(repository) { repository.config.retryAfterLocalDataRecovery(
        onFailure = { container.databaseHealthStore.reportFailure(DatabaseFailureReason.OPEN_FAILED) },
        awaitRecovery = { container.databaseHealthStore.state.first { it == DatabaseHealthState.Healthy } },
    ) }
    val config by safeConfig.collectAsStateWithLifecycle(initialValue = null)
    var permission by remember { mutableStateOf(source.hasPermission()) }
    var busy by remember { mutableStateOf(false) }
    var confirmUsage by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    fun openUsagePermission() {
        try { container.applicationContext.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { message = "请在系统设置中搜索“使用情况访问”，找到停一下并选择是否允许。" }
    }
    suspend fun refresh() {
        permission = source.hasPermission()
        if (busy) return
        busy = true
        try {
            if (eligible && container.database.usageDao().config()?.enabled == true) {
                repository.refresh(retentionDays)
                UsageScheduling.reconcile(container.applicationContext, true)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "暂时无法更新采集状态，请稍后刷新。" }
        finally { busy = false; permission = source.hasPermission() }
    }
    LaunchedEffect(lifecycle, eligible, retentionDays) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { refresh(); awaitCancellation() }
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            BackButton(onClick = onBack, label = "返回")
            Text("权限与数据用途", style = MaterialTheme.typography.headlineMedium)
            Text("每项单独选择。使用时长分析可跳过，不影响基础停顿。")
        }
        item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("打开前干预", style = MaterialTheme.typography.titleLarge)
                Text("只接收所选应用的窗口切换事件，用于显示停顿；不读取页面、聊天或键盘内容。")
                Text(if (accessibilityEnabled) "系统无障碍权限：已开启" else "系统无障碍权限：未开启")
                OutlinedButton(onClick = onAccessibility) { Text(if (!eligible) "确认年龄与用途" else "设置打开前干预") }
            } }
        }
        item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("使用时长分析 · 可选", style = MaterialTheme.typography.titleLarge)
                Text(USAGE_LOCAL_CONSENT)
                Text(if (config?.enabled == true) "本地分析：已开启" else "本地分析：未开启")
                Text(if (permission) "系统使用情况访问：已允许" else "系统使用情况访问：未允许")
                if (!eligible) Text("请先确认年龄与权限用途，再选择是否开启本地分析。")
                if (config?.enabled == true && permission) Text("可以采集后续使用。正常使用目标应用后返回刷新；报告需要刷新才显示新数据。")
                if (config?.enabled != true) Button(enabled = eligible && !busy, onClick = { confirmUsage = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("开启本地分析并设置权限")
                } else {
                    OutlinedButton(enabled = !busy, onClick = ::openUsagePermission, modifier = Modifier.fillMaxWidth()) { Text("设置系统使用情况访问") }
                    TextButton(enabled = !busy, onClick = { scope.launch {
                        busy = true
                        try {
                            repository.setEnabled(false)
                            UsageScheduling.reconcile(container.applicationContext, false)
                            message = "已暂停本地采集；已有记录保留，系统权限由你另行管理。"
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { message = "设置未能完成，请核对当前状态后重试。" }
                        finally { busy = false }
                    } }) { Text("暂停本地采集") }
                }
                TextButton(enabled = !busy, onClick = { scope.launch { refresh() } }) { Text(if (busy) "正在更新…" else "刷新授权与采集状态") }
            } }
        }
        item {
            if (message.isNotBlank()) Text(message)
            Text("不在这里授权AI。将时长发送给AI、使用个性化背景或AI分类，都需在对应功能里另行确认。", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onBack, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("完成 / 暂时跳过可选项") }
        }
    }
    if (confirmUsage) AlertDialog(onDismissRequest = { confirmUsage = false }, title = { Text("开启本地使用时长分析？") },
        text = { Text("$USAGE_LOCAL_CONSENT\n确认保存后，如果尚未授予系统权限，将打开系统设置，由你决定是否允许。") },
        confirmButton = { TextButton(enabled = eligible && !busy, onClick = {
            confirmUsage = false; busy = true; message = ""
            scope.launch {
                var saved = false
                try { repository.setEnabled(true); saved = true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "启用未保存，尚未打开系统权限设置，请稍后重试。" }
                finally { busy = false }
                if (saved) {
                    refresh()
                    if (!source.hasPermission()) openUsagePermission()
                }
            }
        }) { Text("确认开启并继续") } }, dismissButton = { TextButton(onClick = { confirmUsage = false }) { Text("暂时跳过") } })
}
