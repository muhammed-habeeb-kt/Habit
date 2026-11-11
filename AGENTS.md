# Habit — project notes

_Last updated: 2026-11-11 (Part 22: continuous private-tab watchdog + switch sync)_

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
| Tabs: Home / AI / Settings | `src/app/(tabs)/` |
| Browser Blocking screen | `src/app/browsers.tsx` |
| AI app + watched website UI | `src/app/(tabs)/apps.tsx` |
| AI settings hook (JS bridge) | `src/hooks/use-ai-monitoring.ts` |
| AI settings storage (native) | `HabitStore.kt` (`AiSettings`, `KEY_AI`) |
| Block overlay source badges | `BlockOverlay.kt` (`BlockSource`), `HabitGuard.onAiMatch` |
| AI debug session (frames + CSV + Download export) | `AiDebugSession.kt`, `HabitModule.exportAiDebugSession` |
| Settings tamper shield (zero-power) | `HabitAccessibilityService.scanTamperWindows` + `armTamperWatch`, `HabitGuard.onTamperDetected` |

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
- The device admin only forces an uninstall confirmation. The Settings screens themselves
  are guarded by the Part 12 tamper shield, which applies to Strict as well as Aggressive.

## Clocks, reboots, alarms

Habit keeps its own clock (Part 15). **No timer or counter reads the wall clock**, so changing
Date, Time or Time Zone in Android Settings can neither shorten nor extend anything.

- `UnlockWindow` stores `savedRemainingMs` + `savedAtElapsed` + `savedBootCount`
  (`Settings.Global.BOOT_COUNT`). `HabitUnlock.remainingMs` subtracts only a monotonic
  `SystemClock.elapsedRealtime()` delta; a different boot session (or an elapsed value that
  went backwards) returns the persisted remaining value untouched, so a reboot can only pause
  a timer, never skip part of it.
- The same pair rules the auto-restart OFF window, the rate-limit pause (1 / 5 / 15 min) and
  the 12 s tamper stand-down; there is no `endWall` / `lockedUntilWall` any more.
- Daily counters use a persisted `DayClock` (`dayKey`, `anchorElapsedMs`, `anchorWallMs`,
  `bootCount`). While the phone stays on, a day is 24 monotonic hours from the anchor, so a
  date change cannot roll or wipe today's count. Across a reboot the wall date is trusted
  only forwards, so setting the clock back cannot resurrect an old day.
- Every tick persists the remaining value at most every 5 s (`PERSIST_INTERVAL_MS`).
- Alarms are `ELAPSED_REALTIME_WAKEUP` (`SystemClock.elapsedRealtime() + remainingMs`), never
  `RTC_WAKEUP`, so they are immune to clock changes. They do not survive reboot, so
  `BootReceiver` re-arms them from encrypted state.
- If the exact-alarm permission is unavailable, scheduling falls back to `setAndAllowWhileIdle`
  and the foreground-service ticker still applies the transition on time.
- Habit registers no `ACTION_TIME_CHANGED` / `ACTION_TIMEZONE_CHANGED` receiver: nothing in
  the app reacts to a clock change, because nothing in the app depends on one.

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

## AI Visual Scanner Architecture

The UI, settings, storage and the on-device engine for AI monitoring are all built. This
section lists the surface area and the rules the engine keeps.

### What already exists

- **Persistence (native).** `HabitStore.AiSettings` is stored encrypted under `KEY_AI` as
  `{ enabled, sensitivity: "low"|"balanced"|"high", watchedApps: [packageName, ...],
  watchedDomains: [hostname, ...] }`. `HabitStore.aiSettings(context)` reads it;
  `setAiMonitoringEnabled`, `setAiSensitivity`, `setAiWatchedApps` and
  `setAiMonitoredDomains` write it and fire `onStateChanged`. The value is also included in
  every `getSnapshot()` result under `"ai"`.
- **JS bridge.** `src/native/habit.ts` exposes `getAiSettings`, `setAiMonitoringEnabled`,
  `setAiSensitivity`, `setAiWatchedApps`, `setAiMonitoredDomains` and `normalizeDomain`;
  `src/hooks/use-ai-monitoring.ts` is the single screen-facing hook and re-reads the
  settings on native state changes.
