# Habit — project notes

_Last updated: 2026-11-06 (Part 7: on-device AI screenshot cascade)_

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

Part 4:

- `FaceUnlock` — unlock method C. Native session: start → first face photo → wait ≥ 3 s →
  second face photo. Uses Android's built-in offline `FaceDetector`; images are decoded  in memory, counted, and recycled; the camera's temporary capture file is deleted right
  after counting, so no photo is kept. Missing faces count as failed attempts and respect
  the same rate limit as method A. Success calls `HabitUnlock.completeFaceUnlock`, which
  opens a fixed 30-minute OFF window and restores the previous mode afterwards.

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

## AI Visual Scanner (on-device screenshot cascade)

Built in `AiVisualScanner.kt` with every tunable in `AiCascadeConfig.kt`. The settings, bridge
and UI from Part 5 are unchanged; the engine only reads `HabitStore.aiSettings(context)`.

### Models and build

- `nsfw_mobilenet_v2.tflite` (17 MB, MobileNetV2 140 224, classes drawings / hentai / neutral /
  porn / sexy) and `nudenet_320n.onnx` (12 MB, NudeNet YOLOv8n, 18 classes) live in
  `modules/habit-native/android/src/main/assets/`.
- Both files are gitignored (about 30 MB) and fetched by `scripts/download-ai-models.sh`, which
  runs from npm `postinstall` and again as an explicit GitHub Actions step before
  `expo prebuild`. It verifies the `TFL3` magic, the ONNX protobuf tag and a > 1 MB size.
- `build.gradle` adds `org.tensorflow:tensorflow-lite:2.16.1` and
  `com.microsoft.onnxruntime:onnxruntime-android:1.19.2`, and stores `.tflite` / `.onnx`
  uncompressed so TFLite can map the asset instead of copying it into RAM.
- The generated accessibility XML declares `android:canTakeScreenshot="true"` and
  `flagRetrieveInteractiveWindows`; capture needs Android 11 (API 30) or newer.
- The XML deliberately has **no `android:packageNames`**: a package filter would stop Android
  from delivering window events for Telegram and the other watched apps, so the loop could
  never arm. The service receives every app's events and filters in Kotlin.
- The verified I/O contract (checked by parsing the files themselves): stage 1 is
  `FLOAT32 [1,224,224,3] -> [1,5]`; stage 2 is `FLOAT32 images [N,3,H,W] -> output0 [N,22,A]`
  (22 = 4 box rows + 18 classes). `AiVisualScanner` validates the stage-1 input shape on
  load, adapts to a 1-class or unknown-class output, and reports a mismatch instead of
  failing silently.

### Cascade per frame

1. `AccessibilityService.takeScreenshot` runs at most once per 1000 ms and only while a
   selected app is in front, protection is on and the screen is interactive. One capture +
   inference may be in flight; a tick that arrives meanwhile is dropped.
2. The hardware buffer is wrapped and copied to an ARGB_8888 software bitmap (hardware
   bitmaps cannot be read pixel by pixel), then split into three overlapping square tiles —
   top, centre and bottom. The centre tile is what catches a media viewer or chat column in
   an app like Telegram.
3. Stage 1 scores each tile as `P(Porn) + P(Hentai) + 0.3 * P(Sexy)`:
   below `stage1SafeExit` (0.20) the frame exits, at or above `stage1InstantBlock` (0.88) it
   blocks immediately, and otherwise Stage 2 runs once on the strongest tile.
4. Stage 2 keeps the highest confidence of the explicit exposed classes 2
   (`BUTTOCKS_EXPOSED`), 3 (`FEMALE_BREAST_EXPOSED`), 4 (`FEMALE_GENITALIA_EXPOSED`),
   6 (`ANUS_EXPOSED`) and 14 (`MALE_GENITALIA_EXPOSED`). Covered classes and faces are
   ignored. YOLO rows start with 4 box coordinates, so a class index maps to row `4 + index`;
   both `[1, 22, 2100]` and its transposed form are read.
5. `>= stage2ConfirmBlock` (0.55) blocks; `< stage2FalseAlarm` (0.20) clears the frame.
   Between the two, a strike is counted, and 2 consecutive borderline frames
   (`TEMPORAL_STRIKES_REQUIRED`) block. Any decisive frame resets the strikes.
6. `low` / `balanced` / `high` sensitivity shifts the four thresholds; `balanced` is exactly
   the numbers above.

### Lifecycle and blocking

- `HabitAccessibilityService` offers every accepted event (window state changed, windows
  changed, content changed, text changed, scrolled) to the router, not just a window-state
  change: Telegram can appear through a notification, a share sheet or a split-screen resume
  without one. The router returns immediately while the loop already runs for that package.
  Habit and the launcher stop it; SystemUI (notification shade) deliberately does not, so the
  live AI line stays readable while the user pulls the shade down inside a watched app.
  Leaving the app, disabling protection/AI, or showing the overlay stops it and resets the
  strikes.
- A verdict calls `HabitGuard.onAiMatch` with the stable key `<package>:ai-screenshot`, so the
  existing 5-second dedupe counts one attempt, the overlay shows `• AI • APP`, and Back/Home
  remain the escape actions. The loop stops before the overlay is added.
