package com.payjoy.status.habit

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Watches browser windows and blocks adult pages.
 *
 * Only readability-friendly text is inspected (URL bar, window title, visible text).
 * Nothing is stored except a per-day counter.
 */
class HabitAccessibilityService : AccessibilityService() {
  private var lastScanAt = 0L
  /** Throttles the AI router: content-changed events arrive many times per second. */
  private var lastRoutedPackage: String? = null
  private var lastRoutedAt = 0L
  /** The launcher package never changes while the service runs. */
  @Volatile private var cachedHomePackage: String? = null

  override fun onServiceConnected() {
    super.onServiceConnected()
    try {
      HabitStore.init(this)
      HabitStore.ensureFilter(this)
      HabitGuard.attach(this)
      // Starts the keep-alive only when protection (or an unlock timer) needs it.
      HabitUnlock.resumeAfterBoot(this)
      // If Habit was enabled while a watched app was already open, no window change
      // will arrive, so arm the cascade from the current window once.
      rootInActiveWindow?.packageName?.toString()?.let { updateAiScanner(it) }
    } catch (error: Throwable) {
      Log.w(TAG, "onServiceConnected failed: ${error.message}")
    }
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    val safeEvent = event ?: return
    when (safeEvent.eventType) {
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
      AccessibilityEvent.TYPE_WINDOWS_CHANGED,
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
      AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
      AccessibilityEvent.TYPE_VIEW_SCROLLED -> Unit
      else -> return
    }

    val pkg = safeEvent.packageName?.toString() ?: return

    // Watched apps are guarded by the screenshot cascade, not by text or URL scanning.
    // Every event is offered to the router, not just a window-state change: Telegram can
    // become visible through a notification, a share sheet or a split-screen resume without
    // ever emitting TYPE_WINDOW_STATE_CHANGED, and the loop has to arm the moment the user
    // is inside a monitored app. The router returns immediately once the loop is running,
    // so the extra events cost nothing, and it stops the loop as soon as another app is in
    // front.
    updateAiScanner(pkg)

    if (pkg == packageName) return
    if (pkg !in BROWSER_PACKAGES) return

    val now = SystemClock.elapsedRealtime()
    if (now - lastScanAt < SCAN_INTERVAL_MS) return
    lastScanAt = now

    try {
      if (!HabitStore.state(this).protectionEnabled) {
        HabitGuard.hideOverlay()
        return
      }
      scanPage(this, pkg, safeEvent)
    } catch (error: Throwable) {
      Log.w(TAG, "scan failed: ${error.message}")
    }
  }

  override fun onInterrupt() = Unit

  /** Routes the foreground app: start the cascade for a selected app, stop it otherwise. */
  private fun updateAiScanner(pkg: String) {
    try {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
      // Already scanning this app: nothing to read, nothing to start.
      if (AiVisualScanner.isRunningFor(pkg)) return

      // Every event comes through here, and TYPE_WINDOW_CONTENT_CHANGED can fire dozens of
      // times a second. The store is only re-read when the package changes or a second has
      // passed, so routing stays cheap without ever missing an app switch.
      val now = SystemClock.elapsedRealtime()
      if (pkg == lastRoutedPackage && now - lastRoutedAt < ROUTE_INTERVAL_MS) return
      lastRoutedPackage = pkg
      lastRoutedAt = now

      // SystemUI is the notification shade / volume panel. It is not a different app, and
      // pulling the shade down inside a watched app is exactly how the user reads the live
      // AI line in the notification, so it must not stop the loop.
      if (pkg == SYSTEM_UI_PACKAGE) return

      if (pkg == packageName || pkg == homePackage()) {
        AiVisualScanner.stop()
        return
      }

      val state = HabitStore.state(this)
      val ai = HabitStore.aiSettings(this)
      if (state.protectionEnabled && ai.enabled && pkg in ai.monitoredAppPackages) {
        AiVisualScanner.start(this, pkg)
      } else if (AiVisualScanner.isRunning) {
        AiVisualScanner.stop()
      }
    } catch (error: Throwable) {
      Log.w(TAG, "AI scanner routing failed: ${error.message}")
    }
  }

  /** Package of the window in front right now, or null when Android hides it. */
  fun foregroundPackage(): String? = try {
    rootInActiveWindow?.packageName?.toString()
  } catch (error: Throwable) {
    null
  }

  private fun homePackage(): String? {
    cachedHomePackage?.let { return it }
    val resolved = try {
      val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
      packageManager
        .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo
        ?.packageName
    } catch (error: Throwable) {
      null
    }
    if (resolved != null) cachedHomePackage = resolved
    return resolved
  }

