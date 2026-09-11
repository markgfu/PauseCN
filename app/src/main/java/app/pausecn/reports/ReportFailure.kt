package app.pausecn.reports

import app.pausecn.ai.AiRequestException

/** Fixed local diagnostic labels only; never include response text, source IDs or credentials. */
enum class ReportFormatProblem(val description: String) {
    JSON("返回内容不是完整的JSON对象"),
    STRUCTURE("观察或建议的字段结构不符合约定"),
    COUNT("观察条数不在1至3条范围内"),
    TEXT_LENGTH("单条观察或建议超过300字"),
    TEXT_FORMAT("文本为空，或包含换行、不可显示字符、链接等不支持内容"),
    SOURCES("观察引用的来源缺失、格式不符或不在本次提供的来源中"),
    RESPONSE_LENGTH("返回内容超过处理长度"),
}

class ReportFormatException(val problem: ReportFormatProblem) : IllegalArgumentException(problem.name)

internal enum class ReportRequestStage { PREPARING, REQUESTING, PARSING, REVALIDATING, SAVING }
internal class ReportSourceChangedException : IllegalStateException()

internal data class ReportFailure(val code: String, val message: String)

internal fun reportFailure(stage: ReportRequestStage, error: Exception, reserved: Boolean): ReportFailure {
    val mayHaveSent = reserved && stage != ReportRequestStage.PREPARING
    val failure = when {
        !mayHaveSent -> ReportFailure("REPORT_NOT_SENT", "本次未发送，请检查AI启用、报告授权及本期事实，或刷新本地报告。")
        error is ReportFormatException -> ReportFailure("REPORT_FORMAT_${error.problem.name}", "AI返回格式未通过：${error.problem.description}。")
        error is ReportSourceChangedException -> ReportFailure("REPORT_SOURCE_CHANGED", "报告来源、授权或有效期已变化，本次旧解读未采用。")
        stage == ReportRequestStage.REQUESTING -> ReportFailure("REPORT_REQUEST_FAILED",
            (error as? AiRequestException)?.publicMessage ?: "请求未完成，暂时无法确认服务端是否处理。")
        stage == ReportRequestStage.PARSING -> ReportFailure("REPORT_PARSE_FAILED", "收到返回内容，但本地解析未完成。")
        stage == ReportRequestStage.REVALIDATING -> ReportFailure("REPORT_SOURCE_CHECK_FAILED", "返回后核对本地来源失败，不能确认解读仍然有效。")
        stage == ReportRequestStage.SAVING -> ReportFailure("REPORT_SAVE_FAILED", "返回内容已解析，但保存解读失败；请先重新打开报告查看。")
        else -> ReportFailure("REPORT_PREPARE_FAILED", "请求准备未完成，未取得可用解读。")
    }
    val charge = if (mayHaveSent) "不会自动重试；可能已计费。" else "不会自动重试。"
    return failure.copy(message = "${failure.message}\n$charge\n诊断码：${failure.code}")
}
