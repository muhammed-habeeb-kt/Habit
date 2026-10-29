package com.payjoy.status.habit

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log

/**
 * Owns every rule about raising or lowering protection.
 *
 * Raising protection is instant. Lowering it in Strict/Aggressive requires an unlock:
 * either a typed character challenge (A) or a monotonic cooldown (B).
 *
 * Cooldowns never depend on the wall clock: remaining time comes from the persisted
 * monotonic snapshot and, after a reboot, from the last persisted remaining value.
 * That means changing the phone clock can't shorten a cooldown and rebooting can't
 * lengthen the OFF window.
 */
object HabitUnlock {
  private const val TAG = "HabitUnlock"

  const val MIN_COOLDOWN_MS = 3L * 60L * 60L * 1000L
  const val DEBUG_COOLDOWN_MS = 60L * 1000L
  const val OFF_WINDOW_MS = 10L * 60L * 1000L

  private const val PERSIST_INTERVAL_MS = 5_000L

  @Volatile private var lastPersistElapsed = 0L

  fun isDebugBuild(context: Context): Boolean {
    return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
  }

  // region queries

  fun remainingMs(window: UnlockWindow?, nowElapsed: Long = SystemClock.elapsedRealtime()): Long {
    if (window == null) return 0L
    val rebooted = nowElapsed < window.savedAtElapsed
    val remaining = if (rebooted) {
      window.savedRemainingMs
    } else {
      window.savedRemainingMs - (nowElapsed - window.savedAtElapsed)
    }
    return remaining.coerceIn(0L, window.durationMs)
  }

  /**
   * The OFF window must never grow, so after a reboot it also respects the wall-clock
   * deadline. Whichever is shorter wins — turning protection back on early is safe.
   */
  fun remainingOffMs(
    window: UnlockWindow?,
    nowElapsed: Long = SystemClock.elapsedRealtime(),
    nowWall: Long = System.currentTimeMillis()
  ): Long {
    if (window == null) return 0L
    val monotonic = remainingMs(window, nowElapsed)
    if (window.endWall <= 0L) return monotonic
    val wall = (window.endWall - nowWall).coerceAtLeast(0L)
    return minOf(monotonic, wall)
  }

  fun lockRemainingMs(context: Context): Long {
    val lockedUntil = HabitStore.unlockState(context).failures.lockedUntilWall
    return (lockedUntil - System.currentTimeMillis()).coerceAtLeast(0L)
  }

  fun cooldownDurationMs(context: Context): Long {
    val fast = HabitStore.unlockState(context).fastCooldown
    return if (isDebugBuild(context) && fast) DEBUG_COOLDOWN_MS else MIN_COOLDOWN_MS
  }

  fun failuresToday(context: Context): Int {
    val failures = HabitStore.unlockState(context).failures
    return if (failures.day == HabitStore.today()) failures.dayCount else 0
  }

  fun snapshotPart(context: Context): Map<String, Any?> {
    val unlock = HabitStore.unlockState(context)
    val now = SystemClock.elapsedRealtime()
    val cooldownRemaining = remainingMs(unlock.cooldown, now)
    val offRemaining = remainingOffMs(unlock.offWindow, now)
    return mapOf(
      "cooldownActive" to (unlock.cooldown != null),
      "cooldownRemainingMs" to cooldownRemaining,
      "cooldownTotalMs" to (unlock.cooldown?.durationMs ?: MIN_COOLDOWN_MS),
      "offWindowActive" to (unlock.offWindow != null),
      "offWindowRemainingMs" to offRemaining,
      "offWindowTotalMs" to (unlock.offWindow?.durationMs ?: OFF_WINDOW_MS),
      "failuresToday" to failuresToday(context),
      "lockRemainingMs" to lockRemainingMs(context),
      "fastCooldown" to unlock.fastCooldown,
      "restoreMode" to unlock.restoreMode
    )
  }

  // endregion

  // region mode transitions

