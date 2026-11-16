package com.payjoy.status

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat

/**
 * Watches browser windows and blocks adult pages.
 *
 * Only the URL bar of a page that actually loaded is read for the adult filter, and a
 * private tab is caught from browser chrome labels and native view ids — never from a
 * screenshot. Nothing is stored except a per-day counter.
 */
class HabitAccessibilityService : AccessibilityService() {
  private var lastScanAt = 0L
  /**
   * Every browser installed on the phone, discovered through PackageManager instead of a
   * hardcoded list, so Brave, DuckDuckGo, Vivaldi, Kiwi, UC, Tor and anything else that
   * handles an http VIEW intent is guarded. Refreshed when a package is installed, removed
   * or changed, and once per service connection.
   */
  @Volatile private var discoveredBrowsers: Set<String> = emptySet()
  private var browserPackageReceiver: BroadcastReceiver? = null
  /**
   * The browser the user was last seen in. A private window is FLAG_SECURE and can refuse
   * its accessibility root, so `service.windows` / `rootInActiveWindow` may both look empty
   * while a private tab is exactly what is on screen. This latch keeps a queued probe
   * pointed at that browser. Any event from another real app clears it, so a probe never
   * judges a different app's secure window.
   */
  @Volatile private var lastBrowserFrontPackage: String? = null
  /** Throttles the AI router: content-changed events arrive many times per second. */
  private var lastRoutedPackage: String? = null
  private var lastRoutedAt = 0L
  /**
   * The app the user is really in, latched across transient overlays (notification shade,
   * volume panel, soft keyboard). Once a monitored app is in front it keeps the 1 s loop
   * alive until a different non-monitored app or the launcher takes over.
   */
  @Volatile private var currentForegroundAppPackage: String? = null
  /** Last watched website matched in a browser tab, kept while the URL bar is hidden. */
  @Volatile private var latchedBrowserDomain: String? = null
  @Volatile private var latchedBrowserPackage: String? = null
  /** The launcher package never changes while the service runs. */
  @Volatile private var cachedHomePackage: String? = null
  /** Drives the continuous 100 ms Settings poll; idle whenever Settings is not in front. */
  private val tamperHandler = Handler(Looper.getMainLooper())
  private var tamperPolling = false
  private var tamperFollowUpsScheduled = false
  /**
   * True while the open Settings page belongs to Habit (its App info / Storage screen), so a
   * destructive tap or a dialog there can be told apart from another app's settings.
   */
  @Volatile private var isHabitAppInfoContext = false
  private var habitAppInfoContextAt = 0L

