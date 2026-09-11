package app.pausecn.data

import app.pausecn.domain.ContinueReason
import app.pausecn.domain.INTERVENTION_PURPOSES
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Derived from retained, completed choices. No independent copy can outlive/restore its source. */
data class ReasonMemory(val packageName: String, val appLabel: String, val text: String, val uses: Int, val lastUsedAt: Long)

class ReasonMemoryCache {
    private val mutable = MutableStateFlow<List<ReasonMemory>>(emptyList())
    val state = mutable.asStateFlow()
    fun replace(rows: List<ReasonMemory>) { mutable.value = rows.filter { ContinueReason.isValid(it.text) } }
    fun clear() { mutable.value = emptyList() }
    fun choices(packageName: String, now: Long = System.currentTimeMillis(), retentionDays: Int = 30): List<String> =
        rankedReasonChoices(state.value.filter {
            it.packageName == packageName && it.lastUsedAt in (now - minOf(retentionDays, 30) * 86_400_000L)..now
        })
}

fun rankedReasonChoices(rows: List<ReasonMemory>): List<String> {
    val defaults = INTERVENTION_PURPOSES.map { it.label }
    val valid = rows.filter { ContinueReason.isValid(it.text) }
    val byText = valid.associateBy { it.text }
    val custom = valid.filter { it.text !in defaults }
        .sortedWith(compareByDescending<ReasonMemory> { it.uses }.thenByDescending { it.lastUsedAt }.thenBy { it.text })
        .take(3).map { it.text }
    return (defaults + custom).sortedWith(compareByDescending<String> { byText[it]?.uses ?: 0 }
        .thenByDescending { byText[it]?.lastUsedAt ?: 0 })
}
