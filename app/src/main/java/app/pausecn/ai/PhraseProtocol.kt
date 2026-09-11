package app.pausecn.ai

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Codes/descriptions are local constants; diagnostics never echo response text or server errors. */
enum class PhraseProblem(val description: String) {
    JSON_INVALID("返回内容不是单个可解析的 JSON 对象"),
    ROOT_FIELDS("根对象必须含 phrases 数组"),
    BATCH_SIZE("短句数组为空或超过36条"),
    ROW_FIELDS("条目必须含字符串 scene 和 text"),
    UNKNOWN_SCENE("场景不在允许列表中"),
    TEXT_LIMIT("整理空白后短句为空、超过36个Unicode码点或含异常控制字符"),
    SENSITIVE_REQUEST("含外链或疑似密钥，不适合直接展示"),
    DUPLICATE("同场景重复短句"),
    NO_USABLE_PHRASES("没有可用候选句"),
}

data class PhraseIssue(val index: Int, val problem: PhraseProblem)

class PhraseValidationException(
    val code: PhraseProblem,
    details: List<PhraseIssue> = emptyList(),
) : Exception() {
    val publicMessage: String = buildString {
        append("未保存新文案（P-").append(code.name).append("）：").append(code.description).append('。')
        if (details.isNotEmpty()) append(problemSummary(details))
        append("未自动重试；请求可能已计费。")
    }
    override val message: String get() = publicMessage
}

data class PhraseBatch(
    val phrases: List<AiPhrase>,
    val issues: List<PhraseIssue>,
    val missingScenes: Set<PromptScene>,
) {
    val publicMessage: String get() = buildString {
        append("已生成").append(phrases.size).append("条候选短句。")
        if (issues.isNotEmpty()) {
            append("另有").append(issues.size).append("条未采用。")
            append(problemSummary(issues))
        }
        if (missingScenes.isNotEmpty()) append("缺少有效文案的场景暂用固定提醒。")
        append("请先检查全部内容，确认后才用于真实停顿。")
    }
}

private fun problemSummary(issues: List<PhraseIssue>): String = issues.groupingBy { it.problem }
    .eachCount().entries.joinToString(separator = "；", postfix = "。") {
        "${it.key.description}：${it.value}条（P-${it.key.name}）"
    }

object PhraseProtocol {
    const val PROMPT_ASSET = "prompts/short_phrases.md"
    const val MAX_RESPONSE_CHARS = 16_000
    const val MAX_BATCH_SIZE = 36

    fun parse(content: String, version: Long): List<AiPhrase> = parseBatch(content, version).phrases

    fun parseBatch(content: String, version: Long): PhraseBatch {
        if (content.isBlank() || content.length > MAX_RESPONSE_CHARS) fail(PhraseProblem.JSON_INVALID)
        val root = try {
            // Accept one complete JSON fence; never extract fragments from arbitrary prose.
            val trimmed = content.trim()
            val json = jsonFence.matchEntire(trimmed)?.groupValues?.get(1) ?: trimmed
            val tokenizer = JSONTokener(json)
            val value = tokenizer.nextValue()
            if (value !is JSONObject || tokenizer.nextClean() != '\u0000') fail(PhraseProblem.JSON_INVALID)
            value
        } catch (_: JSONException) {
            fail(PhraseProblem.JSON_INVALID)
        }
        val array = root.opt("phrases") as? JSONArray ?: fail(PhraseProblem.ROOT_FIELDS)
        if (array.length() !in 1..MAX_BATCH_SIZE) fail(PhraseProblem.BATCH_SIZE)

        val accepted = mutableListOf<AiPhrase>()
        val issues = mutableListOf<PhraseIssue>()
        val seen = mutableSetOf<Pair<String, String>>()
        for (index in 0 until array.length()) {
            val row = array.opt(index) as? JSONObject
            if (row == null || row.opt("scene") !is String || row.opt("text") !is String) {
                issues += PhraseIssue(index, PhraseProblem.ROW_FIELDS)
                continue
            }
            val scene = PromptScene.entries.firstOrNull { it.name == row.getString("scene") }
            if (scene == null) {
                issues += PhraseIssue(index, PhraseProblem.UNKNOWN_SCENE)
                continue
            }
            // Harmless layout whitespace need not cost the user another generation request.
            val text = normalizeText(row.getString("text"))
            val problem = when {
                !PromptSelector.validShortText(text) -> PhraseProblem.TEXT_LIMIT
                sensitiveContent.containsMatchIn(text) -> PhraseProblem.SENSITIVE_REQUEST
                (scene.name to text) in seen -> PhraseProblem.DUPLICATE
                else -> null
            }
            if (problem != null) {
                issues += PhraseIssue(index, problem)
                continue
            }
            seen += scene.name to text
            accepted += AiPhrase(scene = scene.name, text = text, styleVersion = version)
        }
        if (accepted.isEmpty()) throw PhraseValidationException(PhraseProblem.NO_USABLE_PHRASES, issues)
        return PhraseBatch(accepted, issues, PromptScene.entries.filter { scene ->
            accepted.none { it.scene == scene.name }
        }.toSet())
    }

    // Expression guidance lives in the sent prompt, not a blanket personal-phrase blacklist.
    // These narrow checks are not a semantic safety guarantee.
    private val sensitiveContent = Regex("https?://|www\\.|\\bsk-[A-Za-z0-9_-]{16,}\\b", RegexOption.IGNORE_CASE)
    private val jsonFence = Regex("```(?:json)?[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n```", RegexOption.IGNORE_CASE)
    private fun normalizeText(text: String): String = buildString {
        var previousSpace = false
        for (character in text) {
            val space = character == '\r' || character == '\n' || character == '\t' ||
                Character.getType(character) in setOf(
                    Character.SPACE_SEPARATOR.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
                )
            if (space) {
                if (isNotEmpty() && !previousSpace) append(' ')
            } else append(character)
            previousSpace = space
        }
    }.trim(' ')
    private fun fail(problem: PhraseProblem): Nothing = throw PhraseValidationException(problem)
}