  override fun onServiceConnected() {
    super.onServiceConnected()
    try {
      HabitStore.init(this)
      HabitStore.ensureFilter(this)
      // Delete any debug frames/CSV left by an older calibration build, even when the Habit
      // UI is never opened.
      HabitStore.clearAiDebugSessionDir(this)
      HabitGuard.attach(this)
      // Starts the keep-alive only when protection (or an unlock timer) needs it.
      HabitUnlock.resumeAfterBoot(this)
      // If Habit was enabled while a watched app was already open, no window change
      // will arrive, so arm the cascade from the current window once.
      rootInActiveWindow?.packageName?.toString()?.let {
        updateAiScanner(it, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
      }
      // Same for the shield: a service switched on while a Settings page is already open
      // gets no event at all, so the watch is armed from the window in front.
      if (foregroundPackage()?.let { isSettingsPackage(it) } == true) armTamperWatch()
      // Same idea for private tabs: a browser that was already showing a private page when
      // the service connected emits no fresh event, so its chrome is inspected once here.
      refreshBrowserPackages()
      registerBrowserPackageReceiver()
      noteBrowserFront(foregroundPackage() ?: "")
      browserFrontPackage()?.let { handleBrowserFront(it) }
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

    // Remember (or forget) which browser is in front before anything else: a private
    // window can hide its accessibility root, and the watchdog still has to probe it.
    noteBrowserFront(pkg)

    // Tamper shield: Android Settings, the vendor security centre and the installers are
    // read on every event with no throttle at all, and the moment one of them is in front
    // the 100 ms poll is armed. HyperOS finishes rendering the accessibility page *after*
    // its own events are over, so waiting for another event (or for the 180 ms page
    // throttle below) is exactly how the "Use Habit" switch used to be missed.
    //
    // An event can also be attributed to another package (SystemUI, the launcher) while a
    // Settings page is already in front, so the front window is checked on every event too;
    // the shield then arms from that window instead of waiting for a Settings event.
    val settingsEvent = isSettingsPackage(pkg)
    if (settingsEvent || foregroundPackage()?.let { isSettingsPackage(it) } == true) {
      if (scanTamperWindows(if (settingsEvent) safeEvent else null)) return
      armTamperWatch()
    }

    // Incognito / Private / Secret / InPrivate tabs have no readable URL and must never
    // render at all, so this runs before the URL scan. A tap that opens private mode and
    // browser chrome that only exists inside one are the two signals: an open ⋮ menu, a
    // normal home page and a page that merely contains the word are never matched.
    if (isBrowserPackage(pkg) && handlePrivateTabEvent(safeEvent, pkg)) return

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
    updateAiScanner(pkg, safeEvent.eventType)

    if (pkg == packageName) return
    if (!isBrowserPackage(pkg)) return

    val now = SystemClock.elapsedRealtime()
    if (now - lastScanAt < SCAN_INTERVAL_MS) return
    lastScanAt = now

    try {
      if (!HabitStore.state(this).protectionEnabled) {
        HabitGuard.hideOverlay()
        return
      }
      scanPage(pkg, safeEvent)
    } catch (error: Throwable) {
      Log.w(TAG, "scan failed: ${error.message}")
    }
  }

  override fun onInterrupt() = Unit

  /**
   * Routes the cascade: a selected app or watched website keeps the loop alive wherever it
   * is on screen — full screen, split screen or a floating window.
   *
   * Only an explicit foreground switch (TYPE_WINDOW_STATE_CHANGED to the launcher, Habit or
   * another unselected app) stops a healthy loop. A momentary empty `service.windows` or a
   * null `rootInActiveWindow` while a video plays is not an app switch, so it never kills
   * the scanner.
   */
  private fun updateAiScanner(eventPackage: String, eventType: Int? = null) {
    try {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

      // SystemUI (shade / volume panel), soft keyboards and Habit itself are transient
      // overlays, never a different app. Resolving and latching the real foreground package
      // keeps the loop alive behind them instead of stopping protection while the user is
      // still inside a watched app or site.
      val pkg = resolveForegroundPackage(eventPackage) ?: return
      val browser = isBrowserPackage(pkg)
      // An explicit app switch is the ONLY thing that may stop a healthy loop.
      val appSwitch = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

      // Every event comes through here, and TYPE_WINDOW_CONTENT_CHANGED can fire dozens of
      // times a second. Routing is only re-run when the package changes or the throttle has
      // passed, so it stays cheap while still refreshing a moved floating window.
      val now = SystemClock.elapsedRealtime()
      val throttle = if (browser) BROWSER_ROUTE_INTERVAL_MS else ROUTE_INTERVAL_MS
      if (pkg == lastRoutedPackage && now - lastRoutedAt < throttle) return
      lastRoutedPackage = pkg
      lastRoutedAt = now

      val state = HabitStore.state(this)
      val ai = HabitStore.aiSettings(this)
      if (!state.protectionEnabled || !ai.enabled) {
        clearBrowserLatch()
        AiVisualScanner.stop()
        return
      }

      // Split-screen and floating windows first: the watched app or site can be visible
      // while the launcher or another app owns the focus, and this window's rectangle is
      // what the scanner crops to.
      val watched = watchedWindow(ai)
      if (watched != null) {
        AiVisualScanner.start(this, watched.packageName, watched.domain, watched.bounds)
        return
      }

      if (pkg == packageName || pkg == homePackage()) {
        if (appSwitch) {
          clearBrowserLatch()
          AiVisualScanner.stop()
        }
        return
      }

      if (browser) {
        val host = currentBrowserHost(pkg)
        if (host == null) {
          // The URL bar empties for a moment while a page loads or while the browser hides
          // it during scrolling / video playback. Never clear a watched website here: keep
          // the latched domain (or the loop that already belongs to this browser) and keep
          // scanning every 1000 ms until a new non-matching URL is actually read or the
          // user leaves the browser.
          val latched = if (latchedBrowserPackage == pkg) latchedBrowserDomain else null
          val fallback = latched ?: if (AiVisualScanner.isRunningForPackage(pkg)) {
            AiVisualScanner.runningDomain()
          } else {
            null
          }
          if (fallback != null) {
            latchedBrowserPackage = pkg
            latchedBrowserDomain = fallback
            if (!AiVisualScanner.isRunningFor(pkg, fallback)) {
              AiVisualScanner.start(this, pkg, fallback)
            }
          } else if (AiVisualScanner.isRunning && !AiVisualScanner.isRunningForPackage(pkg)) {
            AiVisualScanner.stop()
          }
          return
        }
        // Flexible host matching: a watched telegram.org covers web.telegram.org,
        // k.telegram.org and t.me, and vice versa.
        val domain = AdultFilter.monitoredDomainFor(host, ai.monitoredDomains)
        if (domain == null) {
          // A real, different URL that is not watched: scanning stops with the page.
          clearBrowserLatch()
          if (AiVisualScanner.isRunning) AiVisualScanner.stop()
          return
        }
        latchedBrowserPackage = pkg
        latchedBrowserDomain = domain
        if (!AiVisualScanner.isRunningFor(pkg, domain)) AiVisualScanner.start(this, pkg, domain)
        return
      }

      // Rock-solid foreground latch: once a monitored app is in front, the loop keeps
      // running even while `service.windows` or `rootInActiveWindow` momentarily return
      // null (video playback, window transitions). Missing windows are not an app switch.
      if (pkg in ai.monitoredAppPackages) {
        if (!AiVisualScanner.isRunningFor(pkg)) AiVisualScanner.start(this, pkg)
        return
      }

      // Another non-overlay app. Only an explicit foreground switch may stop the loop;
      // content changes and missing window roots never do.
      if (appSwitch) {
        clearBrowserLatch()
        AiVisualScanner.stop()
      }
    } catch (error: Throwable) {
      Log.w(TAG, "AI scanner routing failed: ${error.message}")
    }
  }

  /**
   * Incognito / private-tab guard, three layers, all read from the accessibility node tree.
   *
   * Layer 1 — a real tap on a private-mode entry point in browser chrome (`⋮` ->
   * "New Incognito tab", Brave's "New private tab", Samsung's secret-mode toggle, the
   * Incognito chip on Chrome's home screen). The `⋮` menu merely being open is not a tap on
   * a row, and a page is never inspected for labels, so normal browsing is untouched.
   *
   * Layer 2 — browser chrome that only exists while private mode is already on: the native
   * empty private-tab sentences, "close all incognito tabs", "turn off secret mode", and
   * the native private view ids / selected private tab-model node.
   *
   * Layer 3 — browsers with no normal mode at all (Firefox Focus, Tor Browser).
   *
   * No screenshot, no pixel check and no polling: everything below runs from events Android
   * already delivers to this service.
   *
   * This matters because a signed-out private tab shows no URL to the adult filter: the
   * normal page scan sees an empty toolbar and lets it through.
   */
  private fun handlePrivateTabEvent(event: AccessibilityEvent, pkg: String): Boolean {
    if (!incognitoGuardNeeded()) return false

    // Firefox Focus and Tor Browser have no normal mode: every screen in them is private.
    if (pkg in ALWAYS_PRIVATE_BROWSERS) {
      blockPrivateTab()
      return true
    }

    return when (event.eventType) {
      AccessibilityEvent.TYPE_VIEW_CLICKED ->
        handlePrivateModeClick(event, pkg) || scanPrivateModeWindows(pkg)
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
      AccessibilityEvent.TYPE_WINDOWS_CHANGED -> scanPrivateModeWindows(pkg)
      else -> false
    }
  }

  /**
   * Layer 1: did this tap ask for private mode?
   *
   * Chrome and Brave show their toolbar menu in a popup window, and the click that arrives
   * from a row inside it frequently carries no source node at all, so the event's own text
   * and contentDescription are always read first. A source node, when there is one, only
   * proves the tap was browser chrome: a tap inside the page (a link, a form, a video) is
   * never inspected for labels.
   */
  private fun handlePrivateModeClick(event: AccessibilityEvent, pkg: String): Boolean {
    val source = try {
      event.source
    } catch (error: Throwable) {
      null
    }
    // A tap inside the page can never open a private tab.
    if (source != null && insideWebView(source)) return false

    val labels = ArrayList<CharSequence>(8)
    collectEventLabels(event, labels)
    source?.let { collectNodeLabels(it, labels) }

    for (label in labels) {
      if (matchesPrivateActionLabel(label)) {
        Log.i(TAG, "Private-mode tap in $pkg")
        blockPrivateTab()
        return true
      }
    }
    return false
  }

  /** The clicked event's own labels; these exist even when its source node does not. */
  private fun collectEventLabels(event: AccessibilityEvent, out: MutableList<CharSequence>) {
    try {
      val texts = event.text
      if (texts != null) {
        for (item in texts) {
          if (item != null && item.isNotEmpty()) out.add(item)
        }
      }
    } catch (error: Throwable) {
      // Event text is best effort only.
    }
    try {
      event.contentDescription?.let { if (it.isNotEmpty()) out.add(it) }
    } catch (error: Throwable) {
      // The event description is best effort only.
    }
  }

  /** A tapped node's own labels plus its direct children's (a menu row holds the text). */
  private fun collectNodeLabels(node: AccessibilityNodeInfo, out: MutableList<CharSequence>) {
    try {
      node.text?.let { if (it.isNotEmpty()) out.add(it) }
      node.contentDescription?.let { if (it.isNotEmpty()) out.add(it) }
    } catch (error: Throwable) {
      // Node labels are best effort only.
    }
    val children = try {
      node.childCount
    } catch (error: Throwable) {
      0
    }
    for (index in 0 until minOf(children, CLICK_CHILD_LIMIT)) {
      val child = try {
        node.getChild(index)
      } catch (error: Throwable) {
        null
      } ?: continue
      try {
        child.text?.let { if (it.isNotEmpty()) out.add(it) }
        child.contentDescription?.let { if (it.isNotEmpty()) out.add(it) }
      } catch (error: Throwable) {
        // A recycled child is simply skipped.
      }
    }
  }

  /**
   * Layer 2: is private mode on screen right now?
   *
   * One capped walk over the browser's own windows, stopping at every WebView / GeckoView so
   * page text is never read. Only labels and native view ids that exist solely while private
   * mode is on are matched, which is why Chrome's home "Incognito" chip and Brave's "New
   * private tab" menu row cannot trigger it.
   */
  private fun scanPrivateModeWindows(pkg: String): Boolean {
    val roots = browserRoots(pkg)
    if (roots.isEmpty()) return false
    val budget = intArrayOf(PRIVATE_WALK_LIMIT)
    for (root in roots) {
      if (walkPrivateModeWindow(root, 0, budget)) {
        Log.i(TAG, "Private mode on screen in $pkg")
        blockPrivateTab()
        return true
      }
      if (budget[0] <= 0) break
    }
    return false
  }

  private fun walkPrivateModeWindow(
    node: AccessibilityNodeInfo,
    depth: Int,
    budget: IntArray
  ): Boolean {
    if (budget[0] <= 0 || depth > PRIVATE_WALK_DEPTH) return false
    budget[0] -= 1

    val className = try {
      node.className?.toString() ?: ""
    } catch (error: Throwable) {
      ""
    }
    // Page content is never read: private-mode labels live in browser chrome only.
    if (isWebViewClass(className)) return false

    val visible = try {
      node.isVisibleToUser
    } catch (error: Throwable) {
      false
    }
    if (visible) {
      val labelMatches = try {
        matchesPrivateStateLabel(node.text) || matchesPrivateStateLabel(node.contentDescription)
      } catch (error: Throwable) {
        false
      }
      if (labelMatches) return true

      val viewId = try {
        node.viewIdResourceName
      } catch (error: Throwable) {
        null
      }
      if (viewId != null && isPrivateViewId(viewId)) return true

      // A tab-model selector only counts while it reports the private state, so a visible
      // but unselected "Incognito tabs" button on a normal tab switcher is not a hit.
      val selected = try {
        node.isSelected || node.isChecked
      } catch (error: Throwable) {
        false
      }
      if (selected) {
        if (viewId != null && isPrivateTabSelector(viewId)) return true
        val description = normalizeLabel(
          try {
            node.contentDescription
          } catch (error: Throwable) {
            null
          }
        )
        if (description == "incognito tabs" || description == "private tabs") return true
      }
    }

    val children = try {
      node.childCount
    } catch (error: Throwable) {
      0
    }
    for (index in 0 until minOf(children, PRIVATE_CHILD_LIMIT)) {
      val child = try {
        node.getChild(index)
      } catch (error: Throwable) {
        null
      } ?: continue
      if (walkPrivateModeWindow(child, depth + 1, budget)) return true
    }
    return false
  }

  private fun matchesPrivateActionLabel(raw: CharSequence?): Boolean =
    normalizeLabel(raw)?.let { it in PRIVATE_ACTION_LABELS } == true

  private fun matchesPrivateStateLabel(raw: CharSequence?): Boolean =
    normalizeLabel(raw)?.let { it in PRIVATE_STATE_LABELS } == true

  /**
   * Lowercase, collapsed whitespace, no trailing ellipsis or arrow, so a menu row rendered
   * as "New private tab…" still matches its label.
   */
  private fun normalizeLabel(raw: CharSequence?): String? {
    if (raw == null) return null
    val cleaned = raw.toString()
      .replace('\u00A0', ' ')
      .trim()
      .trimEnd('…', '.', '›', '>', ':')
      .trim()
      .lowercase()
    if (cleaned.isEmpty()) return null
    return cleaned.replace(WHITESPACE, " ")
  }

  /** A native view that only exists while the browser is in private mode. */
  private fun isPrivateViewId(viewId: String): Boolean {
    val lower = viewId.lowercase()
    return PRIVATE_VIEW_IDS.any { suffix -> lower.endsWith(":id/$suffix") }
  }

  private fun isPrivateTabSelector(viewId: String): Boolean {
    val lower = viewId.lowercase()
    return PRIVATE_TAB_SELECTOR_IDS.any { suffix -> lower.endsWith(":id/$suffix") }
  }

  /** One state scan for the browser already in front (service connect / switch turned on). */
  private fun handleBrowserFront(pkg: String) {
    if (!incognitoGuardNeeded()) return
    if (pkg in ALWAYS_PRIVATE_BROWSERS) {
      blockPrivateTab()
      return
    }
    scanPrivateModeWindows(pkg)
  }

  private fun blockPrivateTab() {
    AiVisualScanner.stop()
    HabitGuard.onIncognitoDetected(this)
  }

  /** True when [start] or any ancestor is a webpage view, never native browser chrome. */
  private fun insideWebView(start: AccessibilityNodeInfo): Boolean {
    var node: AccessibilityNodeInfo? = start
    var level = 0
    while (node != null && level <= WEBVIEW_ANCESTOR_DEPTH) {
      val className = try {
        node.className?.toString() ?: ""
      } catch (error: Throwable) {
        ""
      }
      if (isWebViewClass(className)) return true
      node = try {
        node.parent
      } catch (error: Throwable) {
        null
      }
      level += 1
    }
    return false
  }

  private fun isWebViewClass(className: String): Boolean {
    if (className.isEmpty()) return false
    val lower = className.lowercase()
    return lower.contains("webview") ||
      lower.contains("geckoview") ||
      lower.contains("webviewchromium")
  }

  /**
   * Re-checks the private-tab guard right now. The UI calls this when the switch is turned
   * on while a browser is already open, because a settings change emits no accessibility
   * event at all.
   */
  fun refreshIncognitoGuard() {
    try {
      if (!incognitoGuardNeeded()) return
      val pkg = browserFrontPackage() ?: return
      handleBrowserFront(pkg)
    } catch (error: Throwable) {
      Log.w(TAG, "Incognito refresh failed: ${error.message}")
    }
  }

  /** Protection and the incognito switch are both required before any work is done. */
  private fun incognitoGuardNeeded(): Boolean = try {
    HabitStore.state(this).protectionEnabled &&
      HabitStore.aiSettings(this).incognitoBlockingEnabled
  } catch (error: Throwable) {
    Log.w(TAG, "Incognito state unreadable: ${error.message}")
    false
  }

  /**
   * True for every installed app that can open a web page: the known browsers plus every
   * package PackageManager resolved for an http VIEW intent. This is what makes private-tab
   * blocking browser-agnostic instead of an eight-package allowlist.
   */
  private fun isBrowserPackage(pkg: String): Boolean =
    pkg in BROWSER_PACKAGES || pkg in discoveredBrowsers

  /** Caches the installed browsers; called on service connect and on package changes. */
  private fun refreshBrowserPackages() {
    try {
      val probe = Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.com"))
      val found = HashSet<String>()
      @Suppress("DEPRECATION")
      val resolved = packageManager.queryIntentActivities(probe, PackageManager.MATCH_ALL)
      for (info in resolved) {
        info.activityInfo?.packageName?.let { found.add(it) }
      }
      // An empty answer means Android hid the list (a stale <queries> declaration); keep the
      // previous cache rather than silently losing every discovered browser.
      if (found.isNotEmpty()) discoveredBrowsers = found
    } catch (error: Throwable) {
      Log.w(TAG, "Browser discovery failed: ${error.message}")
    }
  }

  private fun registerBrowserPackageReceiver() {
    if (browserPackageReceiver != null) return
    val filter = IntentFilter().apply {
      addAction(Intent.ACTION_PACKAGE_ADDED)
      addAction(Intent.ACTION_PACKAGE_REMOVED)
      addAction(Intent.ACTION_PACKAGE_CHANGED)
      addDataScheme("package")
    }
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        refreshBrowserPackages()
      }
    }
    try {
      ContextCompat.registerReceiver(
        this,
        receiver,
        filter,
        ContextCompat.RECEIVER_NOT_EXPORTED
      )
      browserPackageReceiver = receiver
    } catch (error: Throwable) {
      Log.w(TAG, "Browser package receiver unavailable: ${error.message}")
    }
  }

