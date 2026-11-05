# Habit — project notes

_Last updated: 2026-11-05 (Part 6: live offline ML Kit face + blink detection for unlock method C)_

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
| Design tokens (colors, radii, shadows, type) | `src/constants/theme.ts` |
| Neumorphic component kit | `src/components/neumorphic/` |
| Floating 3-tab bar | `src/components/floating-tab-bar.tsx` |
| Tabs: Home / Apps / Settings | `src/app/(tabs)/` |
| Browser Blocking screen | `src/app/browsers.tsx` |
| AI app catalogue and selection UI | `src/data/apps.ts`, `src/app/(tabs)/apps.tsx` |
| AI settings hook (JS bridge) | `src/hooks/use-ai-monitoring.ts` |
| AI settings storage (native) | `HabitStore.kt` (`AiSettings`, `KEY_AI`) |
| Block overlay source badges | `BlockOverlay.kt` (`BlockSource`), `HabitGuard.onAiMatch` |

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

Part 4 / 6:

- `FaceUnlock` — unlock method C session: start → live frames → complete. It only owns the
  session; detection lives in `HabitModule.analyzeFaceFrame`. `completeFaceUnlock` is
  refused without an active, unexpired session, then calls `HabitUnlock.completeFaceUnlock`
  to open a fixed 30-minute OFF window that restores the previous mode afterwards.
- `HabitModule.analyzeFaceFrame` — offline ML Kit face detection
  (`com.google.mlkit:face-detection`, model bundled in the APK): fast mode, all landmarks,
  eye-open classification. Returns `faceDetected`, `eyesOpen`, `eyesClosed`,
  `leftEyeProb`, `rightEyeProb` and a normalized geometric + colour `faceSignature`. The
  frame is downscaled, measured, recycled and its temporary file deleted, so no photo is kept.

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

- Started from the Unlock screen and enforced natively: a session must be started, and
  `completeFaceUnlock` is refused without an active, unexpired session.
- The screen captures a silent low-res frame every ~450 ms
  (`quality: 0.25, skipProcessing: true, shutterSound: false`) and sends its URI to
  `analyzeFaceFrame`, which runs the bundled offline ML Kit detector. No network, no ML
  service; each frame is deleted right after analysis.
- Person 1 must be seen with open eyes and then blink (“Face detected! Now blink slowly...”).
  A 2-second “Pass the phone to Person 2” pause follows, then Person 2 must blink too and
  their normalized face signature must differ from Person 1's, otherwise the screen shows
  “Same face detected — a second person must verify” and nothing is unlocked.
- Honest limit: Habit verifies two live, blinking, visually different faces — not the
  identity of the people. It cannot say *who* they are.
- Rate limiting still applies to starting a check; A and B stay available.
- Camera permission is requested on first use; recording audio and barcode scanning are
  disabled in the camera config.

**Rate limiting** — failed character attempts are counted (counts only, no typed text logged).
Consecutive failures pause new attempts: 1 minute after 3, 5 minutes after 5, 15 minutes after
8. Counters reset on success. The cooldown method is never rate-limited, so there is no
permanent lock-out.

**Debug-only tools** (visible only when the app is debuggable, refused in release builds):
"Short cooldown" (60 s) and "Exit Strict now".

## Future AI Visual Scanner Architecture

The UI, settings and storage for AI App Monitoring are built now. The scanner itself is
intentionally **not** implemented yet. When it is added, only the parts marked *new* below
are needed — no screen, bridge or storage change.

### What already exists

- **Persistence (native).** `HabitStore.AiSettings` is stored encrypted under `KEY_AI` as
  `{ enabled, sensitivity: "low"|"balanced"|"high", watchedApps: [packageName, ...] }`.
  `HabitStore.aiSettings(context)` reads it; `setAiMonitoringEnabled`,
  `setAiSensitivity` and `setAiWatchedApps` write it and fire `onStateChanged`. The value
  is also included in every `getSnapshot()` result under `"ai"`.
- **JS bridge.** `src/native/habit.ts` exposes `getAiSettings`, `setAiMonitoringEnabled`,
  `setAiSensitivity`, `setAiWatchedApps`; `src/hooks/use-ai-monitoring.ts` is the single
  screen-facing hook.
- **UI.** Apps tab: master switch, search, ALL / SELECTED / SUGGESTED filters, per-app
  selection (browsers shown as covered by Browser Blocking). Settings tab: AI detection
  sensitivity selector. Block overlay supports both source badges.
