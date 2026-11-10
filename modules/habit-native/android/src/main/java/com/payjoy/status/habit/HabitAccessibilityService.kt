package com.payjoy.status.habit

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

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
  /** Drives the continuous 100 ms Settings poll; idle whenever Settings is not in front. */
  private val tamperHandler = Handler(Looper.getMainLooper())
  private var tamperPolling = false
  private var tamperFollowUpsScheduled = false

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
      rootInActiveWindow?.packageName?.toString()?.let { updateAiScanner(it) }
      // Same for the shield: a service switched on while a Settings page is already open
      // gets no event at all, so the watch is armed from the window in front.
      if (foregroundPackage()?.let { isSettingsPackage(it) } == true) armTamperWatch()
      // Same idea for private tabs: a browser that was already showing an incognito page
      // when the service connected emits no fresh event, so it is checked once here.
      foregroundPackage()?.let { scanIncognitoTabs(null, it) }
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
    if (pkg in BROWSER_PACKAGES && scanIncognitoTabs(safeEvent, pkg)) return

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
   * is on screen — full screen, split screen or a floating window — and everything else
   * stops it.
   */
  private fun updateAiScanner(pkg: String) {
    try {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

      // SystemUI is the notification shade / volume panel. It is not a different app, and
      // pulling the shade down inside a watched app is exactly how the user reads the live
      // AI line in the notification, so it must not stop the loop.
      if (pkg == SYSTEM_UI_PACKAGE) return

      val browser = pkg in BROWSER_PACKAGES

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
   * True when the browser is in, or is entering, a private session.
   *
   * Two independent signals, both node reads:
   *  1. the tap itself — a menu item or button labelled "New incognito tab", "New private
   *     tab", "Turn on secret mode", … is blocked before the tab can appear;
   *  2. the rendered private page — the browser's own welcome / status text, plus view ids
   *     and content descriptions such as `incognito_badge`, is blocked on sight.
   *
   * This matters because a signed-out private tab shows no URL to the adult filter: the
   * normal page scan sees an empty toolbar and lets it through.
   */
  private fun scanIncognitoTabs(event: AccessibilityEvent?, pkg: String): Boolean {
    return try {
      if (pkg !in BROWSER_PACKAGES) return false
      if (!HabitStore.state(this).protectionEnabled) return false
      if (!HabitStore.aiSettings(this).incognitoBlockingEnabled) return false

      if (event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
        val label = tamperClickedText(event)
        if (label.isNotEmpty() && INCOGNITO_CLICK_TRIGGERS.any { label.contains(it) }) {
          AiVisualScanner.stop()
          HabitGuard.onIncognitoDetected(this)
          return true
        }
      }

      for (root in browserRoots(pkg)) {
        val builder = StringBuilder()
        collectIncognitoText(root, builder, 0, intArrayOf(INCOGNITO_NODE_BUDGET))
        if (builder.isEmpty()) continue
        val text = builder.toString().lowercase()
        if (INCOGNITO_ACTIVE_TRIGGERS.any { text.contains(it) }) {
          AiVisualScanner.stop()
          HabitGuard.onIncognitoDetected(this)
          return true
        }
      }
      false
    } catch (error: Throwable) {
      Log.w(TAG, "Incognito check failed: ${error.message}")
      false
    }
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
   * Text, contentDescription and view id of a browser window. The view id matters because
   * Chrome and Brave mark the private page with `incognito_badge` rather than visible text
   * in some builds.
   *
   * One plain pass, no lowercasing per node: rebuilding and re-testing the whole buffer on
   * every node made the walk quadratic in the page size, which is the one thing this guard
   * cannot afford on every browser event. The caller lowercases once, so a full window
   * stays well under 50 ms.
   */
  private fun collectIncognitoText(
    node: AccessibilityNodeInfo?,
    builder: StringBuilder,
    depth: Int,
    budget: IntArray
  ) {
    if (node == null || budget[0] <= 0 || depth > INCOGNITO_MAX_DEPTH) return
    if (builder.length > INCOGNITO_MAX_TEXT) return
    budget[0] -= 1
    appendNodeText(node, builder)
    try {
      node.viewIdResourceName?.let { id -> builder.append(id).append(' ') }
    } catch (error: Throwable) {
      // stale node
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
      collectIncognitoText(child, builder, depth + 1, budget)
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
    }
    return browserHostFor(root, pkg)
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
      if (!window.isVisible) continue
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
          // Private or still-loading page: keep a loop that already belongs to this browser
          // (fail closed) instead of dropping protection.
          if (AiVisualScanner.isRunningForPackage(windowPackage)) {
            AiVisualScanner.runningDomain()
          } else {
            null
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
   *  - Habit's name next to "App info" and "Settings" (the HyperOS accessibility page);
   *  - Habit's name next to force stop / clear data / uninstall / deactivate / remove admin.
   */
  private fun scanTamperWindows(event: AccessibilityEvent?): Boolean {
    return try {
      if (!HabitGuard.tamperShieldActive(this)) return false

      val clicked = event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
      val clickedLabel = if (clicked && event != null) tamperClickedText(event) else ""
      val graceActive = HabitStore.tamperGraceRemainingMs(this) > 0L

      val activeRoot = try {
        rootInActiveWindow
      } catch (error: Throwable) {
        null
      }
      val roots = settingsRoots(activeRoot)
      if (roots.isEmpty()) return false

      for (root in roots) {
        val text = tamperWindowText(root, if (root == activeRoot) event else null)
        if (text.isEmpty()) continue
        if (!tamperWindowIsFatal(text, clickedLabel, clicked, graceActive)) continue

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
    graceActive: Boolean
  ): Boolean {
    // Screen A — Habit's own accessibility toggle and its "Turn off Habit?" dialog. Never
    // allowed, not even in the grace window: this is what switches the whole service off.
    if (TAMPER_ACCESSIBILITY_TRIGGERS.any { text.contains(it) }) return true

    // (b) A tap on the Habit row of the accessibility list, caught before the detail page
    // has rendered, so the tap itself can never complete.
    if (
      clicked &&
      clickedLabel.contains("habit") &&
      TAMPER_ACCESSIBILITY_LISTS.any { text.contains(it) }
    ) {
      return true
    }

    // Screen B — Habit's app info, uninstall, data and device-admin screens.
    val mentionsHabit = text.contains("habit") ||
      text.contains("com.payjoy.status") ||
      text.contains("com.habit.guardian")
    if (!mentionsHabit) return false

    // Tapping a destructive control is refused immediately, grace or not.
    if (clicked && TAMPER_DANGER_LABELS.any { clickedLabel.contains(it) }) return true
    // A destructive confirmation dialog is refused too: its wording never appears on the
    // plain app-info page Habit itself opens for the battery / autostart "Fix" buttons.
    if (TAMPER_CONFIRM_TEXT.any { text.contains(it) }) return true

    // The 12 s stand-down only covers Habit's own navigation into the app info page; every
    // tap and every dialog above is still refused while it lasts.
    if (graceActive) return false

    // (c) Habit's name next to both page links of the HyperOS accessibility app page.
    if (text.contains("app info") && text.contains("settings")) return true
    // (d) Habit's app info / uninstall / device admin controls.
    return TAMPER_DANGER_LABELS.any { text.contains(it) }
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
    AiVisualScanner.stop()
    HabitGuard.detach()
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
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
    private const val INCOGNITO_MAX_TEXT = 24_000

    /**
     * Menu items / buttons that open a private session. Blocked on the tap itself, before
     * the tab renders, in Chrome, Brave, Firefox, Edge, Samsung Internet, Mi Browser and
     * Opera.
     */
    private val INCOGNITO_CLICK_TRIGGERS = listOf(
      "new incognito tab",
      "incognito tabs",
      "new private tab",
      "private tabs",
      "new inprivate tab",
      "turn on secret mode",
      "use secret mode",
      "incognito mode"
    )

    /**
     * Wording and view ids of a private page that is already on screen. These are the
     * browsers' own strings, so they are not ordinary page content the user could type.
     */
    private val INCOGNITO_ACTIVE_TRIGGERS = listOf(
      // Chrome
      "you’ve gone incognito",
      "you've gone incognito",
      "now browsing privately",
      "close all incognito tabs",
      "incognito tab",
      "switch out of incognito",
      "incognito_badge",
      "incognito mode",
      // Brave
      "this is a private tab",
      "close all private tabs",
      "private tab",
      // Firefox
      "private browsing",
      "firefox clears your search and browsing history from private tabs",
      // Edge
      "inprivate browsing",
      "you're browsing inprivate",
      "close all inprivate tabs",
      // Samsung Internet
      "welcome to secret mode",
      "turn off secret mode",
      "secret mode",
      // Mi Browser / Opera
      "in incognito mode",
      "incognito mode is on",
      "leave incognito mode"
    )
    private const val MAX_DEPTH = 10
    private const val NODE_BUDGET = 220
    private const val MAX_TEXT_LENGTH = 5000
    /** The continuous Settings poll: ten full scans per second while Settings is in front. */
    private const val TAMPER_POLL_MS = 100L
    /** Re-checks after every Settings event; HyperOS animates a fragment for ~250 ms. */
    private val TAMPER_FOLLOW_UP_MS = longArrayOf(80L, 180L, 300L, 500L)
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

    /** Screen B: controls that end protection from Android Settings. */
    private val TAMPER_DANGER_LABELS = listOf(
      "force stop",
      "clear data",
      "clear all data",
      "uninstall",
      "deactivate",
      "remove admin"
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