- **UI.** AI tab (`/apps`): master panel with the master switch, **Apps** / **Browser**
  mode boxes, the app list (search, ALL / SELECTED, per-app checkboxes), the watched-website
  editor (domain input, quick-add chips, `• AI WATCHING` rows) and the Live AI scanner
  status card. Home shows an **AI Monitoring** summary card with the same switch that opens
  the AI tab. Settings tab: AI detection sensitivity selector. Block overlay supports both
  source badges.
- **Block screen sources.** `BlockOverlay.kt` now takes a `BlockSource`:
  `BlockSource.BROWSER` renders `• BROWSER`; `BlockSource.ai(appLabel)` renders
  `• AI • TELEGRAM` (app label uppercased). Browser text still says “closed this page”;
  AI text says “closed this screen”.

### The engine (implemented, Part 7–8)

1. **`AiVisualScanner.kt`** owns both models (TFLite `Interpreter` with XNNPACK + fp16, and
   ONNX `OrtSession`), one reused input buffer each, and a loop with at most one frame in
   flight: 1000 ms normally, 2200 ms after 8 fully safe frames (battery saver for normal
   video), snapped back to 1000 ms by any interaction. Tunables live in
   `AiCascadeConfig.kt`. Nothing is written to disk; buffers and bitmaps are closed or
   recycled in `finally`.
2. **Hook point** — `HabitAccessibilityService.updateAiScanner(pkg)` runs for every accepted
   event (window state changed, windows changed, content changed, text changed, scrolled),
   because a monitored app can become visible without a window-state change. The router
   starts the loop when protection is on, AI monitoring is enabled and the package is in
   `monitoredAppPackages`. The foreground package is resolved from the event and
   `rootInActiveWindow` while ignoring transient overlays (SystemUI shade / volume panel,
   soft keyboards, Habit itself) and is latched, so pulling down the notification shade or
   opening a keyboard inside a watched app never stops the loop. Once armed, the loop is
   only stopped by an explicit `TYPE_WINDOW_STATE_CHANGED` that switches to Habit, the
   launcher or another unselected app — a momentarily empty `service.windows` or a null
   `rootInActiveWindow` during video playback is not an app switch and never stops it.
3. **Capture** — `AccessibilityService.takeScreenshot()` (Android 11+/API 30) on the default
   display while the screen is interactive. The frame is copied from the hardware buffer to
   ARGB_8888 and cut into top, centre and bottom squares. `FLAG_SECURE` windows are the
   honest “protected screens” limit.
4. **Classification** — full-width top / centre / bottom tiles over the screen, trimming
   only the top 8% status / app bar. Stage 1 (NSFW MobileNetV2,
   `FLOAT32 [1,224,224,3] -> [1,5]`) scores `Porn + Hentai + sexyWeight*Sexy` (`0.80` high
   and balanced, `0.30` low). A tile is **positive** when it reaches combined `0.38`,
   `Porn + Hentai` `0.20`, `Sexy` `0.48` or NudeNet `s_nude_max` `0.20`; stage 2 (NudeNet
   320n, `FLOAT32 [N,3,H,W] -> [N,22,A]`) is additive only, runs on tiles that crossed the
   0.05 / porn 0.015 gate, and ignores only boxes smaller than 6% of the crop side. Blocking
   is a 2-screen confirmation: a positive Frame 1 flags the screen and Frame 2 must be
   positive too; a safe Frame 2 clears the flag. A clearly explicit frame (combined `0.65`,
   porn `0.40` or nude `0.40`) blocks instantly on Frame 1. The pixel gate is bypassed while
   a positive frame waits for Frame 2 and whenever the last stage-1 score was `>= 0.08`, so
   a static image can never dodge confirmation.
5. **Verdict** — `HabitGuard.onAiMatch(context, "<pkg>:ai-screenshot", appLabel)` keeps the
   5-second dedupe stable, shows `• AI • APP`, presses Back before the overlay takes focus,
   and falls back to Home when the overlay was refused.
6. **Diagnostics** — `getAiScannerStatus` / `testAiModels` / `canDrawOverlays` on the bridge;
   live notification line (`Watching Telegram • Frame #12 • mob: 0.14 • nude: 0.02`,
   or `AI Model Error: <reason>` when a model cannot load), and
   the Apps tab status card with a model self-test. Never let a model or screenshot failure
   disappear into a log line again.
7. **Lifecycle** — models load lazily on the scanner thread (retried every 15 s after a
   failure), and `release()` closes the interpreter/session when the accessibility service
   goes away or `HabitGuard.detach` runs.

### Debug session recorder (Part 9)