  override fun onUnbind(intent: Intent?): Boolean {
    AiVisualScanner.stop()
    HabitGuard.detach()
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    AiVisualScanner.release()
    HabitGuard.detach()
    super.onDestroy()
  }

  /** Text currently visible in the active window, used by the block flow. */
  fun currentPageText(): String {
    val builder = StringBuilder()
    val root = try {
      rootInActiveWindow
    } catch (error: Throwable) {
      null
    } ?: return ""
    collectText(root, builder, 0, intArrayOf(NODE_BUDGET))
    return builder.toString()
  }

  private fun scanPage(service: AccessibilityService, pkg: String, event: AccessibilityEvent) {
    val builder = StringBuilder()
    event.text?.forEach { value -> if (!value.isNullOrEmpty()) builder.append(value).append(' ') }

    val root = try {
      rootInActiveWindow
    } catch (error: Throwable) {
      null
    }

    if (root != null) {
      try {
        root.window?.title?.let { title -> if (title.isNotEmpty()) builder.append(title).append(' ') }
      } catch (error: Throwable) {
        // window info can disappear mid-traversal
      }

      for (viewId in urlBarIds(pkg)) {
        try {
          root.findAccessibilityNodeInfosByViewId(viewId)?.forEach { node ->
            node.text?.let { text -> builder.append(text).append(' ') }
          }
        } catch (error: Throwable) {
          // ignore stale nodes
        }
      }

      collectText(root, builder, 0, intArrayOf(NODE_BUDGET))
    }

    HabitGuard.onPageText(this, builder.toString())
  }

  private fun collectText(
    node: AccessibilityNodeInfo?,
    builder: StringBuilder,
    depth: Int,
    budget: IntArray
  ) {
    if (node == null || depth > MAX_DEPTH || budget[0] <= 0 || builder.length > MAX_TEXT_LENGTH) return
    budget[0] -= 1
    node.text?.let { if (it.isNotEmpty()) builder.append(it).append(' ') }
    node.contentDescription?.let { if (it.isNotEmpty()) builder.append(it).append(' ') }
    val childCount = node.childCount
    for (index in 0 until childCount) {
      collectText(node.getChild(index), builder, depth + 1, budget)
    }
  }

  private fun urlBarIds(pkg: String): List<String> = when (pkg) {
    "com.android.chrome", "com.chrome.beta", "com.chrome.dev",
    "com.brave.browser", "com.microsoft.emmx" -> listOf("$pkg:id/url_bar", "$pkg:id/url_bar_amphibian")
    "com.sec.android.app.sbrowser" -> listOf(
      "com.sec.android.app.sbrowser:id/location_bar_edit_text",
      "com.sec.android.app.sbrowser:id/url_bar"
    )
    "org.mozilla.firefox", "org.mozilla.firefox_beta" -> listOf(
      "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
      "org.mozilla.firefox:id/url_bar_title",
      "org.mozilla.firefox:id/url_bar"
    )
    "com.opera.browser", "com.opera.mini.native" -> listOf(
      "com.opera.browser:id/url_field",
      "com.opera.mini.native:id/url_field",
      "com.opera.browser:id/urlbar_edit_text"
    )
    "com.duckduckgo.mobile.android" -> listOf(
      "com.duckduckgo.mobile.android:id/omnibarTextInput"
    )
    "com.mi.globalbrowser", "com.android.browser" -> listOf(
      "com.mi.globalbrowser:id/url_bar",
      "com.android.browser:id/url"
    )
    else -> emptyList()
  }

  companion object {
    private const val TAG = "HabitA11y"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val SCAN_INTERVAL_MS = 180L
    private const val ROUTE_INTERVAL_MS = 1_000L
    private const val MAX_DEPTH = 10
    private const val NODE_BUDGET = 220
    private const val MAX_TEXT_LENGTH = 5000

    val BROWSER_PACKAGES: Set<String> = setOf(
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
      "com.android.browser"
    )

    fun isEnabled(context: Context): Boolean {
      val component = ComponentName(context, HabitAccessibilityService::class.java)
      val enabledFlag = try {
        Settings.Secure.getInt(
          context.contentResolver,
          Settings.Secure.ACCESSIBILITY_ENABLED,
          0
        )
      } catch (error: Throwable) {
        0
      }
      if (enabledFlag != 1) return false

      val services = try {
        Settings.Secure.getString(
          context.contentResolver,
          Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
      } catch (error: Throwable) {
        null
      } ?: return false

      val shortName = component.flattenToShortString()
      val fullName = component.flattenToString()
      return services.split(':').any { entry ->
        entry.equals(shortName, ignoreCase = true) || entry.equals(fullName, ignoreCase = true)
      }
    }
  }
}
