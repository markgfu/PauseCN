package app.pausecn.reports

import app.pausecn.ai.AiRequestException

/** Persist fixed codes in the existing status column, never exception or model text. */
internal enum class AutomaticReportStage { PREPARING_FACTS, LOADING_FACTS, VALIDATING_FACTS, GENERATING }

internal object AutomaticReportDiagnostic {
    private val notSent = mapOf(
        "AUTO_FACTS_PREPARE_FAILED" to "本地使用记录准备失败，本次未发送。",
        "AUTO_FACTS_LOAD_FAILED" to "昨日统计读取失败，本次未发送。",
        "AUTO_FACTS_DATE_CHANGED" to "报告日期或时区已变化，本次未发送。",
        "REPORT_BUSY_NOT_SENT" to "当时已有其他AI请求，本次未发送。",
        "REPORT_NOT_SENT" to "发送前检查未通过，请检查AI、报告授权和本期数据；本次未发送。",
    )
    private val possiblySent = mapOf(
        "REPORT_REQUEST_FAILED" to "网络请求或服务返回未完成。",
        "REPORT_PARSE_FAILED" to "收到返回内容，但本地解析失败。",
        "REPORT_SOURCE_CHANGED" to "生成期间记录、授权或有效期已变化，旧解读未采用。",
        "REPORT_SOURCE_CHECK_FAILED" to "返回后核对本地来源失败。",
        "REPORT_SAVE_FAILED" to "返回内容已解析，但本地保存失败。",
        "REPORT_STATE_REFRESH_FAILED" to "处理后的本地状态刷新失败，请先查看昨日确认是否已有解读。",
    ) + ReportFormatProblem.entries.associate { "REPORT_FORMAT_${it.name}" to "返回格式未通过：${it.description}。" }

    fun failure(stage: AutomaticReportStage, error: Exception): String = when (stage) {
        AutomaticReportStage.PREPARING_FACTS -> "AUTO_FACTS_PREPARE_FAILED"
        AutomaticReportStage.LOADING_FACTS -> "AUTO_FACTS_LOAD_FAILED"
        AutomaticReportStage.VALIDATING_FACTS -> "AUTO_FACTS_DATE_CHANGED"
        AutomaticReportStage.GENERATING -> (error as? AiRequestException)?.diagnosticCode
            ?.takeIf { it in notSent || it in possiblySent } ?: "FAILED_OR_UNKNOWN"
    }

    fun message(status: String): String {
        notSent[status]?.let { return "$it 不会自动重试这一天。\n诊断码：$status" }
        possiblySent[status]?.let { return "$it 可能已计费，不会自动重试这一天。\n诊断码：$status" }
        return when (status) {
            "SUCCEEDED" -> "已生成并保存；若来源已删除或到期，旧报告可能已清理。"
            "UNKNOWN" -> "处理中或结果未知，可能已计费；不会自动重试这一天。"
            "CANCELLED_OR_UNKNOWN" -> "已中断、超时或取消，可能已计费；不会自动重试这一天。"
            else -> "未完成或结果未知，可能已计费；这条记录没有具体诊断，不会自动重试这一天。"
        }
    }
}