- **Block screen sources.** `BlockOverlay.kt` now takes a `BlockSource`:
  `BlockSource.BROWSER` renders `• BROWSER`; `BlockSource.ai(appLabel)` renders
  `• AI • TELEGRAM` (app label uppercased). Browser text still says “closed this page”;
  AI text says “closed this screen”.

### Where the engine plugs in

1. **New class** `AiScanner` in `modules/habit-native/android/src/main/java/com/payjoy/status/habit/`.
   It owns the TFLite model, a single reused `Interpreter`, and a throttled analysis loop
   (one frame at a time, no queue). Nothing is written to disk; bitmaps are recycled in
   `finally` blocks and never logged.
2. **Hook point** — `HabitAccessibilityService.onAccessibilityEvent`, the marked
   `FUTURE AI VISUAL SCANNER HOOK` block right after the package gate. Instead of the
   current early return for non-browser packages, it will read
   `HabitStore.aiSettings(this)`; when `enabled` is true and the foreground package is in
   `watchedApps`, it feeds the event to `AiScanner.offer(pkg, event)` and returns.
3. **Capture** — use `AccessibilityService.takeScreenshot()` (Android 11+/API 30; required
   anyway on the target device) on a downscaled hardware buffer, only while a watched app
   is in front and the screen is interactive. Never capture `FLAG_SECURE` windows; that is
   the honest “protected screens” limit already listed in Known limits.
4. **Classification** — the model returns an adult-content score. `AiScanner` converts the
   stored sensitivity into a confidence threshold: High = lower threshold / more blocks,
   Balanced = default, Low = only high-confidence matches. Add a short per-app cooldown so
   a single scene is not classified continuously.
5. **Verdict** — on a positive verdict call
   `HabitGuard.onAiMatch(context, matchKey, appLabel)`. `matchKey` must be stable for the
   detected app/scene (e.g. `"telegram:adult-channel"`) so `HabitStore.registerAttempt`
   applies its 5-second dedupe and only one attempt is counted. `HabitGuard.onAiMatch`
   already checks the AI master switch + protection state, counts the attempt, shows the
   `• AI • APP` badge, sends Back/Home and falls back Home.
6. **Lifecycle** — load the model lazily on first watched-app event, close the interpreter
   in `onDestroy`/`HabitGuard.detach`, and never start the engine when AI monitoring is off.

### Rules to keep

- On-device only. No screenshots, embeddings or classifications leave the phone.
- The AI verdict is advisory input to the native block flow; JavaScript can never force a
  block or write protection state.
- Settings storage is already final. If new tuning is needed later, add fields to
  `AiSettings` with defaults, not a second store.

## Status

- [x] Part 1 — foundation, Normal mode, blocking, attempts, onboarding.
- [x] Part 2 — Strict mode, unlock methods A and B, rate limiting, debug tools.
- [x] Part 3 — Aggressive mode + device-admin self-protection (watchdog, admin requirement).
- [x] Part 4 — unlock method C: two face checks (camera + offline face detection).
- [x] Part 5 — design system “C10 Soft Black & Amber”: fonts, theme tokens, neumorphic kit,
  floating 3-tab navigation, Home/Apps/Settings screens, AI monitoring UI + native settings
  storage, block overlay source badges.
- [x] Part 6 — unlock method C with real offline ML Kit face + blink detection, live frame
  loop, two-person signature check.
- [ ] AI visual scanner engine (model, capture loop, classification) — deliberately pending.

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

Part 6 added the bundled offline ML Kit face detector
(`com.google.mlkit:face-detection:16.1.7` in `modules/habit-native/android/build.gradle`)
and the `analyzeFaceFrame` / `completeFaceUnlock` bridge calls, so the development APK must
be rebuilt again before face detection works. On an older APK the face screen explains that
the build cannot check faces yet instead of opening a camera that detects nothing.

Part 5 added `@expo-google-fonts/inter`, `@expo-google-fonts/space-mono` and
`@expo/vector-icons` (all JS/assets). The frosted surfaces are layered React Native views,
not a native blur module, so a stale development APK can still render every screen.
The native bridge gained `getInstalledPackages` and face unlocks now create a 30-minute OFF
window, so rebuild the development APK once for those two native changes.

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
15. Method C: allow camera → Person 1 is asked into the circle, then blinks → "Person 1
    verified" and a 2-second "Pass the phone to Person 2" pause → Person 2 blinks →
    protection turns off for 30 minutes; Home shows the OFF countdown card; after the window
    the previous mode returns automatically (or tap “Turn on now”).