  private fun unregisterBrowserPackageReceiver() {
    val receiver = browserPackageReceiver ?: return
    browserPackageReceiver = null
    try {
      unregisterReceiver(receiver)
    } catch (error: Throwable) {
      Log.w(TAG, "Browser package receiver already gone: ${error.message}")
    }
  }

  /**
   * The browser in front right now: the focused browser window first, otherwise the browser
   * named by the latest browser event. A private window may expose no readable root at all,
   * so the event latch is the fallback that keeps the probe running exactly when it matters.
   */
  private fun browserFrontPackage(): String? {
    foregroundPackage()?.let { front -> if (isBrowserPackage(front)) return front }
    val currentWindows = try {
      windows
    } catch (error: Throwable) {
      null
    } ?: return lastBrowserFrontPackage
    for (window in currentWindows) {
      if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
      val root = try {
        window.root
      } catch (error: Throwable) {
        null
      } ?: continue
      val windowPackage = try {
        root.packageName?.toString()
      } catch (error: Throwable) {
        null
      } ?: continue
      if (isBrowserPackage(windowPackage)) return windowPackage
    }
    return lastBrowserFrontPackage
  }

  /**
   * Notes the app the latest event came from. A browser is remembered; any other real app
   * (Habit's own UI included) clears the latch, so the watchdog never probes the display
   * after the user left the browser.
   */
  private fun noteBrowserFront(eventPackage: String) {
    if (isBrowserPackage(eventPackage)) {
      lastBrowserFrontPackage = eventPackage
      return
    }
    if (eventPackage == packageName) {
      lastBrowserFrontPackage = null
      return
    }
    if (eventPackage.isEmpty() || isTransientOverlay(eventPackage)) return
    lastBrowserFrontPackage = null
  }

