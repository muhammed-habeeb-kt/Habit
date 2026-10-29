# Habit — project notes

_Last updated: 2026-10-29 (Part 4: unlock method C — two face checks)_

## Product

Habit blocks 18+ (adult) websites on the phone, like the Stay Focused app. An Android
AccessibilityService watches browsers (Chrome, Mi Browser, Samsung Internet, Firefox, Brave,
Edge, Opera, DuckDuckGo), reads URL/search text, and on a match with an adult-domain blocklist
or keyword list it immediately sends the user Back/Home and shows a Block Screen (within ~1
second).

Modes:

- **Normal** — no restrictions, toggle freely.
- **Strict** — cannot be turned off without an unlock.
- **Aggressive** — Strict + device-admin self-protection.

Unlock methods for Strict/Aggressive (any one):

- **A.** Typing a long scrambled character string (slabs of 100/200/300 chars).
- **B.** Timer (minimum 3 hour cooldown, then protection turns off and auto-turns-on again
  within ~10 minutes).
- **C.** Face verification of two people, one after another. (Built: two separate face
  checks; see “Unlock method C” below.)

Raising protection is always instant; lowering it in Strict/Aggressive needs an unlock.

## Architecture rules

- **Expo Development Build** (`expo-dev-client`) so the APK is installed once and UI changes
  are tested live with the dev server.
- **Native Kotlin code owns detection, the mode state machine, timers, unlock checks and
  self-protection.** State lives in encrypted native storage; JS can only request actions.
- **JS/TypeScript owns all UI, theme, texts and the blocklist/keyword JSON** (pushed into
  native storage).
- **Fail closed:** if state is missing or corrupt, treat protection as ON.
- **Privacy:** never store URLs or browsing history, only daily counts.
- No analytics, works offline.

## Code map

| Piece | Where |
| --- | --- |
| Kotlin module (autolinked local Expo module) | `modules/habit-native/android/src/main/java/com/payjoy/status/habit/` |
| Android manifest, permissions, accessibility XML | `plugins/withHabitAndroid.js` (Expo config plugin) |
| Theme (all colors / type / spacing) | `src/constants/theme.ts` |
| Native bridge for JS | `src/native/habit.ts` |
| Screens | `src/app/index.tsx`, `src/app/unlock.tsx`, `src/app/face-unlock.tsx`, `src/app/onboarding.tsx` |
| Countdown helpers | `src/components/countdown.tsx` |
| Blocklist + keywords | `src/data/blocklist.json`, `src/data/keywords.json` |

## Native pieces

Part 1:

- `HabitAccessibilityService` — browser package gate, URL bar / window title / text scan.
- `AdultFilter` — subdomain-aware domain list + whole-word keyword regex.
- `HabitStore` — Keystore-encrypted preferences: protection state, unlock state, per-day
  attempt counts, lists.
- `HabitGuard` — block flow (Back, then Home if still blocked), overlay wiring.
- `BlockOverlay` — full-screen calm block screen drawn over the browser.
- `HabitForegroundService` — persistent notification, `START_STICKY`, 1 s unlock ticker.
- `BootReceiver` — restarts protection and re-arms unlock alarms after reboot / app update.
- `HabitDeviceAdminReceiver` — manifest receiver, activated only by Aggressive mode.
- `HabitModule` — the JS-facing API (snapshot, toggle, mode, unlock, lists, settings).

Part 2:

- `HabitUnlock` — the whole mode state machine, cooldown engine, rate limiting and challenge
  entry points.
- `CharacterChallenge` — SecureRandom printable-ASCII slabs (100/200/300), Fisher-Yates
  shuffled, verified natively, never persisted.
- `HabitAlarms` + `HabitAlarmReceiver` — `ELAPSED_REALTIME_WAKEUP` exact alarms (inexact
  fallback) so cooldown end / auto re-enable happen even if the process was killed.
- `HabitNotifier` — notifications for "cooldown finished" and "protection is back on".

Part 3:

- `HabitDeviceAdmin` — minimal device-admin helper (check, request, self-remove). The admin
  declares **no policies**: it exists only so Android confirms before Habit is uninstalled.
