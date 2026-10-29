package com.payjoy.status.habit

import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * Aggressive mode self-protection.
 *
 * While Aggressive is on, the watchdog keeps checking that protection is actually intact
 * (accessibility service on, device admin still active) and raises a warning notification
 * when it is not. It never silently downgrades the mode: protection stays failing closed.
 */
object HabitWatchdog {
  private const val TAG = "HabitWatchdog"
  private const val CHECK_INTERVAL_MS = 15_000L
  private const val ALARM_INTERVAL_MS = 15L * 60L * 1000L

  @Volatile private var lastCheckElapsed = 0L

  fun check(context: Context, force: Boolean = false) {
    val now = SystemClock.elapsedRealtime()
    val recent = lastCheckElapsed != 0L && now >= lastCheckElapsed && now - lastCheckElapsed < CHECK_INTERVAL_MS
    if (!force && recent) return
    lastCheckElapsed = now

    try {
      if (HabitStore.state(context).mode != ProtectionState.MODE_AGGRESSIVE) {
        HabitNotifier.clearProtectionWarnings(context)
        return
      }
      HabitNotifier.updateProtectionWarnings(
        context,
        accessibilityOn = HabitAccessibilityService.isEnabled(context),
        adminOn = HabitDeviceAdmin.isActive(context)
      )
    } catch (error: Throwable) {
      Log.w(TAG, "Watchdog check failed: ${error.message}")
    }
  }

  /** Keeps a backstop alarm armed while Aggressive is on; cancels it otherwise. */
  fun schedule(context: Context) {
    try {
      val aggressive = HabitStore.state(context).mode == ProtectionState.MODE_AGGRESSIVE
      if (aggressive) {
        HabitAlarms.scheduleWatchdog(context, ALARM_INTERVAL_MS)
      } else {
        HabitAlarms.cancelWatchdog(context)
      }
    } catch (error: Throwable) {
      Log.w(TAG, "Watchdog scheduling failed: ${error.message}")
    }
  }
}