  /** Every window root that belongs to the browser currently in front. */
  private fun browserRoots(pkg: String): List<AccessibilityNodeInfo> {
    val roots = ArrayList<AccessibilityNodeInfo>()
    try {
      windows?.forEach { window ->
        val root = try {
          window.root
        } catch (error: Throwable) {
          null
        } ?: return@forEach
        val rootPackage = try {
          root.packageName?.toString()
        } catch (error: Throwable) {
          null
        }
        if (rootPackage == pkg && roots.none { it == root }) roots.add(root)
      }
    } catch (error: Throwable) {
      Log.w(TAG, "Incognito window list unavailable: ${error.message}")
    }
    val active = try {
      rootInActiveWindow
    } catch (error: Throwable) {
      null
    }
    if (active != null) {
      val activePackage = try {
        active.packageName?.toString()
      } catch (error: Throwable) {
        null
      }
      if (activePackage == pkg && roots.none { it == active }) roots.add(active)
    }
    return roots
  }

  /**
   * Host currently in the browser's URL bar, or null while the toolbar is empty, hidden or
   * still loading. Only the URL bar is read; the window title is the page title, not a URL.
   *
   * Used to arm the watched-website AI scanner, where a search page is a legitimate place
   * to scan. The adult blocklist uses [currentNavigatedHost] instead, which refuses a query.
   */
  private fun currentBrowserHost(pkg: String): String? =
    browserUrlBarTexts(pkg).firstNotNullOfOrNull { text -> AdultFilter.hostOf(text) }

  /**
   * Host of the page the browser actually navigated to, or null while the toolbar shows a
   * search query, an empty field or nothing at all. Only this value may decide an adult
   * block: a query the user is typing or searching with is never a navigation target.
   */
  private fun currentNavigatedHost(pkg: String): String? =
    browserUrlBarTexts(pkg).firstNotNullOfOrNull { text -> AdultFilter.navigatedHost(text) }

  /**
   * URL bar texts of every window of [pkg], the active root first.
   *
   * AccessibilityWindowInfo exposes no visibility flag; a readable root is the only public
   * signal, so a window that cannot be rooted is skipped. A split or floating browser still
   * reports the address of the page it shows.
   */
  private fun browserUrlBarTexts(pkg: String): List<String> {
    val texts = ArrayList<String>(2)
    val active = try {
      rootInActiveWindow
    } catch (error: Throwable) {
      null
    }
    urlBarTextOf(active, pkg)?.let { texts.add(it) }
    try {
      windows?.forEach { window ->
        val root = try {
          window.root
        } catch (error: Throwable) {
          null
        } ?: return@forEach
        val rootPackage = try {
          root.packageName?.toString()
        } catch (error: Throwable) {
          null
        } ?: return@forEach
        if (rootPackage != pkg) return@forEach
        urlBarTextOf(root, pkg)?.let { text -> if (text !in texts) texts.add(text) }
      }
    } catch (error: Throwable) {
      Log.w(TAG, "browser window list unavailable: ${error.message}")
    }
    return texts
  }

