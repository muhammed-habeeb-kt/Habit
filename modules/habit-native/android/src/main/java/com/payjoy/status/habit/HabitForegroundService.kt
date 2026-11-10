package com.payjoy.status.habit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the guard process alive and visible with a persistent notification.
 */
class HabitForegroundService : Service() {
  private val handler = Handler(Looper.getMainLooper())
  private var lastNotificationText: String? = null

  /** Drives the unlock cooldown / auto re-enable transitions once a second. */
  private val ticker = object : Runnable {
    override fun run() {
      try {
        HabitStore.init(this@HabitForegroundService)
        HabitUnlock.tick(this@HabitForegroundService)
        HabitWatchdog.check(this@HabitForegroundService)
        // Includes the live AI line, so the notification updates once a second while a
        // watched app is being scanned (frames, both scores, verdict or the exact error).
        val text = notificationText()
        if (text != lastNotificationText) {
          startAsForeground()
        }
      } catch (error: Throwable) {
        Log.w(TAG, "Unlock tick failed: ${error.message}")
      }
      handler.postDelayed(this, TICK_INTERVAL_MS)
    }
  }

  override fun onCreate() {
    super.onCreate()
    isRunning = true
    createChannel()
    handler.post(ticker)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    try {
      startAsForeground()
    } catch (error: Throwable) {
      Log.w(TAG, "startForeground failed: ${error.message}")
    }
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    handler.removeCallbacks(ticker)
    isRunning = false
    super.onDestroy()
  }

  private fun startAsForeground() {
    val notification = buildNotification()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      // Platform API (added in API 29): the typed overload from androidx.core was the only
      // call that stopped resolving once ML Kit joined the module's classpath, so use the
      // framework method directly. The API 34 guard keeps this safe on older devices.
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  /** Protection sentence, then the live AI scanner line when there is one. */
  private fun notificationText(): String {
    val ai = AiVisualScanner.summary()
    return if (ai.isNullOrBlank()) protectionText() else "${protectionText()}\n$ai"
  }

  private fun protectionText(): String {
    return try {
      val unlock = HabitStore.unlockState(this)
      val offWindow = unlock.offWindow
      val cooldown = unlock.cooldown
      when {
        offWindow != null -> {
          val remaining = HabitUnlock.remainingOffMs(
            offWindow,
            SystemClock.elapsedRealtime(),
            HabitStore.bootCount(this)
          )
          "Protection is off. Turns back on in ${formatRemaining(remaining)}."
        }
        cooldown != null -> "Cooldown running — protection stays on."
        HabitStore.state(this).protectionEnabled -> "Adult websites stay blocked while protection is on."
        else -> "Protection is paused."
      }
    } catch (error: Throwable) {
      "Adult websites stay blocked while protection is on."
    }
  }

  /** "59m", "1h", "1h 25m" for a real remaining OFF window, never a built-in default. */
  private fun formatRemaining(remainingMs: Long): String {
    val totalMinutes = ((remainingMs + 59_999L) / 60_000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
      hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
      hours > 0L -> "${hours}h"
      else -> "${minutes}m"
    }
  }

  private fun buildNotification(): Notification {
    val body = notificationText()
    val ai = AiVisualScanner.summary()
    lastNotificationText = body
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
    val contentIntent = launchIntent?.let {
      PendingIntent.getActivity(
        this,
        0,
        it,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )
    }

    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle(if (ai.isNullOrBlank()) "Habit protection" else "Habit AI scanner")
      // Collapsed: the AI line is what the user is looking for. Expanded: both lines.
      .setContentText(ai ?: body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setSmallIcon(applicationInfo.icon)
      .setOngoing(true)
      .setShowWhen(false)
      .setOnlyAlertOnce(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setVisibility(NotificationCompat.VISIBILITY_SECRET)

    if (contentIntent != null) builder.setContentIntent(contentIntent)
    return builder.build()
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    val channel = NotificationChannel(
      CHANNEL_ID,
      "Protection",
      NotificationManager.IMPORTANCE_LOW
    ).apply {
      description = "Keeps Habit active so blocked pages stay blocked."
      setShowBadge(false)
      enableVibration(false)
      enableLights(false)
    }
    manager.createNotificationChannel(channel)
  }

  companion object {
    private const val TAG = "HabitForeground"
    private const val CHANNEL_ID = "habit_protection"
    private const val NOTIFICATION_ID = 4211
    private const val TICK_INTERVAL_MS = 1000L

    @Volatile var isRunning: Boolean = false
      private set

    fun start(context: Context) {
      try {
        ContextCompat.startForegroundService(
          context,
          Intent(context, HabitForegroundService::class.java)
        )
      } catch (error: Throwable) {
        Log.w(TAG, "Could not start foreground service: ${error.message}")
      }
    }

    fun stop(context: Context) {
      try {
        context.stopService(Intent(context, HabitForegroundService::class.java))
      } catch (error: Throwable) {
        Log.w(TAG, "Could not stop foreground service: ${error.message}")
      }
    }
  }
}
