package com.payjoy.status.habit

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/**
 * The only door between JS and the native guard. JS can read a snapshot of state and
 * request actions; it can never write protection state directly.
 */
class HabitModule : Module() {
  private val appCtx: Context
    get() = requireNotNull(appContext.reactContext) { "React context is unavailable" }

  override fun definition() = ModuleDefinition {
    Name("HabitNative")
    Events("onAttemptsChanged", "onStateChanged")

    OnCreate {
      val ctx = appContext.reactContext ?: return@OnCreate
      try {
        HabitStore.init(ctx)
        HabitStore.ensureFilter(ctx)
        HabitUnlock.tick(ctx)
        HabitStore.onAttemptsChanged = { count ->
          sendEvent("onAttemptsChanged", mapOf("count" to count))
        }
        HabitStore.onStateChanged = {
          sendEvent("onStateChanged", emptyMap<String, Any?>())
        }
        val unlock = HabitStore.unlockState(ctx)
        if (HabitStore.state(ctx).protectionEnabled || unlock.cooldown != null || unlock.offWindow != null) {
          HabitForegroundService.start(ctx)
        }
      } catch (error: Throwable) {
        Log.w(TAG, "Module init failed: ${error.message}")
      }
    }

    OnDestroy {
      HabitStore.onAttemptsChanged = null
      HabitStore.onStateChanged = null
    }

    AsyncFunction("getSnapshot") {
      HabitStore.snapshot(appCtx)
    }

    AsyncFunction("setProtectionEnabled") { enabled: Boolean ->
      HabitUnlock.setProtectionEnabled(appCtx, enabled)
      HabitStore.snapshot(appCtx)
    }

    AsyncFunction("setMode") { mode: String ->
      HabitUnlock.setMode(appCtx, mode)
      HabitStore.snapshot(appCtx)
    }

    AsyncFunction("requestUnlockTimer") {
      HabitUnlock.requestTimerUnlock(appCtx)
      HabitStore.snapshot(appCtx)
    }

    AsyncFunction("cancelUnlockTimer") {
      HabitUnlock.cancelTimerUnlock(appCtx)
      HabitStore.snapshot(appCtx)
    }

    AsyncFunction("startCharacterChallenge") { length: Int ->
      HabitUnlock.startChallenge(appCtx, length)
    }

    AsyncFunction("submitCharacterChallenge") { challengeId: String, typed: String ->
      HabitUnlock.submitChallenge(appCtx, challengeId, typed)
    }

    AsyncFunction("startFaceUnlock") {
      FaceUnlock.start(appCtx)
    }

    AsyncFunction("submitFaceFrames") { uris: List<String> ->
      FaceUnlock.submit(appCtx, uris)
    }

    AsyncFunction("cancelFaceUnlock") {
      FaceUnlock.cancel()
      true
    }

    AsyncFunction("setFastCooldown") { enabled: Boolean ->
      HabitUnlock.setFastCooldown(appCtx, enabled)
    }

    AsyncFunction("debugExitStrict") {
      HabitUnlock.debugExitStrict(appCtx)
      HabitStore.snapshot(appCtx)
    }

    AsyncFunction("publishLists") { domainsJson: String, keywordsJson: String ->
      HabitStore.publishLists(appCtx, domainsJson, keywordsJson)
    }

    AsyncFunction("setOnboardingCompleted") { completed: Boolean ->
      HabitStore.setOnboardingCompleted(appCtx, completed)
      HabitStore.snapshot(appCtx)
    }

    // AI monitoring settings. The scanner itself is not implemented yet: these
    // four calls only persist what the Apps and Settings tabs show.
    AsyncFunction("getAiSettings") {
      HabitStore.aiSettings(appCtx).toMap()
    }

    AsyncFunction("setAiMonitoringEnabled") { enabled: Boolean ->
      HabitStore.setAiMonitoringEnabled(appCtx, enabled).toMap()
    }

    AsyncFunction("setAiSensitivity") { sensitivity: String ->
      HabitStore.setAiSensitivity(appCtx, sensitivity).toMap()
    }

    AsyncFunction("setAiWatchedApps") { packages: List<String> ->
      HabitStore.setAiWatchedApps(appCtx, packages).toMap()
    }

    AsyncFunction("setAiBlockAlerts") { enabled: Boolean ->
      HabitStore.setAiBlockAlerts(appCtx, enabled).toMap()
    }

    AsyncFunction("isAccessibilityEnabled") {
      HabitAccessibilityService.isEnabled(appCtx)
    }

    AsyncFunction("getInstalledPackages") { packages: List<String> ->
      installedPackages(packages)
    }

    AsyncFunction("getInstalledApps") {
      installedApps()
    }

    AsyncFunction("isDeviceAdminActive") {
      HabitDeviceAdmin.isActive(appCtx)
    }

    AsyncFunction("activateDeviceAdmin") {
      requestDeviceAdmin()
    }

    AsyncFunction("areNotificationsEnabled") {
      isNotificationAllowed()
    }

    AsyncFunction("requestNotificationPermission") {
      requestNotificationPermissionInternal()
    }

    AsyncFunction("openAccessibilitySettings") {
      openIntent(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    AsyncFunction("openNotificationSettings") {
      openNotificationSettingsInternal()
    }

    AsyncFunction("openAppDetails") {
      openAppDetailsInternal()
    }

    AsyncFunction("openBatterySettings") {
      openBatterySettingsInternal()
    }

    AsyncFunction("openAutostartSettings") {
      openAutostartSettingsInternal()
    }
  }

  /** Returns the subset of `packages` that this Android device really has installed. */
  private fun installedPackages(packages: List<String>): List<String> {
    val manager = appCtx.packageManager
    return packages.filter { pkg ->
      try {
        manager.getPackageInfo(pkg, 0)
        true
      } catch (error: Throwable) {
        false
      }
    }
  }

  /**
   * Returns every launchable application on the device (third-party and system),
   * except Habit and Termux, sorted A-Z by display label. Each entry carries the
   * real app name (`label`) and its `packageName`.
   */
  private fun installedApps(): List<Map<String, Any>> {
    val manager = appCtx.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val excluded = setOf(appCtx.packageName, "com.termux")
    return manager.queryIntentActivities(launcherIntent, 0)
      .asSequence()
      .map { it.activityInfo.applicationInfo }
      .filter { info -> info.packageName !in excluded }
      .distinctBy { it.packageName }
      .mapNotNull { info ->
        val label = try {
          info.loadLabel(manager)?.toString()?.trim().orEmpty()
        } catch (error: Throwable) {
          ""
        }
        if (label.isEmpty()) return@mapNotNull null
        val isSystemApp =
          (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        mapOf<String, Any>(
          "packageName" to info.packageName,
          "label" to label,
          "isSystemApp" to isSystemApp,
          "isBrowser" to isBrowserPackage(info.packageName),
          "iconUri" to cachedIconUri(manager, info)
        )
      }
      .sortedBy { (it["label"] as String).lowercase() }
      .toList()
  }

  /**
   * Renders the app's real launcher icon into a 96x96 PNG under
   * `cacheDir/app_icons` and returns a `file://` URI for it ("" if it fails).
   * The file is rewritten when the app's APK changes, so updates show the new icon.
   */
  private fun cachedIconUri(manager: PackageManager, info: ApplicationInfo): String {
    val dir = File(appCtx.cacheDir, ICON_DIR)
    val file = File(dir, "${info.packageName}.png")
    return try {
      val apkStamp = File(info.sourceDir).lastModified()
      if (!file.exists() || file.lastModified() < apkStamp) {
        dir.mkdirs()
        val drawable = manager.getApplicationIcon(info)
        val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, ICON_SIZE, ICON_SIZE)
        drawable.draw(canvas)
        file.outputStream().use { output ->
          bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        bitmap.recycle()
      }
      Uri.fromFile(file).toString()
    } catch (error: Throwable) {
      Log.w(TAG, "Could not cache icon for ${info.packageName}: ${error.message}")
      ""
    }
  }

  private fun isBrowserPackage(packageName: String): Boolean {
    return when (packageName) {
      "com.android.chrome",
      "com.chrome.beta",
      "com.chrome.dev",
      "com.brave.browser",
      "com.microsoft.emmx",
      "com.sec.android.app.sbrowser",
      "org.mozilla.firefox",
      "org.mozilla.firefox_beta",
      "com.opera.browser",
      "com.opera.mini.native",
      "com.duckduckgo.mobile.android",
      "com.mi.globalbrowser",
      "com.android.browser" -> true
      else -> false
    }
  }

  private fun requestDeviceAdmin(): Boolean {
    if (HabitDeviceAdmin.isActive(appCtx)) return true
    val activity = appContext.currentActivity ?: return false
    return try {
      activity.runOnUiThread {
        try {
          activity.startActivity(HabitDeviceAdmin.requestIntent(activity))
        } catch (error: Throwable) {
          Log.w(TAG, "Device admin request failed: ${error.message}")
        }
      }
      true
    } catch (error: Throwable) {
      Log.w(TAG, "Device admin request failed: ${error.message}")
      false
    }
  }

  private fun isNotificationAllowed(): Boolean {
    return try {
      NotificationManagerCompat.from(appCtx).areNotificationsEnabled()
    } catch (error: Throwable) {
      false
    }
  }

  private fun requestNotificationPermissionInternal(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return isNotificationAllowed()
    val activity = appContext.currentActivity
    if (activity == null) {
      openNotificationSettingsInternal()
      return false
    }
    val granted = ContextCompat.checkSelfPermission(
      activity,
      Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) {
      activity.runOnUiThread {
        try {
          activity.requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_REQUEST_CODE
          )
        } catch (error: Throwable) {
          Log.w(TAG, "Notification request failed: ${error.message}")
        }
      }
    }
    return granted
  }

  private fun openNotificationSettingsInternal(): Boolean {
    return try {
      val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
          .putExtra(Settings.EXTRA_APP_PACKAGE, appCtx.packageName)
      } else {
        appDetailsIntent()
      }
      openIntent(intent)
    } catch (error: Throwable) {
      openAppDetailsInternal()
    }
  }

  private fun openAppDetailsInternal(): Boolean {
    return openIntent(appDetailsIntent())
  }

  private fun appDetailsIntent(): Intent {
    return Intent(
      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
      Uri.fromParts("package", appCtx.packageName, null)
    )
  }

  private fun openBatterySettingsInternal(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (isBatteryOptimizationIgnored()) return true
      val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${appCtx.packageName}")
      )
      if (openIntent(direct)) return true
      if (openIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return true
    }
    return openAppDetailsInternal()
  }

  private fun isBatteryOptimizationIgnored(): Boolean {
    return try {
      val manager = appCtx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
      manager.isIgnoringBatteryOptimizations(appCtx.packageName)
    } catch (error: Throwable) {
      false
    }
  }

  private fun openAutostartSettingsInternal(): Boolean {
    if (!Build.MANUFACTURER.equals("xiaomi", ignoreCase = true)) {
      // Non-Xiaomi devices: the app details screen is where the battery settings live.
      if (openIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return true
      return openAppDetailsInternal()
    }
    val autostart = Intent().setComponent(
      ComponentName(
        "com.miui.securitycenter",
        "com.miui.permcenter.autostart.AutoStartManagementActivity"
      )
    )
    if (openIntent(autostart)) return true
    return openAppDetailsInternal()
  }

  private fun openIntent(intent: Intent): Boolean {
    return try {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      appCtx.startActivity(intent)
      true
    } catch (error: Throwable) {
      Log.w(TAG, "Could not open settings: ${error.message}")
      false
    }
  }

  companion object {
    private const val TAG = "HabitModule"
    private const val NOTIFICATION_REQUEST_CODE = 4211
    private const val ICON_DIR = "app_icons"
    private const val ICON_SIZE = 96
  }
}