Built for threshold calibration, not for protection:

8. **Toggles** — `AiSettings.debugRecordingEnabled` and `AiSettings.debugDryRun`
   (`HabitStore.setAiDebugRecording` / `setAiDebugDryRun`), surfaced as the two switches in
   the `Live AI scanner status` card. Dry-run is only live while recording is on, and
   switching recording off clears it, so blocking can never be suppressed by a stale flag.
9. **Per frame while recording** — stage 1 runs on all three tiles (full probability vector
   kept) and stage 2 runs on all three tiles too, even when stage 1 is blocked or below the
   gate. The whole screenshot is saved as `filesDir/ai_debug_session/frames/frame_0001.jpg` (540 px wide,
   JPEG 72) and one row is appended to `filesDir/ai_debug_session/scan_log.csv` with the
   columns `frame_id,image_file,timestamp,package_name,sensitivity_mode,s_mob_max,
   winning_tile,mob_top,mob_center,mob_bottom,p_porn,p_hentai,p_sexy,p_neutral,p_drawing,
   s_nude_max,nude_top_class,nude_top_tile,unsure_strikes,would_block,verdict_reason,
   stage1_ms,stage2_ms,total_ms`.
10. **Cascade** — `would_block` is still decided by the normal rules, on the gated tiles
    only, so a recorded log never disagrees with production. Record-only mode blocks as
    usual; dry-run logs `would block (dry run)` and the notification shows `DRY RUN`, so a
    long scrolling session records uninterrupted.
11. **Export** — `exportAiDebugSession()` copies `Download/Habit_scan_log_<timestamp>.csv`
    and `Download/Habit_debug_session_<timestamp>.zip` (CSV + `frames/`) through
    `MediaStore.Downloads`, which needs no storage permission on Android 10+; the exact
    paths come back to JS. `clearAiDebugSession()` deletes the CSV and every frame.

### Aggressive accuracy + power gates (Part 10)

12. **Free static gate** — before any model, a 16x16 RGB sample of the screen is compared with
    the last completely safe frame (mean absolute diff `< 3.0` of 255); with 0 strikes and a
    last stage-1 score under 0.12 the frame is skipped entirely (0 ms of inference, counted
    as `skippedFrames` in the status map).
13. **Battery saver** — 8 consecutive safe frames (`stage1 < 0.06` and NudeNet `< 0.08`)
    relax the loop to 2200 ms; a scroll, typed text, window change or any score `>= 0.08`
    snaps it back to 1000 ms via `AiVisualScanner.noteUserActivity()`.
14. **Text/emoji trap** — `HabitAccessibilityService.scanWatchedAppText` reads the visible
    node text of a selected app (no screenshot, no model) and blocks immediately on adult
    indicators (`🔞`, `കാമം`, `kambi`, `mallu hot`, `onlyfans`, `nsfw`, `ullu`, …). It is
    throttled to 250 ms, skips Habit's own UI, respects protection state and AI dry-run, and
    blocks through the same `HabitGuard.onAiMatch("<pkg>:ai-text")` flow.

### Watched websites (Part 11)

15. **Storage + bridge** — `AiSettings.monitoredDomains` holds normalized lowercase hosts
    (`web.telegram.org`). JS normalizes every input (`https://www.X.com/a` → `x.com`) and
    `HabitStore.setAiMonitoredDomains` normalizes again, so no junk can be persisted.
    Removing a site while Strict/Aggressive is locked is refused natively; adding is always
    instant (same rule as watched apps).
16. **Arming** — for a watched browser, `HabitAccessibilityService.updateAiScanner` reads
    the URL bar host (on a 500 ms throttle) and compares it with `monitoredDomains` through
    `AdultFilter.monitoredDomainFor` (subdomain-aware, so `telegram.org` covers
    `web.telegram.org`). A match starts `AiVisualScanner.start(service, pkg, domain)`; a
    non-matching host, leaving the browser, protection off or AI off stops the loop. If the
    URL bar is empty while a page loads, a loop that already belongs to that browser is
    kept (fail closed).
17. **Badge** — a browser hit blocks through the normal `HabitGuard.onAiMatch` flow with
    the source `BlockSource.ai(domain.uppercase())`, so the block screen says
    `• AI • TELEGRAM.ORG` instead of an app name; app hits keep the app label.

### Settings tamper shield (Part 12–13)

