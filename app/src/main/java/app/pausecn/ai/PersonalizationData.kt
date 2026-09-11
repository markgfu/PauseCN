package app.pausecn.ai

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Entity(tableName = "personalization_config")
data class PersonalizationConfig(
    @PrimaryKey val id: Int = 1,
    val enabled: Boolean = false,
    val useProfile: Boolean = false,
    val useReasons: Boolean = false,
    val epoch: Long = 0,
)

@Entity(tableName = "personalized_phrases")
data class PersonalizedPhrase(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val packageName: String,
    val targetCreatedAt: Long,
    val scene: String,
    val text: String,
    val kind: String,
    val instanceId: String,
    val privacyEpoch: Long,
    val personalizationEpoch: Long,
    val styleVersion: Long,
    val profileRevision: String,
    val fingerprint: String,
    val createdAt: Long,
    val expiresAt: Long,
    val clockOffset: Long,
    val consumed: Boolean = false,
    @ColumnInfo(defaultValue = "''") val contextJson: String = "",
)

/** Prevents an in-memory overlay cache from outliving a source mutation or failed reload. */
class PersonalizationGuard {
    private val mutex = Mutex()
    private val active = AtomicInteger()
    private val mutableRevision = MutableStateFlow(0L)
    val revisions = mutableRevision.asStateFlow()
    val revision: Long get() = mutableRevision.value
    val changing: Boolean get() = active.get() != 0

    /** Short local reads use the same guard → Room lock order as mutations. Never hold across network work. */
    suspend fun <T> readStable(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun <T> mutate(block: suspend () -> T): T = mutex.withLock {
        active.incrementAndGet()
        mutableRevision.value += 1
        try { block() } finally {
            active.decrementAndGet()
            mutableRevision.value += 1
        }
    }
}

/** The queue holds intent, not private snapshots; dispatch re-reads authorized local data. */
data class PreparationIntent(val packageName: String, val stable: Boolean, val fingerprint: String,
    val expiresAt: Long = Long.MAX_VALUE)

class PreparationQueue(private val capacity: Int = 3) {
    private val pending = linkedMapOf<String, PreparationIntent>()
    init { require(capacity > 0) }
    @Synchronized fun offer(intent: PreparationIntent): String? {
        pending[intent.packageName] = intent
        if (pending.size <= capacity) return null
        val first = pending.keys.first()
        pending.remove(first)
        return first
    }
    @Synchronized fun poll(): PreparationIntent? {
        val first = pending.keys.firstOrNull() ?: return null
        return pending.remove(first)
    }
    @Synchronized fun clear() = pending.clear()
    @Synchronized fun size(): Int = pending.size
}

object PersonalizedCachePolicy {
    const val RECENT_TTL_MS = 30 * 60_000L
    const val STABLE = "STABLE"
    const val RECENT = "RECENT"

    /** Stable backup availability must never decide whether a completed choice gets recent context. */
    internal fun hasRecentForNextChoice(rows: List<PersonalizedPhrase>, packageName: String,
        scene: String, fingerprint: String, usable: (PersonalizedPhrase) -> Boolean): Boolean = rows.any {
        it.kind == RECENT && it.packageName == packageName && it.scene == scene &&
            it.fingerprint == fingerprint && usable(it)
    }

    fun valid(row: PersonalizedPhrase, config: AiConfig, personal: PersonalizationConfig,
        profileRevision: String, targetCreatedAt: Long?, now: Long, elapsed: Long): Boolean =
        config.enabled && personal.enabled && !row.consumed && targetCreatedAt == row.targetCreatedAt &&
            row.instanceId == config.instanceId && row.privacyEpoch == config.privacyEpoch &&
            row.personalizationEpoch == personal.epoch && row.styleVersion == config.styleVersion &&
            row.profileRevision == profileRevision && now >= row.createdAt && now < row.expiresAt &&
            (row.kind == STABLE || (row.kind == RECENT &&
                kotlin.math.abs((now - elapsed) - row.clockOffset) <= 5_000 &&
                now - row.createdAt < RECENT_TTL_MS))
}
