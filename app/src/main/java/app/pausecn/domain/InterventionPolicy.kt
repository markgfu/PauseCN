package app.pausecn.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class ScheduleSpec(
    val enabled: Boolean = true,
    val startMinutes: Int = 0,
    val endMinutes: Int = 0,
    val activeDaysMask: Int = ALL_DAYS,
) {
    init {
        require(startMinutes in 0..1439)
        require(endMinutes in 0..1439)
        require(activeDaysMask in 0..ALL_DAYS)
    }

    fun isActive(dayOfWeek: DayOfWeek, minuteOfDay: Int): Boolean {
        if (!enabled || minuteOfDay !in 0..1439) return false
        if (startMinutes == endMinutes) return isDayEnabled(dayOfWeek)
        if (startMinutes < endMinutes) {
            return isDayEnabled(dayOfWeek) && minuteOfDay in startMinutes until endMinutes
        }

        return if (minuteOfDay >= startMinutes) {
            isDayEnabled(dayOfWeek)
        } else {
            isDayEnabled(dayOfWeek.minus(1)) && minuteOfDay < endMinutes
        }
    }

    private fun isDayEnabled(day: DayOfWeek): Boolean {
        val bit = 1 shl (day.value - 1)
        return activeDaysMask and bit != 0
    }

    companion object {
        const val ALL_DAYS = 0b111_1111
    }
}

data class InterventionContext(
    val targetEnabled: Boolean,
    val setupComplete: Boolean,
    val schedule: ScheduleSpec,
    val dayOfWeek: DayOfWeek,
    val minuteOfDay: Int,
    val globallyPaused: Boolean,
    val passValid: Boolean,
    val exitCooldownActive: Boolean,
    val safetyCooldownActive: Boolean,
    val duplicateEvent: Boolean,
    val protectedPackage: Boolean,
)

sealed interface InterventionDecision {
    data object Intervene : InterventionDecision
    data class Allow(val reason: AllowReason) : InterventionDecision
}

enum class AllowReason {
    NOT_A_TARGET,
    SETUP_INCOMPLETE,
    OUTSIDE_SCHEDULE,
    GLOBALLY_PAUSED,
    TEMPORARY_PASS,
    EXIT_COOLDOWN,
    SAFETY_COOLDOWN,
    DUPLICATE_EVENT,
    PROTECTED_PACKAGE,
}

class InterventionPolicy {
    fun decide(context: InterventionContext): InterventionDecision = when {
        !context.targetEnabled -> InterventionDecision.Allow(AllowReason.NOT_A_TARGET)
        !context.setupComplete -> InterventionDecision.Allow(AllowReason.SETUP_INCOMPLETE)
        context.protectedPackage -> InterventionDecision.Allow(AllowReason.PROTECTED_PACKAGE)
        context.globallyPaused -> InterventionDecision.Allow(AllowReason.GLOBALLY_PAUSED)
        context.passValid -> InterventionDecision.Allow(AllowReason.TEMPORARY_PASS)
        context.exitCooldownActive -> InterventionDecision.Allow(AllowReason.EXIT_COOLDOWN)
        context.safetyCooldownActive -> InterventionDecision.Allow(AllowReason.SAFETY_COOLDOWN)
        context.duplicateEvent -> InterventionDecision.Allow(AllowReason.DUPLICATE_EVENT)
        !context.schedule.isActive(context.dayOfWeek, context.minuteOfDay) ->
            InterventionDecision.Allow(AllowReason.OUTSIDE_SCHEDULE)
        else -> InterventionDecision.Intervene
    }
}

/**
 * Returns the next real minute at which the schedule changes from inactive to active. Walking the
 * real instant timeline deliberately mirrors [ScheduleSpec.isActive]: a spring-forward gap may
 * make the first active local minute later than the configured start, while a repeated fall-back
 * hour can contain a second activation that a single local date-time candidate would miss.
 */
fun nextScheduleActivationEpochMs(
    schedule: ScheduleSpec,
    nowEpochMs: Long,
    zoneId: ZoneId,
): Long? {
    if (!schedule.enabled || schedule.activeDaysMask == 0) return null

    val now = Instant.ofEpochMilli(nowEpochMs)
    var cursor = now.truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES)
    val searchEnd = now.plus(NEXT_ACTIVATION_SEARCH_DAYS, ChronoUnit.DAYS)
    var previouslyActive = schedule.isActiveAt(cursor.minus(1, ChronoUnit.MINUTES), zoneId)

    while (!cursor.isAfter(searchEnd)) {
        val active = schedule.isActiveAt(cursor, zoneId)
        if (active && !previouslyActive) return cursor.toEpochMilli()
        previouslyActive = active
        cursor = cursor.plus(1, ChronoUnit.MINUTES)
    }
    return null
}

private fun ScheduleSpec.isActiveAt(instant: Instant, zoneId: ZoneId): Boolean {
    val local = instant.atZone(zoneId)
    return isActive(local.dayOfWeek, local.hour * 60 + local.minute)
}

private const val NEXT_ACTIVATION_SEARCH_DAYS = 8L
