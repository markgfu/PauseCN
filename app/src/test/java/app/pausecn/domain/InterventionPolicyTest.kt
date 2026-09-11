package app.pausecn.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

class InterventionPolicyTest {
    private val policy = InterventionPolicy()

    @Test
    fun `active target inside schedule is intervened`() {
        assertEquals(InterventionDecision.Intervene, policy.decide(context()))
    }

    @Test
    fun `real intervention waits until onboarding preview is completed`() {
        assertEquals(
            InterventionDecision.Allow(AllowReason.SETUP_INCOMPLETE),
            policy.decide(context(setupComplete = false)),
        )
    }

    @Test
    fun `temporary pass wins over active schedule`() {
        val result = policy.decide(context(passValid = true))
        assertEquals(InterventionDecision.Allow(AllowReason.TEMPORARY_PASS), result)
    }

    @Test
    fun `global pause wins over active target`() {
        val result = policy.decide(context(globallyPaused = true))
        assertEquals(InterventionDecision.Allow(AllowReason.GLOBALLY_PAUSED), result)
    }

    @Test
    fun `protected packages are never intervened`() {
        val result = policy.decide(context(protectedPackage = true))
        assertEquals(InterventionDecision.Allow(AllowReason.PROTECTED_PACKAGE), result)
    }

    @Test
    fun `normal schedule excludes end minute`() {
        val schedule = ScheduleSpec(startMinutes = 9 * 60, endMinutes = 18 * 60)
        assertTrue(schedule.isActive(DayOfWeek.MONDAY, 17 * 60 + 59))
        assertEquals(false, schedule.isActive(DayOfWeek.MONDAY, 18 * 60))
        assertEquals(false, ScheduleSpec(enabled = false).isActive(DayOfWeek.MONDAY, 12 * 60))
        assertEquals(false, ScheduleSpec(activeDaysMask = 0).isActive(DayOfWeek.MONDAY, 12 * 60))

        val shanghai = ZoneId.of("Asia/Shanghai")
        val weekdaySchedule = schedule.copy(activeDaysMask = 0b001_1111)
        val mondayEvening = LocalDateTime.of(2026, 9, 7, 20, 0).atZone(shanghai)
        assertEquals(
            LocalDateTime.of(2026, 9, 8, 9, 0).atZone(shanghai).toInstant().toEpochMilli(),
            nextScheduleActivationEpochMs(
                weekdaySchedule,
                mondayEvening.toInstant().toEpochMilli(),
                shanghai,
            ),
        )
        val fridayEvening = LocalDateTime.of(2026, 9, 11, 20, 0).atZone(shanghai)
        assertEquals(
            LocalDateTime.of(2026, 9, 14, 9, 0).atZone(shanghai).toInstant().toEpochMilli(),
            nextScheduleActivationEpochMs(
                weekdaySchedule,
                fridayEvening.toInstant().toEpochMilli(),
                shanghai,
            ),
        )
        assertEquals(null, nextScheduleActivationEpochMs(schedule.copy(enabled = false), 1L, shanghai))
        assertEquals(null, nextScheduleActivationEpochMs(schedule.copy(activeDaysMask = 0), 1L, shanghai))
    }

    @Test
    fun `equal start and end means all day`() {
        val schedule = ScheduleSpec(startMinutes = 0, endMinutes = 0)
        assertTrue(schedule.isActive(DayOfWeek.SUNDAY, 23 * 60 + 59))

        val shanghai = ZoneId.of("Asia/Shanghai")
        val saturdayNoon = LocalDateTime.of(2026, 9, 12, 12, 0).atZone(shanghai)
        assertEquals(
            LocalDateTime.of(2026, 9, 13, 0, 0).atZone(shanghai).toInstant().toEpochMilli(),
            nextScheduleActivationEpochMs(
                schedule.copy(activeDaysMask = 1 shl (DayOfWeek.SUNDAY.value - 1)),
                saturdayNoon.toInstant().toEpochMilli(),
                shanghai,
            ),
        )
    }