An instant, zero-screenshot guard that keeps the user out of the Android Settings that would
end protection. No screenshot, no model, nothing captured or stored.

1. **When it is armed** — `HabitGuard.tamperShieldActive`: protection is ON in **any** mode
   (Normal included, because Normal can run protection too), or `mode != normal` while a
   Strict/Aggressive cooldown is still running. Only a Normal-mode OFF window leaves Settings
   alone. Unreadable state fails closed.
2. **Cost** — node text only, and only while the front package is a Settings package:
   `com.android.settings` (plus `*.settings` variants), `com.miui.securitycenter`,
   `com.google.android.packageinstaller`, `com.miui.packageinstaller` (`isSettingsPackage`).
   Nothing is throttled inside them.
3. **Polling** — `armTamperWatch`: the first Settings event scans at 0 ms, repeats at
   80/180/300/500 ms and starts a 100 ms `Handler` loop that keeps scanning while a Settings
   package is in front. The loop’s first tick outside Settings stops it, so nothing polls
   anywhere else. The watch also arms from any event whose *front window* is Settings (an
   event can be attributed to SystemUI or the launcher) and from `onServiceConnected`.
4. **Deep multi-window scan** — `scanTamperWindows` walks every `service.windows` root of a
   Settings package plus `rootInActiveWindow`, reading `text` and `contentDescription`, with a
   6 000-node / depth 60 runaway guard instead of a page-sized cap, because “Turn off Habit?”
   is a separate dialog window.
5. **Triggers (case-insensitive)** — `Use Habit`, `Habit shortcut`, `Turn off Habit`,
   `Habit blocks adult websites`; a tap on a Habit row while the window shows
   `Accessibility` / `Downloaded apps`; Habit’s device-admin page by `Deactivate this device
   admin` / `Remove admin` (blocked on entry); a tap on `Force stop`, `Uninstall`, `Delete`,
   `Clear data`, `Clear all data`, `Clear cache`, `Manage space`, `Reset`, `Deactivate` or
   `Remove admin` while Habit’s App info / Storage page is open; and its confirmation bottom
   sheet (`Force stop?`, `If you force stop`, `Misbehave`, `Clear all data?`, `All of this
   app’s data`, `Deleted permanently`, `Clear cache?`, `Do you want to uninstall`, …).
   Habit’s plain App info page is deliberately allowed — buttons being visible is not
   tampering — so App permissions, Notifications, Power and the `⋮` menu (`Allow restricted
   settings`) stay reachable. `isHabitAppInfoContext` is derived from all open Settings
   windows, because the confirmation dialog in front may not repeat the app name.
6. **Grace window** — `HabitModule.openBatterySettings` / `openAutostartSettings` /
   `openAppDetails` still mark `HabitStore.markTamperGrace` (`TAMPER_GRACE_MS`, 12 s) before
   starting the intent. The App info page no longer needs it; destructive taps and dialogs
   are refused whether or not it is active.
7. **Kick-out** — `HabitGuard.onTamperDetected` shows the opaque `BlockOverlay` with the
   `• TAMPER SHIELD` badge first, at 0 ms, so the switch under the finger is covered, then
   fires four escape pulses at 0/80/180/320 ms, each one BACK, BACK, HOME. The tamper shield
   swallows BACK itself so a pulse can never dismiss it. One kick-out per 400 ms.
8. **Event type** — the accessibility XML declares `typeViewClicked` (Part 12). After
   installing an APK with that change the service must be switched off and on once.

### Incognito / private tab blocker (Part 14)

The one browsing mode the URL filter cannot see: a private tab has no readable URL, so a
signed-out page would render untouched. Three layers close it: a throttled `FLAG_SECURE`
screenshot probe, exact native menu-click interception and the browser's own private-mode
view ids / sentences. Nothing is stored beyond the existing attempt counter, and webpage
text is never read.

1. **Storage + bridge** — `AiSettings.incognitoBlockingEnabled` (default `true`) is stored
   encrypted under `KEY_AI`. `HabitStore.setIncognitoBlockingEnabled` allows ON in every mode
   and refuses OFF while `isMonitoringLocked` (protection ON in Strict/Aggressive), so JS can
   never lower the lock. It is in `getSnapshot()` under `ai` and as a top-level
   `incognitoBlockingEnabled`, in `src/native/habit.ts` and in `useAiMonitoring`.
