package app.pausecn.usage

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.withTransaction
import app.pausecn.data.PauseDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.UUID

@Entity(tableName = "pause_displays")
data class PauseDisplay(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val packageName: String,
    val startedAt: Long,
    val startedElapsed: Long,
    val bootId: String,
    val zoneId: String,
    val endedAt: Long? = null,
    val endedElapsed: Long? = null,
    val status: String = "OPEN",
) {
    @get:androidx.room.Ignore
    val durationMs: Long? get() = if (status == "CONFIRMED" && endedElapsed != null)
        (endedElapsed - startedElapsed).takeIf { it >= 0 } else null

    fun closed(time: UsageTime): PauseDisplay {
        val change = UsageClock.change(UsageTime(startedAt, startedElapsed, bootId, zoneId), time)
        return copy(endedAt = time.wall, endedElapsed = time.elapsed,
            status = if (change == UsageClockChange.STABLE) "CONFIRMED" else "CLOCK_CHANGED")
    }
}

/** Attach/detach notifications only enqueue I/O. Removing a window never awaits its recording.
 * A pending insert carries an in-process deletion generation so clear/reset cannot resurrect it.
 */
class PauseDisplayRecorder(private val database: PauseDatabase, private val time: () -> UsageTime) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    class Handle internal constructor(internal val display: PauseDisplay, internal val inserted: Deferred<Boolean>)

    fun attached(packageName: String): Handle {
        val now = time()
        val display = PauseDisplay(packageName = packageName, startedAt = now.wall,
            startedElapsed = now.elapsed, bootId = now.bootId, zoneId = now.zoneId)
        val generation = database.displayGeneration.get()
        val inserted = scope.async {
            try {
                database.withTransaction {
                    if (database.displayGeneration.get() != generation) return@withTransaction false
                    if (database.targetRuleDao().getAllForExport().none { it.packageName == packageName && it.enabled })
                        return@withTransaction false
                    database.usageDao().insertDisplay(display)
                    database.usageDao().capDisplays(10_000)
                    true
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        }
        return Handle(display, inserted)
    }

    fun detached(handle: Handle) {
        val closed = handle.display.closed(time())
        scope.launch {
            try {
                if (handle.inserted.await()) database.usageDao().finishDisplay(closed.id,
                    requireNotNull(closed.endedAt), requireNotNull(closed.endedElapsed), closed.status)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Missing end remains unknown; it cannot hold the overlay open. */ }
        }
    }
}
