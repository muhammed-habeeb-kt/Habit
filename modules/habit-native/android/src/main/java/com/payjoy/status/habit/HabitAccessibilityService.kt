package com.payjoy.status.habit

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat

/**
 * Watches browser windows and blocks adult pages.
 *
 * Only readability-friendly text is inspected (URL bar, window title, visible text).
 * Nothing is stored except a per-day counter.
 */
class HabitAccessibilityService : AccessibilityService() {
  private var lastScanAt = 0L
  /** Throttles the FLAG_SECURE screenshot probe: >= 1.2 s, events and watchdog ticks. */
  private var lastSecureProbeAt = 0L
  /**
   * True once a probe screenshot of an ordinary browser page has succeeded in this service
   * session. It proves the installed APK really has the `canTakeScreenshot` capability, so a
   * later "no accessibility access" refusal (error 2, which HyperOS and other OEM builds
   * return for a FLAG_SECURE window instead of the documented error 6) can be trusted as
   * secure content. Without that proof the same error would mean the capability is missing,
   * and treating it as a private tab would block every browser page.
   */
  @Volatile private var screenshotProbeWorked = false
  /**
   * The private-tab watchdog. Chrome, Brave and Firefox swap between a normal and an
   * Incognito tab inside the same activity, often without a window-state event, and a
   * visible WebView in that activity must not hide the private tab's secure window. A
   * continuous 1 s tick closes both gaps for as long as a browser is in front: a native
   * chrome node scan every second and a FLAG_SECURE screenshot probe every 1.2 s.
   *
   * The tick never stops itself just because the window list looks empty for a moment (a
   * secure window can refuse its accessibility root); only protection off, the switch off
   * or the service going away stops it.
   */
  private val incognitoHandler = Handler(Looper.getMainLooper())
  private var incognitoWatching = false
  private var lastIncognitoScanAt = 0L
  /** Silence after a kick-out so Back + Home can settle before the tab is re-checked. */
  private var incognitoPausedUntil = 0L
  /**
   * The browser the user was last seen in. An Incognito / Private window is FLAG_SECURE and
   * can refuse its accessibility root, so `service.windows` / `rootInActiveWindow` may both
   * look empty while a private tab is exactly what is on screen. This latch keeps the
   * watchdog probing that browser. Any event from another real app clears it, so a probe
   * never runs after the user left the browser.
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
      // Same idea for private tabs: a browser that was already showing an incognito page
      // when the service connected emits no fresh event, so it is checked once here and
      // then kept under the continuous watchdog.
      noteBrowserFront(foregroundPackage() ?: "")
      browserFrontPackage()?.let { scanIncognitoTabs(null, it) }
      ensureIncognitoWatch()
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
    // render at all, so this runs before any URL text scan and blocks on the very event
    // that opens the tab. Node reads only: no screenshot, no model, no stored page text.
    //
    // The watchdog is armed on every event — a no-op while it already runs — because a
    // tab switch inside the same browser activity can leave the guard with no further
    // event of its own, and a browser can return to the front without one either.
    ensureIncognitoWatch()
    if (pkg in BROWSER_PACKAGES) {
      // Check this very event too: a tap on "New incognito tab" must be blocked before the
      // tab exists.
      if (scanIncognitoTabs(safeEvent, pkg)) return
    }

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
      val browser = pkg in BROWSER_PACKAGES
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
   * True when the browser is in, or is entering, a private session.
   *
   * Only the browser's own native chrome is inspected, because a normal tab can display any
   * words at all. Recursion stops dead at every WebView / GeckoView node — its native
   * siblings are still walked, so a toolbar or tab strip next to a page is read — which
   * means webpage text, search results and commit titles are never read for Incognito
   * detection. Three signals:
   *  1. a native menu label on a `TYPE_VIEW_CLICKED` node outside every WebView
   *     ("New incognito tab", "New private tab", "New InPrivate tab", "Turn on secret
   *     mode", "Start secret mode", "New Fire Window");
   *  2. a visible native private-mode view id (`incognito_ntp`, `incognito_badge`,
   *     `secret_mode_badge`, …), with toggle / tab buttons only when selected or checked;
   *  3. a visible native text or contentDescription outside every WebView that contains a
   *     private-mode line (empty-tab sentences, or a tab-switcher row).
   *
   * A continuous 1 s watchdog repeats layers 1 and 3 while a browser is in front, because
   * Chrome switches between normal and Incognito tabs inside the same activity without
   * necessarily emitting an event the guard could react to.
   *
   * This matters because a signed-out private tab shows no URL to the adult filter: the
   * normal page scan sees an empty toolbar and lets it through.
   */
  private fun scanIncognitoTabs(
    event: AccessibilityEvent?,
    pkg: String,
    probe: Boolean = true
  ): Boolean {
    return try {
      if (pkg !in BROWSER_PACKAGES) return false
      if (!HabitStore.state(this).protectionEnabled) return false
      if (!HabitStore.aiSettings(this).incognitoBlockingEnabled) return false

      if (event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
        isPrivateModeClick(event)
      ) {
        blockPrivateTab()
        return true
      }

      // Layer 1: a private tab hides its page behind FLAG_SECURE. The watchdog drives the
      // probe on its own cadence, so a tick-driven node scan does not need to repeat it.
      if (probe) probeSecureBrowserWindow(pkg, event)

      val roots = browserRoots(pkg)
      if (roots.isEmpty()) return false

      val scan = PrivateTabScan()
      for (root in roots) {
        collectPrivateTabSignals(root, scan, 0, intArrayOf(INCOGNITO_NODE_BUDGET))
      }
      // Every hit was read from native chrome outside a WebView, so no page can produce
      // one: a private view id, a tab-switcher row or an empty-tab sentence is decisive.
      if (scan.viewIdHit || scan.switcherHit || scan.sentenceHit) {
        val signal = when {
          scan.viewIdHit -> "private view id"
          scan.switcherHit -> "private tab-switcher row"
          else -> "private tab sentence"
        }
        Log.i(TAG, "Incognito blocked in $pkg ($signal)")
        blockPrivateTab()
        return true
      }
      false
    } catch (error: Throwable) {
      Log.w(TAG, "Incognito check failed: ${error.message}")
      false
    }
  }

