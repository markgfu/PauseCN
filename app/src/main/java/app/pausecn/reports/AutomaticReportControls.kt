package app.pausecn.reports

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pausecn.data.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
internal fun AutomaticReportControls(container: AppContainer, onYesterday: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<AutomaticReportState?>(null) }
    var confirmation by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(container) {
        try {
            container.database.invalidationTracker.createFlow("automatic_report").collect {
                state = container.database.automaticReportDao().state() ?: AutomaticReportState()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { state = null; message = "自动复盘状态暂不可用。" }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }) { Text("自动昨日复盘 · ${if (state?.enabled == true) "已开启" else "默认关闭"}") }
        if (expanded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("由系统择机生成昨日报告", Modifier.weight(1f))
                Switch(enabled = state != null && !busy, checked = state?.enabled == true, onCheckedChange = { enabled ->
                    if (enabled) confirmation = true else {
                        busy = true; scope.launch {
                            try {
                                container.aiRepository.setAutomaticReports(false)
                                AutomaticReportScheduling.reconcile(container.applicationContext, false)
                                message = "已关闭后续自动复盘；已发送请求可能计费，已有报告不删除。"
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { message = "关闭未确认，请核对开关状态。" }
                            finally { busy = false }
                        }
                    }
                })
            }
            Text("约每日一次，有网络（含移动网络）且电量允许时由系统安排，可能延迟，不保证零点或每天完成。不自动生成周报，不自动应用建议。", style = MaterialTheme.typography.bodySmall)
            state?.takeIf { it.lastDate.isNotBlank() }?.let { last ->
                Text("最近自动报告日期：${last.lastDate} · ${last.lastZone}")
                Text(AutomaticReportDiagnostic.message(last.status), style = MaterialTheme.typography.bodySmall)
                if (runCatching { LocalDate.parse(last.lastDate) >= LocalDate.now() }.getOrDefault(true))
                    Text("自动记录日期不早于今天，可能发生过改时或换时区；为避免重复费用，等待报告日期超过它后再自动尝试。")
            }
            TextButton(onClick = onYesterday) { Text("查看昨日（需要时可手动生成，可能计费）") }
            if (state?.enabled == true) TextButton(enabled = !busy, onClick = { scope.launch {
                try {
                    val enabled = AutomaticReportScheduling.enabled(container.database).first()
                    AutomaticReportScheduling.reconcile(container.applicationContext, enabled)
                    message = if (enabled) "已向系统确认调度，不代表报告已生成。" else "AI或报告发送授权未开启，自动任务暂停。"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "系统尚未接受调度，可稍后重试调度；本次未调用AI。" }
            } }) { Text("检查系统调度") }
            message?.let { Text(it) }
        }
    }
    if (confirmation) AlertDialog(onDismissRequest = { if (!busy) confirmation = false }, title = { Text("开启自动昨日复盘？") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("这是额外的后台联网授权。系统首次安排时也可能生成昨日报告，每次可能产生DeepSeek费用。需已启用AI、保存Key并开启报告发送授权。")
            Text("自动生成使用报告发送选项，包括你已勾选的时长、画像、记忆、理由和设置；以后修改报告发送选项也适用于自动任务。")
            Text("同一本地记录下，每个报告日期最多自动尝试一次。忙碌、失败、取消、超时或结果未知都不自动重试；普通重启/关闭再开启不会重置日期记录。清空全部数据后该记录也被删除，重新开启可能再次尝试。")
            Text("可随时关闭。不能撤回已发送请求或保证它未计费；不会自动应用设置建议，不保证每天准时完成。")
        } }, confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true; scope.launch {
                try {
                    container.aiRepository.setAutomaticReports(true)
                    try {
                        AutomaticReportScheduling.reconcile(container.applicationContext, true)
                        message = "已授权并提交系统调度，不代表报告已经生成。"
                    } catch (_: Exception) { message = "授权已开启，但系统调度未确认，可点检查系统调度。" }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "未开启，请先检查AI启用、已保存Key和报告发送授权。" }
                finally { busy = false; confirmation = false }
            }
        }) { Text(if (busy) "保存中…" else "同意开启（可能计费）") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirmation = false }) { Text("暂不开启") } })
}