  /**
   * True while the browser's own URL / search bar holds the input focus, i.e. the user is
   * typing an address or a query. Nothing is blocked in that state: the bar holds a
   * fragment of text ("xhamster" on the way to a search or a domain) rather than a page.
   */
  private fun browserUrlBarFocused(pkg: String): Boolean {
    for (root in browserRoots(pkg)) {
      for (viewId in urlBarIds(pkg)) {
        val focused = try {
          root.findAccessibilityNodeInfosByViewId(viewId)?.any { node -> node.isFocused }
        } catch (error: Throwable) {
          null
        }
        if (focused == true) return true
      }
      // Browsers without a known URL-bar id: any focused text field in the browser's own
      // chrome is that browser's address / search bar; a field inside a page is in a WebView.
      val focused = try {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
      } catch (error: Throwable) {
        null
      } ?: continue
      val className = try {
        focused.className?.toString() ?: ""
      } catch (error: Throwable) {
        ""
      }
      if (!className.lowercase().contains("edittext")) continue
      if (!insideWebView(focused)) return true
    }
    return false
  }

  /**
   * True for the notification shade, the volume panel, every soft keyboard and Habit's own
   * windows. These must never replace the latched foreground app.
   */
  private fun isTransientOverlay(pkg: String): Boolean =
    pkg == packageName ||
      pkg == SYSTEM_UI_PACKAGE ||
      pkg in TRANSIENT_OVERLAY_PACKAGES ||
      pkg.contains("inputmethod", ignoreCase = true) ||
      pkg.contains("keyboard", ignoreCase = true) ||
      pkg.endsWith(".input", ignoreCase = true)

  /**
   * The app the user is actually in, latched across transient overlays.
   *
   * The event package is the window that just changed, so it wins when it is a real app.
   * When it is SystemUI / a keyboard (or missing), `rootInActiveWindow` is used instead, and
   * finally the previously latched package. Only a non-transient package is ever latched, so
   * pulling down the shade or opening a keyboard can never stop the scanner.
   */
  private fun resolveForegroundPackage(eventPackage: String): String? {
    val eventCandidate = eventPackage.takeIf { it.isNotEmpty() && !isTransientOverlay(it) }
    val rootCandidate = foregroundPackage()?.takeIf { !isTransientOverlay(it) }
    val resolved = eventCandidate ?: rootCandidate ?: currentForegroundAppPackage
    if (resolved != null && resolved != packageName) currentForegroundAppPackage = resolved
    return resolved
  }

  private fun clearBrowserLatch() {
    latchedBrowserPackage = null
    latchedBrowserDomain = null
  }

  /** URL-bar host of one browser window root (the active one, or a split/floating one). */
  private fun browserHostFor(root: AccessibilityNodeInfo?, pkg: String): String? =
    urlBarTextOf(root, pkg)?.let { text -> AdultFilter.hostOf(text) }

  /** Visible text of one browser window's URL bar, or null while it is empty or hidden. */
  private fun urlBarTextOf(root: AccessibilityNodeInfo?, pkg: String): String? {
    if (root == null) return null
    if (root.packageName?.toString() != pkg) return null

    for (viewId in urlBarIds(pkg)) {
      val text = try {
        root.findAccessibilityNodeInfosByViewId(viewId)?.firstNotNullOfOrNull { node ->
          node.text?.toString()?.takeIf { value -> value.isNotBlank() }
        }
      } catch (error: Throwable) {
        null
      }
      if (text != null) return text
    }
    return null
  }

  /** A visible window the screenshot cascade should watch, with its screen rectangle. */
  private data class WatchedWindow(
    val packageName: String,
    val domain: String?,
    val bounds: Rect
  )

  /**
   * The watched window on screen right now, or null when nothing watched is visible.
   *
   * On HyperOS (and stock Android) a monitored app or a watched site can sit in a freeform
   * floating window or a split-screen half while another app, or the launcher, owns the
   * focus. `rootInActiveWindow` then belongs to the *other* app, so every visible window in
   * `service.windows` is inspected instead. The focused window wins; otherwise the largest
   * watched window does. Its exact `getBoundsInScreen` rectangle travels to
   * [AiVisualScanner], which crops to it so the rest of the display is never classified.
   */
  private fun watchedWindow(ai: AiSettings): WatchedWindow? {
    val currentWindows = try {
      windows
    } catch (error: Throwable) {
      Log.w(TAG, "Window list unavailable: ${error.message}")
      null
    } ?: return null

    var largest: WatchedWindow? = null
    var largestArea = 0L
    for (window in currentWindows) {
      // No public visibility flag on AccessibilityWindowInfo: a window with a readable
      // root and real bounds is visible, anything else is skipped.
      if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
      val root = try {
        window.root
      } catch (error: Throwable) {
        null
      } ?: continue
      val windowPackage = try {
        root.packageName?.toString()
      } catch (error: Throwable) {
        null
      } ?: continue
      if (windowPackage == packageName || windowPackage == SYSTEM_UI_PACKAGE) continue

      // A browser is guarded by its website, exactly like the foreground path: being in the
      // app list as well must not turn a whole browser into a watched app.
      val domain: String?
      if (isBrowserPackage(windowPackage)) {
        val host = browserHostFor(root, windowPackage) ?: currentBrowserHost(windowPackage)
        domain = if (host == null) {
          // Private or still-loading page, or a toolbar hidden by scrolling / video: keep
          // the latched watched site (or a loop that already belongs to this browser, fail
          // closed) instead of dropping protection.
          when {
            windowPackage == latchedBrowserPackage && latchedBrowserDomain != null ->
              latchedBrowserDomain
            AiVisualScanner.isRunningForPackage(windowPackage) ->
              AiVisualScanner.runningDomain()
            else -> null
          }
        } else {
          AdultFilter.monitoredDomainFor(host, ai.monitoredDomains)
        }
        if (domain == null) continue
      } else if (windowPackage in ai.monitoredAppPackages) {
        domain = null
      } else {
        continue
      }

      val bounds = Rect()
      try {
        window.getBoundsInScreen(bounds)
      } catch (error: Throwable) {
        continue
      }
      if (bounds.width() < MIN_WATCHED_WINDOW_PX || bounds.height() < MIN_WATCHED_WINDOW_PX) {
        continue
      }

      val candidate = WatchedWindow(windowPackage, domain, bounds)
      val focused = try {
        window.isFocused
      } catch (error: Throwable) {
        false
      }
      if (focused) return candidate
      val area = bounds.width().toLong() * bounds.height().toLong()
      if (area > largestArea) {
        largest = candidate
        largestArea = area
      }
    }
    return largest
  }

