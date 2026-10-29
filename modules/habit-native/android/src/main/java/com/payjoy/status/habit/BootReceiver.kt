package com.payjoy.status.habit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the guard after a reboot, after the app is updated, or after a swipe-away
 * of the app process. The system re-binds an enabled AccessibilityService by itself;
 * this receiver restarts the foreground keep-alive and re-arms unlock alarms.
 */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    try {
      HabitStore.init(context)
      HabitUnlock.resumeAfterBoot(context)
      if (HabitStore.state(context).protectionEnabled) {
        HabitStore.ensureFilter(context)
      }
    } catch (error: Throwable) {
      Log.w(TAG, "Boot handling failed: ${error.message}")
    }
  }

  companion object {
    private const val TAG = "HabitBoot"
  }
}
