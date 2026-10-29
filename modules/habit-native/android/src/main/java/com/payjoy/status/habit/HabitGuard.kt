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
    showBlockScreen(matched, total)
  }

  private fun showBlockScreen(matchKey: String, attemptsToday: Int) {
    val currentService = service ?: return
    mainHandler.post {
      try {
        val screen = overlay ?: BlockOverlay(currentService).also { overlay = it }
        screen.show(attemptsToday)
        currentService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        mainHandler.postDelayed({ verifyLeftPage(matchKey) }, HOME_FALLBACK_DELAY_MS)
      } catch (error: Throwable) {
        Log.w(TAG, "showBlockScreen failed: ${error.message}")
      }
    }
  }

  /** If the blocked content is still on screen, escape to the home screen instead. */
  private fun verifyLeftPage(matchKey: String) {
    val currentService = service ?: return
    try {
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