- `HabitWatchdog` — Aggressive-mode self-protection: checks the accessibility service and the
  device admin every 15 s, warns loudly when either is missing, and keeps a 15-minute backstop
  alarm armed.

Part 4:

- `FaceUnlock` — unlock method C. Native session: start → first face photo → wait ≥ 3 s →
  second face photo. Uses Android's built-in offline `FaceDetector`; images are decoded  in memory, counted, and recycled; the camera's temporary capture file is deleted right
  after counting, so no photo is kept. Missing faces count as failed attempts and respect
  the same rate limit as method A.

## Mode state machine (native)

- OFF → ON: instant, from any mode.
- Normal → Strict: instant, protection turns on.
- Strict → Normal, Strict → OFF, or choosing Normal while Strict: refused by native until an
  unlock succeeds. JS routes these taps to the Unlock screen.
- Aggressive is refused in Part 2 (button is "coming soon").
- A successful unlock (A or B) ends Strict: mode becomes Normal and protection turns off.
- Timer unlock: mode becomes Normal + protection off **only at the end** of the cooldown; an
  OFF window of 10 minutes follows, then protection returns automatically in the previous
  mode (Strict).
- Turning protection back on during the OFF window is instant and cancels the pending restore.

## Aggressive mode (native rules)

- Selecting Aggressive requires the device admin to be active first (JS opens Android's
  activation dialog); once admin is active, raising to Aggressive is instant.
- Aggressive inherits every Strict rule: protection always ON; turning it off or lowering
  the mode is refused until an unlock (methods A or B).
- Turning Aggressive back to Strict is a *lower*, so it needs an unlock too.
- Unlock A from Aggressive: mode → Normal, protection OFF, and Habit removes its own device
  admin again.
- Timer unlock from Aggressive: protection OFF only for the 10-minute window, then returns to
  Aggressive (admin is kept, so the restore works).
- Watchdog: while Aggressive, if the accessibility service or device admin is off, Habit shows
  a high-importance warning notification and a red banner on Home. The mode never silently
  downgrades — protection stays failing closed.
- Self-protection is deliberately **not** the "block the Settings screens" variant: Habit does
  not fight the user inside Android Settings. The device admin only forces an
  uninstall confirmation.

## Clocks, reboots, alarms

- Cooldown time is stored as a monotonic remaining value (`SystemClock.elapsedRealtime`), never
  as a wall-clock deadline. Changing the phone clock cannot shorten it.
- Every tick (1 s while the foreground service runs) persists the remaining value at most every
  5 s. After a reboot the last persisted value is used, so a reboot cannot shorten a cooldown.
- The 10-minute OFF window additionally respects a wall-clock deadline and always takes the
  *shorter* of the two values, so rebooting (or a forward clock change) cannot lengthen it.
- Alarms are `ELAPSED_REALTIME_WAKEUP` (immune to clock changes). They do not survive reboot,
  so `BootReceiver` re-arms them from encrypted state.
- If the exact-alarm permission is unavailable, scheduling falls back to `setAndAllowWhileIdle`
  and the foreground-service ticker still applies the transition on time.

## Unlock methods

**A — character challenge**

- Slabs of 100, 200 or 300 printable ASCII characters; any one completed slab unlocks.
- Shown in monospace, chunked in groups of 10. Typed value is compared natively (whitespace is
  ignored); one wrong character fails immediately and a brand-new string is generated.
- JS blocks paste (large jumps), autocorrect, suggestions, autofill and text selection.

**B — timer**

- 3 hours minimum (1 minute in debug builds when the debug switch is on).
- Protection stays fully active during the cooldown; the countdown is shown and can be
  cancelled (cancelling keeps Strict on and unlocks nothing).
- At zero: notification, protection off, 10-minute OFF window, then automatic re-enable.

**C — two face checks**

- Started from the Unlock screen (“Start face check”) and enforced natively: a session must
  be started, each photo must contain at least one face, and the second photo must be taken
  at least 3 seconds after the first.
- Uses `expo-camera` (front camera) and `android.media.FaceDetector`; fully offline, no ML
  service. Each capture is deleted immediately after the face count, so no photo is kept.
- Honest limit: Habit verifies that **two separate face photos** were taken, not the identity
  of the people. It cannot tell whether the same person stepped in twice.
