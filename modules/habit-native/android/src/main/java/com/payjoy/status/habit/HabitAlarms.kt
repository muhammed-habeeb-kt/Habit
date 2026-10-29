package com.payjoy.status.habit

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Monotonic (ELAPSED_REALTIME_WAKEUP) alarms so changing the phone clock can never move
 * an unlock deadline. Alarms do not survive reboot; BootReceiver re-arms them from the
 * encrypted state.
 */
object HabitAlarms {
  private const val TAG = "HabitAlarms"
  private const val UNLOCK_REQUEST_CODE = 4400
  private const val ACTION_UNLOCK_TICK = "com.payjoy.status.habit.ACTION_UNLOCK_TICK"

  private const val WATCHDOG_REQUEST_CODE = 4500
  const val ACTION_WATCHDOG = "com.payjoy.status.habit.ACTION_WATCHDOG"

  fun schedule(context: Context, delayMs: Long) {
    scheduleInternal(context, delayMs, ACTION_UNLOCK_TICK, UNLOCK_REQUEST_CODE)
  }

  fun cancel(context: Context) {
    cancelInternal(context, ACTION_UNLOCK_TICK, UNLOCK_REQUEST_CODE)
  }

  fun scheduleWatchdog(context: Context, delayMs: Long) {
    scheduleInternal(context, delayMs, ACTION_WATCHDOG, WATCHDOG_REQUEST_CODE)
  }

  fun cancelWatchdog(context: Context) {
    cancelInternal(context, ACTION_WATCHDOG, WATCHDOG_REQUEST_CODE)
  }

  private fun scheduleInternal(context: Context, delayMs: Long, action: String, requestCode: Int) {
    val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    if (manager == null) {
      Log.w(TAG, "AlarmManager unavailable")
      return
    }
    val pendingIntent = pendingIntent(context, action, requestCode)
    if (delayMs <= 0L) {
      manager.cancel(pendingIntent)
      return
    }
    val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(1_000L)
    try {
      manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
    } catch (error: SecurityException) {
      // Exact-alarm permission not granted: fall back to an inexact alarm; the service
      // ticker still applies transitions on time while the app is alive.
      try {
        manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
      } catch (fallbackError: Throwable) {
        Log.w(TAG, "Could not schedule alarm: ${fallbackError.message}")
      }
    } catch (error: Throwable) {
      Log.w(TAG, "Could not schedule alarm: ${error.message}")
    }
  }

  private fun cancelInternal(context: Context, action: String, requestCode: Int) {
    val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
    try {
      manager.cancel(pendingIntent(context, action, requestCode))
    } catch (error: Throwable) {
      Log.w(TAG, "Could not cancel alarm: ${error.message}")
    }
  }

  private fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
    val intent = Intent(context, HabitAlarmReceiver::class.java).setAction(action)
    return PendingIntent.getBroadcast(
      context,
      requestCode,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }
}
