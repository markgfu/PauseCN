package app.pausecn.ai

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class DisplayedReminder(val text: String, val contextJson: String, val generatedAt: Long,
    val expiresAt: Long, val revision: Long, val actualDisplay: Boolean = true)

/** These are the app's recorded request inputs, never a model-written rationale or reconstructed facts. */
object ReminderContext {
    fun local(config: AiConfig, choice: PromptChoice): String = JSONObject()
        .put("mode", if (config.manualPhrase.isNotBlank()) "MANUAL" else if (choice.id != null) "GENERIC" else "DEFAULT")
        .put("style", if (choice.id != null) config.style else "").toString()

    fun lines(json: String): List<String> {
        if (json.isBlank() || json.length > 32_000) return listOf("此旧版本短句未保存生成来源，不能根据现在的背景倒推。")
        return runCatching {
            val root = JSONObject(json)
            when (root.optString("mode")) {
                "MANUAL" -> return listOf("这是你手写的句子，不是 AI 根据行为生成的。")
                "DEFAULT" -> return listOf("这是本地备用提醒；本次展示没有请求 AI，也没有引用个人背景。")
                "GENERIC" -> return listOf("这是已确认的通用 AI 句库，只提供了生成时保存的风格：${root.optString("style")}")
            }
            buildList {
                add("生成时提供给 AI 的背景如下，不代表模型已核实语义，也不是模型内部推理：")
                add("应用：${root.optString("target_name")}；风格：${root.optString("style")}")
                add(if (root.optString("kind") == "STABLE") "稳定背景批次（未附带近期行为/理由/临时情境）" else "近期背景批次")
                val rows = root.optJSONArray("sources")
                for (i in 0 until minOf(rows?.length() ?: 0, 16)) {
                    val row = rows?.optJSONObject(i) ?: continue
                    val id = row.optString("id")
                    add(when {
                        id == "app_category" -> "生成时允许参考的应用分类：${row.optString("category")}；分类不是本次用途或行为判断。"
                        id == "profile" -> "你保存的背景：${row.optString("goal")}\n提醒偏好：${row.optString("preferences")}" 
                        id.startsWith("memory_") -> "已确认记忆摘要（不是原话引用）：${row.optString("text")}" 
                        id == "recent" -> "当时已记录的近期完成停顿：继续 ${row.optInt("continued")} 次，离开 ${row.optInt("exited")} 次；不是全部启动次数。"
                        id.startsWith("reason_") -> "过去的理由陈述：${row.optString("text")}（当时记录 ${row.optInt("uses")} 次，不代表本次用途）"
                        id.startsWith("feedback_") -> "你对表达的反馈：${row.optString("instruction")}" 
                        else -> "未识别的来源，不作解释。"
                    })
                }
                if (rows == null || rows.length() == 0) add("没有附带画像、记忆、理由或行为汇总。")
            }
        }.getOrElse { listOf("来源记录暂时不可用，不根据当前资料猜测。") }
    }
}

@Composable
fun ReminderContextDialog(reminder: DisplayedReminder?, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("为什么这样提醒") },
        text = { Text(if (reminder == null) "这条提醒已过期、删除或背景发生变化，不再展示旧来源。" else buildString {
            append(if (reminder.actualDisplay) "最近实际显示的提醒\n" else "已准备、尚可用的提醒\n")
            append(reminder.text).append("\n\n")
            if (reminder.generatedAt > 0) append("背景记录于：").append(DateTimeFormatter.ofPattern("MM-dd HH:mm")
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(reminder.generatedAt))).append("\n\n")
            append(ReminderContext.lines(reminder.contextJson).joinToString("\n\n"))
        }, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } })
}
