package app.pausecn.data

import app.pausecn.domain.ScheduleSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuleChangesTest {
    @Test
    fun `published choices are exact and unsupported values are rejected without clamping`() {
        assertEquals(listOf(3, 6, 10), RulePatch.WAIT_CHOICES)
        assertEquals(listOf(1, 5, 15), RulePatch.PASS_CHOICES)
        RulePatch.WAIT_CHOICES.forEach { RulePatch(waitSeconds = it).validate() }
        RulePatch.PASS_CHOICES.forEach { RulePatch(passMinutes = it).validate() }

        val unsupportedWait = RulePatch(waitSeconds = 4)
        val unsupportedPass = RulePatch(passMinutes = 2)
        assertEquals(4, unsupportedWait.target(RuleValues()).waitSeconds)
        assertEquals(2, unsupportedPass.target(RuleValues()).passMinutes)
        assertThrows(IllegalArgumentException::class.java) {
            RuleChangePlanner.apply(state(), state(), unsupportedWait, "apply")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuleChangePlanner.apply(state(), state(), unsupportedPass, "apply")
        }
    }

    @Test
    fun `complete patch and undo codec round trip all fields and revisions`() {
        val before = state()
        val schedule = ScheduleSpec(enabled = false, startMinutes = 480, endMinutes = 1_020, activeDaysMask = 0b001_1111)
        val patch = RulePatch(waitSeconds = 10, passMinutes = 15, schedule = schedule)

        val undo = RuleChangePlanner.apply(before, before, patch, "batch")

        assertEquals(patch, RulePatch.between(before.values, undo.after.values))
        assertEquals(RuleValues(10, 15, schedule), undo.after.values)
        assertEquals("batch", undo.after.revision)
        assertEquals("batch", undo.after.waitRevision)
        assertEquals("batch", undo.after.passRevision)
        assertEquals("batch", undo.after.scheduleRevision)
        assertEquals(undo, RuleUndoCodec.decode(RuleUndoCodec.encode(undo)))
    }

    @Test
    fun `apply rejects later changes even when values are changed back`() {
        val expected = state()
        val changed = RuleChangePlanner.changed(expected, expected.values.copy(waitSeconds = 10), "later")
        assertThrows(RuleConflictException::class.java) {
            RuleChangePlanner.apply(changed, expected, RulePatch(passMinutes = 15), "apply")
        }

        val changedBack = RuleChangePlanner.changed(changed, expected.values, "changed-back")
        assertEquals(expected.values, changedBack.values)
        assertNotEquals(expected, changedBack)
        assertThrows(RuleConflictException::class.java) {
            RuleChangePlanner.apply(changedBack, expected, RulePatch(passMinutes = 15), "apply")
        }
    }

    @Test
    fun `undo restores only fields changed by the batch and preserves unrelated edits`() {
        val before = state()
        val undo = RuleChangePlanner.apply(before, before, RulePatch(waitSeconds = 10), "batch")
        val unrelated = RuleChangePlanner.changed(
            undo.after,
            undo.after.values.copy(passMinutes = 15),
            "unrelated-pass",
        )

        val restored = RuleChangePlanner.undo(unrelated, undo, "undo")

        assertEquals(6, restored.values.waitSeconds)
        assertEquals(15, restored.values.passMinutes)
        assertEquals("undo", restored.waitRevision)
        assertEquals("unrelated-pass", restored.passRevision)
        assertEquals(unrelated.scheduleRevision, restored.scheduleRevision)
    }

    @Test
    fun `undo rejects a related field changed away and back`() {
        val before = state()
        val undo = RuleChangePlanner.apply(before, before, RulePatch(waitSeconds = 10), "batch")
        val changed = RuleChangePlanner.changed(undo.after, undo.after.values.copy(waitSeconds = 3), "later")
        val changedBack = RuleChangePlanner.changed(changed, undo.after.values, "later-back")

        assertEquals(undo.after.values, changedBack.values)
        assertThrows(RuleConflictException::class.java) {
            RuleChangePlanner.undo(changedBack, undo, "undo")
        }
    }

    @Test
    fun `undo restores legacy legal values outside the new choice whitelist`() {
        val legacy = state(values = RuleValues(waitSeconds = 4, passMinutes = 2))
        val applied = RuleChangePlanner.apply(
            legacy,
            legacy,
            RulePatch(waitSeconds = 6, passMinutes = 5),
            "batch",
        )
        val decoded = RuleUndoCodec.decode(RuleUndoCodec.encode(applied))

        val restored = RuleChangePlanner.undo(applied.after, decoded, "undo")

        assertEquals(4, restored.values.waitSeconds)
        assertEquals(2, restored.values.passMinutes)
    }

    private fun state(values: RuleValues = RuleValues()) = RuleState(
        values = values,
        revision = "root",
        waitRevision = "wait-root",
        passRevision = "pass-root",
        scheduleRevision = "schedule-root",
    )
}