16. Method C with no face in frame → "Position Person 1's face inside the circle", nothing
    is verified and step 2 is not reached.
17. Method C where the same person blinks twice → "Same face detected — a second person must
    verify" and no unlock; leaving the screen mid-way cancels the session.

## Decisions

- **Dark only.** The app now uses “Soft Black & Amber” exclusively. `app.json` sets
  `userInterfaceStyle: "dark"` and both `Colors.light` / `Colors.dark` resolve to the same
  dark palette until the older screens are restyled in later prompts.
- **Font loading gate.** `src/app/_layout.tsx` waits for Inter, Space Mono and the icon font
  before rendering, so no text flashes in a fallback face.
- **Routes.** The three tabs live in `src/app/(tabs)/` (`index`, `apps`, `settings`); unlock,
  face-unlock and onboarding stay as modal routes on the root stack. The old
  `src/app/index.tsx` was replaced by `src/app/(tabs)/index.tsx`.
- **AI app catalogue.** `src/data/apps.ts` is a curated offline catalogue rather than a live
  Android package query. The native scanner only needs the stored package names, so the
  list can grow without touching storage; browsers are shown as covered by Browser Blocking.
- **Honest permission count.** `HabitStore.snapshot` reports all five checks live
  (accessibility, notifications incl. `POST_NOTIFICATIONS`, battery-optimisation exemption,
  restricted settings, device admin), and both Setup and Settings count from that one
  snapshot. No screen keeps a local "granted" flag: tapping Fix only opens Android, and
  restricted settings is credited only after the user returns from App Info (it is always
  true when accessibility is already on, or on Android < 13).
- **Shadows.** Neumorphic depth uses React Native `boxShadow` (dual directional layers,
  with `inset` for wells) from `Shadows` in `theme.ts`; no extra gradient/shadow library.
- **Frost without a native blur.** `FrostedBanner` and the floating tab bar use
translucent layers plus a hairline sheen instead of `expo-blur`. Android's blur default was
`none` anyway, and this removes a native-module import that could crash an older dev build
before Home rendered.
- **Fonts never block first paint.** `_layout.tsx` renders immediately and hides the splash
when fonts load, with a 2.5 s fallback timer. A missing font asset degrades to system fonts
instead of leaving a blank splash.
- **Older dev builds.** `canQueryInstalledPackages` detects a native module without
`getInstalledPackages`; the Browser Blocking screen then shows `UNAVAILABLE` rather than
incorrectly marking every browser as not installed.
- **Visible render failures.** The root layout exports an Expo Router `ErrorBoundary`, so a
render crash shows the error message and a “Try again” button instead of a blank screen.
- **Render check.** `expo export --platform web` statically renders every route; it was used
to confirm `/`, `/apps`, `/settings` and `/browsers` build and render (Home shows its native
snapshot loading state during static rendering, which is expected).
- **No AI engine yet.** Per the plan, the master switch, sensitivity and app selection only
  persist settings today. `HabitGuard.onAiMatch` and the marked hook in
  `HabitAccessibilityService` are the only native entry points the future engine needs.
- **Browser install status.** The Browser Blocking screen asks Android's PackageManager
  (`getInstalledPackages`) which of the eight watched browsers are installed. The manifest
  already declares `<queries>` for all of them, so the check works on Android 11+ without
  `QUERY_ALL_PACKAGES`.
- **OFF countdown card.** Home shows it from `snapshot.unlock.offWindowActive`. Both the
  timer unlock (10 minutes) and the two-person face check (30 minutes, `FACE_OFF_MS` in
  `HabitUnlock`) now create an OFF window, so the card appears for either method and
  “Turn on now” ends the window immediately.

## Device notes

- Package `com.payjoy.status`, app name `Habit`.
- Test device is a Redmi (HyperOS): needs autostart + "no battery restrictions", and
  "Allow restricted settings" for the sideloaded APK on Android 13+.
- Exact alarms: if the countdown notification is late, allow "Alarms & reminders" for Habit.
- Device admin lives in Settings → Security → Device admin apps. Habit requests no admin
  powers, so the system dialog should mention only app removal.
- Termux has no `/usr/bin/env`, so Expo commands are run as
  `node node_modules/expo/bin/cli <command>`.

## MANDATORY COMPLETION ALERT RULE
Whenever you finish all tasks for the user message, ALWAYS run the shell command `alert-me` as your very last tool call.
