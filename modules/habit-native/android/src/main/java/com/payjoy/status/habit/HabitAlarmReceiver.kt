package com.payjoy.status.habit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Applies deadlines and watchdog checks even if the app process was killed. */
class HabitAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    try {
      HabitStore.init(context)
      HabitUnlock.tick(context)

      if (intent?.action == HabitAlarms.ACTION_WATCHDOG) {
        HabitWatchdog.check(context, force = true)
        HabitWatchdog.schedule(context)
      }

      if (HabitStore.state(context).protectionEnabled) {
        HabitForegroundService.start(context)
      }
    } catch (error: Throwable) {
      Log.w(TAG, "Unlock alarm failed: ${error.message}")
    }
  }

  companion object {
    private const val TAG = "HabitAlarm"
  }
}
