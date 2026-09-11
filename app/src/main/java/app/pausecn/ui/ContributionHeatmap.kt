package app.pausecn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.pausecn.usage.HeatmapCell
import app.pausecn.usage.HeatmapMetric
import app.pausecn.usage.formatUsageDuration

/** Same hourly facts in a compact contribution-style overview; large targets remain available. */
@Composable
fun ContributionHeatmap(cells: List<HeatmapCell>, metric: HeatmapMetric,
    describe: (HeatmapCell) -> String, onCell: (HeatmapCell) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val currentOnCell by rememberUpdatedState(onCell)
    val maximum = cells.mapNotNull { it.value }.maxOrNull()?.coerceAtLeast(1) ?: 1L
    val palette = listOf(SageSoft, lerp(SageSoft, Sage, .35f), lerp(SageSoft, Sage, .65f), Sage, lerp(Sage, Ink, .5f))
    fun color(cell: HeatmapCell): Color = if (cell.value == null) Paper else palette[
        if (cell.value <= 0) 0 else (kotlin.math.ceil(cell.value.toDouble() / maximum * 4).toInt()).coerceIn(1, 4)]
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(48.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("00", "06", "12", "18", "24时").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = Muted) }
            }
        }
        cells.chunked(24).forEach { day ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(day.first().date.toString().substring(5), Modifier.width(48.dp), style = MaterialTheme.typography.labelSmall, color = Muted)
                BoxWithConstraints(Modifier.weight(1f)) {
                    val rowHeight = maxOf(maxWidth / 24, 20.dp)
                    Canvas(Modifier.fillMaxWidth().height(rowHeight)
                        .semantics { contentDescription = "${day.first().date} 全天分布；可展开逐小时查看详细数值" }
                        .pointerInput(day) { detectTapGestures { point ->
                            day.getOrNull((point.x / size.width * 24).toInt().coerceIn(0, 23))?.let(currentOnCell)
                        } }) {
                        val pitch = size.width / 24
                        val edge = (pitch - 2.dp.toPx()).coerceAtLeast(1f)
                        day.forEachIndexed { index, cell ->
                            val origin = Offset(index * pitch + (pitch - edge) / 2, (size.height - edge) / 2)
                            drawRoundRect(color(cell), origin, Size(edge, edge), CornerRadius(1.5.dp.toPx()))
                            if (cell.value == null) {
                                drawRoundRect(Line, origin, Size(edge, edge), CornerRadius(1.5.dp.toPx()), style = Stroke(1.dp.toPx()))
                                drawLine(Muted, origin + Offset(edge * .3f, edge * .7f), origin + Offset(edge * .7f, edge * .3f), 1.dp.toPx())
                            } else if (cell.partial) {
                                drawCircle(Ink, radius = 1.dp.toPx(), center = origin + Offset(edge * .75f, edge * .25f))
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text("少", style = MaterialTheme.typography.labelSmall, color = Muted)
            palette.forEach { shade -> Box(Modifier.padding(horizontal = 2.dp).size(10.dp).background(shade, RoundedCornerShape(2.dp))) }
            Text("多", style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        Text("每格1小时 · 斜线为未知 · 圆点为部分数据", style = MaterialTheme.typography.labelSmall, color = Muted)
        Text("色阶上限 ${if (metric == HeatmapMetric.PAUSES) "$maximum 次" else formatUsageDuration(maximum)} · 点格子看详情", style = MaterialTheme.typography.labelSmall, color = Muted)
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起逐小时明细" else "放大逐小时查看") }
        if (expanded) {
            val touchSize = maxOf(48f, 32f * LocalDensity.current.fontScale).dp
            Row {
                Column(Modifier.width(64.dp)) {
                    Spacer(Modifier.height(touchSize))
                    cells.chunked(24).forEach { day -> Box(Modifier.height(touchSize), contentAlignment = Alignment.CenterStart) {
                        Text(day.first().date.toString().substring(5), style = MaterialTheme.typography.labelSmall)
                    } }
                }
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Row { (0..23).forEach { hour -> Box(Modifier.size(touchSize), contentAlignment = Alignment.Center) { Text(hour.toString()) } } }
                    cells.chunked(24).forEach { day -> Row { day.forEach { cell ->
                        Box(Modifier.size(touchSize).padding(2.dp).background(color(cell), RoundedCornerShape(4.dp))
                            .clickable { onCell(cell) }.semantics { contentDescription = describe(cell) }, contentAlignment = Alignment.Center) {
                            Text((cell.value?.let { if (metric == HeatmapMetric.PAUSES) it.toString() else if (it in 1..59_999) "<1" else (it / 60_000).toString() } ?: "—") +
                                if (cell.partial && cell.value != null) "*" else "", color = if (cell.value != null && cell.value > maximum / 2) Color.White else Ink,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    } } }
                }
            }
            if (metric == HeatmapMetric.FOREGROUND) Text("明细格内单位：分钟。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
