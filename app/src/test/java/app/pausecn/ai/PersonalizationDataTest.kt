package app.pausecn.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizationDataTest {
    @Test
    fun `cache validity is isolated by consent profile target and privacy versions`() {
        val config = AiConfig(instanceId = "instance", privacyEpoch = 2, styleVersion = 3, enabled = true)
        val consent = PersonalizationConfig(enabled = true, useProfile = true, epoch = 4)
        val row = phrase(kind = PersonalizedCachePolicy.STABLE)

        assertTrue(SerializedValidity.valid(row, config, consent, "profile-v1", targetCreatedAt = 5, now = 200, elapsed = 100))
        assertFalse(SerializedValidity.valid(row, config.copy(enabled = false), consent, "profile-v1", 5, 200, 100))
        assertFalse(SerializedValidity.valid(row, config.copy(privacyEpoch = 9), consent, "profile-v1", 5, 200, 100))
        assertFalse(SerializedValidity.valid(row, config, consent.copy(enabled = false), "profile-v1", 5, 200, 100))
        assertFalse(SerializedValidity.valid(row, config, consent.copy(epoch = 9), "profile-v1", 5, 200, 100))
        assertFalse(SerializedValidity.valid(row, config, consent, "profile-v2", 5, 200, 100))
        assertFalse(SerializedValidity.valid(row, config, consent, "profile-v1", 6, 200, 100))
    }

    @Test
    fun `recent cache enforces expiry clock and consumption while stable ignores clock drift`() {
        val config = AiConfig(instanceId = "instance", privacyEpoch = 2, styleVersion = 3, enabled = true)
        val consent = PersonalizationConfig(enabled = true, epoch = 4)
        val recent = phrase(kind = PersonalizedCachePolicy.RECENT)

        assertTrue(SerializedValidity.valid(recent, config, consent, "profile-v1", 5, 200, 100))
        assertFalse(SerializedValidity.valid(recent.copy(consumed = true), config, consent, "profile-v1", 5, 200, 100))
        assertFalse(SerializedValidity.valid(recent, config, consent, "profile-v1", 5, 1_001, 901))
        assertFalse(SerializedValidity.valid(recent, config, consent, "profile-v1", 5, 200, 6_000))
        assertTrue(SerializedValidity.valid(recent.copy(kind = PersonalizedCachePolicy.STABLE), config, consent, "profile-v1", 5, 200, 6_000))
    }

    @Test
    fun `preparation queue replaces per app and evicts oldest distinct app`() {
        val queue = PreparationQueue(capacity = 2)
        val first = PreparationIntent("app.a", stable = true, fingerprint = "old")
        val replacement = first.copy(stable = false, fingerprint = "new")
        val second = PreparationIntent("app.b", stable = true, fingerprint = "b")
        val third = PreparationIntent("app.c", stable = true, fingerprint = "c")

        assertEquals(null, queue.offer(first))
        assertEquals(null, queue.offer(replacement))
        assertEquals(null, queue.offer(second))
        assertEquals("app.a", queue.offer(third))
        assertEquals(second, queue.poll())
        assertEquals(third, queue.poll())
        assertEquals(null, queue.poll())
    }

    private fun phrase(kind: String) = PersonalizedPhrase(
        id = "row", packageName = "app.a", targetCreatedAt = 5,
        scene = PromptScene.ORDINARY.name, text = "个性提醒", kind = kind,
        instanceId = "instance", privacyEpoch = 2, personalizationEpoch = 4,
        styleVersion = 3, profileRevision = "profile-v1", fingerprint = "fingerprint",
        createdAt = 100, expiresAt = 1_000, clockOffset = 100,
    )

    @Test
    fun `completed choice needs recent batch whether stable is absent available or consumed`() {
        val stable = phrase(PersonalizedCachePolicy.STABLE)
        listOf(emptyList(), listOf(stable), listOf(stable.copy(consumed = true))).forEach { rows ->
            assertFalse(hasRecent(rows))
        }
        val recent = phrase(PersonalizedCachePolicy.RECENT)
        assertTrue(hasRecent(listOf(recent)))
        assertTrue(hasRecent(listOf(stable.copy(consumed = true), recent)))
    }

    @Test
    fun `only current unconsumed matching recent phrase avoids an extra request`() {
        val recent = phrase(PersonalizedCachePolicy.RECENT)
        listOf(
            recent.copy(consumed = true),
            recent.copy(expiresAt = 200),
            recent.copy(personalizationEpoch = 9),
            recent.copy(profileRevision = "changed"),
            recent.copy(packageName = "other.app"),
            recent.copy(scene = PromptScene.REPEATED.name),
            recent.copy(fingerprint = "old-reasons"),
        ).forEach { row -> assertFalse(hasRecent(listOf(row))) }
        assertFalse(hasRecent(listOf(recent), consumedInMemory = setOf(recent.id)))
        assertTrue(hasRecent(listOf(recent)))
    }

    private fun hasRecent(rows: List<PersonalizedPhrase>, consumedInMemory: Set<String> = emptySet()) =
        PersonalizedCachePolicy.hasRecentForNextChoice(rows, "app.a", PromptScene.ORDINARY.name, "fingerprint") {
            it.id !in consumedInMemory && PersonalizedCachePolicy.valid(it,
                AiConfig(instanceId = "instance", privacyEpoch = 2, styleVersion = 3, enabled = true),
                PersonalizationConfig(enabled = true, epoch = 4), "profile-v1", 5, 200, 100)
        }

    private object SerializedValidity {
        fun valid(row: PersonalizedPhrase, config: AiConfig, consent: PersonalizationConfig,
            profileRevision: String, targetCreatedAt: Long?, now: Long, elapsed: Long,
        ) = PersonalizedCachePolicy.valid(row, config, consent, profileRevision, targetCreatedAt, now, elapsed)
    }
}
