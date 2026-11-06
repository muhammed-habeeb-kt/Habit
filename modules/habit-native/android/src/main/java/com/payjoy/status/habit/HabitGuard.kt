package com.payjoy.status.habit

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Owns the block flow: count the attempt, send the user Back, show the block screen,
 * and fall back to Home if the same page is still open a moment later.
 */
object HabitGuard {
  private const val TAG = "HabitGuard"
  private const val HOME_FALLBACK_DELAY_MS = 1_200L

  private val mainHandler = Handler(Looper.getMainLooper())

  @Volatile private var service: HabitAccessibilityService? = null
  @Volatile private var overlay: BlockOverlay? = null

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