- Failure to read a photo counts as a failed attempt (same 1/5/15-minute rate limit as A), so
  the method can never permanently lock you out — A and B stay available.
- Camera permission is requested on first use; recording audio and barcode scanning are
  disabled in the camera config.

**Rate limiting** — failed character attempts are counted (counts only, no typed text logged).
Consecutive failures pause new attempts: 1 minute after 3, 5 minutes after 5, 15 minutes after
8. Counters reset on success. The cooldown method is never rate-limited, so there is no
permanent lock-out.

**Debug-only tools** (visible only when the app is debuggable, refused in release builds):
"Short cooldown" (60 s) and "Exit Strict now".

## Status

- [x] Part 1 — foundation, Normal mode, blocking, attempts, onboarding.
- [x] Part 2 — Strict mode, unlock methods A and B, rate limiting, debug tools.
- [x] Part 3 — Aggressive mode + device-admin self-protection (watchdog, admin requirement).
- [x] Part 4 — unlock method C: two face checks (camera + offline face detection).

## Build & run (Termux, no PC)

Termux has no `/usr/bin/env`, so `npx expo` / bare `expo` / `eas` shims fail with
`not found`. Always call the JS entry files directly (npm scripts below already do).

```bash
cd ~/Habit
npm install                              # once, after pulling changes
npm run eas -- login                     # interactive, then `eas whoami`
npm run eas -- build -p android --profile development
npm run start                            # metro, for the dev build
timeout 180 npm run typecheck            # tsc
npm run lint                             # eslint
```

Part 4 added a native dependency (`expo-camera`), so the development APK has to be rebuilt
once before the face unlock screen can work.

Build profiles live in `eas.json` (`development` = dev client APK, `preview` = release APK).
The generated `android/` folder is never committed; `expo prebuild` (run by EAS) recreates it
from `app.json` + `plugins/withHabitAndroid.js`.

## Test checklist (Part 2)

1. Normal mode: toggle OFF/ON freely.
2. Choose Strict → confirm explanation; protection turns on.
3. Tap the ON toggle → Unlock screen opens (no way to turn it off directly).
4. Method A: pick 100, type a wrong character → new string; type a slab correctly → unlocked,
   mode Normal, protection off.
5. Method A: 200 and 300 slabs generate correctly; paste is rejected.
6. Method B: start the 3-hour cooldown → protection keeps blocking; cancel → still Strict.
7. Debug build: turn on "Short cooldown" (1 min), start it, lock the phone → after ~1 min a
   notification arrives, protection is off, then ~10 min later protection returns in Strict.
8. Reboot during a cooldown → countdown continues (does not restart or shorten); reboot during
   the OFF window → it does not get longer.
9. Change the phone clock during a cooldown → countdown unaffected.
10. 3+ wrong character attempts → pause message with countdown; cooldown method still works.
11. Aggressive: choose it → Android device-admin dialog appears → Activate → mode becomes
    Aggressive and protection stays ON.
12. In Aggressive, tapping OFF opens Unlock; method A removes device admin on success.
13. In Aggressive, deactivate device admin in Android Settings → high-importance warning and
    red Home banner; mode stays Aggressive.
14. In Aggressive, turn accessibility off → warning notification + banner; turn it back on →
    warnings disappear.
15. Method C: allow camera → take the first photo → step 2 appears; wait ~3 s, take the
    second photo → protection turns off and mode becomes Normal.
16. Method C with no face in frame → “No face found”, attempt counted, second step not
    reached.
17. Method C leaving the screen mid-way and reopening starts a fresh session (the old one is
    cancelled).

## Device notes

- Package `com.payjoy.status`, app name `Habit`.
- Test device is a Redmi (HyperOS): needs autostart + "no battery restrictions", and
  "Allow restricted settings" for the sideloaded APK on Android 13+.
- Exact alarms: if the countdown notification is late, allow "Alarms & reminders" for Habit.
- Device admin lives in Settings → Security → Device admin apps. Habit requests no admin
  powers, so the system dialog should mention only app removal.
- Termux has no `/usr/bin/env`, so Expo commands are run as
  `node node_modules/expo/bin/cli <command>`.