2. **Detection** — `HabitAccessibilityService.scanIncognitoTabs` runs on every event of a
   watched browser (and once from `onServiceConnected`) when protection is ON and the switch
   is on. Only native browser chrome is inspected; **recursion stops dead at every WebView /
   GeckoView node**, so page text, search results and commit titles are never read, and only
   visible nodes are visited:
   - **Layer 1, `FLAG_SECURE` screenshot probe** (`probeSecureBrowserWindow`): Chromium
     browsers mark an Incognito / Private tab secure, so `takeScreenshot` (API 30+) fails
     with `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`. That failure blocks the tab, and so does a
     frame whose central half is more than 98% pure black (`R`, `G`, `B` all under 4,
     sampled every 4 px). Probes run only on window-state / windows-changed / click events,
     at most once per 1.2 s. No frame is stored; the bitmap and hardware buffer are closed
     immediately;
   - **Layer 2, menu-click interception** (`isPrivateModeClick`): the clicked node's own
     text / contentDescription, the event text and its direct child TextViews must
     **exactly equal** a native menu label (`new incognito tab`, `new private tab`,
     `new inprivate tab`, `turn on secret mode`, `start secret mode`, `new fire window`) and
     the node must be visible and outside every WebView, so the tab is blocked before it
     exists;
   - **Layer 3, native browser chrome**: native view ids on visible nodes: `incognito_ntp`,
     `incognito_ntp_content`, `new_tab_incognito_container`, `incognito_badge` (Chrome /
     Brave), `private_browsing_description` (Firefox), `secret_mode_badge` /
     `secret_mode_empty_view` (Samsung Internet); `incognito_toggle_button` /
     `incognito_tabs_button` / `privateBrowsingButton` count only while `isSelected` or
     `isChecked` is true. Exact native tab-switcher rows (`search your incognito tabs`,
     `search your private tabs`, `close all incognito tabs`, `close all private tabs`) block
     on their own, and the exact empty-tab sentences (`you've gone incognito` with either
     apostrophe, `this is a private tab`, `firefox clears your search and browsing history
     from private tabs`, `you're browsing inprivate`, `welcome to secret mode`) count only
     when **no visible WebView exists** anywhere in the browser's windows. No broad substring
     such as `private tab`, `incognito tab`, `private mode`, `secret mode` or `inprivate` is
     matched anywhere.
3. **Block** — `HabitGuard.onIncognitoDetected` (1.5 s cooldown) records one attempt, then
   fires BACK + HOME and shows `BlockOverlay` with `• INCOGNITO BLOCKED` and “Incognito &
   private tabs are blocked by Habit.”
4. **UI** — the AI tab’s Browser box starts with a raised 24 dp `NeuCard` (44 dp amber
   eye-off tile, switch) whose OFF tap routes to `/unlock` when locked. `/apps?tab=browser`
   is applied on focus and cleared, so the link works every time. Settings now opens with an
   **AI** section (sensitivity, AI Visual Protection counts) and a **BROWSER** section
   (incognito switch, watched-site count plus the amber “Add Site →” that opens
   `?tab=browser`, and Protected Browsers opening `/browsers`).

### Split-screen & floating windows (Part 15)

A watched app or site can be visible while another window owns the focus, so the router no
longer trusts `rootInActiveWindow` alone.

1. **Visibility** — `HabitAccessibilityService.watchedWindow(ai)` walks every visible
   `service.windows` entry (skipping accessibility overlays, Habit itself and SystemUI). A
   window is watched when its package is in `monitoredAppPackages` — or, for a browser, when
   that window's own URL bar matches `monitoredDomains` via `browserHostFor` +
   `AdultFilter.monitoredDomainFor`; an unreadable URL bar keeps an already-armed loop (fail
   closed). The focused window wins, otherwise the largest watched one.
2. **Bounds** — the window's `getBoundsInScreen` rectangle goes to
   `AiVisualScanner.start(service, pkg, domain, bounds)` and is refreshed while the loop runs
   (same package/domain = rect update only, so the frame history is kept).
3. **Crop plan** — `AiVisualScanner.planTiles`: a window covering >= 80% of both axes is the
   full-screen case (the classic three overlapping squares over the central media viewport).
   Anything smaller is cropped to the window, its top 10% title bar dropped, and covered by
   ONE square tile when `max/min <= 1.45`, otherwise two overlapping squares along the long
   axis. Nothing outside the window is ever classified, so a floating window costs a third of
   the full-screen inference work.