  fun setMode(context: Context, requested: String): ProtectionState {
    val state = HabitStore.state(context)
    val target = ProtectionState.normalizeMode(requested)
    if (target == state.mode) return state

    // Raising to Aggressive needs the device admin already active. It is still instant.
    if (target == ProtectionState.MODE_AGGRESSIVE) {
      if (!HabitDeviceAdmin.isActive(context)) return state
      val next = HabitStore.updateState(context) {
        it.copy(mode = ProtectionState.MODE_AGGRESSIVE, protectionEnabled = true)
      }
      HabitStore.writeUnlockState(
        context,
        HabitStore.unlockState(context).copy(
          offWindow = null,
          restoreMode = ProtectionState.MODE_AGGRESSIVE
        )
      )
      HabitAlarms.cancel(context)
      HabitForegroundService.start(context)
      HabitWatchdog.schedule(context)
      HabitWatchdog.check(context, force = true)
      return next
    }

    if (target == ProtectionState.MODE_STRICT) {
      // Aggressive -> Strict is a lower, so it needs an unlock.
      if (state.mode == ProtectionState.MODE_AGGRESSIVE) return state
      val next = HabitStore.updateState(context) {
        it.copy(mode = ProtectionState.MODE_STRICT, protectionEnabled = true)
      }
      HabitStore.writeUnlockState(
        context,
        HabitStore.unlockState(context).copy(
          offWindow = null,
          restoreMode = ProtectionState.MODE_STRICT
        )
      )
      HabitAlarms.cancel(context)
      HabitForegroundService.start(context)
      HabitWatchdog.schedule(context)
      return next
    }

    // Lowering a strict/aggressive mode needs an unlock; JS shows the Unlock screen.
    return state
  }

  fun setProtectionEnabled(context: Context, enabled: Boolean): ProtectionState {
    val state = HabitStore.state(context)
    if (!enabled) {
      if (state.mode != ProtectionState.MODE_NORMAL) return state
      val next = HabitStore.updateState(context) { it.copy(protectionEnabled = false) }
      HabitForegroundService.stop(context)
      return next
    }

    val unlock = HabitStore.unlockState(context)
    val restoring = unlock.offWindow != null
    val next = HabitStore.updateState(context) { current ->
      current.copy(
        protectionEnabled = true,
        mode = if (restoring && current.mode == ProtectionState.MODE_NORMAL) {
          unlock.restoreMode
        } else {
          current.mode
        }
      )
    }
    if (restoring) {
      HabitStore.writeUnlockState(context, HabitStore.unlockState(context).copy(offWindow = null))
      HabitAlarms.cancel(context)
    }
    HabitForegroundService.start(context)
    HabitWatchdog.schedule(context)
    return next
  }

  // endregion

  // region timer unlock (method B)

  fun requestTimerUnlock(context: Context): ProtectionState {
    val state = HabitStore.state(context)
    if (state.mode == ProtectionState.MODE_NORMAL) return state
    val unlock = HabitStore.unlockState(context)
    if (unlock.cooldown != null) return state

    val now = SystemClock.elapsedRealtime()
    val duration = cooldownDurationMs(context)
    HabitStore.writeUnlockState(
      context,
      unlock.copy(
        cooldown = UnlockWindow(duration, duration, now, System.currentTimeMillis() + duration),
        failures = unlock.failures.copy(consecutive = 0, lockedUntilWall = 0L)
      )
    )
    reschedule(context)
    return state
  }

  fun cancelTimerUnlock(context: Context): ProtectionState {
    val unlock = HabitStore.unlockState(context)
    if (unlock.cooldown != null) {
      HabitStore.writeUnlockState(context, unlock.copy(cooldown = null))
      HabitAlarms.cancel(context)
      reschedule(context)
    }
    return HabitStore.state(context)
  }

