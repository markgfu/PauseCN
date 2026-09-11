package app.pausecn.domain

import java.time.DayOfWeek

enum class InterventionAvailability {
    SETUP_REQUIRED,
    SERVICE_STARTING,
    SERVICE_STALE,
    PAUSED,
    SCHEDULE_DISABLED,
    OUTSIDE_SCHEDULE,
    ACTIVE,
}

fun evaluateInterventionAvailability(
    disclosureAccepted: Boolean,
    ageEligibilityConfirmed: Boolean,
    onboardingPreviewCompleted: Boolean,
    accessibilityEnabled: Boolean,
    targetCount: Int,
    serviceHealth: ServiceHealth,
    globallyPaused: Boolean,
    schedule: ScheduleSpec,
    dayOfWeek: DayOfWeek,
    minuteOfDay: Int,
): InterventionAvailability = when {
    !disclosureAccepted ||
        !ageEligibilityConfirmed ||
        !onboardingPreviewCompleted ||
        !accessibilityEnabled ||
        targetCount <= 0 -> InterventionAvailability.SETUP_REQUIRED
    serviceHealth == ServiceHealth.STALE -> InterventionAvailability.SERVICE_STALE
    serviceHealth != ServiceHealth.HEALTHY -> InterventionAvailability.SERVICE_STARTING
    globallyPaused -> InterventionAvailability.PAUSED
    !schedule.enabled || schedule.activeDaysMask == 0 -> InterventionAvailability.SCHEDULE_DISABLED
    !schedule.isActive(dayOfWeek, minuteOfDay) -> InterventionAvailability.OUTSIDE_SCHEDULE
    else -> InterventionAvailability.ACTIVE
}
