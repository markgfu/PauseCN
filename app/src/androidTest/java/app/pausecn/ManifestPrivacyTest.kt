package app.pausecn

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pausecn.accessibility.PauseAccessibilityService
import app.pausecn.data.protectedPackages
import app.pausecn.data.safetyDismissalPackages
import app.pausecn.platform.SystemSettingsOpenResult
import app.pausecn.platform.ExternalUriOpenResult
import app.pausecn.platform.openAccessibilitySettings
import app.pausecn.platform.openBatteryOptimizationSettings
import app.pausecn.platform.openHttpsUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManifestPrivacyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun packagedPlatformPermissionsMatchReviewedAsset() {
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = packageInfo.requestedPermissions?.toSet().orEmpty()
        val reviewed = context.assets.open("platform-permissions.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
        }
        val packagePrivate = requested.filter { it.startsWith("${context.packageName}.") }.toSet()

        assertEquals(
            "Manifest platform permissions must exactly match platform-permissions.txt",
            reviewed,
            requested - packagePrivate,
        )
        assertFalse(Manifest.permission.QUERY_ALL_PACKAGES in requested)
    }

    @Test
    fun accessibilityServiceIsProtectedBySystemBindingPermission() {
        @Suppress("DEPRECATION")
        val serviceInfo = context.packageManager.getServiceInfo(
            ComponentName(context, PauseAccessibilityService::class.java),
            PackageManager.GET_META_DATA,
        )

        assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, serviceInfo.permission)
        assertTrue(serviceInfo.exported)
    }

    @Test
    fun systemBackupIsDisabled() {
        val applicationInfo = context.applicationInfo
        assertEquals(0, applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun currentHomeDialerAndSmsPackagesAreProtected() {
        val protected = protectedPackages(context)
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homePackages = context.packageManager.queryIntentActivities(homeIntent, 0)
            .mapNotNull { it.activityInfo?.packageName }

        assertTrue(homePackages.isNotEmpty())
        assertTrue(protected.containsAll(homePackages))
        context.getSystemService(TelecomManager::class.java)
            ?.defaultDialerPackage
            ?.let { assertTrue(it in protected) }
        Telephony.Sms.getDefaultSmsPackage(context)
            ?.let { assertTrue(it in protected) }

        assertTrue("com.android.camera2" in protected)
        assertTrue("com.android.incallui" in protected)
        assertTrue("com.samsung.android.incallui" in protected)
        assertTrue("com.google.android.deskclock" in protected)
        assertTrue("com.android.permissioncontroller" in protected)

        val accessibilityFallback = RecordingLaunchContext(
            context,
            failures = listOf(ActivityNotFoundException()),
        )
        assertEquals(
            SystemSettingsOpenResult.GENERAL_SETTINGS,
            openAccessibilitySettings(accessibilityFallback),
        )
        assertEquals(
            listOf(Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_SETTINGS),
            accessibilityFallback.launchedActions,
        )

        val batteryFallback = RecordingLaunchContext(
            context,
            failures = listOf(SecurityException("synthetic vendor restriction")),
        )
        assertEquals(
            SystemSettingsOpenResult.APP_DETAILS,
            openBatteryOptimizationSettings(batteryFallback),
        )
        assertEquals(
            listOf(
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ),
            batteryFallback.launchedActions,
        )

        val unavailable = RecordingLaunchContext(
            context,
            failures = List(3) { RuntimeException("synthetic vendor settings failure") },
        )
        assertEquals(SystemSettingsOpenResult.UNAVAILABLE, openBatteryOptimizationSettings(unavailable))
        assertEquals(3, unavailable.launchedActions.size)

        val browser = RecordingLaunchContext(context, failures = emptyList())
        assertEquals(
            ExternalUriOpenResult.OPENED,
            openHttpsUri(browser, " https://privacy.example.cn/rules "),
        )
        assertEquals(Intent.ACTION_VIEW, browser.launchedIntents.single().action)
        assertEquals("https://privacy.example.cn/rules", browser.launchedIntents.single().dataString)
        assertTrue(Intent.CATEGORY_BROWSABLE in browser.launchedIntents.single().categories.orEmpty())

        val invalidLink = RecordingLaunchContext(context, failures = emptyList())
        assertEquals(
            ExternalUriOpenResult.INVALID_HTTPS_URL,
            openHttpsUri(invalidLink, "http://privacy.example.cn/rules"),
        )
        assertTrue(invalidLink.launchedIntents.isEmpty())

        val blockedBrowser = RecordingLaunchContext(
            context,
            failures = listOf(SecurityException("synthetic browser restriction")),
        )
        assertEquals(
            ExternalUriOpenResult.UNAVAILABLE,
            openHttpsUri(blockedBrowser, "https://privacy.example.cn/rules"),
        )
    }

    @Test
    fun safetyDismissalFilterIsLimitedAndExcludesHighFrequencySystemUiEvents() {
        val dismissalPackages = safetyDismissalPackages(context)

        assertFalse(context.packageName in dismissalPackages)
        assertTrue("com.android.settings" in dismissalPackages)
        assertTrue("com.android.incallui" in dismissalPackages)
        assertTrue("com.android.camera2" in dismissalPackages)
        assertFalse("android" in dismissalPackages)
        assertFalse("com.android.systemui" in dismissalPackages)
    }

    private class RecordingLaunchContext(
        base: Context,
        private val failures: List<RuntimeException>,
    ) : ContextWrapper(base) {
        val launchedIntents = mutableListOf<Intent>()
        val launchedActions: List<String?> get() = launchedIntents.map { it.action }

        override fun startActivity(intent: Intent) {
            launchedIntents += intent
            failures.getOrNull(launchedIntents.lastIndex)?.let { throw it }
        }
    }
}
