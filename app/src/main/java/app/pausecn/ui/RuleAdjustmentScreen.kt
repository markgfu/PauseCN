package app.pausecn.ui

import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pausecn.data.*
import app.pausecn.domain.ScheduleSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import app.pausecn.reports.RuleProposal

private data class PendingRuleChange(val expected: RuleState, val patch: RulePatch)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleAdjustmentScreen(store: SettingsStore, onBack: () -> Unit, initialProposal: RuleProposal? = null,
    proposalValidity: Flow<Boolean>? = null,
    applyProposal: (suspend (RuleState, RulePatch) -> Unit)? = null) {
    var state by remember { mutableStateOf<RuleAdjustmentState?>(null) }
    var baseline by remember { mutableStateOf<RuleState?>(null) }
    var draft by remember { mutableStateOf(RuleValues()) }
    var pending by remember { mutableStateOf<PendingRuleChange?>(null) }
    var pendingUndo by remember { mutableStateOf<RuleUndo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var sourceValid by remember { mutableStateOf(initialProposal == null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    BackHandler { if (!busy) onBack() }
    LaunchedEffect(proposalValidity) { proposalValidity?.collect { sourceValid = it; if (!it) pending = null } }
    LaunchedEffect(store) {
        try {
            store.ruleAdjustments.collect {
                state = it
                if (baseline == null) {
                    baseline = initialProposal?.basis ?: it.current
                    draft = initialProposal?.let { candidate -> candidate.patch.target(candidate.basis.values) } ?: it.current.values
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { state = null; message = "设置暂时无法读取，请返回后重试。" }
    }
    val current = state?.current
    val stale = baseline != null && baseline != current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BackButton(enabled = !busy, onClick = onBack)
        Text("调整停顿设置", style = MaterialTheme.typography.headlineSmall)
        Text(if (initialProposal == null) "根据复盘自行选择，确认后才保存。本页不联网，也不会从AI文字猜测要改的值。"
            else "AI建议已填入，可自行调整。确认前不会保存；作用于全局，不是分应用或分昼夜设置。", style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it) }
        if (current == null) { Text("正在读取当前设置…"); return@Column }
        if (initialProposal != null && !sourceValid) { Text("正在核对建议，或建议已失效。返回报告刷新；也可从设置页手动调整。"); return@Column }
        Text("当前：${ruleSummary(current.values)}", style = MaterialTheme.typography.bodyMedium)
        if (stale) {
            Text("设置已有变化，请重新载入后再确认。", color = MaterialTheme.colorScheme.error)
            TextButton(enabled = !busy, onClick = {
                baseline = current; draft = initialProposal?.patch?.target(current.values) ?: current.values
                message = if (initialProposal == null) "已载入当前值，未保存的选择已重置。" else "已按当前设置重新对比原建议，请再次核对修改范围；尚未保存。"
            }) { Text(if (initialProposal == null) "重新载入当前值" else "按当前设置重新对比建议") }
        }
        Text("等待时间", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { RulePatch.WAIT_CHOICES.forEach { seconds ->
            FilterChip(enabled = !busy, selected = draft.waitSeconds == seconds, onClick = { draft = draft.copy(waitSeconds = seconds) }, label = { Text("$seconds 秒") })
        } }
        Text("临时通行", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { RulePatch.PASS_CHOICES.forEach { minutes ->
            FilterChip(enabled = !busy, selected = draft.passMinutes == minutes, onClick = { draft = draft.copy(passMinutes = minutes) }, label = { Text("$minutes 分钟") })
        } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("启用干预计划", Modifier.weight(1f))
            Switch(enabled = !busy, checked = draft.schedule.enabled, onCheckedChange = { draft = draft.copy(schedule = draft.schedule.copy(enabled = it)) })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, label ->
            val bit = 1 shl index
            FilterChip(enabled = !busy, selected = draft.schedule.activeDaysMask and bit != 0,
                onClick = { draft = draft.copy(schedule = draft.schedule.copy(activeDaysMask = draft.schedule.activeDaysMask xor bit)) }, label = { Text("周$label") })
        } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { TimePickerDialog(context, { _, hour, minute ->
                draft = draft.copy(schedule = draft.schedule.copy(startMinutes = hour * 60 + minute))
            }, draft.schedule.startMinutes / 60, draft.schedule.startMinutes % 60, true).show() }) { Text("开始 ${ruleTime(draft.schedule.startMinutes)}") }
            OutlinedButton(enabled = !busy, onClick = { TimePickerDialog(context, { _, hour, minute ->
                draft = draft.copy(schedule = draft.schedule.copy(endMinutes = hour * 60 + minute))
            }, draft.schedule.endMinutes / 60, draft.schedule.endMinutes % 60, true).show() }) { Text("结束 ${ruleTime(draft.schedule.endMinutes)}") }
        }
        Text(scheduleSummary(draft.schedule), style = MaterialTheme.typography.bodySmall)
        Button(enabled = !busy && !stale && baseline != null && draft != baseline?.values, onClick = {
            val source = baseline ?: return@Button
            pending = PendingRuleChange(source, RulePatch.between(source.values, draft))
        }) { Text("查看修改并确认") }
        state?.undo?.let { undo ->
            HorizontalDivider()
            Text("最近一次批量修改", style = MaterialTheme.typography.titleMedium)
            Text(ruleDiff(undo.before, undo.after.values))
            TextButton(enabled = !busy, onClick = { pendingUndo = undo }) { Text("查看撤销") }
        }
    }
    pending?.let { proposal ->
        val target = proposal.patch.target(proposal.expected.values)
        AlertDialog(onDismissRequest = { if (!busy) pending = null }, title = { Text("确认修改全局规则？") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(ruleDiff(proposal.expected.values, target))
                Text("影响所有已选目标应用。${scheduleSummary(target.schedule)}")
                Text("不会修改应用列表、授权、保留期或全局暂停状态。保存时发现后续修改将停止，不覆盖新设置。")
            } }, confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true; scope.launch {
                    try {
                        if (applyProposal != null) applyProposal(proposal.expected, proposal.patch)
                        else store.applyRuleChange(proposal.expected, proposal.patch)
                        val latest = store.ruleAdjustments.first().current
                        baseline = latest; draft = latest.values; message = "修改已保存并回读确认，可撤销最近这次修改。"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { message = if (failure is RuleConflictException) failure.message else "本次保存未确认，请核对当前值后再操作。" }
                    finally { pending = null; busy = false }
                }
            }) { Text(if (busy) "保存中…" else "确认保存") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text("取消") } })
    }
    pendingUndo?.let { undo ->
        AlertDialog(onDismissRequest = { if (!busy) pendingUndo = null }, title = { Text("撤销最近这次修改？") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(ruleDiff(undo.after.values, undo.before))
                Text("只恢复本次修改的项目，保留其他设置；相关项目已有后续调整时停止撤销。")
            } }, confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true; scope.launch {
                    try {
                        store.undoRuleChange(undo.id)
                        val latest = store.ruleAdjustments.first().current
                        baseline = latest; draft = latest.values; message = "撤销已保存并回读确认。"
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { message = if (failure is RuleConflictException) failure.message else "撤销未确认，请核对当前值。" }
                    finally { pendingUndo = null; busy = false }
                }
            }) { Text(if (busy) "保存中…" else "确认撤销") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { pendingUndo = null }) { Text("取消") } })
    }
}

