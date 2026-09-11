package app.pausecn.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** Keep the measured space and composition, but do not place private content until validated.
 * Unplaced children are neither drawn nor touch targets; clear semantics as well while hidden.
 * The automatic controls stay outside this body and are never removed by report revalidation.
 */
@Composable
internal fun ReportValidatedBody(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = (if (visible) Modifier else Modifier.clearAndSetSemantics { })
            .layout { measurable, constraints ->
                val measured = measurable.measure(constraints)
                layout(measured.width, measured.height) {
                    if (visible) measured.placeRelative(0, 0)
                }
            },
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
internal fun ReportGenerationConfirmation(enabled: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("生成这份AI解读？") },
        text = { Text(if (enabled)
            "确认后按当前报告发送授权调用一次DeepSeek，可能计费。取消不发请求，生成失败也不会自动重试。"
        else "报告正在核对、依据已变化或授权不可用。若依据已变化，请取消后重新选择生成。") },
        confirmButton = { TextButton(enabled = enabled, onClick = onConfirm) { Text("确认生成（可能计费）") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