4. **Block** — a hit from a partial window calls `HabitGuard.onAiMatch(..., multiWindow =
   true)`, which fires BACK (closes the floating window / leaves the split) *and* HOME before
   `BlockOverlay`, so both views are gone.
5. **Limit** — a `FLAG_SECURE` window exposes no root, so a fully secure floating window can
   be neither identified nor screenshotted. That is the honest ceiling of this feature.

## Rules to keep

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
- [x] Part 7 — on-device AI screenshot cascade (both models, lifecycle, overlay sources).
- [x] Part 8 — AI reliability: all-app event routing, centre tile, verified tensors,
  compressed-asset fallback, overlay fallback + `SYSTEM_ALERT_WINDOW`, live notification
  diagnostics and the AI-tab status card with a model self-test.
- [x] Part 9 — AI debug session recorder: every frame as a JPEG + CSV row (both stages on
  all three tiles), dry-run logging, one-tap CSV + ZIP export into `Download/` via
  `MediaStore`, and a clear button.
- [x] Part 10 — accuracy and battery: stage 1 blocks on its own (NudeNet can no longer veto
  it), NudeNet runs only on gated tiles, a 16x16 pixel gate skips unchanged screens, safe
  video relaxes to a 2.2 s interval, XNNPACK + fp16 on the TFLite interpreter, and the
  instant text/emoji trap inside watched apps.
- [x] Part 12 — Settings tamper shield: Strict/Aggressive kick the user out of Habit's
  accessibility toggle and app-info / clear-data / uninstall / device-admin screens from the
  node tree only (zero frames), with a 12 s battery/autostart grace window, `• TAMPER SHIELD`
  block badge and `typeViewClicked` in the accessibility XML.
- [x] Part 13 — the shield never misses a Settings screen again: untrottled scanning in every
  protected mode, a 100 ms poll while a Settings package is in front (stopped anywhere else),
  follow-up scans at 80/180/300/500 ms, a deep walk of all Settings windows (text +
  contentDescription), the shield shown before the escape pulses, and BACK×2+HOME repeated four
  times with the shield swallowing BACK.
- [x] Part 11 — AI monitoring UI + watched websites: middle tab renamed to AI with a
  four-point sparkle, Home AI summary card, AI tab master panel with Apps/Browser mode
  boxes, domain add/quick-add/remove list, `monitoredDomains` in encrypted storage and the
  bridge, browser-URL arming of the screenshot scanner, and per-domain `• AI • DOMAIN`
  block badges.
- [x] Part 15 — split-screen / floating-window monitoring with smart bounds cropping (every
  visible window is inspected, the watched window's own rectangle is cropped and classified
  with one or two tiles) and Habit's own monotonic clock: no wall-clock timer, no RTC alarm,
  boot-session-aware remaining values and a monotonic 24-hour day counter.
- [x] Part 14 — Incognito / private tab blocker: `incognitoBlockingEnabled` in encrypted
  storage with a native Strict/Aggressive lock on turning it off, click and window-text
  detection for Incognito / Private / Secret / InPrivate tabs in every watched browser
  (node reads only), BACK + HOME plus a `• INCOGNITO BLOCKED` block screen, the AI tab’s
  incognito card, `?tab=browser` routing, and the new AI / BROWSER sections on Settings.
- [x] Part 16 — Clean streak counter: `StreakState` in encrypted storage (`currentMs`,
  `bestMs`, monotonic anchor + boot session), accrued by `HabitUnlock.tick` only while
  protection is ON with no OFF window, reset by every counted block and by protection
  turning OFF, exposed as `currentStreakMs` / `bestStreakMs` in `getSnapshot()`, and shown
  as the amber “Clean streak” Home card with milestone pill and best record.
- [x] Part 17 — Reliable AI app + browser monitoring: strict 2-screen confirmation
  (positive Frame 1 flags, positive Frame 2 blocks, safe Frame 2 clears) with the pixel gate
  disabled while confirmation is pending; crash-proof normalized tile crops (top 12% header
  and leftmost 15% avatar strip excluded); latched foreground package that ignores transient
  overlays; and a latched watched browser domain that survives a hidden URL bar until a new
  non-matching URL is read, plus flexible root-domain / `t.me` alias matching.
