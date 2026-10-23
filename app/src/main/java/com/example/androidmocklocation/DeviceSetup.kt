package com.example.androidmocklocation

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** Phone family, used only to word setup paths and pick vendor settings pages. */
enum class Rom(val label: String) {
    HARMONY("HarmonyOS"), EMUI("EMUI"), MAGIC("MagicOS"), OTHER("Android")
}

/** Per-install switches. Kept in SharedPreferences so the service can read them without the UI. */
object Prefs {
    private const val FILE = "settings"
    const val MOCK_NETWORK = "mock_network"
    const val SETUP_SEEN = "setup_seen"
    const val CONFIRMED_APP_LAUNCH = "confirmed_app_launch"
    const val CONFIRMED_RECENTS_LOCK = "confirmed_recents_lock"
    const val LAST_TARGET = "last_target"
    const val SHIELD_OBSERVE = "shield_observe"
    const val SHIELD_BLOCK = "shield_block"
    private fun of(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun get(context: Context, key: String, default: Boolean = false) = of(context).getBoolean(key, default)
    fun set(context: Context, key: String, value: Boolean) = of(context).edit().putBoolean(key, value).apply()
    fun mockNetwork(context: Context) = get(context, MOCK_NETWORK, true)
    /** Last target the user picked or typed, so a restarted process does not fall back to the default city. */
    fun lastTarget(context: Context): Point? = of(context).getString(LAST_TARGET, null)
        ?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
        ?.takeIf { it.size == 2 }?.let { Point(it[0], it[1]) }?.takeIf { it.valid() }
    fun setLastTarget(context: Context, point: Point) {
        of(context).edit().putString(LAST_TARGET, "${point.lat},${point.lon}").apply()
    }
}

/**
 * One first-run setup item. [done] is null when the system offers no way to read the state
 * (Huawei app launch management, locking the task in recents); the user then confirms it by hand.
 */
data class SetupStep(
    val key: String,
    val title: String,
    val path: String,
    val required: Boolean,
    val done: Boolean?,
    val action: String,
    val open: (Context) -> Boolean,
    val confirmKey: String? = null
)

object DeviceSetup {
    private const val HUAWEI_MANAGER = "com.huawei.systemmanager"
    private const val HONOR_MANAGER = "com.hihonor.systemmanager"

    val rom: Rom by lazy {
        val maker = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        when {
            harmonyBrand() -> Rom.HARMONY
            maker == "honor" || brand == "honor" -> Rom.MAGIC
            maker == "huawei" -> Rom.EMUI
            else -> Rom.OTHER
        }
    }

    /** HarmonyOS 2–4 phones still run Android apps on an Android 10/12 base; the brand comes from Huawei's BuildEx. */
    private fun harmonyBrand(): Boolean = try {
        val value = Class.forName("com.huawei.system.BuildEx").getMethod("getOsBrand").invoke(null)
        "harmony".equals(value as? String, ignoreCase = true)
    } catch (_: Throwable) { false }

    val huaweiFamily get() = rom == Rom.HARMONY || rom == Rom.EMUI || rom == Rom.MAGIC

    fun systemLabel(): String = "${rom.label} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    fun developerOptionsEnabled(context: Context) =
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0

    fun locationEnabled(context: Context) = try {
        context.getSystemService(LocationManager::class.java).isLocationEnabled
    } catch (_: Exception) { false }

