package com.payjoy.status.habit

import android.content.Context
import android.os.SystemClock

/**
 * Unlock method C — the two-person face check session.
 *
 * Detection runs live in `HabitModule.analyzeFaceFrame` (offline ML Kit): the screen
 * samples silent camera frames, verifies a blink from Person 1, a short hand-over, then a
 * different face plus a blink from Person 2, and only then calls [complete]. The native
 * side keeps the session so the 30-minute OFF window can never be started without a live
 * check — JS can request it, but not force it.
 *
 * Nothing leaves the phone: each frame is downscaled, measured and recycled, then deleted.
 *
 * Honest limit: this proves two live, blinking, visually different faces were in front of
 * the camera. It is not identity recognition — it cannot say *who* the people are.
 */
object FaceUnlock {
  /** A check left open longer than this expires; the screen must start a new one. */
  private const val SESSION_TIMEOUT_MS = 5L * 60L * 1000L

  @Volatile private var active = false
  @Volatile private var startedElapsed = 0L

  @Synchronized
  fun start(context: Context): Boolean {
    if (HabitStore.state(context).mode == ProtectionState.MODE_NORMAL) return false
    if (HabitUnlock.lockRemainingMs(context) > 0L) return false
    active = true
    startedElapsed = SystemClock.elapsedRealtime()
    return true
  }

  @Synchronized
  fun cancel() {
    active = false
    startedElapsed = 0L
  }

  /**
   * Ends a verified check: protection is off for the fixed 30-minute window and the
   * previous mode returns by itself afterwards. Refused without an active, unexpired
   * session, so a stale or repeated call cannot unlock anything.
   */
  @Synchronized
  fun complete(context: Context): Boolean {
    if (!active) return false
    if (SystemClock.elapsedRealtime() - startedElapsed > SESSION_TIMEOUT_MS) {
      cancel()
      return false
    }
    active = false
    startedElapsed = 0L
    if (HabitStore.state(context).mode == ProtectionState.MODE_NORMAL) return false
    HabitUnlock.completeFaceUnlock(context)
    return true
  }
}