private fun ruleTime(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)
private fun scheduleSummary(value: ScheduleSpec): String {
    if (!value.enabled || value.activeDaysMask == 0) return "计划关闭或未选择星期，不进行打开前停顿。"
    val days = listOf("一", "二", "三", "四", "五", "六", "日").filterIndexed { index, _ -> value.activeDaysMask and (1 shl index) != 0 }.joinToString("、")
    return "周$days：" + if (value.startMinutes == value.endMinutes) "全天生效（起止相同）。"
        else "${ruleTime(value.startMinutes)}—${ruleTime(value.endMinutes)}${if (value.startMinutes > value.endMinutes) "次日（星期按开始日）" else ""}生效，其他时段不提醒。"
}
private fun ruleSummary(value: RuleValues) = "等待${value.waitSeconds}秒 · 通行${value.passMinutes}分钟 · ${scheduleSummary(value.schedule)}"
private fun ruleDiff(before: RuleValues, after: RuleValues): String = buildList {
    if (before.waitSeconds != after.waitSeconds) add("等待：${before.waitSeconds}秒 → ${after.waitSeconds}秒")
    if (before.passMinutes != after.passMinutes) add("通行：${before.passMinutes}分钟 → ${after.passMinutes}分钟")
    if (before.schedule != after.schedule) add("生效计划：${scheduleSummary(before.schedule)}\n改为：${scheduleSummary(after.schedule)}")
}.joinToString("\n")
