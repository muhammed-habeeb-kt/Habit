package com.payjoy.status.habit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** User-facing unlock notifications. Never contains browsing data. */
object HabitNotifier {
  private const val TAG = "HabitNotifier"
  private const val CHANNEL_ID = "habit_unlock"
  private const val COOLDOWN_DONE_ID = 5211
  private const val RESTORED_ID = 5212

  private const val ALERT_CHANNEL_ID = "habit_alerts"
  private const val ACCESSIBILITY_ALERT_ID = 5301
  private const val ADMIN_ALERT_ID = 5302

  fun notifyCooldownFinished(context: Context, offWindowMs: Long) {
    val totalMinutes = ((offWindowMs + 59_999L) / 60_000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    val label = when {
      hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
      hours > 0L -> "${hours}h"
      else -> "${minutes}m"
    }
    post(
      context,
      COOLDOWN_DONE_ID,
      "Cooldown finished",
      "Protection is off. Turns back on automatically in $label."
    )
  }

  fun notifyProtectionRestored(context: Context) {
    post(
      context,
      RESTORED_ID,
      "Protection is back on",
      "Habit is blocking adult websites again."
    )
  }

  /**
   * Aggressive-mode warnings. Both flags false means protection is fully intact.
   */
  fun updateProtectionWarnings(context: Context, accessibilityOn: Boolean, adminOn: Boolean) {
    try {
      val compat = NotificationManagerCompat.from(context)
      if (accessibilityOn) {
        compat.cancel(ACCESSIBILITY_ALERT_ID)
      } else {
        postWarning(
          context,
          ACCESSIBILITY_ALERT_ID,
          "Accessibility is off",
          "Aggressive mode cannot block pages. Turn Habit's accessibility service back on."
        )
      }
      if (adminOn) {
        compat.cancel(ADMIN_ALERT_ID)
      } else {
        postWarning(
          context,
          ADMIN_ALERT_ID,
          "Device admin is off",
          "Aggressive mode can no longer guard against uninstalling. Turn device admin back on."
        )
      }
    } catch (error: Throwable) {
      Log.w(TAG, "Could not update warnings: ${error.message}")
    }
  }

  fun clearProtectionWarnings(context: Context) {
    try {
      val compat = NotificationManagerCompat.from(context)
      compat.cancel(ACCESSIBILITY_ALERT_ID)
      compat.cancel(ADMIN_ALERT_ID)
    } catch (error: Throwable) {
      Log.w(TAG, "Could not clear warnings: ${error.message}")
    }
  }

  private fun postWarning(context: Context, id: Int, title: String, text: String) {
    try {
      ensureAlertChannel(context)
      val compat = NotificationManagerCompat.from(context)
      if (!compat.areNotificationsEnabled()) return

      val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
        .setSmallIcon(context.applicationInfo.icon)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_ERROR)
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)

      val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
      val contentIntent = launchIntent?.let {
        PendingIntent.getActivity(
          context,
          id,
          it,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
      }
      if (contentIntent != null) builder.setContentIntent(contentIntent)
      compat.notify(id, builder.build())
    } catch (error: Throwable) {
      Log.w(TAG, "Could not post warning: ${error.message}")
    }
  }

  private fun post(context: Context, id: Int, title: String, text: String) {
    try {
      ensureChannel(context)
      val compat = NotificationManagerCompat.from(context)
      if (!compat.areNotificationsEnabled()) return

      val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
      val contentIntent = launchIntent?.let {
        PendingIntent.getActivity(
          context,
          id,
          it,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
      }

      val builder = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(context.applicationInfo.icon)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .setShowWhen(false)

      if (contentIntent != null) builder.setContentIntent(contentIntent)
      compat.notify(id, builder.build())
    } catch (error: Throwable) {
      Log.w(TAG, "Could not post notification: ${error.message}")
    }
  }

  private fun ensureAlertChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    if (manager.getNotificationChannel(ALERT_CHANNEL_ID) != null) return
    val channel = NotificationChannel(
      ALERT_CHANNEL_ID,
      "Protection warnings",
      NotificationManager.IMPORTANCE_HIGH
    ).apply {
      description = "Warns when Aggressive mode is no longer fully protecting you."
      setShowBadge(true)
    }
    manager.createNotificationChannel(channel)
  }

  private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    val channel = NotificationChannel(
      CHANNEL_ID,
      "Unlock updates",
      NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
      description = "Tells you when a cooldown ends and when protection turns back on."
      setShowBadge(true)
    }
    manager.createNotificationChannel(channel)
  }
}