  /**
   * One tick of the continuous Settings watch.
   *
   * Returns true while a Settings package is still in front, which is the only thing that
   * keeps the 100 ms loop alive: outside Settings the loop stops on the very next tick, so
   * there is no polling cost anywhere else.
   */
  private fun tamperTick(): Boolean {
    if (!HabitGuard.tamperShieldActive(this)) return false
    if (!settingsInFront()) return false
    scanTamperWindows(null)
    // A hit shows the shield, which then owns the front window; polling has nothing left to
    // cover, and the next Settings event re-arms it if the user comes back.
    return settingsInFront()
  }

  /**
   * Arms the Settings watch: an immediate scan at 0 ms, repeat scans at 80/180/300/500 ms
   * (HyperOS animates a fragment for ~250 ms, so the page that opened after the event needs
   * several looks) and the 100 ms loop until Settings leaves the foreground.
   */
  private fun armTamperWatch() {
    if (!HabitGuard.tamperShieldActive(this)) {
      stopTamperWatch()
      return
    }
    if (!tamperPolling) {
      tamperPolling = true
      tamperHandler.post(tamperPoll)
    }
    if (tamperFollowUpsScheduled) return
    tamperFollowUpsScheduled = true
    TAMPER_FOLLOW_UP_MS.forEachIndexed { index, delay ->
      tamperHandler.postDelayed({
        if (index == TAMPER_FOLLOW_UP_MS.lastIndex) tamperFollowUpsScheduled = false
        tamperTick()
      }, delay)
    }
  }

  private fun stopTamperWatch() {
    tamperPolling = false
    tamperFollowUpsScheduled = false
    tamperHandler.removeCallbacksAndMessages(null)
  }

  private val tamperPoll = object : Runnable {
    override fun run() {
      if (!tamperPolling) return
      if (!tamperTick()) {
        tamperPolling = false
        return
      }
      tamperHandler.postDelayed(this, TAMPER_POLL_MS)
    }
  }

  /**
   * Instant tamper scan of every Settings window on screen.
   *
   * The active root is not enough: "Turn off Habit?" is a separate dialog window, and
   * HyperOS keeps the accessibility list in another window under the detail page, so every
   * interactive window of a Settings package is walked (`service.windows`), text and
   * contentDescription, with no small node budget. Nothing is captured, modelled or
   * uploaded: the whole check is node reads.
   *
   * Rules (all case-insensitive substrings of the visible text):
   *  - the accessibility page or its confirmation dialog by wording;
   *  - a tap on the Habit row of "Downloaded apps" / "Accessibility";
   *  - Habit's device-admin deactivation screen on entry;
   *  - a tap on force stop / uninstall / delete / clear data / clear cache / manage space /
   *    reset while Habit's App info or Storage page is open;
   *  - a destructive confirmation dialog / bottom sheet over that page.
   *
   * Habit's plain App info page is deliberately allowed: the buttons existing on screen is
   * not tampering, and the user needs App permissions, Notifications, Power and the ⋮ menu
   * ("Allow restricted settings"). Only an actual destructive tap or its dialog blocks.
   */
  private fun scanTamperWindows(event: AccessibilityEvent?): Boolean {
    return try {
      if (!HabitGuard.tamperShieldActive(this)) return false

      val clicked = event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
      val clickedLabel = if (clicked && event != null) tamperClickedText(event) else ""

      val activeRoot = try {
        rootInActiveWindow
      } catch (error: Throwable) {
        null
      }
      val roots = settingsRoots(activeRoot)
      if (roots.isEmpty()) {
        isHabitAppInfoContext = false
        return false
      }

      val texts = roots.map { root ->
        tamperWindowText(root, if (root == activeRoot) event else null)
      }
      // Habit's own page is recognised from any open window: the confirmation dialog in
      // front does not always repeat the app name, while the App info page behind it does.
      // The flag is kept for a short moment so a dialog that fully covers the page still
      // counts as Habit's, but it can never leak into another app's settings.
      val ownPackage = packageName
      val mentionsHabit = texts.any { text ->
        text.contains("habit") || text.contains(ownPackage)
      }
      val now = SystemClock.elapsedRealtime()
      if (mentionsHabit) {
        isHabitAppInfoContext = true
        habitAppInfoContextAt = now
      }
      val habitContext = isHabitAppInfoContext &&
        now - habitAppInfoContextAt < HABIT_CONTEXT_TTL_MS
      if (!habitContext) isHabitAppInfoContext = false

      for (text in texts) {
        if (text.isEmpty()) continue
        if (!tamperWindowIsFatal(text, clickedLabel, clicked, habitContext)) continue

        // Stop the scanner before the overlay exists so the covered screen is never
        // classified, exactly like the browser and AI block flows.
        AiVisualScanner.stop()
        HabitGuard.onTamperDetected(this)
        return true
      }
      false
    } catch (error: Throwable) {
      Log.w(TAG, "Tamper shield check failed: ${error.message}")
      false
    }
  }

  /** True while a Settings package is really in front of the user. */
  private fun settingsInFront(): Boolean {
    val rootPackage = try {
      rootInActiveWindow?.packageName?.toString()
    } catch (error: Throwable) {
      null
    }
    if (rootPackage != null) return isSettingsPackage(rootPackage)
    // Android hides the active root for a moment during a fragment or dialog transition;
    // any Settings window still on screen keeps the watch armed instead of dropping it.
    return settingsRoots(null).isNotEmpty()
  }

  /**
   * True for every package the shield reads: the four known Settings / security / installer
   * packages plus vendor builds that ship them under a different name (the `*.settings`
   * variants and the security-centre / installer families are all covered).
   */
  private fun isSettingsPackage(pkg: String): Boolean =
    pkg in SETTINGS_PACKAGES ||
      pkg.contains(".settings") ||
      pkg.contains("securitycenter") ||
      pkg.contains("packageinstaller")

  /** Roots of every window that belongs to a settings, security or installer package. */
  private fun settingsRoots(activeRoot: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
    val roots = ArrayList<AccessibilityNodeInfo>()
    try {
      windows?.forEach { window ->
        val root = try {
          window.root
        } catch (error: Throwable) {
          null
        } ?: return@forEach
        val pkg = try {
          root.packageName?.toString()
        } catch (error: Throwable) {
          null
        }
        if (pkg == null || !isSettingsPackage(pkg)) return@forEach
        if (roots.none { it == root }) roots.add(root)
      }
    } catch (error: Throwable) {
      Log.w(TAG, "tamper window list unavailable: ${error.message}")
    }
    // The active root is not guaranteed to be in the list while a window is animating.
    if (activeRoot != null) {
      val pkg = try {
        activeRoot.packageName?.toString()
      } catch (error: Throwable) {
        null
      }
      if (pkg != null && isSettingsPackage(pkg) && roots.none { it == activeRoot }) {
        roots.add(activeRoot)
      }
    }
    return roots
  }

