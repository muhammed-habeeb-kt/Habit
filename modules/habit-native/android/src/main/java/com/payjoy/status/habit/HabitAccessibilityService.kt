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
  /** Throttles the zero-power text trap, which runs on every accepted event. */
  private var lastTextTrapPackage: String? = null
  private var lastTextTrapAt = 0L
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
      AccessibilityEvent.TYPE_VIEW_CLICKED,
      AccessibilityEvent.TYPE_VIEW_SCROLLED -> Unit
      else -> return
    }

    val pkg = safeEvent.packageName?.toString() ?: return

    // Zero-power tamper shield: a settings / security screen that would switch Habit's
    // accessibility service off, force stop it, clear its data or drop the device admin is
    // kicked out before the user can reach the control. No screenshot, no model involved.
    if (scanTamperShield(pkg, safeEvent)) return

    // A scroll, a typed word or an app switch means a new frame can be in view, so the
    // relaxed battery interval is dropped before it can hide content the user just opened.
    if (safeEvent.eventType in INTERACTION_EVENTS) AiVisualScanner.noteUserActivity()

    // Watched apps and watched websites are guarded by the screenshot cascade, not by text
    // or URL scanning. Every event is offered to the router, not just a window-state change:
    // Telegram can become visible through a notification, a share sheet or a split-screen
    // resume without ever emitting TYPE_WINDOW_STATE_CHANGED, and the loop has to arm the
    // moment the user is inside a monitored app or site. The router returns immediately once
    // the loop is running, so the extra events cost nothing, and it stops the loop as soon
    // as another app or page is in front.
    //
    // This runs before the browser page scan below on purpose: arming is asynchronous, and
    // if the instant adult blocker matches in this same event it blocks the page and stops
    // the cascade, so the browser verdict always wins over a late AI screenshot.
    updateAiScanner(pkg)

    // Zero-power trap: a watched app that already shows an adult indicator as text blocks
    // right here, before any screenshot or model runs.
    scanWatchedAppText(pkg)

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

  /**
   * Routes the foreground app: start the cascade for a selected app or for a watched
   * website open in a browser, and stop it everywhere else.
   */
  private fun updateAiScanner(pkg: String) {
    try {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

      // SystemUI is the notification shade / volume panel. It is not a different app, and
      // pulling the shade down inside a watched app is exactly how the user reads the live
      // AI line in the notification, so it must not stop the loop.
      if (pkg == SYSTEM_UI_PACKAGE) return

      val browser = pkg in BROWSER_PACKAGES
      // An app loop depends only on its package, so one already running needs no work. A
      // browser loop also depends on the URL, which can change without the package
      // changing, so a browser's host is re-read on every routing pass.
      if (!browser && AiVisualScanner.isRunningFor(pkg)) return

      // Every event comes through here, and TYPE_WINDOW_CONTENT_CHANGED can fire dozens of
      // times a second. The store is only re-read when the package changes or the throttle
      // has passed, so routing stays cheap without ever missing an app or site switch.
      val now = SystemClock.elapsedRealtime()
      val throttle = if (browser) BROWSER_ROUTE_INTERVAL_MS else ROUTE_INTERVAL_MS
      if (pkg == lastRoutedPackage && now - lastRoutedAt < throttle) return
      lastRoutedPackage = pkg
      lastRoutedAt = now

      if (pkg == packageName || pkg == homePackage()) {
        AiVisualScanner.stop()
        return
      }

      val state = HabitStore.state(this)
      val ai = HabitStore.aiSettings(this)
      if (!state.protectionEnabled || !ai.enabled) {
        AiVisualScanner.stop()
        return
      }

      if (browser) {
        val host = currentBrowserHost(pkg)
        if (host == null) {
          // The URL bar empties for a moment while a page loads. Never let that window
          // stop protection: keep a loop that already belongs to this browser.
          if (AiVisualScanner.isRunning && !AiVisualScanner.isRunningForPackage(pkg)) {
            AiVisualScanner.stop()
          }
          return
        }
        // Subdomain-aware: web.telegram.org matches a watched telegram.org or itself.
        val domain = AdultFilter.monitoredDomainFor(host, ai.monitoredDomains)
        if (domain == null) {
          // Navigated to a site that is not watched: scanning stops with the page.
          if (AiVisualScanner.isRunning) AiVisualScanner.stop()
          return
        }
        if (!AiVisualScanner.isRunningFor(pkg, domain)) AiVisualScanner.start(this, pkg, domain)
        return
      }

      if (pkg in ai.monitoredAppPackages) {
        if (!AiVisualScanner.isRunningFor(pkg)) AiVisualScanner.start(this, pkg)
      } else if (AiVisualScanner.isRunning) {
        AiVisualScanner.stop()
      }
    } catch (error: Throwable) {
      Log.w(TAG, "AI scanner routing failed: ${error.message}")
    }
  }

  /**
   * Host currently in the browser's URL bar, or null while the toolbar is empty, hidden or
   * still loading. Only the URL bar is read; the window title is the page title, not a URL.
   */
  private fun currentBrowserHost(pkg: String): String? {
    val root = try {
      rootInActiveWindow
    } catch (error: Throwable) {
      null
    } ?: return null
    if (root.packageName?.toString() != pkg) return null

    for (viewId in urlBarIds(pkg)) {
      val host = try {
        root.findAccessibilityNodeInfosByViewId(viewId)?.firstNotNullOfOrNull { node ->
          node.text?.toString()?.let { AdultFilter.hostOf(it) }
        }
      } catch (error: Throwable) {
        null
      }
      if (host != null) return host
    }
    return null
  }

  /**
   * Zero-power text trap for watched apps.
   *
   * A screenshot arrives at most once a second, but a channel list, a forwarded caption or
   * a search box shows the adult indicator as text the moment it appears. Reading the
   * visible node text takes well under a millisecond and needs no capture, permission or
   * model, so the block screen appears instantly instead of after the next frame.
   *
   * Nothing is stored or uploaded: the text is only compared with a fixed keyword list.
   */
  private fun scanWatchedAppText(pkg: String) {
    // Habit's own UI (onboarding, settings, block screen) is never inspected.
    if (pkg == packageName) return
    val now = SystemClock.elapsedRealtime()
    if (pkg == lastTextTrapPackage && now - lastTextTrapAt < TEXT_TRAP_INTERVAL_MS) return
    lastTextTrapPackage = pkg
    lastTextTrapAt = now

    try {
      if (!HabitStore.state(this).protectionEnabled) return
      val ai = HabitStore.aiSettings(this)
      if (!ai.enabled || pkg !in ai.monitoredAppPackages) return
      // A calibration dry-run must not block, exactly like the screenshot cascade.
      if (ai.debugRecordingEnabled && ai.debugDryRun) return

      val root = try {
        rootInActiveWindow
      } catch (error: Throwable) {
        null
      } ?: return
      // Only the window that really belongs to the app in front: the notification shade or
      // Habit's own block overlay must never be read as the app's content.
      if (root.packageName?.toString() != pkg) return

      val builder = StringBuilder()
      try {
        val title = root.window?.title
        if (!title.isNullOrEmpty()) builder.append(title).append(' ')
      } catch (error: Throwable) {
        // window info can disappear mid-traversal
      }
      collectText(root, builder, 0, intArrayOf(TEXT_TRAP_NODE_BUDGET))
      if (builder.isEmpty()) return

      if (adultTextIndicator(builder.toString()) == null) return
      // Stop before the overlay exists so the covered screen is never classified again.
      AiVisualScanner.stop()
      HabitGuard.onAiMatch(this, "$pkg:ai-text", labelFor(pkg))
    } catch (error: Throwable) {
      Log.w(TAG, "AI text trap failed: ${error.message}")
    }
  }

  /**
   * Instant, zero-power tamper shield.
   *
   * While Strict/Aggressive protects the phone, two Settings screens can end protection:
   * Habit's own accessibility toggle (screen A) and Habit's app info / uninstall / device
   * admin screen (screen B). Both are recognised from the accessibility node text of a
   * system settings, vendor security centre or installer window only, so nothing is
   * captured, modelled or uploaded and the whole check is a few hundred node reads.
   *
   * Screen A is always blocked. Screen B is always blocked for a force-stop, clear-data,
   * uninstall or deactivate tap and for their confirmation dialogs. Only the plain app-info
   * page passes while Habit itself just opened the battery or autostart screen (the 12 s
   * [HabitStore.tamperGraceRemainingMs] window), so the "Fix" button can actually work.
   */
  private fun scanTamperShield(pkg: String, event: AccessibilityEvent): Boolean {
    if (pkg !in SETTINGS_PACKAGES) return false
    return try {
      if (!HabitGuard.tamperShieldActive(this)) return false

      val root = try {
        rootInActiveWindow
      } catch (error: Throwable) {
        null
      }
      val rootPackage = root?.packageName?.toString()
      // Only the window really in front: a stale tree from another app is never read as a
      // settings screen.
      if (root == null || rootPackage == null || rootPackage !in SETTINGS_PACKAGES) return false

      val text = tamperScreenText(root, event)
      if (text.isEmpty()) return false
      val clicked = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
      val clickedLabel = if (clicked) tamperClickedLabel(event) else ""
      if (!tamperScreenIsFatal(text, clickedLabel, clicked)) return false

      // Stop the scanner before the overlay exists so the covered screen is never
      // classified, exactly like the browser and AI block flows.
      AiVisualScanner.stop()
      HabitGuard.onTamperDetected(this)
      true
    } catch (error: Throwable) {
      Log.w(TAG, "Tamper shield check failed: ${error.message}")
      false
    }
  }

  /** Lowercased text of the front settings window, including the event's own text. */
  private fun tamperScreenText(root: AccessibilityNodeInfo, event: AccessibilityEvent): String {
    val builder = StringBuilder()
    try {
      root.window?.title?.let { title -> if (title.isNotEmpty()) builder.append(title).append(' ') }
    } catch (error: Throwable) {
      // window info can disappear mid-traversal
    }
    event.text?.forEach { value -> if (!value.isNullOrEmpty()) builder.append(value).append(' ') }
    collectText(root, builder, 0, intArrayOf(TAMPER_NODE_BUDGET))
    return builder.toString().lowercase()
  }

  /** Label of the item the user just tapped, for the click-based triggers. */
  private fun tamperClickedLabel(event: AccessibilityEvent): String {
    val builder = StringBuilder()
    event.text?.forEach { value -> if (!value.isNullOrEmpty()) builder.append(value).append(' ') }
    try {
      event.source?.let { node ->
        node.text?.let { if (it.isNotEmpty()) builder.append(it).append(' ') }
        node.contentDescription?.let { if (it.isNotEmpty()) builder.append(it).append(' ') }
      }
    } catch (error: Throwable) {
      // the node can be gone by the time the event is delivered
    }
    return builder.toString().lowercase().trim()
  }

  /**
   * True when the visible settings screen would let the user end protection. Split out so
   * every rule is readable in one place; [HabitGuard.tamperShieldActive] already decided
   * whether the shield applies at all.
   */
  private fun tamperScreenIsFatal(
    text: String,
    clickedLabel: String,
    clicked: Boolean
  ): Boolean {
    // Screen A — Habit's own accessibility toggle. Never allowed, not even in the grace
    // window: this is the screen that switches the whole service off.
    if (TAMPER_ACCESSIBILITY_TRIGGERS.any { text.contains(it) }) return true
    if (
      clicked &&
      clickedLabel.startsWith("habit") &&
      TAMPER_ACCESSIBILITY_LISTS.any { text.contains(it) }
    ) {
      return true
    }

    // Screen B — Habit's app info, uninstall and device-admin screens.
    val mentionsHabit = text.contains("habit") ||
      text.contains("com.payjoy.status") ||
      text.contains("com.habit.guardian")
    if (!mentionsHabit) return false

    // Tapping a destructive control is refused immediately, grace or not.
    if (clicked && TAMPER_DANGER_LABELS.any { clickedLabel.contains(it) }) return true
    // A destructive confirmation dialog is refused too: its wording never appears on the
    // plain app-info page the battery "Fix" button needs.
    if (TAMPER_CONFIRM_TEXT.any { text.contains(it) }) return true

    // Outside the battery / autostart stand-down, merely showing those controls is enough.
    if (HabitStore.tamperGraceRemainingMs(this) > 0L) return false
    return TAMPER_DANGER_LABELS.any { text.contains(it) }
  }

  /**
   * First indicator found in [text], or null. Matched case-insensitively as a substring, so
   * `Kambi`, `kambi_story` and `kambikatha` all hit, and the emoji / Malayalam words are
   * matched exactly as written.
   */
  private fun adultTextIndicator(text: String): String? {
    val lower = text.lowercase()
    return AI_TEXT_INDICATORS.firstOrNull { lower.contains(it) }
  }

  @Suppress("DEPRECATION")
  private fun labelFor(pkg: String): String = try {
    val info = packageManager.getApplicationInfo(pkg, 0)
    packageManager.getApplicationLabel(info).toString().ifBlank { pkg }
  } catch (error: Throwable) {
    pkg
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
    /** Browsers re-read their URL bar on this cadence so a site change stops scanning fast. */
    private const val BROWSER_ROUTE_INTERVAL_MS = 500L
    private const val TEXT_TRAP_INTERVAL_MS = 250L
    private const val MAX_DEPTH = 10
    private const val NODE_BUDGET = 220
    private const val TEXT_TRAP_NODE_BUDGET = 160
    private const val TAMPER_NODE_BUDGET = 220
    private const val MAX_TEXT_LENGTH = 5000

    /** The only packages the tamper shield ever reads: settings, vendor security, installer. */
    private val SETTINGS_PACKAGES = setOf(
      "com.android.settings",
      "com.miui.securitycenter",
      "com.google.android.packageinstaller",
      "com.miui.packageinstaller"
    )

    /** Screen A: any of these means the accessibility detail screen is open. */
    private val TAMPER_ACCESSIBILITY_TRIGGERS = listOf(
      "use habit",
      "habit shortcut",
      "habit blocks adult websites"
    )

    /** Screen A: the lists a tap on the "Habit" item can come from. */
    private val TAMPER_ACCESSIBILITY_LISTS = listOf(
      "accessibility",
      "downloaded apps",
      "downloaded services"
    )

    /** Screen B: controls that end protection from Android Settings. */
    private val TAMPER_DANGER_LABELS = listOf(
      "force stop",
      "clear data",
      "clear all data",
      "uninstall",
      "remove",
      "deactivate"
    )

    /** Screen B: destructive confirmation dialogs, refused even during the grace window. */
    private val TAMPER_CONFIRM_TEXT = listOf(
      "force stop an app",
      "clear app data",
      "all of this app's data",
      "uninstall this app",
      "uninstall habit",
      "deactivate this device admin"
    )

    /**
     * Adult indicators a watched app shows as text (channel titles, captions, search text).
     * Matched case-insensitively as substrings, so transliterations such as `kambikatha`
     * are caught without a word boundary.
     */
    private val AI_TEXT_INDICATORS = listOf(
      "🔞",
      "കാമം",
      "തുണ്ട്",
      "കമ്പി",
      "നീലതടാകം",
      "kambi",
      "thund",
      "pooru",
      "mula",
      "kunna",
      "vedi",
      "mallu hot",
      "desi leaked",
      "onlyfans",
      "nsfw",
      "xxx",
      "porn",
      "nude",
      "sex video",
      "ullu",
      "altbalaji"
    )

    /**
     * Events where the user actually touched, typed, scrolled or switched something. The
     * noisy TYPE_WINDOW_CONTENT_CHANGED (a video, a chat, a seek bar) is deliberately left
     * out so a long safe movie can relax the capture interval.
     */
    private val INTERACTION_EVENTS = setOf(
      AccessibilityEvent.TYPE_VIEW_SCROLLED,
      AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
      AccessibilityEvent.TYPE_WINDOWS_CHANGED
    )

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
