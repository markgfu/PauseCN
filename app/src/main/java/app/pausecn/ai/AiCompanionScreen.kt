package app.pausecn.ai

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pausecn.data.AppContainer
import app.pausecn.reports.ReportScreen

typealias AiNavigationGuard = (() -> Unit) -> Unit

/** A first-level home for everyday conversation and on-demand interpretation; entry is local only. */
@Composable
fun AiCompanionScreen(
    container: AppContainer,
    retentionDays: Int,
    onBack: () -> Unit,
    onPreview: () -> Unit,
    onNavigationGuard: (AiNavigationGuard?) -> Unit,
) {
    var interpretation by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var guard by remember { mutableStateOf<AiNavigationGuard?>(null) }
    val latestRegistration by rememberUpdatedState(onNavigationGuard)
    val register = remember { { next: AiNavigationGuard? -> guard = next; latestRegistration(next) } }
    fun navigate(action: () -> Unit) { guard?.invoke(action) ?: action() }
    if (settings) {
        AiSettingsScreen(container.aiRepository, onBack = { settings = false }, onPreview = onPreview, backLabel = "返回AI陪伴")
        return
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("AI陪伴", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = { navigate { settings = true } }) { Text("连接与风格") }
            }
            Text("随时聊聊，找到适合自己的使用节奏。", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(false to "交流建议", true to "使用解读").forEach { (value, title) ->
                    FilterChip(selected = interpretation == value,
                        onClick = { if (interpretation != value) navigate { interpretation = value } },
                        label = { Text(title, style = MaterialTheme.typography.titleMedium) },
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp))
                }
            }
        }
        Box(Modifier.weight(1f)) {
            if (interpretation) ReportScreen(container, retentionDays,
                onBack = { interpretation = false }, aiFirst = true)
            else ConversationScreen(container.aiRepository, onBack,
                embedded = true, onNavigationGuard = register)
        }
    }
}
