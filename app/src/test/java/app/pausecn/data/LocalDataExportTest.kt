package app.pausecn.data

import app.pausecn.SensitiveCharArrayBuffer
import app.pausecn.domain.ScheduleSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDataExportTest {
    @Test
    fun jsonContainsAllUserControlledDataAndEscapesStrings() {
        val json = encodeLocalDataExport(
            LocalDataExportSnapshot(
                exportedAtEpochMs = 1234,
                appVersion = "0.1.0\n测试",
                settings = SettingsSnapshot(
                    disclosureAccepted = true,
                    ageEligibilityConfirmed = true,
                    onboardingPreviewCompleted = true,
                    schedule = ScheduleSpec(enabled = true, startMinutes = 60, endMinutes = 120, activeDaysMask = 5),
                    globallyPausedUntilEpochMs = 999,
                    interventionSeconds = 10,
                    temporaryPassMinutes = 15,
                    historyRetentionDays = 30,
                ),
                targets = listOf(TargetRuleEntity("example.app", "A\"\\\n应用", createdAtEpochMs = 42)),
                events = listOf(
                    InterventionEventEntity(
                        id = 7,
                        packageName = "example.app",
                        appLabel = "示例",
                        occurredAtEpochMs = 88,
                        outcome = InterventionOutcome.CONTINUED.name,
                        purpose = null,
                        triggerLatencyMs = 321,
                    ),
                ),
                serviceSessions = listOf(
                    ServiceSessionEntity(
                        id = 9,
                        connectedAtEpochMs = 70,
                        connectedAtElapsedMs = 7,
                        lastHeartbeatAtEpochMs = 80,
                        lastHeartbeatAtElapsedMs = 17,
                        heartbeatCount = 3,
                        maxHeartbeatGapMs = 6,
                    ),
                ),
            ),
        )

        assertTrue(json.startsWith("{\"format\":\"app.pausecn.local-data\""))
        assertTrue(json.contains("\"formatVersion\":6"))
        assertTrue(json.contains("\"appVersion\":\"0.1.0\\n测试\""))
        assertTrue(json.contains("\"label\":\"A\\\"\\\\\\n应用\""))
        assertTrue(json.contains("\"activeDaysMask\":5"))
        assertTrue(json.contains("\"ageEligibilityConfirmed\":true"))
        assertTrue(json.contains("\"onboardingPreviewCompleted\":true"))
        assertTrue(json.contains("\"historyRetentionDays\":30"))
        assertTrue(json.contains("\"purpose\":null"))
        assertTrue(json.contains("\"triggerLatencyMs\":321"))
        assertTrue(json.contains("\"serviceSessions\":[{\"id\":9,\"connectedAtEpochMs\":70,\"connectedAtElapsedMs\":7,\"lastHeartbeatAtEpochMs\":80,\"lastHeartbeatAtElapsedMs\":17,\"heartbeatCount\":3,\"maxHeartbeatGapMs\":6}]"))
        assertFalse(json.contains("A\"\\\n应用"))
    }

    @Test
    fun encryptionRoundTripsAndUsesFreshRandomness() {
        val passphrase = "足够长的导出密码123456".toCharArray()
        val plainText = "{\"hello\":\"世界\"}"

        val first = encryptLocalDataExport(plainText, passphrase)
        val second = encryptLocalDataExport(plainText, passphrase)

        assertNotEquals(first, second)
        assertFalse(first.contains("世界"))
        assertEquals(plainText, decryptLocalDataExport(first, passphrase))
        assertTrue(first.contains("\"iterations\":600000"))
        assertTrue(first.contains("\"cipher\":\"AES/GCM/NoPadding\""))

        val legacyEnvelope = """{"format":"app.pausecn.encrypted-export","version":1,"kdf":"PBKDF2WithHmacSHA256","iterations":210000,"cipher":"AES/GCM/NoPadding","salt":"AAECAwQFBgcICQoLDA0ODw==","iv":"EBESExQVFhcYGRob","ciphertext":"uLCX17x4Xm9zYTAL/qDg4ORsN56VXw=="}"""
        assertEquals("legacy", decryptLocalDataExport(legacyEnvelope, "12345678".toCharArray()))

        val firstPending = "first-pending-password".toCharArray()
        val secondPending = "second-pending-password".toCharArray()
        val buffer = SensitiveCharArrayBuffer()
        buffer.replace(firstPending)
        buffer.replace(secondPending)
        assertTrue(firstPending.all { it == '\u0000' })
        assertTrue(buffer.take() === secondPending)
        buffer.replace(secondPending)
        buffer.clear()
        assertTrue(secondPending.all { it == '\u0000' })
    }

    @Test
    fun wrongPasswordCannotDecryptExport() {
        val encrypted = encryptLocalDataExport("sensitive", "correct-password".toCharArray())

        assertThrows(Exception::class.java) {
            decryptLocalDataExport(encrypted, "wrong-password".toCharArray())
        }
    }

    @Test
    fun shortPasswordIsRejected() {
        val elevenAscii = "12345678901"
        val twelveAscii = "123456789012"
        assertFalse(hasMinimumExportPassphraseLength(""))
        assertFalse(hasMinimumExportPassphraseLength(CharArray(0)))
        assertFalse(hasMinimumExportPassphraseLength(elevenAscii))
        assertFalse(hasMinimumExportPassphraseLength(elevenAscii.toCharArray()))
        assertTrue(hasMinimumExportPassphraseLength(twelveAscii))
        assertTrue(hasMinimumExportPassphraseLength(twelveAscii.toCharArray()))

        // The rule is deliberately based on Unicode code points, not UTF-16 code units
        // or user-perceived grapheme clusters.
        val sixEmoji = "😀😀😀😀😀😀"
        val twelveEmojiText = "😀😁😂😃😄😅😆😉😊😋😎😍"
        val sixCombiningGraphemes = "e\u0301".repeat(6)
        assertFalse(hasMinimumExportPassphraseLength(sixEmoji))
        assertFalse(hasMinimumExportPassphraseLength(sixEmoji.toCharArray()))
        assertTrue(hasMinimumExportPassphraseLength(twelveEmojiText))
        assertTrue(hasMinimumExportPassphraseLength(twelveEmojiText.toCharArray()))
        assertTrue(hasMinimumExportPassphraseLength(sixCombiningGraphemes))
        assertTrue(hasMinimumExportPassphraseLength(sixCombiningGraphemes.toCharArray()))

        // One valid surrogate pair is one code point, while every unpaired surrogate is invalid.
        assertFalse(hasMinimumExportPassphraseLength("1234567890😀"))
        assertTrue(hasMinimumExportPassphraseLength("12345678901😀"))
        listOf(
            charArrayOf('\uD83D', 'a') + "123456789012".toCharArray(),
            charArrayOf('\uDE00') + "123456789012".toCharArray(),
            "123456789012".toCharArray() + charArrayOf('\uD83D'),
        ).forEach { malformed ->
            assertFalse(hasMinimumExportPassphraseLength(malformed))
            assertFalse(hasMinimumExportPassphraseLength(malformed.concatToString()))
        }

        assertThrows(IllegalArgumentException::class.java) {
            encryptLocalDataExport("data", elevenAscii.toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            encryptLocalDataExport("data", sixEmoji.toCharArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            encryptLocalDataExport("data", CharArray(12) { '\uD83D' })
        }
        val twelveEmoji = twelveEmojiText.toCharArray()
        assertEquals(
            "emoji-password",
            decryptLocalDataExport(
                encryptLocalDataExport("emoji-password", twelveEmoji),
                twelveEmoji,
            ),
        )
    }
}