    fun notificationsEnabled(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun ignoringBatteryOptimizations(context: Context) =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /**
     * Starts the first intent the phone can resolve; vendor pages differ between EMUI, HarmonyOS and MagicOS releases.
     * When none resolves it opens the app details page instead and returns false.
     */
    fun open(context: Context, vararg intents: Intent): Boolean {
        for (intent in intents) if (start(context, intent)) return true
        start(context, appDetails(context))
        return false
    }

    private fun start(context: Context, intent: Intent): Boolean {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
        return false
    }

    /** Runs a setup step. The nova 5z (HarmonyOS 3) refuses every launch-management page to other apps, so say so instead of silently showing app info. */
    fun launch(context: Context, step: SetupStep) {
        if (!step.open(context)) android.widget.Toast.makeText(context,
            "没能直接打开该设置页，已改为打开应用信息。请按卡片上的路径手动前往。", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    private fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    fun openDeveloperSettings(context: Context): Boolean =
        if (developerOptionsEnabled(context)) open(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        else open(context, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))

    fun openLocationSettings(context: Context) = open(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

    fun openNotificationSettings(context: Context) = open(context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))

    /** The standard "allow running in background" dialog; Huawei and Honor both honour it, with the list page as fallback. */
    @SuppressLint("BatteryLife")
    fun openBatterySettings(context: Context) = open(context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    /** 应用启动管理: EMUI 8+ and HarmonyOS keep it in the phone manager; Honor's MagicOS renamed the package. */
    fun openAppLaunchSettings(context: Context) = open(context,
        component(HUAWEI_MANAGER, "$HUAWEI_MANAGER.startupmgr.ui.StartupNormalAppListActivity"),
        component(HONOR_MANAGER, "$HONOR_MANAGER.startupmgr.ui.StartupNormalAppListActivity"),
        component(HONOR_MANAGER, "$HUAWEI_MANAGER.startupmgr.ui.StartupNormalAppListActivity"),
        component(HUAWEI_MANAGER, "$HUAWEI_MANAGER.power.ui.HwPowerManagerActivity"))

    fun steps(context: Context): List<SetupStep> {
        val devPath = when (rom) {
            Rom.HARMONY, Rom.EMUI, Rom.MAGIC -> "设置 → 关于手机 → 连续点击“版本号”7 次并输入锁屏密码；之后在 设置 → 系统和更新 → 开发人员选项 中可以找到。"
            Rom.OTHER -> "设置 → 关于手机 → 连续点击“版本号”7 次；之后在 设置 → 系统 → 开发者选项 中可以找到。"
        }
        val mockPath = when (rom) {
            Rom.HARMONY, Rom.EMUI, Rom.MAGIC -> "设置 → 系统和更新 → 开发人员选项 → 向下找到“选择模拟位置信息应用” → 选“安卓改定位”。"
            Rom.OTHER -> "开发者选项 → “选择模拟位置信息应用” → 选“安卓改定位”。"
        }
        val launchPath = when (rom) {
            Rom.HARMONY -> "设置 → 应用和服务 → 应用启动管理 → 找到“安卓改定位”，关闭“自动管理”，在弹窗中打开“允许自启动”“允许关联启动”“允许后台活动”三项。"
            Rom.EMUI -> "设置 → 应用 → 应用启动管理 → “安卓改定位”关闭“自动管理”，打开弹窗里的三项开关。"
            Rom.MAGIC -> "设置 → 应用和服务 → 应用启动管理 → “安卓改定位”改为手动管理，打开“允许自启动”“允许关联启动”“允许后台活动”。"
            Rom.OTHER -> "在系统的自启动或后台管理中允许“安卓改定位”后台运行（不同品牌位置不同）。"
        }
        val batteryPath = when (rom) {
            Rom.HARMONY, Rom.EMUI, Rom.MAGIC -> "在弹窗中选择“允许”。也可在 设置 → 电池 中关闭“省电模式”和“超级省电”，它们会冻结后台应用。"
            Rom.OTHER -> "在弹窗中选择“允许”，并关闭系统省电模式。"
        }
        val recentsPath = when (rom) {
            Rom.HARMONY, Rom.EMUI, Rom.MAGIC -> "从屏幕底部上滑并停顿打开多任务界面，按住或向下拖动“安卓改定位”卡片，出现小锁图标即锁定。锁定后“一键清理”不会结束它。"
            Rom.OTHER -> "在最近任务中锁定“安卓改定位”，避免一键清理时被结束。"
        }
        val developer = developerOptionsEnabled(context)
        return listOf(
            SetupStep("developer", "打开开发者选项", devPath, true, developer,
                if (developer) "已打开" else "去关于手机", ::openDeveloperSettings),
            SetupStep("mock", "选为模拟位置信息应用", mockPath, true, Session.mockAllowed(context),
                "去开发者选项", ::openDeveloperSettings),
            SetupStep("location", "打开系统位置信息", "下拉控制中心打开“位置信息”，或 设置 → 位置信息 → 打开“访问我的位置信息”。关闭时其他 App 读不到任何位置。",
                true, locationEnabled(context), "去打开", ::openLocationSettings),
            SetupStep("notification", "允许通知", "运行时通知栏会常驻“安卓改定位”，可随时一键停止；关闭通知后系统更容易结束后台服务。",
                false, notificationsEnabled(context), "去设置", ::openNotificationSettings),
            SetupStep("battery", "允许后台运行（忽略电池优化）", batteryPath, false, ignoringBatteryOptimizations(context),
                "去允许", ::openBatterySettings),
            SetupStep("launch", "应用启动管理改为手动", launchPath, false, null, "去设置", ::openAppLaunchSettings, Prefs.CONFIRMED_APP_LAUNCH),
            SetupStep("recents", "多任务界面锁定", recentsPath, false, null, "", { false }, Prefs.CONFIRMED_RECENTS_LOCK)
        )
    }

    fun requiredDone(context: Context) = steps(context).filter { it.required }.all { it.done == true }
}