- [x] Part 18 — Tamper-shield refinement for Habit’s App info page: opening App info,
  Storage, Permissions or Notifications is allowed (visible buttons no longer trigger the
  shield), while a tap on Force stop / Uninstall / Delete / Clear data / Clear all data /
  Clear cache / Manage space / Reset fires the 0 ms overlay + BACK + HOME kick-out, and a
  destructive confirmation bottom sheet is caught from the dialog wording. Accessibility
  and device-admin deactivation screens stay blocked on entry.
- [x] Part 19 — AI monitoring works again: the proven high-sensitivity thresholds are back
  (combined `0.38`, explicit `0.20`, sexy `0.48`, NudeNet `0.20`, with an instant block at
  `0.65` / porn `0.40` / nude `0.40` on Frame 1), full-width three-square crops trimming only
  the top 8%, the 6%-side NudeNet box filter, a 0.05 / 0.015 / 0.05 stage-2 gate, and a
  foreground latch that only an explicit app switch can stop (empty `service.windows` during
  video no longer kills the scanner). The notification shows
  `Watching <app> • Frame #N • mob: … • nude: …` or `AI Model Error: …`, and CI fails the
  build unless both model files exist at their real sizes before Gradle runs.
- [x] Part 20 — Incognito detection can no longer false-trigger on a normal tab: the walk
  stops at every WebView / GeckoView node and skips invisible nodes, clicks must exactly
  match a native private-mode menu label outside a WebView, view-id hits are limited to
  visible private views (toggles only when selected / checked), and the empty-tab sentences
  are exact matches that only count when no WebView is visible. A GitHub commit title
  mentioning “Incognito” or “private tabs” is now just page text.
- [x] Part 21 — Three-layer private-tab guard: the FLAG_SECURE screenshot probe (a secure
  window refusal or a >98% pure-black central viewport blocks, at most once per 1.2 s on
  tab / window changes), click interception that also reads the clicked row's child
  TextViews, and the Chromium tab-switcher rows (`search your incognito tabs`, `close all
  incognito tabs`), all still strictly outside WebView content.
- [x] Part 21 also removed the last dead UI files (`protection-toggle.tsx`,
  `use-theme.ts`) and confirmed the single-Kotlin-package layout: `expo prebuild --clean`
  generates exactly one service / receiver entry per class, all under
  `com.payjoy.status.habit`, and never copies `design-screens/` or `scripts/` into the APK.
- [x] Part 22 — Incognito blocking actually runs while the user browses: a continuous
  browser watchdog (600 ms wake-up, node walk ~1 s, `FLAG_SECURE` probe ~1.2 s) keeps the
  guard alive when Chrome swaps tabs inside the same activity without an event; every
  visible native node's text **and** contentDescription outside any `WebView` is matched,
  the extra private-mode lines (`now browsing privately`, `turn on/off secret mode`, …) are
  included, and `setIncognitoBlockingEnabled` commits, re-checks the window in front at
  once and returns the updated snapshot so the switch always shows the stored value.

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
be rebuilt again before face detection works.

Part 7–8 added TensorFlow Lite + ONNX Runtime, the two model assets (fetched by
`scripts/download-ai-models.sh`, gitignored), `SYSTEM_ALERT_WINDOW` and the
`getAiScannerStatus` / `testAiModels` bridge calls, so the APK must be rebuilt again. The
accessibility XML also changed (`typeWindowsChanged`, still no `packageNames`), so after
installing the new APK turn Habit's accessibility service off and on — Android keeps the old
capabilities until it reconnects. On an older APK the face screen explains that
the build cannot check faces yet instead of opening a camera that detects nothing.

Part 9 adds the AI debug session bridge (`setAiDebugRecording`, `setAiDebugDryRun`,
`exportAiDebugSession`, `clearAiDebugSession`), so the development APK must be rebuilt
again. Nothing in the manifest or the accessibility XML changed, so the accessibility
service does not need to be toggled off and on after installing it.

Part 11 adds the watched-website bridge (`setAiMonitoredDomains`) and browser-domain
arming, so the development APK must be rebuilt again. The manifest and the accessibility
XML are unchanged, so the service does not need to be toggled off and on.

Part 12 adds the Settings tamper shield and declares `typeViewClicked` in the accessibility
XML, so the APK must be rebuilt and the accessibility service toggled off and on once after
installing it (Android keeps the old event types until the service reconnects). Nothing in
the manifest changed.

Part 13 changes Kotlin only (the 100 ms Settings watch and the new kick-out order), so the
development APK must be rebuilt. The manifest and the accessibility XML are unchanged, so the
service does not need to be toggled off and on after installing it.

