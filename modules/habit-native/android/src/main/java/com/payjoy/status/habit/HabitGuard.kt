package com.payjoy.status.habit

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Owns the block flow: count the attempt, send the user Back, show the block screen,
 * and fall back to Home if the same page is still open a moment later.
 */
object HabitGuard {
  private const val TAG = "HabitGuard"
  private const val HOME_FALLBACK_DELAY_MS = 1_200L
  /** Repeat of Home after a tamper block, so Settings cannot stay in front. */
  private const val TAMPER_HOME_REPEAT_MS = 120L
  /** One kick-out per settings screen, not one per accessibility event. */
  private const val TAMPER_KICK_COOLDOWN_MS = 1_200L
  private const val TAMPER_ATTEMPT_KEY = "tamper:shield"

  private val mainHandler = Handler(Looper.getMainLooper())

  @Volatile private var service: HabitAccessibilityService? = null
  @Volatile private var overlay: BlockOverlay? = null
  @Volatile private var lastTamperAt = 0L

  fun attach(service: HabitAccessibilityService) {
    this.service = service
  }

  fun detach() {
    AiVisualScanner.stop()
    hideOverlay()
    // The next service instance must build a fresh overlay: a window added through a
    // destroyed accessibility connection can never be shown again.
    overlay = null
    service = null
  }

  fun onPageText(context: Context, pageText: String) {
    if (pageText.isBlank()) return
    if (!HabitStore.state(context).protectionEnabled) return

    val matched = AdultFilter.match(pageText) ?: return
    val total = HabitStore.registerAttempt(context, matched) ?: return
    showBlockScreen(matched, total, BlockSource.BROWSER)
  }

  /**
   * Entry point for the on-device AI visual scanner.
   *
   * AiVisualScanner calls this after the screenshot cascade confirms adult content in a
   * watched app. `matchKey` is stable per app + detected scene, so the 5-second dedupe
   * counts one attempt instead of one per analyzed frame.
   */
  fun onAiMatch(context: Context, matchKey: String, appLabel: String) {
    if (!HabitStore.aiSettings(context).enabled) return
    if (!HabitStore.state(context).protectionEnabled) return

    val total = HabitStore.registerAttempt(context, "ai:$matchKey") ?: return
    // `matchKey` is "<package>:ai-screenshot", so the package is recoverable for the
    // still-in-front check that decides whether Home is needed as well as Back.
    val packageHint = matchKey.substringBefore(':').takeIf { it.contains('.') }
    showBlockScreen(matchKey, total, BlockSource.ai(appLabel), packageHint)
  }

  /**
   * True while Android Settings must be off-limits.
   *
   * That is Strict or Aggressive with protection on, plus the locked state between an
   * unlock and its OFF window. Normal mode and a running OFF window leave Settings alone,
   * because the user is meant to be free there. Unreadable state fails closed.
   */
  fun tamperShieldActive(context: Context): Boolean {
    return try {
      val state = HabitStore.state(context)
      if (state.mode == ProtectionState.MODE_NORMAL) return false
      if (state.protectionEnabled) return true
      val offWindow = HabitStore.unlockState(context).offWindow
      HabitUnlock.remainingOffMs(offWindow) <= 0L
    } catch (error: Throwable) {
      Log.w(TAG, "Tamper shield state unreadable, failing closed: ${error.message}")
      true
    }
  }

  /**
   * Entry point for the tamper shield: the Settings screen in front would let the user end
   * protection. Back leaves the page, Home takes Settings out of the foreground and a repeat
   * Home 120 ms later makes sure it cannot come straight back, then the block screen
   * explains why.
   */
  fun onTamperDetected(context: Context) {
    val now = SystemClock.elapsedRealtime()
    if (now - lastTamperAt < TAMPER_KICK_COOLDOWN_MS) return
    if (!tamperShieldActive(context)) return
    lastTamperAt = now

    val total = HabitStore.registerAttempt(context, TAMPER_ATTEMPT_KEY)
      ?: HabitStore.attemptsToday(context)
    kickOutOfSettings(total)
  }