    @Test
    fun `overnight schedule uses previous day after midnight`() {
        val mondayOnly = 1 shl (DayOfWeek.MONDAY.value - 1)
        val schedule = ScheduleSpec(
            startMinutes = 22 * 60,
            endMinutes = 7 * 60,
            activeDaysMask = mondayOnly,
        )
        assertTrue(schedule.isActive(DayOfWeek.MONDAY, 23 * 60))
        assertTrue(schedule.isActive(DayOfWeek.TUESDAY, 6 * 60 + 59))
        assertEquals(false, schedule.isActive(DayOfWeek.TUESDAY, 7 * 60))

        val shanghai = ZoneId.of("Asia/Shanghai")
        val tuesdayMorning = LocalDateTime.of(2026, 9, 8, 7, 0).atZone(shanghai)
        assertEquals(
            LocalDateTime.of(2026, 9, 14, 22, 0).atZone(shanghai).toInstant().toEpochMilli(),
            nextScheduleActivationEpochMs(
                schedule,
                tuesdayMorning.toInstant().toEpochMilli(),
                shanghai,
            ),
        )

        val newYork = ZoneId.of("America/New_York")
        val springForward = ScheduleSpec(
            startMinutes = 2 * 60 + 30,
            endMinutes = 4 * 60,
            activeDaysMask = 1 shl (DayOfWeek.SUNDAY.value - 1),
        )
        val beforeGap = LocalDateTime.of(2026, 3, 8, 1, 0).atZone(newYork)
        assertEquals(
            LocalDateTime.of(2026, 3, 8, 3, 0).atZone(newYork).toInstant().toEpochMilli(),
            nextScheduleActivationEpochMs(
                springForward,
                beforeGap.toInstant().toEpochMilli(),
                newYork,
            ),
        )
        assertEquals(
            LocalDateTime.of(2026, 3, 8, 3, 0).atZone(newYork).toInstant().toEpochMilli(),
            nextScheduleActivationEpochMs(
                springForward.copy(endMinutes = 3 * 60 + 15),
                beforeGap.toInstant().toEpochMilli(),
                newYork,
            ),
        )

        val repeatedHour = ScheduleSpec(
            startMinutes = 1 * 60 + 30,
            endMinutes = 1 * 60 + 45,
            activeDaysMask = 1 shl (DayOfWeek.SUNDAY.value - 1),
        )
        val firstOccurrenceAfterWindow = java.time.ZonedDateTime.ofLocal(
            LocalDateTime.of(2026, 11, 1, 1, 50),
            newYork,
            java.time.ZoneOffset.ofHours(-4),
        )
        val secondActivation = java.time.ZonedDateTime.ofLocal(
            LocalDateTime.of(2026, 11, 1, 1, 30),
            newYork,
            java.time.ZoneOffset.ofHours(-5),
        )
        assertEquals(
            secondActivation.toInstant().toEpochMilli(),
            nextScheduleActivationEpochMs(
                repeatedHour,
                firstOccurrenceAfterWindow.toInstant().toEpochMilli(),
                newYork,
            ),
        )
    }

    @Test
    fun `duplicate window event is allowed without a second overlay`() {
        val result = policy.decide(context(duplicateEvent = true))
        assertEquals(InterventionDecision.Allow(AllowReason.DUPLICATE_EVENT), result)
    }

    @Test
    fun `exit cooldown prevents return animation from reopening overlay`() {
        val result = policy.decide(context(exitCooldownActive = true))
        assertEquals(InterventionDecision.Allow(AllowReason.EXIT_COOLDOWN), result)
    }

    @Test
    fun `safety cooldown prevents transition events from reopening overlay`() {
        val result = policy.decide(context(safetyCooldownActive = true))
        assertEquals(InterventionDecision.Allow(AllowReason.SAFETY_COOLDOWN), result)
    }

    private fun context(
        setupComplete: Boolean = true,
        globallyPaused: Boolean = false,
        passValid: Boolean = false,
        exitCooldownActive: Boolean = false,
        safetyCooldownActive: Boolean = false,
        duplicateEvent: Boolean = false,
        protectedPackage: Boolean = false,
    ) = InterventionContext(
        targetEnabled = true,
        setupComplete = setupComplete,
        schedule = ScheduleSpec(),
        dayOfWeek = DayOfWeek.MONDAY,
        minuteOfDay = 12 * 60,
        globallyPaused = globallyPaused,
        passValid = passValid,
        exitCooldownActive = exitCooldownActive,
        safetyCooldownActive = safetyCooldownActive,
        duplicateEvent = duplicateEvent,
        protectedPackage = protectedPackage,
    )
}
