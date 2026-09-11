package app.pausecn.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek

class InterventionAvailabilityTest {
    @Test
    fun `missing setup never appears active`() {
        assertEquals(InterventionAvailability.SETUP_REQUIRED, availability(accessibilityEnabled = false))
        assertEquals(InterventionAvailability.SETUP_REQUIRED, availability(targetCount = 0))
        assertEquals(InterventionAvailability.SETUP_REQUIRED, availability(onboardingPreviewCompleted = false))
    }

    @Test
    fun `service health is shown before other runtime states`() {
        assertEquals(
            InterventionAvailability.SERVICE_STALE,
            availability(serviceHealth = ServiceHealth.STALE, globallyPaused = true),
        )
        assertEquals(
            InterventionAvailability.SERVICE_STARTING,
            availability(serviceHealth = ServiceHealth.STARTING, globallyPaused = true),
        )
    }

    @Test
    fun `intentional pause and schedule rest are distinguished`() {
        assertEquals(InterventionAvailability.PAUSED, availability(globallyPaused = true))
        assertEquals(
            InterventionAvailability.SCHEDULE_DISABLED,
            availability(schedule = ScheduleSpec(enabled = false)),
        )
        assertEquals(
            InterventionAvailability.SCHEDULE_DISABLED,
            availability(schedule = ScheduleSpec(activeDaysMask = 0)),
        )
        assertEquals(
            InterventionAvailability.OUTSIDE_SCHEDULE,
            availability(
                schedule = ScheduleSpec(startMinutes = 9 * 60, endMinutes = 18 * 60),
                minuteOfDay = 20 * 60,
            ),
        )
    }

    @Test
    fun `healthy configured service inside schedule is active`() {
        assertEquals(InterventionAvailability.ACTIVE, availability())
    }

    private fun availability(
        accessibilityEnabled: Boolean = true,
        targetCount: Int = 1,
        onboardingPreviewCompleted: Boolean = true,
        serviceHealth: ServiceHealth = ServiceHealth.HEALTHY,
        globallyPaused: Boolean = false,
        schedule: ScheduleSpec = ScheduleSpec(),
        minuteOfDay: Int = 12 * 60,
    ) = evaluateInterventionAvailability(
        disclosureAccepted = true,
        ageEligibilityConfirmed = true,
        onboardingPreviewCompleted = onboardingPreviewCompleted,
        accessibilityEnabled = accessibilityEnabled,
        targetCount = targetCount,
        serviceHealth = serviceHealth,
        globallyPaused = globallyPaused,
        schedule = schedule,
        dayOfWeek = DayOfWeek.MONDAY,
        minuteOfDay = minuteOfDay,
    )
}
