package app.pausecn.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageEventsQuery
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.UserManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class UsageReadStatus { AVAILABLE, EMPTY, NOT_AUTHORIZED, DEVICE_LOCKED, FAILED, TOO_MANY_EVENTS, NO_TARGETS }

data class UsageRead(val status: UsageReadStatus, val window: UsageSpan, val signals: List<UsageSignal> = emptyList())

interface UsageSource {
    fun hasPermission(): Boolean
    suspend fun read(window: UsageSpan, packages: Set<String>): UsageRead
}

/** System query only. The repository must also verify its independent local opt-in and revision
 * before/after calling this source; system permission is never permission to send data to AI.
 */
class AndroidUsageSource(context: Context) : UsageSource {
    private val context = context.applicationContext

    override fun hasPermission(): Boolean = try {
        context.getSystemService(AppOpsManager::class.java)?.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    override suspend fun read(window: UsageSpan, packages: Set<String>): UsageRead = withContext(Dispatchers.IO) {
        require(window.duration in 1..MAX_QUERY_MS) { "Usage query must fit the bounded 48-hour window" }
        if (packages.isEmpty()) return@withContext UsageRead(UsageReadStatus.NO_TARGETS, window)
        require(packages.none { it.isBlank() })
        if (!hasPermission()) return@withContext UsageRead(UsageReadStatus.NOT_AUTHORIZED, window)
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true)
            return@withContext UsageRead(UsageReadStatus.DEVICE_LOCKED, window)
        try {
            val manager = context.getSystemService(UsageStatsManager::class.java)
                ?: return@withContext UsageRead(UsageReadStatus.FAILED, window)
            val selected = mutableListOf<UsageSignal>()
            suspend fun consume(events: UsageEvents?, globalOnly: Boolean?): Boolean {
                if (events == null) return false
                val event = UsageEvents.Event()
                var seen = 0
                while (events.hasNextEvent()) {
                    currentCoroutineContext().ensureActive()
                    if (++seen > MAX_SCANNED_EVENTS) throw UsageEventLimit()
                    if (!events.getNextEvent(event)) return false
                    val kind = kind(event.eventType) ?: continue
                    val global = event.eventType in GLOBAL_TYPES
                    if (globalOnly != null && global != globalOnly) continue
                    if (!global && event.packageName !in packages) continue
                    if (event.timeStamp !in window.start until window.end) continue
                    if (selected.size >= MAX_SELECTED_EVENTS) throw UsageEventLimit()
                    selected.add(UsageSignal(event.timeStamp, kind,
                        if (global) "" else event.packageName.orEmpty(),
                        if (global) "" else event.className.orEmpty().take(512)))
                }
                return true
            }
            val complete = if (Build.VERSION.SDK_INT >= 35) {
                val targets = manager.queryEvents(UsageEventsQuery.Builder(window.start, window.end)
                    .setPackageNames(*packages.toTypedArray()).setEventTypes(*ACTIVITY_TYPES).build())
                val targetComplete = consume(targets, false)
                val boundaries = manager.queryEvents(UsageEventsQuery.Builder(window.start, window.end)
                    .setEventTypes(*GLOBAL_TYPES).build())
                targetComplete && consume(boundaries, true)
            } else {
                // Older APIs cannot filter in the system service. Discard non-target events
                // immediately; never copy extras, UI content, or other app identities.
                consume(manager.queryEvents(window.start, window.end), null)
            }
            if (!hasPermission()) return@withContext UsageRead(UsageReadStatus.NOT_AUTHORIZED, window)
            if (!complete) return@withContext UsageRead(UsageReadStatus.FAILED, window)
            // System boundaries win equal-time ties across the two sources; Activity ties retain
            // their original order. Contradictory signals are conservatively flagged by aggregation.
            val ordered = selected.sortedWith(compareBy<UsageSignal> { it.at }.thenBy { if (it.packageName.isEmpty()) 0 else 1 })
            UsageRead(if (ordered.isEmpty()) UsageReadStatus.EMPTY else UsageReadStatus.AVAILABLE, window, ordered)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: UsageEventLimit) { UsageRead(UsageReadStatus.TOO_MANY_EVENTS, window) }
        catch (_: SecurityException) { UsageRead(UsageReadStatus.NOT_AUTHORIZED, window) }
        catch (_: Exception) { UsageRead(UsageReadStatus.FAILED, window) }
    }

    private class UsageEventLimit : Exception()

    companion object {
        const val MAX_QUERY_MS = 48L * 60 * 60_000
        private const val MAX_SCANNED_EVENTS = 200_000
        private const val MAX_SELECTED_EVENTS = 50_000
        private val ACTIVITY_TYPES = intArrayOf(UsageEvents.Event.ACTIVITY_RESUMED,
            UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED)
        private val GLOBAL_TYPES = intArrayOf(UsageEvents.Event.SCREEN_NON_INTERACTIVE,
            UsageEvents.Event.SCREEN_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN,
            UsageEvents.Event.KEYGUARD_HIDDEN, UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP)
        private fun kind(type: Int): UsageEventKind? = when (type) {
            UsageEvents.Event.ACTIVITY_RESUMED -> UsageEventKind.RESUMED
            UsageEvents.Event.ACTIVITY_PAUSED -> UsageEventKind.PAUSED
            UsageEvents.Event.ACTIVITY_STOPPED -> UsageEventKind.STOPPED
            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageEventKind.SCREEN_OFF
            UsageEvents.Event.SCREEN_INTERACTIVE -> UsageEventKind.SCREEN_ON
            UsageEvents.Event.KEYGUARD_SHOWN -> UsageEventKind.LOCKED
            UsageEvents.Event.KEYGUARD_HIDDEN -> UsageEventKind.UNLOCKED
            UsageEvents.Event.DEVICE_SHUTDOWN -> UsageEventKind.SHUTDOWN
            UsageEvents.Event.DEVICE_STARTUP -> UsageEventKind.STARTUP
            else -> null
        }
    }
}
