package app.pausecn.reports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pausecn.data.AppContainer
import kotlinx.coroutines.CancellationException

/** Direct records-page entry. Loads existing local reports; never requests AI or force-refreshes facts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportShareEntryScreen(container: AppContainer, retentionDays: Int, onBack: () -> Unit) {
    val repository = remember(container) {
        ReportRepository(container.database,
            usagePermission = app.pausecn.usage.AndroidUsageSource(container.applicationContext)::hasPermission,
            bootId = { app.pausecn.usage.UsageClock.bootId(container.applicationContext) })
    }
    var period by remember { mutableStateOf(ReportPeriod.THIS_WEEK) }
    var snapshot by remember { mutableStateOf<LocalReportSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    BackHandler(enabled = snapshot == null, onBack = onBack)
    LaunchedEffect(period, retentionDays, retry) {
        snapshot = null; error = null
        try { snapshot = repository.load(period, retentionDays, false).first }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "本地报告暂时不可用，请稍后重试。" }
    }
    Column {
        FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportPeriod.entries.forEach { option ->
                FilterChip(selected = period == option, onClick = { if (period != option) { snapshot = null; period = option } }, label = { Text(option.label) })
            }
        }
        val source = snapshot
        if (source != null) {
            Box(Modifier.weight(1f)) {
                key(source) { ReportShareScreen(container, source, onBack, backLabel = "返回记录") }
            }
        } else Column(Modifier.padding(20.dp)) {
            app.pausecn.ui.BackButton(onClick = onBack, label = "返回记录")
            Text(error ?: "正在读取本地报告…")
            if (error != null) TextButton(onClick = { retry++ }) { Text("重试（不联网）") }
        }
    }
}