- Models load lazily on the single scanner thread, and `release()` closes both interpreters
  when the accessibility service goes away.
- Privacy: no frame, embedding or verdict leaves the phone and nothing is written to disk.
  Every hardware buffer and bitmap is closed or recycled in `finally`.
- Honest limits: `FLAG_SECURE` windows (banking apps, some streaming apps) refuse screenshots,
  so they are not scanned; the cascade is advisory input to the native block flow only.

### Diagnostics (Part 8)

Every failure used to be a `Log.w` and nothing else, which looked exactly like “nothing
happens”. Now:

- `AiVisualScanner` keeps live counters (`frames`, `blocks`, `lastStage1Score`,
  `lastStage2Score`, `verdict`) plus `modelError`, `stage1Model` / `stage2Model` descriptors,
  `lastError` and its age. `HabitModule` exposes them as `getAiScannerStatus`.
- The persistent notification rebuilds every second while scanning:
  `AI scanning Telegram • Frames: 12 • mob 0.14 • nude 0.02 (safe)`, or
  `AI error: <exact reason>`, with the protection sentence in the expanded text.
- The Apps tab shows a **Live AI scanner status** card (accessibility, screenshot support,
  display-over-other-apps, both model descriptors, watched apps, last app/frames/scores,
  last error) and a **Test AI models now** button that runs both models once on a generated
  bitmap (never the screen) through `testAiModels`.
- A model load failure is retried every 15 s instead of disabling the scanner for the whole
  session, and `openFd` mapping falls back to copying the asset into a direct buffer, so a
  compressed `.tflite` can no longer break the engine silently.
- `HabitGuard` now presses Back **before** showing the overlay (the overlay takes focus, so a
  later global Back would be handled by the block screen itself), and the overlay retries
  with `TYPE_APPLICATION_OVERLAY` when HyperOS refuses an accessibility overlay. If neither
  window type can be added, the failure is reported in the card and the scanner falls back to
  Home instead of leaving the user in the app.

## Status

- [x] Part 1 — foundation, Normal mode, blocking, attempts, onboarding.
- [x] Part 2 — Strict mode, unlock methods A and B, rate limiting, debug tools.
- [x] Part 3 — Aggressive mode + device-admin self-protection (watchdog, admin requirement).
- [x] Part 4 — unlock method C: two face checks (camera + offline face detection).
- [x] Part 5 — design system “C10 Soft Black & Amber”: fonts, theme tokens, neumorphic kit,
  floating 3-tab navigation, Home/Apps/Settings screens, AI monitoring UI + native settings
  storage, block overlay source badges.
- [x] Part 7 — on-device AI screenshot cascade: both models, two-stage gray-zone scan,
  two-frame temporal confirmation, lifecycle wired to the accessibility service.
- [x] Part 8 — AI scanner reliability: all-app event routing, three-tile capture with a
  centre tile, verified model tensors, compressed-asset fallback, live notification text and
  an Apps-tab diagnostics card with a model self-test.

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

Part 5 added `@expo-google-fonts/inter`, `@expo-google-fonts/space-mono` and
`@expo/vector-icons` (all JS/assets). The frosted surfaces are layered React Native views,
not a native blur module, so a stale development APK can still render every screen.
The native bridge gained `getInstalledPackages` and face unlocks now create a 30-minute OFF
window, so rebuild the development APK once for those two native changes.

Part 7 added TensorFlow Lite and ONNX Runtime as native dependencies and downloads the two
model assets, so the development APK must be rebuilt once. On a fresh clone run
`bash scripts/download-ai-models.sh` (npm `postinstall` already does it) before building; the
installer fails loudly if either model is missing or is an error page instead of a model.

The GitHub Actions workflow now installs NDK `27.1.12297006` (the version Expo 57 / RN 0.86
pins) itself, with retries and a `source.properties` check, before Gradle runs. Build #12
failed because Gradle's own SDK auto-install downloaded a truncated NDK archive
(`ZipException: Archive is not a ZIP archive`), not because of Kotlin. The workflow also
deletes the preinstalled NDK versions it never uses, which frees roughly 10 GB.

Part 8 added `SYSTEM_ALERT_WINDOW`, the `getAiScannerStatus` / `testAiModels` /
`canDrawOverlays` bridge calls and the diagnostics card, so the development APK has to be
rebuilt again. The accessibility XML changed (`typeWindowsChanged`, no package filter), so
turn Habit's accessibility service off and on after installing the new APK.

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
    second photo → protection turns off for 30 minutes; Home shows the OFF countdown card;
    after the window the previous mode returns automatically (or tap “Turn on now”).
16. Method C with no face in frame → “No face found”, attempt counted, second step not
    reached.
17. Method C leaving the screen mid-way and reopening starts a fresh session (the old one is
    cancelled).

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
- **Honest permission count.** Settings shows only the two permissions the app can actually
  verify from JS/native (accessibility + notifications), so the progress bar is never a
  guess. Battery/autostart stay in the onboarding checklist.
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