Part 14 adds the incognito-blocking bridge (`setIncognitoBlockingEnabled`) and the browser
private-tab detection, so the development APK must be rebuilt. The manifest and the
accessibility XML are unchanged since Part 12 (it already listens to `typeViewClicked`), so
the service does not need to be toggled off and on.

Part 15 changes Kotlin only (multi-window routing, the window-bounds crop plan and the
monotonic clock), so the development APK must be rebuilt. The manifest and the accessibility
XML are unchanged, so the service does not need to be toggled off and on.

Part 16 adds the native streak engine and its two snapshot fields, so the development APK
must be rebuilt before Home can show a real streak (an older APK reports 0). The manifest and
the accessibility XML are unchanged, so the service does not need to be toggled off and on.

Part 21 changes Kotlin only (the FLAG_SECURE probe, its bitmap analysis and the extra
native labels), so the development APK must be rebuilt. The accessibility XML already sets
`canTakeScreenshot="true"`, and the manifest is unchanged, so the service does not need to
be toggled off and on.

Part 17 changes Kotlin only (two-screen confirmation, normalized crops, foreground and
browser-domain latching), so the development APK must be rebuilt. The manifest and the
accessibility XML are unchanged, so the service does not need to be toggled off and on.

Part 20 changes Kotlin only (strict native-chrome incognito detection), so the development
APK must be rebuilt. The manifest and the accessibility XML are unchanged since Part 12, so
the service does not need to be toggled off and on.

Part 22 changes Kotlin and the JS bridge only (the continuous private-tab watchdog and the
snapshot reply from `setIncognitoBlockingEnabled`), so the development APK must be rebuilt.
The manifest and the accessibility XML are unchanged, so the service does not need to be
toggled off and on.

Part 19 changes Kotlin only (AI thresholds, crops, foreground latch and the live
notification line) and hardens the CI model check, so the development APK must be rebuilt.

Part 18 changes Kotlin only (App info allowance, click interception and confirmation
wording), so the development APK must be rebuilt. The manifest and the accessibility XML are
unchanged, and `typeViewClicked` is already declared, so the service does not need to be
toggled off and on.

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
- **AI app list.** The Apps box lists the phone's own installed packages
  (`getInstalledApps`), never a bundled catalogue. The native scanner only needs the stored
  package names, so the list can grow without touching storage; browsers are handled by the
  Browser box instead.
- **Watched websites.** `monitoredDomains` stores bare lowercase hostnames and matching is
  subdomain-aware in `AdultFilter.monitoredDomainFor` (`telegram.org` covers
  `web.telegram.org`). A browser loop is armed from the URL bar only — never the page title
  — and a page whose URL bar is momentarily empty keeps an already-running loop rather than
  dropping protection. The block badge names the watched site (`• AI • TELEGRAM.ORG`).
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

- **Browser install status.** The Browser Blocking screen asks Android's PackageManager
  (`getInstalledPackages`) which of the eight watched browsers are installed. The manifest
  already declares `<queries>` for all of them, so the check works on Android 11+ without
  `QUERY_ALL_PACKAGES`.
- **OFF countdown card.** Home shows it from `snapshot.unlock.offWindowActive`. Both the
  timer unlock (10 minutes) and the two-person face check (30 minutes, `FACE_OFF_MS` in
  `HabitUnlock`) now create an OFF window, so the card appears for either method and
  “Turn on now” ends the window immediately.

## Device notes

- App name `Habit`; installed application id `com.habit.guardian` (`app.json`).
- **One Kotlin package only:** every native file lives in
  `modules/habit-native/android/src/main/java/com/payjoy/status/habit/` and
  `expo-module.config.json` registers `com.payjoy.status.habit.HabitModule`. There is no
  `java/com/habit/guardian/` package; `com.habit.guardian` is the Android application id
  (and the legacy app id the tamper shield recognises), never a duplicate source tree.
- Test device is a Redmi (HyperOS): needs autostart + "no battery restrictions", and
  "Allow restricted settings" for the sideloaded APK on Android 13+.
- Exact alarms: if the countdown notification is late, allow "Alarms & reminders" for Habit.
- Device admin lives in Settings → Security → Device admin apps. Habit requests no admin
  powers, so the system dialog should mention only app removal.
- Termux has no `/usr/bin/env`, so Expo commands are run as
  `node node_modules/expo/bin/cli <command>`.