  /**
   * Applies any elapsed transition. Safe to call often (once a second from the service)
   * and from several threads: transitions happen once, not twice.
   */
  @Synchronized
  fun tick(context: Context): ProtectionState {
    var unlock = HabitStore.unlockState(context)
    val now = SystemClock.elapsedRealtime()
    var changed = false

    val cooldown = unlock.cooldown
    if (cooldown != null && remainingMs(cooldown, now) <= 0L) {
      val previousMode = HabitStore.state(context).mode
      val restore = if (previousMode == ProtectionState.MODE_NORMAL) {
        ProtectionState.MODE_STRICT
      } else {
        previousMode
      }
      unlock = unlock.copy(
        cooldown = null,
        restoreMode = restore,
        offWindow = UnlockWindow(
          OFF_WINDOW_MS,
          OFF_WINDOW_MS,
          now,
          System.currentTimeMillis() + OFF_WINDOW_MS
        )
      )
      HabitStore.writeUnlockState(context, unlock)
      HabitStore.updateState(context) {
        it.copy(mode = ProtectionState.MODE_NORMAL, protectionEnabled = false)
      }
      // Aggressive is temporarily normal now, so stop its warnings and backstop alarm.
      HabitWatchdog.schedule(context)
      HabitWatchdog.check(context, force = true)
      HabitNotifier.notifyCooldownFinished(context, OFF_WINDOW_MS)
      changed = true
    }

    val offWindow = unlock.offWindow
    if (offWindow != null && remainingOffMs(offWindow, now) <= 0L) {
      unlock = unlock.copy(offWindow = null)
      HabitStore.writeUnlockState(context, unlock)
      HabitStore.updateState(context) {
        it.copy(
          protectionEnabled = true,
          mode = if (it.mode == ProtectionState.MODE_NORMAL) unlock.restoreMode else it.mode
        )
      }
      HabitNotifier.notifyProtectionRestored(context)
      HabitForegroundService.start(context)
      HabitStore.ensureFilter(context)
      HabitWatchdog.schedule(context)
      changed = true
    }

    val hasWindows = unlock.cooldown != null || unlock.offWindow != null
    if (hasWindows) {
      val shouldPersist = changed ||
        lastPersistElapsed == 0L ||
        now < lastPersistElapsed ||
        now - lastPersistElapsed >= PERSIST_INTERVAL_MS
      if (shouldPersist) {
        unlock = advanceWindows(unlock, now)
        HabitStore.writeUnlockState(context, unlock)
        lastPersistElapsed = now
      }
      if (changed) reschedule(context)
    } else {
      lastPersistElapsed = 0L
      if (changed) HabitAlarms.cancel(context)
    }
    return HabitStore.state(context)
  }

  private fun advanceWindows(unlock: UnlockState, now: Long): UnlockState {
    fun advance(window: UnlockWindow?): UnlockWindow? {
      if (window == null) return null
      return window.copy(savedRemainingMs = remainingMs(window, now), savedAtElapsed = now)
    }
    return unlock.copy(cooldown = advance(unlock.cooldown), offWindow = advance(unlock.offWindow))
  }

  fun reschedule(context: Context) {
    val unlock = HabitStore.unlockState(context)
    val now = SystemClock.elapsedRealtime()
    val cooldownRemaining = remainingMs(unlock.cooldown, now)
    val offRemaining = remainingOffMs(unlock.offWindow, now)
    val delay = when {
      unlock.cooldown != null && cooldownRemaining > 0L -> cooldownRemaining
      unlock.offWindow != null && offRemaining > 0L -> offRemaining
      else -> -1L
    }
    HabitAlarms.schedule(context, delay)
  }

  fun resumeAfterBoot(context: Context) {
    tick(context)
    reschedule(context)
    HabitWatchdog.schedule(context)
    HabitWatchdog.check(context, force = true)
    val state = HabitStore.state(context)
    val unlock = HabitStore.unlockState(context)
    if (state.protectionEnabled || unlock.cooldown != null || unlock.offWindow != null) {
      HabitForegroundService.start(context)
    }
  }

  // endregion

  // region character challenge (method A)

  fun startChallenge(context: Context, length: Int): Map<String, Any?>? {
    if (lockRemainingMs(context) > 0L) return null
    val challenge = CharacterChallenge.start(length) ?: return null
    return mapOf(
      "challengeId" to challenge.id,
      "text" to challenge.text,
      "length" to challenge.length
    )
  }