  /** Lowercased text of one Settings window, including the event's own text. */
  private fun tamperWindowText(root: AccessibilityNodeInfo, event: AccessibilityEvent?): String {
    val builder = StringBuilder()
    try {
      val title = root.window?.title
      if (!title.isNullOrEmpty()) builder.append(title).append(' ')
    } catch (error: Throwable) {
      // window info can disappear mid-traversal
    }
    event?.text?.forEach { value -> if (!value.isNullOrEmpty()) builder.append(value).append(' ') }
    collectTamperText(root, builder, 0, intArrayOf(TAMPER_NODE_BUDGET))
    return builder.toString().lowercase()
  }

  /**
   * Deep walk of a Settings window: every node, both text and contentDescription. The budget
   * is a runaway guard (6 000 nodes, depth 60), not a page-sized cap, so a long list of
   * installed apps can never hide Habit's own row.
   */
  private fun collectTamperText(
    node: AccessibilityNodeInfo?,
    builder: StringBuilder,
    depth: Int,
    budget: IntArray
  ) {
    if (node == null || budget[0] <= 0 || depth > TAMPER_MAX_DEPTH) return
    if (builder.length > TAMPER_MAX_TEXT) return
    budget[0] -= 1
    appendNodeText(node, builder)
    val childCount = try {
      node.childCount
    } catch (error: Throwable) {
      0
    }
    for (index in 0 until childCount) {
      val child = try {
        node.getChild(index)
      } catch (error: Throwable) {
        null
      }
      collectTamperText(child, builder, depth + 1, budget)
    }
  }

  /**
   * Label of the item the user just tapped, read together with its children and its closest
   * parents: a tap on the switch inside the "Habit" row still resolves to that row.
   */
  private fun tamperClickedText(event: AccessibilityEvent): String {
    val builder = StringBuilder()
    event.text?.forEach { value -> if (!value.isNullOrEmpty()) builder.append(value).append(' ') }
    try {
      var node: AccessibilityNodeInfo? = event.source
      var level = 0
      while (node != null && level <= TAMPER_CLICK_LEVELS) {
        appendNodeText(node, builder)
        // The tapped control is often the title or the switch inside the row, so the row and
        // its direct children are read too. Ancestors above that contribute only their own
        // label: reading a list container's children would pull in every row and make a tap
        // on an unrelated app look like a tap on Habit.
        if (level <= 1) {
          val childCount = try {
            node.childCount
          } catch (error: Throwable) {
            0
          }
          for (index in 0 until childCount) {
            val child = try {
              node.getChild(index)
            } catch (error: Throwable) {
              null
            }
            if (child != null) appendNodeText(child, builder)
          }
        }
        node = try {
          node.parent
        } catch (error: Throwable) {
          null
        }
        level += 1
      }
    } catch (error: Throwable) {
      // the node can be gone by the time the event is delivered
    }
    return builder.toString().lowercase().trim()
  }

  private fun appendNodeText(node: AccessibilityNodeInfo, builder: StringBuilder) {
    try {
      node.text?.let { if (it.isNotEmpty()) builder.append(it).append(' ') }
      node.contentDescription?.let { if (it.isNotEmpty()) builder.append(it).append(' ') }
    } catch (error: Throwable) {
      // ignore stale nodes
    }
  }

  /**
   * True when the visible Settings window would let the user end protection. Split out so
   * every rule is readable in one place; [HabitGuard.tamperShieldActive] already decided
   * whether the shield applies at all.
   */
  private fun tamperWindowIsFatal(
    text: String,
    clickedLabel: String,
    clicked: Boolean,
    habitContext: Boolean
  ): Boolean {
    // Screen A — Habit's own accessibility toggle and its "Turn off Habit?" dialog. Never
    // allowed: this is what switches the whole service off.
    if (TAMPER_ACCESSIBILITY_TRIGGERS.any { text.contains(it) }) return true

    // A tap on the Habit row of the accessibility list, caught before the detail page has
    // rendered, so the tap itself can never complete.
    if (
      clicked &&
      clickedLabel.contains("habit") &&
      TAMPER_ACCESSIBILITY_LISTS.any { text.contains(it) }
    ) {
      return true
    }

    if (!habitContext) return false

    // Screen C — Habit's device-admin deactivation page. Blocked on entry, exactly like the
    // accessibility toggle: lowering the mode needs Habit's own unlock flow first.
    if (TAMPER_DEVICE_ADMIN_TRIGGERS.any { text.contains(it) }) return true

    // A destructive confirmation dialog / bottom sheet is fatal on sight, even during the
    // short battery / autostart grace window.
    if (TAMPER_CONFIRM_TEXT.any { text.contains(it) }) return true

    // Habit's plain App info / Storage page is allowed. The buttons being visible is not
    // tampering; a tap on one of them is intercepted at 0 ms by the click check above.
    if (clicked && TAMPER_DANGER_LABELS.any { clickedLabel.contains(it) }) return true

    return false
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
    stopTamperWatch()
    unregisterBrowserPackageReceiver()
    AiVisualScanner.stop()
    HabitGuard.detach()
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    stopTamperWatch()
    unregisterBrowserPackageReceiver()
    AiVisualScanner.release()
    HabitGuard.detach()
    super.onDestroy()
  }

  /**
   * Adult-domain check for the browser page in front.
   *
   * Only the URL bar of a page that actually loaded is read, and only while it is neither
   * focused nor changing. Nothing else is ever matched: not the event text, not the window
   * title, not the page's own words. Typing "xhamster" into Chrome's bar, finishing
   * "xhamster.com" before pressing enter, or searching "xhamster 2025 most viewed video"
   * on google.com therefore never blocks; opening the adult domain itself does.
   */
  private fun scanPage(pkg: String, event: AccessibilityEvent) {
    if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
    if (browserUrlBarFocused(pkg)) return
    val host = currentNavigatedHost(pkg) ?: return
    if (!AdultFilter.isAdultHost(host)) return
    HabitGuard.onBlockedHost(this, host)
  }

