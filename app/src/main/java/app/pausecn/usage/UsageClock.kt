package app.pausecn.usage

import android.content.Context
import android.provider.Settings
import java.util.UUID
import kotlin.math.abs

data class UsageTime(val wall: Long, val elapsed: Long, val bootId: String, val zoneId: String)
enum class UsageClockChange { INITIAL, STABLE, REBOOT, WALL_CHANGED, ZONE_CHANGED }

object UsageClock {
    const val TOLERANCE_MS = 60_000L
    fun change(previous: UsageTime?, current: UsageTime): UsageClockChange = when {
        previous == null || previous.elapsed < 0 || previous.bootId.isEmpty() -> UsageClockChange.INITIAL
        current.bootId != previous.bootId || current.elapsed < previous.elapsed -> UsageClockChange.REBOOT
        current.wall < previous.wall ||
            abs((current.wall - previous.wall).toDouble() - (current.elapsed - previous.elapsed).toDouble()) > TOLERANCE_MS -> UsageClockChange.WALL_CHANGED
        current.zoneId != previous.zoneId -> UsageClockChange.ZONE_CHANGED
        else -> UsageClockChange.STABLE
    }
    private val processFallback = "process:${UUID.randomUUID()}"
    fun bootId(context: Context): String = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1).let { if (it >= 0) "boot:$it" else processFallback }
    internal fun processBootId(): String = processFallback
}

internal fun UsageConfig.anchor(): UsageTime? = if (lastElapsed < 0) null else UsageTime(lastChecked, lastElapsed, lastBootId, lastZoneId)
internal fun UsageConfig.at(time: UsageTime): UsageConfig = copy(lastChecked = time.wall,
    lastElapsed = time.elapsed, lastBootId = time.bootId, lastZoneId = time.zoneId)