  fun submitChallenge(context: Context, challengeId: String, typed: String): Map<String, Any?> {
    val locked = lockRemainingMs(context)
    if (locked > 0L) {
      return mapOf(
        "success" to false,
        "challengeId" to "",
        "text" to "",
        "length" to 0,
        "lockRemainingMs" to locked,
        "failuresToday" to failuresToday(context)
      )
    }

    if (CharacterChallenge.submit(challengeId, typed)) {
      recordSuccess(context)
      exitStrict(context)
      return mapOf(
        "success" to true,
        "challengeId" to "",
        "text" to "",
        "length" to 0,
        "lockRemainingMs" to 0L,
        "failuresToday" to failuresToday(context)
      )
    }

    recordFailure(context)
    val rotated = CharacterChallenge.rotate()
    return mapOf(
      "success" to false,
      "challengeId" to (rotated?.id ?: ""),
      "text" to (rotated?.text ?: ""),
      "length" to (rotated?.length ?: 0),
      "lockRemainingMs" to lockRemainingMs(context),
      "failuresToday" to failuresToday(context)
    )
  }

  // endregion

  // region failures (counts only)

  fun recordFailure(context: Context) {
    var unlock = HabitStore.unlockState(context)
    val failures = unlock.failures
    val today = HabitStore.today()
    val dayCount = if (failures.day == today) failures.dayCount + 1 else 1
    val consecutive = failures.consecutive + 1
    val lockMs = when {
      consecutive >= 8 -> 15L * 60L * 1000L
      consecutive >= 5 -> 5L * 60L * 1000L
      consecutive >= 3 -> 60L * 1000L
      else -> 0L
    }
    val lockedUntil = if (lockMs > 0L) System.currentTimeMillis() + lockMs else failures.lockedUntilWall
    unlock = unlock.copy(
      failures = FailureState(
        total = failures.total + 1,
        consecutive = consecutive,
        day = today,
        dayCount = dayCount,
        lockedUntilWall = lockedUntil
      )
    )
    HabitStore.writeUnlockState(context, unlock)
    Log.i(TAG, "Unlock failed (consecutive=$consecutive, today=$dayCount)")
  }

  fun recordSuccess(context: Context) {
    val unlock = HabitStore.unlockState(context)
    HabitStore.writeUnlockState(
      context,
      unlock.copy(failures = unlock.failures.copy(consecutive = 0, lockedUntilWall = 0L))
    )
  }

  // endregion

  // region unlock completion + debug

  private fun exitStrict(context: Context): ProtectionState {
    CharacterChallenge.clear()
    val wasAggressive = HabitStore.state(context).mode == ProtectionState.MODE_AGGRESSIVE
    val unlock = HabitStore.unlockState(context)
    HabitStore.writeUnlockState(
      context,
      unlock.copy(
        cooldown = null,
        offWindow = null,
        failures = unlock.failures.copy(consecutive = 0, lockedUntilWall = 0L)
      )
    )
    val next = HabitStore.updateState(context) {
      it.copy(mode = ProtectionState.MODE_NORMAL, protectionEnabled = false)
    }
    HabitAlarms.cancel(context)
    HabitAlarms.cancelWatchdog(context)
    HabitNotifier.clearProtectionWarnings(context)
    if (wasAggressive) HabitDeviceAdmin.remove(context)
    HabitForegroundService.stop(context)
    return next
  }

  /** Called by any successful unlock method (A, B or C). */
  fun completeUnlock(context: Context): ProtectionState {
    recordSuccess(context)
    return exitStrict(context)
  }

  /** Debug builds only: lets the tester skip the cooldown and leave Strict instantly. */
  fun setFastCooldown(context: Context, enabled: Boolean): Boolean {
    if (!isDebugBuild(context)) return false
    val unlock = HabitStore.unlockState(context)
    HabitStore.writeUnlockState(context, unlock.copy(fastCooldown = enabled))
    return true
  }

  fun debugExitStrict(context: Context): ProtectionState? {
    if (!isDebugBuild(context)) return null
    CharacterChallenge.clear()
    return exitStrict(context)
  }

  // endregion
}