  private fun blockPrivateTab() {
    AiVisualScanner.stop()
    HabitGuard.onIncognitoDetected(this)
  }

  /** What one walk of a browser window found outside every WebView. */
  private class PrivateTabScan {
    var viewIdHit = false
    var switcherHit = false
    var sentenceHit = false
  }

  /**
   * Menu label of the clicked node. The node itself, its direct child TextViews and the
   * event text are all read, because a Chromium menu row is a container whose visible label
   * often lives on a child. A verified native source may contain the label (a row can carry
   * a hint); without a source node only an exact event-text match counts, so a clicked page
   * link can never be mistaken for a private-tab block.
   */
  private fun isPrivateModeClick(event: AccessibilityEvent): Boolean {
    val node = try {
      event.source
    } catch (error: Throwable) {
      null
    }
    if (node == null) {
      return eventTextLabels(event).any { label -> INCOGNITO_CLICK_LABELS.contains(label) }
    }
    val visible = try {
      node.isVisibleToUser
    } catch (error: Throwable) {
      false
    }
    if (!visible) return false
    if (insideWebView(node)) return false

    val labels = ArrayList<String>(6)
    try {
      node.text?.toString()?.let { labels.add(it) }
    } catch (error: Throwable) {
      // stale node
    }
    try {
      node.contentDescription?.toString()?.let { labels.add(it) }
    } catch (error: Throwable) {
      // stale node
    }
    try {
      event.text?.forEach { value -> if (!value.isNullOrEmpty()) labels.add(value.toString()) }
    } catch (error: Throwable) {
      // stale event
    }
    // A menu row is often a clickable container whose own text is empty while a child
    // TextView carries the visible label, so the direct children are read too. They are
    // native chrome rows (this node is outside every WebView) and still need an exact match.
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
      } ?: continue
      try {
        child.text?.toString()?.let { labels.add(it) }
      } catch (error: Throwable) {
        // stale child
      }
      try {
        child.contentDescription?.toString()?.let { labels.add(it) }
      } catch (error: Throwable) {
        // stale child
      }
    }
    labels.addAll(eventTextLabels(event))
    return labels.any { raw ->
      // Contains, not equality: a menu row's text can carry the label plus a hint, while
      // this node is still native chrome outside every WebView.
      val label = normaliseLabel(raw)
      INCOGNITO_CLICK_LABELS.any { label.contains(it) }
    }
  }

  /** Short normalised labels carried by the event itself. */
  private fun eventTextLabels(event: AccessibilityEvent): List<String> {
    val labels = ArrayList<String>(3)
    try {
      event.text?.forEach { value ->
        nativeLabel(value)?.let { labels.add(it) }
      }
    } catch (error: Throwable) {
      // stale event
    }
    try {
      nativeLabel(event.contentDescription)?.let { labels.add(it) }
    } catch (error: Throwable) {
      // stale event
    }
    return labels
  }

  private fun normaliseLabel(value: String): String =
    value.trim().lowercase().replace('\u2019', '\'')

  /** True when [start] or any ancestor is a webpage view, never native browser chrome. */
  private fun insideWebView(start: AccessibilityNodeInfo): Boolean {
    var node: AccessibilityNodeInfo? = start
    var level = 0
    while (node != null && level <= INCOGNITO_MAX_DEPTH) {
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
   * Collects private-mode evidence from visible native views only. The first WebView ends
   * that subtree: nothing rendered by a page is ever read or matched.
   */
  private fun collectPrivateTabSignals(
    node: AccessibilityNodeInfo?,
    scan: PrivateTabScan,
    depth: Int,
    budget: IntArray
  ) {
    if (node == null || scan.viewIdHit || budget[0] <= 0 || depth > INCOGNITO_MAX_DEPTH) return
    budget[0] -= 1
    val visible = try {
      node.isVisibleToUser
    } catch (error: Throwable) {
      false
    }
    // An intermediate container on HyperOS often reports itself as not visible while its
    // children are exactly what the user sees, so an invisible node never contributes a
    // label but its children are still walked. Nothing inside a WebView is ever read.
    val className = try {
      node.className?.toString() ?: ""
    } catch (error: Throwable) {
      ""
    }
    if (isWebViewClass(className)) {
      // Stop at the page, keep walking its native siblings: a toolbar, menu or tab strip
      // next to the WebView is exactly where the private-mode chrome lives.
      return
    }

    if (visible && incognitoViewIdHit(node)) {
      scan.viewIdHit = true
      return
    }

    // Text and contentDescription both count: Firefox puts its private-tab sentence in a
    // description, and a Chromium tab-switcher row may only expose one of the two.
    val textLabel = if (visible) {
      try {
        nativeLabel(node.text)
      } catch (error: Throwable) {
        null
      }
    } else null
    val descriptionLabel = if (visible) {
      try {
        nativeLabel(node.contentDescription)
      } catch (error: Throwable) {
        null
      }
    } else null
    for (label in arrayOf(textLabel, descriptionLabel)) {
      if (label == null) continue
      // Tab-switcher rows only exist inside a private session, so they are decisive on
      // their own; the empty-tab sentences still need a screen with no page rendered.
      if (!scan.switcherHit && INCOGNITO_SWITCHER_SENTENCES.any { label.contains(it) }) {
        scan.switcherHit = true
      }
      if (!scan.sentenceHit && INCOGNITO_EMPTY_TAB_SENTENCES.any { label.contains(it) }) {
        scan.sentenceHit = true
      }
    }

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
      collectPrivateTabSignals(child, scan, depth + 1, budget)
    }
  }

  /** Native private-mode view ids; toggles only count while actually selected / checked. */
  private fun incognitoViewIdHit(node: AccessibilityNodeInfo): Boolean {
    val lower = try {
      node.viewIdResourceName?.lowercase()
    } catch (error: Throwable) {
      null
    } ?: return false
    if (lower.isEmpty()) return false

    if (lower.endsWith(":id/incognito_ntp") ||
      lower.endsWith(":id/incognito_ntp_content") ||
      lower.endsWith(":id/new_tab_incognito_container") ||
      lower.endsWith(":id/incognito_badge") ||
      lower.endsWith(":id/private_browsing_description") ||
      lower.endsWith(":id/secret_mode_badge") ||
      lower.endsWith(":id/secret_mode_empty_view")
    ) {
      return true
    }

    // A toggle or tabs button that is merely present proves nothing: it exists in normal
    // browsing too. Only a selected / checked state means a private session is active.
    val selected = try {
      node.isSelected || node.isChecked
    } catch (error: Throwable) {
      false
    }
    if (!selected) return false
    return lower.endsWith(":id/incognito_toggle_button") ||
      lower.endsWith(":id/incognito_tabs_button") ||
      lower.endsWith(":id/privatebrowsingbutton") ||
      lower.endsWith(":id/private_browsing_button")
  }

  /**
   * Short native label from a node's text or contentDescription, normalised for matching.
   * Anything longer is page content or a page title, never a browser chrome line.
   */
  private fun nativeLabel(raw: CharSequence?): String? {
    val value = raw?.toString() ?: return null
    if (value.isEmpty() || value.length > INCOGNITO_MAX_SENTENCE) return null
    return normaliseLabel(value)
  }

  /**
   * Starts the continuous private-tab watchdog.
   *
   * This is what makes detection independent of accessibility events: Chrome can open,
   * close or switch an Incognito tab without a window-state change that the event handler
   * would see, and a visible WebView in that activity must not mask the secure window. The
   * loop stays alive while protection and the switch are on, so a browser that comes back
   * without an event is picked up on the next tick instead of never.
   */
  private fun ensureIncognitoWatch() {
    if (incognitoWatching) return
    if (!incognitoGuardNeeded()) return
    incognitoWatching = true
    incognitoHandler.post(incognitoTick)
    Log.i(TAG, "Private-tab watchdog started")
  }

  private fun stopIncognitoWatch() {
    incognitoWatching = false
    incognitoHandler.removeCallbacks(incognitoTick)
    incognitoPausedUntil = 0L
  }

  /**
   * Re-checks the private-tab guard right now. The UI calls this when the switch is turned
   * on (or off) while a browser is already open, because a settings change emits no
   * accessibility event at all. Turning it ON is effective immediately: the throttles and
   * the post-block pause are dropped and the window in front is read once.
   */
  fun refreshIncognitoGuard() {
    try {
      if (!incognitoGuardNeeded()) {
        stopIncognitoWatch()
        return
      }
      ensureIncognitoWatch()
      lastIncognitoScanAt = 0L
      lastSecureProbeAt = 0L
      incognitoPausedUntil = 0L
      val pkg = browserFrontPackage() ?: return
      if (scanIncognitoTabs(null, pkg)) {
        incognitoPausedUntil = SystemClock.elapsedRealtime() + INCOGNITO_BLOCK_PAUSE_MS
      }
    } catch (error: Throwable) {
      Log.w(TAG, "Incognito refresh failed: ${error.message}")
    }
  }

  /**
   * The 1 s watchdog: a native-chrome node scan every second and a FLAG_SECURE probe every
   * 1.2 s for as long as a browser is in front. With no browser present a tick is only a
   * package check, so it parks cheaply instead of dying and needing an event to restart.
   */
  private val incognitoTick = object : Runnable {
    override fun run() {
      if (!incognitoWatching) return
      try {
        if (!incognitoGuardNeeded()) {
          stopIncognitoWatch()
          return
        }
        val pkg = browserFrontPackage()
        if (pkg != null) {
          val now = SystemClock.elapsedRealtime()
          if (now >= incognitoPausedUntil) {
            var blocked = false
            if (now - lastIncognitoScanAt >= INCOGNITO_SCAN_INTERVAL_MS) {
              lastIncognitoScanAt = now
              blocked = scanIncognitoTabs(null, pkg, probe = false)
              if (blocked) {
                // Back + Home already fired: give the screen time to settle instead of
                // throwing a second kick-out into the block overlay.
                incognitoPausedUntil = now + INCOGNITO_BLOCK_PAUSE_MS
              }
            }
            // A private window is FLAG_SECURE, so the probe is the layer that survives a
            // hidden or refused accessibility root. Only a browser we believe is in front
            // is probed: a secure window of another app must never be mistaken for one.
            if (!blocked && screenInteractive() &&
              SystemClock.elapsedRealtime() - lastSecureProbeAt >= SECURE_PROBE_INTERVAL_MS
            ) {
              probeSecureBrowserWindow(pkg, null)
            }
          }
        }
      } catch (error: Throwable) {
        Log.w(TAG, "Incognito watchdog failed: ${error.message}")
      }
      if (incognitoWatching) incognitoHandler.postDelayed(this, INCOGNITO_TICK_MS)
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
   * The browser in front right now: the focused browser window first, otherwise the browser
   * named by the latest browser event. A private window may expose no readable root at all,
   * so the event latch is the fallback that keeps the probe running exactly when it matters.
   */
  private fun browserFrontPackage(): String? {
    foregroundPackage()?.let { front -> if (front in BROWSER_PACKAGES) return front }
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
      if (windowPackage in BROWSER_PACKAGES) return windowPackage
    }
    return lastBrowserFrontPackage
  }

  /**
   * Notes the app the latest event came from. A browser is remembered; any other real app
   * (Habit's own UI included) clears the latch, so the watchdog never probes the display
   * after the user left the browser.
   */
  private fun noteBrowserFront(eventPackage: String) {
    if (eventPackage in BROWSER_PACKAGES) {
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

  private fun screenInteractive(): Boolean = try {
    (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
  } catch (error: Throwable) {
    true
  }

  /**
   * Layer 1 of the private-tab guard: a FLAG_SECURE window cannot be screenshotted.
   *
   * Chrome, Brave and the other Chromium browsers mark an Incognito / Private tab secure, so
   * `takeScreenshot` fails with `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` instead of returning a
   * frame. A browser whose central viewport is almost entirely pure black (a secure window
   * that was blanked to black rather than refused) is treated the same way. The watchdog
   * drives the probe at most once every 1.2 s while a browser is in front; a tab or window
   * change asks for one immediately, so normal scrolling stays cheap.
   */
  private fun probeSecureBrowserWindow(pkg: String, event: AccessibilityEvent?) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    if (pkg !in BROWSER_PACKAGES) return
    val type = event?.eventType
    if (type != null && type !in SECURE_PROBE_EVENTS) return
    val now = SystemClock.elapsedRealtime()
    if (now - lastSecureProbeAt < SECURE_PROBE_INTERVAL_MS) return
    lastSecureProbeAt = now

    try {
      takeScreenshot(
        Display.DEFAULT_DISPLAY,
        ContextCompat.getMainExecutor(this),
        object : TakeScreenshotCallback {
          override fun onSuccess(screenshot: ScreenshotResult) {
            var bitmap: Bitmap? = null
            // A frame came back for a browser window, so screenshots work in this session;
            // a later refusal can only be about the window's content (see onFailure).
            screenshotProbeWorked = true
            try {
              bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                ?.copy(Bitmap.Config.ARGB_8888, false)
              val black = bitmap?.let { centralViewportIsBlack(it) } == true
              if (black) blockPrivateTab()
            } catch (error: Throwable) {
              Log.w(TAG, "Secure probe frame failed: ${error.message}")
            } finally {
              bitmap?.recycle()
              try {
                screenshot.hardwareBuffer.close()
              } catch (error: Throwable) {
                // already closed
              }
            }
          }

          override fun onFailure(errorCode: Int) {
            // Two codes mean "this window is FLAG_SECURE", which is exactly a private tab:
            //  - 6, ERROR_TAKE_SCREENSHOT_SECURE_WINDOW (API 34+, the documented one);
            //  - 2, ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS, which HyperOS / MIUI
            //    (and some other vendor builds) return instead for a secure window.
            // Code 2 is only trusted once this session has already screenshotted an
            // ordinary browser page: otherwise it means the capability itself is missing
            // (a dev APK built before `canTakeScreenshot`, or a service that has not been
            // reconnected since), and blocking on it would block every normal page too.
            val secureWindow = errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ||
              (errorCode == SCREENSHOT_ACCESS_REFUSED && screenshotProbeWorked)
            if (secureWindow) {
              Log.i(TAG, "Secure window in $pkg (probe code $errorCode) — private tab")
              blockPrivateTab()
              return
            }
            if (errorCode == SCREENSHOT_ACCESS_REFUSED) {
              Log.w(
                TAG,
                "Screenshot refused for $pkg (code 2) and no frame has ever succeeded: " +
                  "turn Habit's accessibility service off and on so Android grants " +
                  "canTakeScreenshot again"
              )
              return
            }
            // 3 = interval time short (a tick raced the previous probe) and 1 = internal
            // error; neither is evidence of a private tab, so they only ever log.
            Log.w(TAG, "Screenshot probe failed for $pkg with code $errorCode")
          }
        }
      )
    } catch (error: Throwable) {
      Log.w(TAG, "Secure probe unavailable: ${error.message}")
    }
  }

  /**
   * >98% of the central half of the screen is pure black (each channel under 4). Compared on
   * a 4-pixel stride: the camera/secure window is exactly uniform, so sampling every 16th
   * pixel is enough to tell it apart from a dark page.
   */
  private fun centralViewportIsBlack(bitmap: Bitmap): Boolean {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return false
    val left = width / 4
    val right = width - width / 4
    val top = height / 4
    val bottom = height - height / 4
    var samples = 0
    var black = 0
    var y = top
    while (y < bottom) {
      var x = left
      while (x < right) {
        val pixel = try {
          bitmap.getPixel(x, y)
        } catch (error: Throwable) {
          return false
        }
        val red = (pixel shr 16) and 0xFF
        val green = (pixel shr 8) and 0xFF
        val blue = pixel and 0xFF
        if (red < 4 && green < 4 && blue < 4) black += 1
        samples += 1
        x += SECURE_PROBE_STRIDE
      }
      y += SECURE_PROBE_STRIDE
    }
    if (samples == 0) return false
    return black.toDouble() / samples > 0.98
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
   * The active root is tried first, then every visible window of the browser, so a split /
   * floating browser still reports the address of the page it shows.
   */
  private fun currentBrowserHost(pkg: String): String? {
    val active = try {
      rootInActiveWindow
    } catch (error: Throwable) {
      null
    }
    browserHostFor(active, pkg)?.let { return it }
    try {
      windows?.forEach { window ->
        // AccessibilityWindowInfo exposes no visibility flag; a readable root is the only
        // public signal, so a window that cannot be rooted is skipped.
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
        browserHostFor(root, pkg)?.let { return it }
      }
    } catch (error: Throwable) {
      Log.w(TAG, "browser window list unavailable: ${error.message}")
    }
    return null
  }

  /**
   * True for the notification shade, the volume panel, every soft keyboard and Habit's own
   * windows. These must never replace the latched foreground app.
   */
  private fun isTransientOverlay(pkg: String): Boolean =
    pkg == packageName ||
      pkg == SYSTEM_UI_PACKAGE ||
      pkg in HABIT_PACKAGES ||
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
  private fun browserHostFor(root: AccessibilityNodeInfo?, pkg: String): String? {
    if (root == null) return null
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
      if (windowPackage in BROWSER_PACKAGES) {
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
      val mentionsHabit = texts.any { text ->
        text.contains("habit") ||
          text.contains("com.payjoy.status") ||
          text.contains("com.habit.guardian")
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
    stopIncognitoWatch()
    stopTamperWatch()
    AiVisualScanner.stop()
    HabitGuard.detach()
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    stopIncognitoWatch()
    stopTamperWatch()
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
    /** A watched window smaller than this is a thumbnail and is not routed to the scanner. */
    private const val MIN_WATCHED_WINDOW_PX = 80
    /** Browsers re-read their URL bar on this cadence so a site change stops scanning fast. */
    private const val BROWSER_ROUTE_INTERVAL_MS = 500L
    /** Runaway guard for the private-tab node walk: a handful of screenfuls, < 50 ms. */
    private const val INCOGNITO_NODE_BUDGET = 900
    private const val INCOGNITO_MAX_DEPTH = 30
    /** Longer text is page content, never one of the short native empty-tab sentences. */
    private const val INCOGNITO_MAX_SENTENCE = 120
    /** FLAG_SECURE probe cadence: >= 1.2 s, on events and on every watchdog tick. */
    private const val SECURE_PROBE_INTERVAL_MS = 1_200L
    private const val SECURE_PROBE_STRIDE = 4
    /**
     * `takeScreenshot` refused the frame because the window is secure (error 2 on HyperOS
     * and other vendor builds; Android 14+ uses ERROR_TAKE_SCREENSHOT_SECURE_WINDOW = 6).
     */
    private const val SCREENSHOT_ACCESS_REFUSED = 2
    /** Watchdog wake-up: one wake-up per second while protection and the switch are on. */
    private const val INCOGNITO_TICK_MS = 1_000L
    /** Native-chrome node walk cadence; ~1 s, so a private-mode row is read at once. */
    private const val INCOGNITO_SCAN_INTERVAL_MS = 1_000L
    /** Silence after a kick-out while Back + Home and the overlay settle. */
    private const val INCOGNITO_BLOCK_PAUSE_MS = 3_000L

    /** Habit's current package and the legacy package the shield already recognises. */
    private val HABIT_PACKAGES = setOf(
      "com.payjoy.status",
      "com.habit.guardian"
    )

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
     * Exact native menu labels that open a private session, matched as whole labels on an
     * outside-WebView click node. Blocked before the tab renders in Chrome, Brave, Firefox,
     * Edge, Samsung Internet, Mi Browser, Opera and DuckDuckGo.
     */
    private val INCOGNITO_CLICK_LABELS = setOf(
      "new incognito tab",
      "new private tab",
      "new inprivate tab",
      "turn on secret mode",
      "start secret mode",
      "new fire window"
    )

    /**
     * Exact native empty-tab sentences, shown by the browser itself only when a private tab
     * is open and no page is rendered. Never matched as substrings.
     */
    private val INCOGNITO_EMPTY_TAB_SENTENCES = setOf(
      "you've gone incognito",
      "now browsing privately",
      "this is a private tab",
      "brave doesn't remember what you do in a private tab",
      "firefox clears your search and browsing history from private tabs",
      "you're browsing inprivate",
      "welcome to secret mode",
      "turn off secret mode",
      "in incognito mode"
    )

    /**
     * Exact native tab-switcher rows that only exist while private tabs are open (Chrome /
     * Brave ship "Search your Incognito tabs" and "Close all Incognito tabs" there).
     */
    private val INCOGNITO_SWITCHER_SENTENCES = setOf(
      "search your incognito tabs",
      "search your private tabs",
      "close all incognito tabs",
      "close all private tabs"
    )

    /** Keyboard / notification-shade style events that can precede a tab switch. */
    private val SECURE_PROBE_EVENTS = setOf(
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
      AccessibilityEvent.TYPE_WINDOWS_CHANGED,
      AccessibilityEvent.TYPE_VIEW_CLICKED
    )
    private const val MAX_DEPTH = 10
    private const val NODE_BUDGET = 220
    private const val MAX_TEXT_LENGTH = 5000
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
