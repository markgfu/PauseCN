package app.pausecn.domain

import app.pausecn.platform.oemGuideFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceReadinessTest {
    @Test
    fun interactiveUnlockedDeviceIsReady() {
        assertTrue(isDeviceReadyForIntervention(screenInteractive = true, keyguardLocked = false))

        val xiaomi = oemGuideFor("XIAOMI")
        assertEquals("小米 / Redmi 后台设置", xiaomi.title)
        assertEquals("com.miui.securitycenter", xiaomi.settingsTargets.single().packageName)
        assertTrue(oemGuideFor("realme").settingsTargets.any { it.packageName == "com.oplus.safecenter" })
        assertTrue(oemGuideFor("vivo").settingsTargets.any { it.packageName == "com.vivo.permissionmanager" })
        assertTrue(oemGuideFor("HUAWEI").settingsTargets.any { it.packageName == "com.huawei.systemmanager" })
        assertTrue(oemGuideFor("HONOR").settingsTargets.any { it.packageName == "com.hihonor.systemmanager" })
        assertTrue(oemGuideFor("samsung").settingsTargets.any { it.packageName == "com.samsung.android.lool" })
        assertTrue(oemGuideFor("unknown").settingsTargets.isEmpty())
    }

    @Test
    fun lockedDeviceIsNeverReady() {
        assertFalse(isDeviceReadyForIntervention(screenInteractive = true, keyguardLocked = true))
    }

    @Test
    fun screenOffDeviceIsNeverReady() {
        assertFalse(isDeviceReadyForIntervention(screenInteractive = false, keyguardLocked = false))
    }
}
