package app.pausecn.domain

data class InterventionPurpose(
    val label: String,
    val requiresConfirmation: Boolean = false,
)

val INTERVENTION_PURPOSES = listOf(
    InterventionPurpose("回消息"),
    InterventionPurpose("发内容"),
    InterventionPurpose("搜资料"),
    InterventionPurpose("休息一下"),
    InterventionPurpose("随便看看", requiresConfirmation = true),
)

fun interventionPurpose(label: String): InterventionPurpose? =
    INTERVENTION_PURPOSES.firstOrNull { it.label == label }

/** User statements, not verified motives. Shared by preview, overlay and persistence. */
object ContinueReason {
    const val MAX_CODE_POINTS = 80

    fun isValid(text: String): Boolean = text.isNotBlank() &&
        text.codePointCount(0, text.length) <= MAX_CODE_POINTS &&
        text.codePoints().noneMatch {
            Character.isISOControl(it) || Character.getType(it) in setOf(
                Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(),
                Character.PARAGRAPH_SEPARATOR.toInt(), Character.SURROGATE.toInt(),
            )
        }

    fun compose(selected: String?, input: String): String? {
        if (selected != null && interventionPurpose(selected) == null) return null
        val note = input.trim(' ', '\u3000')
        val reason = when {
            note.isEmpty() -> selected.orEmpty()
            selected == null || note == selected -> note
            else -> "$selected · $note"
        }
        return reason.takeIf(::isValid)
    }

    fun isCasual(reason: String?): Boolean = reason == "随便看看" || reason?.startsWith("随便看看 · ") == true
}
