package app.pausecn.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.pausecn.domain.ContinueReason
import app.pausecn.domain.INTERVENTION_PURPOSES
import app.pausecn.domain.interventionPurpose

@Composable
internal fun ReasonPicker(
    choices: List<String> = INTERVENTION_PURPOSES.map { it.label },
    onConfirm: (String) -> Unit,
) {
    // Drafts are intentionally not saved across activity recreation or screen dismissal.
    var selected by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    val reason = ContinueReason.compose(selected, input)
    Column {
        choices.filter(ContinueReason::isValid).distinct().take(8).forEach { choice ->
            OutlinedButton(onClick = {
                if (selected == choice) {
                    selected = null
                } else {
                    selected = choice.takeIf { interventionPurpose(it) != null }
                    input = if (selected == null) choice else ""
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (choice == selected || choice == reason) "✓ $choice" else choice)
            }
        }
        OutlinedTextField(value = input, onValueChange = { if (it.length <= 320) input = it },
            modifier = Modifier.fillMaxWidth().testTag("continue_reason_input"), singleLine = true,
            label = { Text("补充说明，或直接写自己的理由") },
            supportingText = { Text("理由合计最多80个字；确认继续前不会保存。") },
            isError = reason == null && input.isNotEmpty())
        if (ContinueReason.isCasual(reason)) Text("没有明确目的，也可以继续。请再确认这是现在想做的事。")
        Button(onClick = { reason?.let(onConfirm) }, enabled = reason != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("confirm_continue_reason")) {
            Text(if (ContinueReason.isCasual(reason)) "仍然继续" else "确认理由并继续")
        }
    }
}
