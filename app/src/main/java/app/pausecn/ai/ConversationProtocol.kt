package app.pausecn.ai

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class MemoryCandidate(val sourceId: String, val text: String, val kind: String)
data class ConversationReply(val reply: String, val candidates: List<MemoryCandidate>)

object ConversationProtocol {
    const val PROMPT_ASSET = "prompts/conversation.md"
    fun parse(content: String, userSourceIds: Set<String>): ConversationReply {
        require(content.length <= 16_000) { "交流返回过大" }
        val json = content.trim().let { fence.matchEntire(it)?.groupValues?.get(1) ?: it }
        val tokenizer = JSONTokener(json)
        val root = tokenizer.nextValue() as? JSONObject ?: error("交流返回不是JSON对象")
        require(tokenizer.nextClean() == '\u0000') { "交流返回含多余内容" }
        val reply = root.opt("reply") as? String ?: error("交流返回缺少reply")
        require(ConversationPolicy.textValid(reply)) { "交流回复为空、过长或含异常字符" }
        val rows = root.opt("memories") as? JSONArray ?: JSONArray()
        require(rows.length() <= 3) { "候选记忆过多" }
        val accepted = mutableListOf<MemoryCandidate>()
        val seen = mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.opt(i) as? JSONObject ?: continue
            val source = row.opt("source_id") as? String ?: continue
            val text = row.opt("text") as? String ?: continue
            val kind = row.opt("kind") as? String ?: continue
            if (source !in userSourceIds || kind !in ConversationPolicy.KINDS ||
                !ConversationPolicy.textValid(text, ConversationPolicy.MEMORY_LIMIT) || !seen.add(source)) continue
            accepted += MemoryCandidate(source, text.trim(), kind)
        }
        return ConversationReply(reply.trim(), accepted)
    }
    private val fence = Regex("```(?:json)?[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n```", RegexOption.IGNORE_CASE)
}
