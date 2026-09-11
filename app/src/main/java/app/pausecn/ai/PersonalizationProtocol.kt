package app.pausecn.ai

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest

object PersonalizationProtocol {
    const val PROMPT_ASSET = "prompts/personalized_phrases.md"
    fun parse(content: String, version: Long, sourceIds: Set<String>): List<AiPhrase> {
        if (content.length > PhraseProtocol.MAX_RESPONSE_CHARS) throw PhraseValidationException(PhraseProblem.JSON_INVALID)
        val cleaned = content.trim().let { fenced.matchEntire(it)?.groupValues?.get(1) ?: it }
        val tokenizer = JSONTokener(cleaned)
        val root = tokenizer.nextValue() as? JSONObject ?: throw PhraseValidationException(PhraseProblem.JSON_INVALID)
        if (tokenizer.nextClean() != '\u0000') throw PhraseValidationException(PhraseProblem.JSON_INVALID)
        val references = root.opt("source_ids") as? JSONArray ?: throw PhraseValidationException(PhraseProblem.ROOT_FIELDS)
        if (references.length() > sourceIds.size || (0 until references.length()).any {
            references.opt(it) !is String || references.getString(it) !in sourceIds
        }) throw PhraseValidationException(PhraseProblem.ROW_FIELDS)
        val phrases = PhraseProtocol.parseBatch(cleaned, version).phrases.filter {
            it.scene == PromptScene.ORDINARY.name || it.scene == PromptScene.REPEATED.name
        }.groupBy { it.scene }.values.flatMap { it.take(2) }
        if (phrases.isEmpty()) throw PhraseValidationException(PhraseProblem.NO_USABLE_PHRASES)
        return phrases
    }
    fun fingerprint(vararg values: String): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private val fenced = Regex("```(?:json)?[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n```", RegexOption.IGNORE_CASE)
}
