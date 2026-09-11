package app.pausecn.ai

enum class PromptScene { ORDINARY, REPEATED, GOAL, CONTEXT, PURPOSE, FEEDBACK }

data class PromptChoice(val text: String, val scene: PromptScene, val id: String?,
    val contextJson: String = "", val contextExpiresAt: Long = Long.MAX_VALUE,
    val contextGeneratedAt: Long = 0, val contextRevision: Long = -1)

/** In-memory only. Preview never commits; process restart intentionally loses continuity. */
class PromptSelector {
    private data class Completed(val continued: Boolean, val elapsed: Long)
    private val completed = mutableMapOf<String, List<Completed>>()
    private val lastAdjustment = mutableMapOf<String, Long>()
    private val lastPhrase = mutableMapOf<String, String>()
    private var clockOffset: Long? = null

    @Synchronized
    fun choose(
        packageName: String,
        config: AiConfig,
        phrases: List<AiPhrase>,
        wallMs: Long,
        elapsedMs: Long,
    ): PromptChoice {
        val clockChanged = clockOffset?.let { kotlin.math.abs((wallMs - elapsedMs) - it) > 5_000 } == true
        val recent = if (clockChanged) emptyList() else completed[packageName].orEmpty()
            .filter { elapsedMs - it.elapsed in 0..WINDOW }
        val adjustment = if (clockChanged) null else lastAdjustment[packageName]
        val repeated = recent.takeLast(2).let { it.size == 2 && it.all(Completed::continued) } &&
            (adjustment == null || elapsedMs - adjustment >= WINDOW)
        val scene = if (repeated) PromptScene.REPEATED else PromptScene.ORDINARY
        if (config.manualPhrase.isNotBlank()) return PromptChoice(config.manualPhrase, scene, null)
        val candidates = if (config.enabled) phrases.filter {
            it.approved && it.styleVersion == config.styleVersion && it.scene == scene.name
        } else emptyList()
        val selected = candidates.firstOrNull { it.id != lastPhrase[packageName] } ?: candidates.firstOrNull()
        return PromptChoice(selected?.text ?: fallback(scene), scene, selected?.id)
    }

    @Synchronized
    fun shown(packageName: String, choice: PromptChoice, wallMs: Long, elapsedMs: Long) {
        updateClock(wallMs, elapsedMs)
        choice.id?.let { lastPhrase[packageName] = it }
        if (choice.scene == PromptScene.REPEATED) lastAdjustment[packageName] = elapsedMs
    }

    @Synchronized
    fun complete(packageName: String, continued: Boolean, wallMs: Long, elapsedMs: Long) {
        updateClock(wallMs, elapsedMs)
        completed[packageName] = (completed[packageName].orEmpty().filter {
            elapsedMs - it.elapsed in 0..WINDOW
        } + Completed(continued, elapsedMs)).takeLast(2)
    }

    @Synchronized fun clear() {
        completed.clear(); lastAdjustment.clear(); lastPhrase.clear(); clockOffset = null
    }

    private fun updateClock(wall: Long, elapsed: Long) {
        val offset = wall - elapsed
        if (clockOffset?.let { kotlin.math.abs(offset - it) > 5_000 } == true) clear()
        clockOffset = offset
    }

    companion object {
        const val WINDOW = 30 * 60_000L
        const val DEFAULT = "先缓慢呼吸，再决定是否继续。"
        fun fallback(scene: PromptScene) = if (scene == PromptScene.REPEATED)
            "这个停顿有帮助吗？可以晚点调整。" else DEFAULT

        fun validShortText(text: String): Boolean = text.isNotBlank() &&
            text.codePointCount(0, text.length) <= 36 && text.codePoints().noneMatch {
                Character.isISOControl(it) || Character.getType(it) in setOf(
                    Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
                )
            } && text.indices.all { i ->
                when {
                    text[i].isHighSurrogate() -> i + 1 < text.length && text[i + 1].isLowSurrogate()
                    text[i].isLowSurrogate() -> i > 0 && text[i - 1].isHighSurrogate()
                    else -> true
                }
            }
    }
}
