package app.pausecn.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterventionPurposeTest {
    @Test
    fun `purpose labels are unique and nonblank`() {
        assertEquals(INTERVENTION_PURPOSES.size, INTERVENTION_PURPOSES.map { it.label }.toSet().size)
        assertTrue(INTERVENTION_PURPOSES.all { it.label.isNotBlank() })
    }

    @Test
    fun `casual browsing is the only choice requiring confirmation`() {
        val casual = interventionPurpose("随便看看")
        assertEquals("随便看看", casual?.label)
        assertTrue(casual?.requiresConfirmation == true)
        assertEquals(1, INTERVENTION_PURPOSES.count { it.requiresConfirmation })
        assertFalse(interventionPurpose("搜资料")!!.requiresConfirmation)
    }

    @Test
    fun `unknown purpose is rejected`() {
        assertEquals(null, interventionPurpose("未知目的"))
    }
}