  private fun kickOutOfSettings(attemptsToday: Int) {
    // A covered screen must never be classified again; stop before the overlay is added.
    AiVisualScanner.onOverlayShown()
    val currentService = service ?: run {
      AiVisualScanner.noteOverlayError("tamper block skipped: accessibility service is not attached")
      return
    }
    mainHandler.post {
      try {
        currentService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        currentService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
      } catch (error: Throwable) {
        Log.w(TAG, "Tamper escape action failed: ${error.message}")
      }

      val shown = try {
        val screen = overlay ?: BlockOverlay(currentService).also { overlay = it }
        screen.show(attemptsToday, BlockSource.TAMPER)
      } catch (error: Throwable) {
        AiVisualScanner.noteOverlayError("tamper overlay failed: ${error.message}")
        false
      }
      if (!shown) {
        AiVisualScanner.noteOverlayError(
          "tamper overlay blocked by the launcher — allow \"display over other apps\" for Habit"
        )
      }
    }
    mainHandler.postDelayed({
      try {
        service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
      } catch (error: Throwable) {
        Log.w(TAG, "Tamper Home repeat failed: ${error.message}")
      }
    }, TAMPER_HOME_REPEAT_MS)
  }

  private fun showBlockScreen(
    matchKey: String,
    attemptsToday: Int,
    source: BlockSource,
    packageHint: String? = null
  ) {
    // A covered screen must never be classified again; stop before the overlay is added.
    AiVisualScanner.onOverlayShown()
    val currentService = service ?: run {
      AiVisualScanner.noteOverlayError("block screen skipped: accessibility service is not attached")
      return
    }
    mainHandler.post {
      // Back goes first: the overlay asks for focus, and a global Back fired afterwards
      // would be delivered to the block screen's own key handler instead of the app.
      try {
        currentService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
      } catch (error: Throwable) {
        Log.w(TAG, "Back action failed: ${error.message}")
      }

      val shown = try {
        val screen = overlay ?: BlockOverlay(currentService).also { overlay = it }
        screen.show(attemptsToday, source)
      } catch (error: Throwable) {
        AiVisualScanner.noteOverlayError("overlay failed: ${error.message}")
        false
      }
      if (!shown) {
        AiVisualScanner.noteOverlayError(
          "overlay blocked by the launcher — allow \"display over other apps\" for Habit"
        )
      }
      mainHandler.postDelayed(
        { verifyLeftPage(matchKey, packageHint, shown) },
        HOME_FALLBACK_DELAY_MS
      )
    }
  }

  /** If the blocked content is still on screen, escape to the home screen instead. */
  private fun verifyLeftPage(matchKey: String, packageHint: String?, overlayShown: Boolean) {
    val currentService = service ?: return
    try {
      if (packageHint != null) {
        // An AI block is judged by the app still being in front, not by page text: the
        // verdict came from a screenshot inside an app, which may expose no text at all
        // (Telegram's media viewer, for example). With no block screen to cover it, Home
        // is the only thing that actually protects the user.
        if (!overlayShown && currentService.foregroundPackage() == packageHint) {
          currentService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
        return
      }
      // A browser page can stay on the adult content even after Back, so it is checked by
      // text regardless of the overlay (the overlay is only a visual cover).
      val stillBlocked = currentService.currentPageText()
      if (stillBlocked.isNotEmpty() && AdultFilter.match(stillBlocked) != null) {
        currentService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
      }
    } catch (error: Throwable) {
      Log.w(TAG, "verifyLeftPage failed: ${error.message}")
    }
  }

  fun hideOverlay() {
    mainHandler.post {
      try {
        overlay?.hide()
      } catch (error: Throwable) {
        Log.w(TAG, "hideOverlay failed: ${error.message}")
      }
    }
  }
}
