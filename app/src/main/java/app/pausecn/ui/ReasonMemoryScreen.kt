package app.pausecn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pausecn.data.ReasonMemory

@Composable
internal fun ReasonMemoryScreen(
    rows: List<ReasonMemory>,
    retentionDays: Int,
    onBack: () -> Unit,
    onForget: (ReasonMemory) -> Unit,
    onForgetAll: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var pending by remember { mutableStateOf<ReasonMemory?>(null) }
    var forgetAll by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { BackButton(onClick = onBack, label = "返回设置") }
        item { Text("继续理由与本地记忆", style = MaterialTheme.typography.headlineSmall) }
        item { Text("按每个应用分别统计最近${minOf(retentionDays, 30)}天已确认继续的理由。次数多的在前，同次数按最近使用排序；停顿最多展示三个自定义常用理由，加上预设选项。预览、离开和未确认输入不计入。") }
        item { Text("理由默认仅在本机复用，不代表下次打开的目的。用于AI提醒时，需在“我的背景与下次提醒”中开启预生成并允许发送理由摘要，最多发送当前应用3项常用理由及次数。用于使用解读时，需在解读页另行授权理由发送，最多发送3个目标各2项理由及应用名、次数。") }
        item { Text("忘记会清除历史中的对应理由文字，并使旧个性化缓存失效；继续/离开次数保留。理由原文随干预记录保留，也包含在基础加密导出中。") }
        item { OutlinedButton(onClick = { forgetAll = true }) { Text("清空全部理由记忆") } }
        if (rows.isEmpty()) item { Text("还没有可复用的理由。真实停顿中确认继续后，这里会出现记录。") }
        rows.groupBy { it.packageName }.toSortedMap().forEach { (_, appRows) ->
            item { Text(appRows.first().appLabel, style = MaterialTheme.typography.titleMedium) }
            items(appRows.sortedWith(compareByDescending<ReasonMemory> { it.uses }.thenByDescending { it.lastUsedAt }),
                key = { "${it.packageName}\u0000${it.text}" }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(row.text)
                        Text("确认使用 ${row.uses} 次", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { pending = row }) { Text("忘记这条理由") }
                    }
                }
            }
        }
    }
    if (pending != null || forgetAll) AlertDialog(onDismissRequest = { pending = null; forgetAll = false },
        title = { Text(if (forgetAll) "清空全部理由记忆？" else "忘记这条理由？") },
        text = { Text("会同时清除历史记录中的${if (forgetAll) "全部理由文字" else "该应用同一理由文字（含更早记录）"}，防止从旧记录再次生成记忆。继续/离开次数保留；已导出的文件不会改变。今后你主动再次使用该理由会作为新记录。") },
        confirmButton = { TextButton(onClick = {
            if (forgetAll) onForgetAll() else pending?.let(onForget)
            pending = null; forgetAll = false
        }) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = { pending = null; forgetAll = false }) { Text("取消") } })
}