  /**
   * True while the browser in front still shows an adult domain in its URL bar. Used by the
   * block flow to fall back to Home when Back did not leave the page.
   */
  fun stillOnAdultHost(): Boolean {
    val pkg = foregroundPackage() ?: return false
    if (!isBrowserPackage(pkg)) return false
    val host = currentNavigatedHost(pkg) ?: return false
    return AdultFilter.isAdultHost(host)
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
    /** A watched window smaller than this is a thumbnail and is not routed to the scanner. */
    private const val MIN_WATCHED_WINDOW_PX = 80
    /** Browsers re-read their URL bar on this cadence so a site change stops scanning fast. */
    private const val BROWSER_ROUTE_INTERVAL_MS = 500L
    /** Ancestors walked above a tapped node to tell page content from browser chrome. */
    private const val WEBVIEW_ANCESTOR_DEPTH = 30
    /** Direct children read from a tapped node, so a menu row's own text is seen. */
    private const val CLICK_CHILD_LIMIT = 24
    /** Runaway guards for the private-mode walk: browser chrome is small, pages are pruned. */
    private const val PRIVATE_WALK_LIMIT = 2_000
    private const val PRIVATE_WALK_DEPTH = 60
    private const val PRIVATE_CHILD_LIMIT = 200
    /** Whitespace collapsed by [normalizeLabel]. */
    private val WHITESPACE = Regex("\\s+")

    /**
     * Overlay packages that are never treated as a different foreground app: the system UI
     * (notification shade, volume panel) and the common soft keyboards.
     */
    private val TRANSIENT_OVERLAY_PACKAGES = setOf(
      "com.android.systemui",
      "com.google.android.inputmethod.latin",
      "com.android.inputmethod.latin",
      "com.google.android.inputmethod",
      "com.samsung.android.honeyboard",
      "com.miui.inputmethod",
      "com.baidu.input",
      "com.sohu.inputmethod.sogou",
      "com.iflytek.inputmethod"
    )

    /**
     * Browsers that have no normal mode at all: Firefox Focus and Tor Browser render every
     * screen privately, so entering them is enough.
     */
    private val ALWAYS_PRIVATE_BROWSERS = setOf(
      "org.mozilla.focus",
      "org.torproject.torbrowser"
    )

    /**
     * Layer 1 vocabulary: controls that open private mode. Only a real tap on one of these
     * blocks, which is why a `⋮` menu that merely lists "New private tab" stays harmless.
     */
    private val PRIVATE_ACTION_LABELS = setOf(
      "new incognito tab",
      "new private tab",
      "new secret tab",
      "new inprivate tab",
      "open in incognito tab",
      "open in private tab",
      "turn on secret mode",
      "start incognito",
      "new fire window",
      "incognito",
      "incognito tabs",
      "private",
      "private tabs"
    )

    /**
     * Layer 2 vocabulary: labels that only ever render while private mode is already on.
     * The bare words "incognito" and "private" are deliberately absent, so Chrome's home
     * shortcut chip and Brave's menu row can never block a normal session.
     */
    private val PRIVATE_STATE_LABELS = setOf(
      "close all incognito tabs",
      "close all private tabs",
      "turn off secret mode",
      "private browsing",
      "inprivate tabs",
      "you’ve gone incognito",
      "you've gone incognito",
      "now you can browse privately",
      "this is a private tab",
      "brave doesn’t remember what you do in a private tab",
      "brave doesn't remember what you do in a private tab",
      "welcome to secret mode",
      "you're browsing inprivate",
      "you’re browsing inprivate"
    )

    /** Native private-mode views: these ids are never part of a normal browser page. */
    private val PRIVATE_VIEW_IDS = setOf(
      "incognito_ntp",
      "incognito_ntp_content",
      "incognito_badge",
      "location_bar_incognito_badge",
      "secret_mode_badge"
    )

    /** Tab-model selectors that report the private state through isSelected / isChecked. */
    private val PRIVATE_TAB_SELECTOR_IDS = setOf("incognito_toggle_button")

    /** The continuous Settings poll: ten full scans per second while Settings is in front. */
    private const val TAMPER_POLL_MS = 100L
    /** Re-checks after every Settings event; HyperOS animates a fragment for ~250 ms. */
    private val TAMPER_FOLLOW_UP_MS = longArrayOf(80L, 180L, 300L, 500L)
    /**
     * How long Habit's App info context survives a scan where no open window repeats the
     * app name (a confirmation dialog covering the page), before another app's settings get
     * their own context back.
     */
    private const val HABIT_CONTEXT_TTL_MS = 2_000L
    /** Runaway guard for the deep Settings walk: every node, not a page-sized budget. */
    private const val TAMPER_NODE_BUDGET = 6_000
    private const val TAMPER_MAX_DEPTH = 60
    private const val TAMPER_MAX_TEXT = 60_000
    /** Levels above the tapped control that are read as its row. */
    private const val TAMPER_CLICK_LEVELS = 3

    /**
     * The known Settings / vendor-security / installer packages. [isSettingsPackage] also
     * accepts the vendor variants (`*.settings`, security-centre and installer families).
     */
    private val SETTINGS_PACKAGES = setOf(
      "com.android.settings",
      "com.miui.securitycenter",
      "com.google.android.packageinstaller",
      "com.miui.packageinstaller"
    )

    /** Screen A: any of these means the accessibility detail or its dialog is open. */
    private val TAMPER_ACCESSIBILITY_TRIGGERS = listOf(
      "use habit",
      "habit shortcut",
      "turn off habit",
      "habit blocks adult websites"
    )

    /** Screen A: the lists a tap on the "Habit" item can come from. */
    private val TAMPER_ACCESSIBILITY_LISTS = listOf(
      "accessibility",
      "downloaded apps",
      "downloaded services"
    )

    /** Screen B: controls that end protection when the user taps them on Habit's page. */
    private val TAMPER_DANGER_LABELS = listOf(
      "force stop",
      "uninstall",
      "delete",
      "clear data",
      "clear all data",
      "clear cache",
      "manage space",
      "reset",
      "deactivate",
      "remove admin"
    )

    /**
     * Screen C: Habit's device-admin deactivation page. Matched on entry, without a tap.
     */
    private val TAMPER_DEVICE_ADMIN_TRIGGERS = listOf(
      "deactivate this device admin",
      "remove this device admin",
      "deactivate & uninstall",
      "remove admin"
    )

    /**
     * Screen B: destructive confirmation dialogs and bottom sheets. These are dialog
     * wording, never the plain App info buttons, so "clear all data" is only matched with
     * a question mark (HyperOS itself labels a button "Clear all data").
     */
    private val TAMPER_CONFIRM_TEXT = listOf(
      "force stop?",
      "force stop an app",
      "if you force stop",
      "misbehave",
      "clear app data",
      "clear all data?",
      "all of this app's data",
      "all this app's data",
      "delete all data",
      "deleted permanently",
      "clear cache?",
      "uninstall this app",
      "do you want to uninstall",
      "uninstall habit",
      "remove app",
      "deactivate this device admin"
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
      "com.android.browser",
      "com.vivaldi.browser",
      "com.kiwibrowser.browser",
      "com.uc.browser.en",
      "com.UCMobile.intl",
      "com.opera.gx",
      "org.mozilla.focus",
      "org.torproject.torbrowser"
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
