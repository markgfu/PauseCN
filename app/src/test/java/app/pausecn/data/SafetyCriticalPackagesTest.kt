package app.pausecn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyCriticalPackagesTest {
    @Test
    fun cameraClockAndInstallerPackagesAreAlwaysExcluded() {
        assertTrue(isSafetyCriticalApplication("com.android.camera2"))
        assertTrue(isSafetyCriticalApplication("com.google.android.deskclock"))
        assertTrue(isSafetyCriticalApplication("com.android.permissioncontroller"))
    }

    @Test
    fun paymentAndSuperAppCriticalFlowsAreExcluded() {
        assertTrue(isSafetyCriticalApplication("com.eg.android.AlipayGphone"))
        assertTrue(isSafetyCriticalApplication("com.tencent.mm"))
        assertTrue(isSafetyCriticalApplication("com.unionpay"))
        assertFalse(isSafetyCriticalApplication("social.example"))
    }

    @Test
    fun incomingCallSurfacesAreAlwaysExcluded() {
        assertTrue(isSafetyCriticalApplication("com.android.incallui"))
        assertTrue(isSafetyCriticalApplication("com.miui.incallui"))
        assertTrue(isSafetyCriticalApplication("com.oplus.incallui"))
        assertTrue(isSafetyCriticalApplication("com.samsung.android.incallui"))
    }

    @Test
    fun eventFilterContainsOnlyTargetsSafetyPackagesAndTheAppItself() {
        val result = accessibilityEventPackages(
            targetPackages = setOf("video.example"),
            safetyPackages = setOf("com.android.incallui", "com.android.systemui"),
            ownPackage = "app.pausecn",
        )

        assertEquals(
            setOf("video.example", "com.android.incallui", "com.android.systemui", "app.pausecn"),
            result,
        )
        assertFalse("unrelated.example" in result)

        assertEquals(
            "伪装 应用 名称",
            sanitizeInstalledAppLabel("  伪装\u202e\n应用\u0000名称  ", "social.example"),
        )
        assertEquals(
            "social.example",
            sanitizeInstalledAppLabel("\u202e\n", "social.example"),
        )
        val cappedLabel = sanitizeInstalledAppLabel("应".repeat(70), "social.example")
        assertEquals(60, cappedLabel.codePointCount(0, cappedLabel.length))
        val cappedAtWordBoundary = sanitizeInstalledAppLabel("应".repeat(59) + " 下一词", "social.example")
        assertEquals("应".repeat(59), cappedAtWordBoundary)
    }

    @Test
    fun onlySafetyPackageEventsRequestImmediateOverlayDismissal() {
        val safetyPackages = setOf("com.android.incallui", "com.android.settings")

        assertTrue(isSafetyDismissalEvent("com.android.incallui", safetyPackages))
        assertTrue(isSafetyDismissalEvent("com.android.settings", safetyPackages))
        assertFalse(isSafetyDismissalEvent("video.example", safetyPackages))
    }
}
