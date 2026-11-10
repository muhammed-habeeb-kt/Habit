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
  /**
   * Escape pulses for a tamper block: each one fires Back twice then Home, so a fragment
   * transition that swallows the first Back cannot leave Settings in front.
   */
  private val TAMPER_PULSE_DELAYS_MS = longArrayOf(0L, 80L, 180L, 320L)
  /** Short guard so the 100 ms poll cannot stack kick-outs, never a reason to miss one. */
  private const val TAMPER_KICK_COOLDOWN_MS = 400L
  private const val TAMPER_ATTEMPT_KEY = "tamper:shield"
  /** One incognito kick-out per 1.5 s: the browser's own events repeat many times. */
  private const val INCOGNITO_COOLDOWN_MS = 1_500L
  private const val INCOGNITO_ATTEMPT_KEY = "incognito:tab"

  private val mainHandler = Handler(Looper.getMainLooper())

  @Volatile private var service: HabitAccessibilityService? = null
  @Volatile private var overlay: BlockOverlay? = null
  @Volatile private var lastTamperAt = 0L
  @Volatile private var lastIncognitoAt = 0L

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
   * Entry point for the incognito / private tab guard.
   *
   * The browser is already in a private session (or the user just tapped "New incognito
   * tab"), so this closes it with Back and Home before showing the block screen: no page
   * is ever rendered inside a private tab. Signed-out private browsing leaves no URL for
   * the normal filter to read, which is why this path exists at all.
   */
  fun onIncognitoDetected(context: Context) {
    if (!HabitStore.state(context).protectionEnabled) return
    if (!HabitStore.aiSettings(context).incognitoBlockingEnabled) return

    val now = SystemClock.elapsedRealtime()
    if (now - lastIncognitoAt < INCOGNITO_COOLDOWN_MS) return
    lastIncognitoAt = now

    val total = HabitStore.registerAttempt(context, INCOGNITO_ATTEMPT_KEY) ?: return
    showIncognitoBlock(total)
  }

  /** Back + Home first, then the ``INCOGNITO BLOCKED`` screen over whatever is left. */
  private fun showIncognitoBlock(attemptsToday: Int) {
    AiVisualScanner.onOverlayShown()
    val currentService = service ?: return
    runOnMain {
      try {
        currentService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        currentService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
      } catch (error: Throwable) {
        Log.w(TAG, "Incognito escape action failed: ${error.message}")
      }

      val shown = try {
        val screen = overlay ?: BlockOverlay(currentService).also { overlay = it }
        screen.show(attemptsToday, BlockSource.INCOGNITO)
      } catch (error: Throwable) {
        AiVisualScanner.noteOverlayError("incognito overlay failed: ${error.message}")
        false
      }
      if (!shown) {
        AiVisualScanner.noteOverlayError(
          "incognito overlay blocked by the launcher — allow \"display over other apps\" for Habit"
        )
      }
    }
  }

  /**
   * True while Android Settings must be off-limits.
   *
   * Any mode with protection ON is protected: Normal mode can run protection too, and the
   * Settings switch that kills the service must be unreachable there as well. Strict and
   * Aggressive stay armed during a cooldown wait as well (`mode != normal`), because the
   * lock is still held until the unlock completes. Only a Normal mode OFF window leaves
   * Settings alone, which is exactly when the user is meant to be free. Unreadable state
   * fails closed.
   */
  fun tamperShieldActive(context: Context): Boolean {
    return try {
      val state = HabitStore.state(context)
      if (state.protectionEnabled) return true
      state.mode != ProtectionState.MODE_NORMAL
    } catch (error: Throwable) {
      Log.w(TAG, "Tamper shield state unreadable, failing closed: ${error.message}")
      true
    }
  }

  /**
   * Entry point for the tamper shield: the Settings screen in front would let the user end
   * protection. The opaque shield is shown first, in the same frame as the verdict, so the
   * control under the user's finger is covered before anything else happens; then four
   * escape pulses close the page underneath it.
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
    // Runs inline when the shield was detected on the main thread (the 100 ms watch and the
    // event callback both are), so the shield really appears at 0 ms instead of one hop later.
    runOnMain {
      // FIRST, at 0 ms: the full-screen opaque shield. It is the front window and swallows
      // touch, so the accessibility switch / "Turn off" button is physically unreachable
      // from this same frame on.
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

      // SECOND: Back twice then Home, repeated four times. HyperOS slides the fragment in
      // over ~250 ms and can swallow a single Back, and the last Home makes sure Settings
      // is out of the foreground even if every Back was lost.
      TAMPER_PULSE_DELAYS_MS.forEach { delay ->
        if (delay <= 0L) {
          tamperEscapePulse()
        } else {
          mainHandler.postDelayed({ tamperEscapePulse() }, delay)
        }
      }
    }
  }

  private fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
  }

  private fun tamperEscapePulse() {
    val currentService = service ?: return
    try {
      currentService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
      currentService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
      currentService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    } catch (error: Throwable) {
      Log.w(TAG, "Tamper escape action failed: ${error.message}")
    }
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
