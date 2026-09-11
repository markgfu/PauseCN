package app.pausecn.platform

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.net.toUri
import app.pausecn.accessibility.PauseAccessibilityService
import java.util.Locale

data class DeviceStatus(
    val accessibilityEnabled: Boolean,
    val ignoringBatteryOptimizations: Boolean,
)

fun readDeviceStatus(context: Context): DeviceStatus {
    val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
    val expected = ComponentName(context, PauseAccessibilityService::class.java)
    val accessibilityEnabled = accessibilityManager
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { info ->
            val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
            ComponentName(serviceInfo.packageName, serviceInfo.name) == expected
        }

    val powerManager = context.getSystemService(PowerManager::class.java)
    return DeviceStatus(
        accessibilityEnabled = accessibilityEnabled,
        ignoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName),
    )
}

enum class SystemSettingsOpenResult {
    REQUESTED_PAGE,
    APP_DETAILS,
    GENERAL_SETTINGS,
    UNAVAILABLE,
}

fun openAccessibilitySettings(context: Context): SystemSettingsOpenResult =
    openSystemSettingsCandidates(
        context,
        listOf(
            SystemSettingsOpenResult.REQUESTED_PAGE to Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            SystemSettingsOpenResult.GENERAL_SETTINGS to Intent(Settings.ACTION_SETTINGS),
        ),
    )

fun openBatteryOptimizationSettings(context: Context): SystemSettingsOpenResult =
    openSystemSettingsCandidates(
        context,
        listOf(
            SystemSettingsOpenResult.REQUESTED_PAGE to
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            SystemSettingsOpenResult.APP_DETAILS to appDetailsIntent(context),
            SystemSettingsOpenResult.GENERAL_SETTINGS to Intent(Settings.ACTION_SETTINGS),
        ),
    )

fun openAppDetails(context: Context): SystemSettingsOpenResult =
    openSystemSettingsCandidates(
        context,
        listOf(
            SystemSettingsOpenResult.REQUESTED_PAGE to appDetailsIntent(context),
            SystemSettingsOpenResult.GENERAL_SETTINGS to Intent(Settings.ACTION_SETTINGS),
        ),
    )

private fun appDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData("package:${context.packageName}".toUri())

private fun openSystemSettingsCandidates(
    context: Context,
    candidates: List<Pair<SystemSettingsOpenResult, Intent>>,
): SystemSettingsOpenResult {
    candidates.forEach { (result, intent) ->
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return result
        } catch (_: ActivityNotFoundException) {
            // Some ROMs remove or rename standard Settings entry points; try a broader page.
        } catch (_: SecurityException) {
            // A vendor may expose an intent action but block third-party callers.
        } catch (_: RuntimeException) {
            // Treat vendor Settings launch failures as recoverable navigation failures.
        }
    }
    return SystemSettingsOpenResult.UNAVAILABLE
}

data class OemGuide(
    val title: String,
    val steps: List<String>,
    val settingsTargets: List<OemSettingsTarget> = emptyList(),
)

data class OemSettingsTarget(
    val packageName: String,
    val className: String,
)

enum class OemSettingsOpenResult {
    OEM_PANEL,
    BATTERY_OPTIMIZATION_LIST,
    APP_DETAILS,
    GENERAL_SETTINGS,
    UNAVAILABLE,
}

internal fun oemGuideFor(manufacturerName: String): OemGuide {
    val manufacturer = manufacturerName.lowercase(Locale.ROOT)
    return when {
        "xiaomi" in manufacturer || "redmi" in manufacturer -> OemGuide(
            "小米 / Redmi 后台设置",
            listOf("开启自启动", "省电策略选择“无限制”", "若系统提供“后台弹出界面”，请允许"),
            listOf(
                OemSettingsTarget(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            ),
        )
        "oppo" in manufacturer || "realme" in manufacturer || "oneplus" in manufacturer -> OemGuide(
            "OPPO / realme / 一加后台设置",
            listOf("允许自启动", "允许后台运行", "关闭针对“停一下”的电池优化"),
            listOf(
                OemSettingsTarget(
                    "com.oplus.safecenter",
                    "com.oplus.safecenter.startupapp.StartupAppListActivity",
                ),
                OemSettingsTarget(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                ),
                OemSettingsTarget(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity",
                ),
            ),
        )
        "vivo" in manufacturer || "iqoo" in manufacturer -> OemGuide(
            "vivo / iQOO 后台设置",
            listOf("允许自启动", "允许高耗电后台运行", "在后台管理中锁定“停一下”"),
            listOf(
                OemSettingsTarget(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                ),
                OemSettingsTarget(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                ),
            ),
        )
        "huawei" in manufacturer -> OemGuide(
            "华为后台设置",
            listOf("进入应用启动管理", "关闭自动管理", "允许自启动、关联启动与后台活动"),
            listOf(
                OemSettingsTarget(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
                OemSettingsTarget(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity",
                ),
            ),
        )
        "honor" in manufacturer -> OemGuide(
            "荣耀后台设置",
            listOf("进入应用启动管理", "关闭自动管理", "允许后台活动并关闭电池优化"),
            listOf(
                OemSettingsTarget(
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
                OemSettingsTarget(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
            ),
        )
        "samsung" in manufacturer -> OemGuide(
            "三星后台设置",
            listOf("将“停一下”加入永不休眠应用", "关闭电池优化", "不要启用深度休眠"),
            listOf(
                OemSettingsTarget(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                ),
                OemSettingsTarget(
                    "com.samsung.android.sm",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                ),
            ),
        )
        else -> OemGuide(
            "后台运行建议",
            listOf("允许后台活动", "关闭电池优化", "如果系统提供自启动管理，请允许“停一下”"),
        )
    }
}

fun currentOemGuide(): OemGuide = oemGuideFor(android.os.Build.MANUFACTURER)

fun openOemBackgroundSettings(
    context: Context,
    guide: OemGuide = currentOemGuide(),
): OemSettingsOpenResult {
    val candidates = buildList {
        guide.settingsTargets.forEach { target ->
            add(
                OemSettingsOpenResult.OEM_PANEL to Intent().setComponent(
                    ComponentName(target.packageName, target.className),
                ),
            )
        }
        add(
            OemSettingsOpenResult.BATTERY_OPTIMIZATION_LIST to
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
        add(
            OemSettingsOpenResult.APP_DETAILS to
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData("package:${context.packageName}".toUri()),
        )
        add(OemSettingsOpenResult.GENERAL_SETTINGS to Intent(Settings.ACTION_SETTINGS))
    }

    candidates.forEach { (result, intent) ->
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return result
        } catch (_: ActivityNotFoundException) {
            // Try the next increasingly broad system entry point.
        } catch (_: SecurityException) {
            // Vendor pages are often private or renamed; never strand the user there.
        } catch (_: RuntimeException) {
            // Some vendor settings throw during launch on mismatched ROM revisions.
        }
    }
    return OemSettingsOpenResult.UNAVAILABLE
}
