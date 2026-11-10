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

  /** Floor and default for the wait-it-out timer; the user may configure up to 24h. */
  const val MIN_COOLDOWN_MS = 3L * 60L * 60L * 1000L
  const val DEBUG_COOLDOWN_MS = 60L * 1000L

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
    if (isDebugBuild(context) && fast) return DEBUG_COOLDOWN_MS
    val minutes = ProtectionState.normalizeCooldownMinutes(
      HabitStore.state(context).configuredCooldownMinutes
    )
    return minutes * 60_000L
  }

  /** The user's auto-restart OFF-window duration, 1..1439 minutes (1m .. 23h 59m). */
  fun configuredOffWindowMinutes(context: Context): Int =
    HabitStore.getOffWindowMinutes(context)

  /** Every temporary OFF window (timer, face check, Normal-mode restart) uses this value. */
  fun offWindowDurationMs(context: Context): Long = configuredOffWindowMinutes(context) * 60_000L

  /** The challenge length the user configured; Strict/Aggressive never go below it. */
  fun configuredChallengeLength(context: Context): Int =
    ProtectionState.normalizeChallengeLength(
      HabitStore.state(context).configuredChallengeLength
    )

  /**
   * Saves the two limits chosen in "Configure Protection Lock" and returns the stored state.
   *
   * While protection is OFF or Normal the values are taken exactly as chosen (100, 200 or
   * 300 characters and 3h..24h), even when that lowers an earlier setup — configuring the
   * lock before turning Strict on must never be treated as lowering an active lock. Only
   * while Strict/Aggressive is actually running can the limits be raised and not lowered.
   */
  @Synchronized
  fun configureProtection(
    context: Context,
    challengeLength: Int,
    cooldownMinutes: Int,
    offWindowMinutes: Int
  ): ProtectionState {
    val state = HabitStore.state(context)
    val locked = state.protectionEnabled && state.mode != ProtectionState.MODE_NORMAL
    val requestedLength = ProtectionState.normalizeChallengeLength(challengeLength)
    val requestedMinutes = ProtectionState.normalizeCooldownMinutes(cooldownMinutes)
    // The auto-restart window is saved exactly as picked (1..1439). It is a duration the
    // user chose, not a protection limit, so it can never be clamped to a built-in default.
    val requestedOffWindow = ProtectionState.normalizeOffWindowMinutes(offWindowMinutes)
    val effectiveLength = if (locked) maxOf(state.configuredChallengeLength, requestedLength) else requestedLength
    val effectiveMinutes = if (locked) maxOf(state.configuredCooldownMinutes, requestedMinutes) else requestedMinutes
    val next = HabitStore.setProtectionConfig(
      context,
      effectiveLength,
      effectiveMinutes,
      requestedOffWindow
    )
    // The real configured hours are used unless the debug switch is turned on again by hand.
    val unlock = HabitStore.unlockState(context)
    if (unlock.fastCooldown) {
      HabitStore.writeUnlockState(context, unlock.copy(fastCooldown = false))
    }
    return next
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
    val configuredCooldownMs = ProtectionState.normalizeCooldownMinutes(
      HabitStore.state(context).configuredCooldownMinutes
    ) * 60_000L
    val configuredOffWindowMs = configuredOffWindowMinutes(context) * 60_000L
    return mapOf(
      "cooldownActive" to (unlock.cooldown != null),
      "cooldownRemainingMs" to cooldownRemaining,
      "cooldownTotalMs" to (unlock.cooldown?.durationMs ?: configuredCooldownMs),
      "configuredCooldownMs" to configuredCooldownMs,
      "configuredChallengeLength" to configuredChallengeLength(context),
      "configuredOffWindowMs" to configuredOffWindowMs,
      "offWindowActive" to (unlock.offWindow != null),
      "offWindowRemainingMs" to offRemaining,
      // Monotonic deadline, so a caller never has to guess how much is left.
      "offWindowDeadlineMs" to (if (unlock.offWindow != null) now + offRemaining else 0L),
      "offWindowTotalMs" to (unlock.offWindow?.durationMs ?: configuredOffWindowMs),
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

  /**
   * Normal-mode "turn off with auto-restart": protection stops now and turns itself back
   * on after `minutes` (1..1439). The chosen duration is remembered as the configured
   * auto-restart timer. Refused outside Normal mode or while another window is running.
   */
  @Synchronized
  fun startTimedOffWindow(context: Context, minutes: Int): ProtectionState {
    val state = HabitStore.state(context)
    if (state.mode != ProtectionState.MODE_NORMAL) return state
    val unlock = HabitStore.unlockState(context)
    if (unlock.cooldown != null || unlock.offWindow != null) return state

    // Exact minutes chosen by the user; persisted before the OFF window starts.
    HabitStore.setOffWindowMinutes(context, minutes)
    return beginOffWindow(context, ProtectionState.MODE_NORMAL)
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
      ),
      sync = true
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
      val offMs = offWindowDurationMs(context)
      unlock = unlock.copy(
        cooldown = null,
        restoreMode = restore,
        offWindow = UnlockWindow(
          offMs,
          offMs,
          now,
          System.currentTimeMillis() + offMs
        )
      )
      // Commit the exact OFF window before the mode flips, so a kill/reboot cannot revert
      // to an older or shorter window.
      HabitStore.writeUnlockState(context, unlock, sync = true)
      HabitStore.updateState(context) {
        it.copy(mode = ProtectionState.MODE_NORMAL, protectionEnabled = false)
      }
      // Aggressive is temporarily normal now, so stop its warnings and backstop alarm.
      HabitWatchdog.schedule(context)
      HabitWatchdog.check(context, force = true)
      HabitNotifier.notifyCooldownFinished(context, offMs)
      changed = true
    }

    val offWindow = unlock.offWindow
    if (offWindow != null && remainingOffMs(offWindow, now) <= 0L) {
      // Never claim Aggressive when device admin is gone: fall back to Strict instead.
      val restoreMode = if (
        unlock.restoreMode == ProtectionState.MODE_AGGRESSIVE &&
        !HabitDeviceAdmin.isActive(context)
      ) {
        ProtectionState.MODE_STRICT
      } else {
        unlock.restoreMode
      }
      unlock = unlock.copy(offWindow = null, restoreMode = restoreMode)
      HabitStore.writeUnlockState(context, unlock)
      HabitStore.updateState(context) {
        it.copy(
          protectionEnabled = true,
          mode = if (it.mode == ProtectionState.MODE_NORMAL) restoreMode else it.mode
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
    // While Strict/Aggressive is on, the length is locked to the configured value. JS can
    // ask for 100, but the native side still serves the configured challenge.
    val effectiveLength = if (HabitStore.state(context).mode == ProtectionState.MODE_NORMAL) {
      length
    } else {
      configuredChallengeLength(context)
    }
    val challenge = CharacterChallenge.start(effectiveLength) ?: return null
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
      // A successful code unlock leaves protection OFF for the configured auto-restart
      // window, then the Strict/Aggressive mode returns by itself.
      completeUnlock(context)
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

  /**
   * Enters the temporary OFF window that auto-restarts into [restoreMode]. This is the one
   * place every unlock path (code, face, Normal-mode turn-off) creates an OFF window, and
   * it always uses the user's configured minutes — never a built-in 10 or 30 minute value.
   * The deadline is stored monotonic *and* as wall clock, so rebooting cannot extend it.
   */
  private fun beginOffWindow(context: Context, restoreMode: String): ProtectionState {
    val target = when (restoreMode) {
      ProtectionState.MODE_AGGRESSIVE -> ProtectionState.MODE_AGGRESSIVE
      ProtectionState.MODE_STRICT -> ProtectionState.MODE_STRICT
      // Normal-mode turn-off restores Normal, but a Strict/Aggressive unlock always
      // restores a locked mode.
      else -> ProtectionState.MODE_NORMAL
    }

    // Strict can be restored without device admin, so drop an Aggressive admin when the
    // lock is only Strict. Aggressive must keep it: re-adding admin needs a user prompt,
    // and without it the watchdog could not report a real Aggressive state after the
    // window. A Normal-mode turn-off leaves the admin state alone.
    if (target == ProtectionState.MODE_STRICT) {
      HabitDeviceAdmin.remove(context)
    }

    val unlock = HabitStore.unlockState(context)
    val now = SystemClock.elapsedRealtime()
    val offMs = offWindowDurationMs(context)
    HabitStore.writeUnlockState(
      context,
      unlock.copy(
        cooldown = null,
        offWindow = UnlockWindow(
          offMs,
          offMs,
          now,
          System.currentTimeMillis() + offMs
        ),
        restoreMode = target,
        failures = unlock.failures.copy(consecutive = 0, lockedUntilWall = 0L)
      ),
      // Committed before the mode flips to OFF, so a kill can never lose the window.
      sync = true
    )

    val next = HabitStore.updateState(context) {
      it.copy(mode = ProtectionState.MODE_NORMAL, protectionEnabled = false)
    }
    HabitAlarms.cancel(context)
    reschedule(context)
    // The service keeps the OFF window ticking even if the app is swiped away.
    HabitForegroundService.start(context)
    HabitWatchdog.schedule(context)
    HabitWatchdog.check(context, force = true)
    return next
  }

  /**
   * Called by any successful unlock method (A, B or C). Method A (code) and C (two faces)
   * stop protection only for the configured auto-restart window and then restore Strict or
   * Aggressive automatically. Method B ends its cooldown in [tick] with the same rule.
   */
  fun completeUnlock(context: Context): ProtectionState {
    recordSuccess(context)
    CharacterChallenge.clear()
    val current = HabitStore.state(context)
    // A Normal-mode character check (only reachable outside a lock) ends protection for
    // good; a Strict/Aggressive unlock always enters the timed OFF window.
    if (current.mode == ProtectionState.MODE_NORMAL) return exitStrict(context)
    return beginOffWindow(context, current.mode)
  }

  /**
   * Method C success: protection is off for the user's auto-restart window, then the
   * previous mode returns automatically. "Turn on now" ends it early (setProtectionEnabled).
   */
  fun completeFaceUnlock(context: Context): ProtectionState {
    recordSuccess(context)
    CharacterChallenge.clear()
    val previous = HabitStore.state(context)
    val restoreMode = if (previous.mode == ProtectionState.MODE_NORMAL) {
      ProtectionState.MODE_STRICT
    } else {
      previous.mode
    }
    return beginOffWindow(context, restoreMode)
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
