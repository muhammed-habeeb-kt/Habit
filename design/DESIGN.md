# HABIT - DESIGN.md
Complete UI and behaviour specification for the coding agent.
Version 1.0. Android only. React Native (Expo SDK 57, development build). Light theme only.

Folder layout: this file is `~/Habit/design/DESIGN.md`. Reference pictures are in `~/Habit/design/screens/` (S01 to S14). The pictures are only a look-and-feel reference. The text in this file is the truth.

---

## 0. READ THIS FIRST (rules for the coding agent)

0.1 The person you work for cannot code and cannot fix your mistakes. You write all the code. Be careful, simple and exact.

0.2 This file is the single source of truth. If PROJECT_NOTES.md, an earlier prompt, or a picture disagrees with this file, THIS FILE WINS. The pictures contain mistakes (listed in section 11).

0.3 Do exactly what is written. Do NOT add features, cards, statistics, tips, streaks, badges or texts that are not in this file. Do NOT remove things that are in this file. Use the texts in quotes character for character.

0.4 When something is not specified, choose the simplest option that fits this file, and write the choice into PROJECT_NOTES.md under the heading "Decisions". Ask the person a question only if you are blocked, and only one question at a time.

0.5 No HTML, no CSS files, no WebView for the UI. Everything is React Native components with TypeScript.

0.6 Build order: (1) theme and ui components (sections 3 and 4), (2) navigation (section 5), (3) screens S1 to S15 in order (section 6), (4) shared states (section 7), (5) the checklist in section 12. After each screen, tell the person in one or two lines what to look at on the phone.

0.7 The security rules in section 2.8 apply to every feature.

0.8 Units: all sizes are dp (density-independent pixels). The reference screen is 390 dp wide. Values marked with the sign ~ are approximate: match the look of the picture by eye, then keep the same value everywhere.

0.9 Everything marked "NO PICTURE" is not in the pictures. Build it from the same components and the same style.

---

## 1. What the app is

Habit is an Android self-control app with three features.

| Code | Feature | What it does |
|---|---|---|
| F1 | 18+ Blocker | Detects 18+ websites in browsers and blocks them at once. |
| F2 | Instagram Reels limiter | Limits only the Reels screen inside Instagram. |
| F3 | YouTube Shorts limiter | Limits only the Shorts screen inside YouTube. |

Mode names (never mix them):
- 18+ Blocker modes: Normal, Strict, Aggressive.
- Reels and Shorts control modes (ONE common mode for both F2 and F3): Normal (Level 1), Strict (Level 2), Restrict (Level 3).

The app has three bottom tabs: Home, 18+ Blocker, Short Video (the Reels & Shorts hub).

---

## 2. Product rules (authoritative)

### 2.1 F1 detection and blocking
- An Android AccessibilityService watches window and content changes.
- It reads the address bar / URL of these browsers: Chrome (`com.android.chrome`), Mi Browser (`com.mi.globalbrowser`, `com.android.browser`), Samsung Internet (`com.sec.android.app.sbrowser`), Firefox (`org.mozilla.firefox`), Brave (`com.brave.browser`), Edge (`com.microsoft.emmx`), Opera (`com.opera.browser`), DuckDuckGo (`com.duckduckgo.mobile.android`). The list is in a JSON config so it is easy to extend.
- It matches against (a) a bundled offline blocklist of adult domains (subdomain-aware) and (b) a keyword list applied to URLs and search queries (whole-word match, small allowlist to avoid false positives). Both lists are JSON on the JavaScript side and are pushed to native storage, so they can change without rebuilding the APK.
- On a match: within about 1 second send the user Back (or Home) and show the Block Screen (S15). Count one attempt.
- Runs as a foreground service with a persistent notification, restarts after reboot, after an app update and after the app is swiped away from Recents.
- Works also in private/incognito tabs where Android exposes the address bar.

### 2.2 F1 modes
- Normal: no restrictions. Protection can be switched OFF and ON any time. The mode can be changed any time.
- Strict: protection cannot be turned OFF, and the mode cannot be lowered, without an Unlock (section 2.3).
- Aggressive: everything in Strict, plus Habit protects itself (section 2.4). Same unlock methods.
- Raising protection is ALWAYS instant and needs no challenge: OFF to ON, Normal to Strict, Strict to Aggressive.
- Lowering protection in Strict or Aggressive ALWAYS needs an Unlock. This includes: turning protection OFF, switching to a lower mode, deactivating device admin, deleting or changing unlock settings.

### 2.3 F1 unlock methods (Strict and Aggressive). Any ONE method is enough.

Method A "Type the code" (screen S5)
- The app makes a cryptographically random string (SecureRandom) from the printable ASCII characters with codes 33 to 126 (letters, digits, symbols, no space). The string is deliberately mixed and hard to type.
- There are three sizes ("slabs"): 100, 200 and 300 characters. The person picks one. Typing that whole slab exactly unlocks.
- One wrong character = the sequence restarts from 0 with a brand-new string of the same size.
- No paste, no text selection, no autofill, no keyboard suggestions. If more than one character appears at once in the input, ignore it (it is a paste).
- After 5 mistakes in 10 minutes, Method A pauses for 5 minutes (show "Try again in mm:ss").
- On success: protection turns OFF and stays OFF until the person turns it ON again (no automatic turn-on for Method A).

Method B "Wait it out" (screen S6)
- The person asks to turn protection off. Protection stays fully ON during a waiting period of 3 hours (constant `WAIT_MS = 3 * 60 * 60 * 1000`, easy to change). The person can cancel any time.
- When the 3 hours end, protection turns OFF by itself and a notification says "Protection is OFF for 10 minutes".
- 10 minutes after it turned OFF (constant), protection turns ON again automatically, in the same mode it had before.
- Time uses a monotonic clock plus persisted values. Rebooting the phone or changing the phone clock must NEVER shorten the waiting period or lengthen the 10-minute OFF window.

Method C "Two-person face check" (screen S7)
- ANY two different live human faces. There are NO enrolled or named people. Nothing is registered in advance.
- Person 1 is verified first. Only if that passes, person 2 is verified. Each person must pass a liveness check (slow blink). The two faces must be different people (compare face embeddings in memory; if they look like the same person, show "This looks like the same person. A different person must verify.").
- Everything happens on the phone, in memory only. No picture or face data is saved or sent anywhere.
- After 3 failed attempts, Method C is locked for 15 minutes.
- On success: protection turns OFF at once for a FIXED 30 minutes (constant `FACE_OFF_MS = 30 * 60 * 1000`). When the 30 minutes end, protection turns ON again by itself and the mode returns to what it was before. The person can also turn it ON earlier with "Turn on now".

### 2.4 F1 Aggressive mode (self-protection)
- Habit is registered as Device Administrator, so a normal uninstall is blocked until admin is deactivated. Deactivating admin is itself an Unlock action.
- Self-protection guard (done by the AccessibilityService): when Habit's App Info page, the Device Admin page, or the Accessibility settings page for Habit is on screen, the actions Uninstall, Force stop, Clear data / Clear storage, turning off Habit's accessibility service and Deactivate admin are blocked by sending the user Back and showing the Block Screen S15 style message "Habit is protected". The guard is lifted only during an OFF window created by an Unlock (10 minutes after Method A or B, 30 minutes after Method C) and re-arms by itself after that.
- It survives swipe-away, reboot and battery optimisation. Onboarding (S1) guides the person through HyperOS / MIUI settings (autostart ON, battery "No restrictions").
- Honest limits (also shown in the app, see S3 footer link "Known limits"): Android cannot be fully locked by a normal app. Safe Mode, ADB, factory reset and another user profile can bypass it.

### 2.5 F1 attempts counter
- One block event = one attempt. The same domain within 5 seconds counts once.
- Show only TODAY's total. It resets at local midnight. Store per-day counts on the device (counts and dates only). NEVER store URLs, page text or browsing history. No monthly view.

### 2.6 F2 and F3 limiter rules (Instagram Reels, YouTube Shorts)
- Only the Reels viewer inside Instagram (`com.instagram.android`) and only the Shorts player inside YouTube (`com.google.android.youtube`) are limited. Stories, messages, feed, posts, notes, normal videos, long videos and channels must NEVER be affected.
- Each feature has its own toggle, its own "watch time allowed" (1 to 60 minutes) and its own "waiting time" (1 to 60 minutes). Defaults: watch 5 min, wait 20 min, for both features.
- Watch credit counts down only while that Reels or Shorts screen is on screen (screen on, app in front, Reels/Shorts detected).
- When the credit reaches 0: show the time's-up overlay (S14), move the person out of Reels/Shorts (Back until out, fallback Home), and start the waiting time right then.
- During the waiting time, any attempt to open Reels/Shorts is intercepted at once with the same overlay.
- When the waiting time ends, the credit refills to the full allowance.
- The credit is cumulative: leaving Reels early keeps the unused minutes. It refills only after a waiting time.
- If the allowance is changed, the credit left is capped at the new allowance; it is not raised until the next refill.
- Detection rules (package names, view-id patterns, content descriptions for the Reels viewer and the Shorts player) live in a JSON config in JavaScript and are pushed to native, because Instagram and YouTube change often. Add a DEBUG-ONLY screen "Detector inspector": while the person is inside Reels or Shorts it saves the visible accessibility node ids and descriptions to a text file, so the rules can be tuned. This screen is disabled in release builds.

### 2.7 F2 and F3 control modes (one common mode for both)
- Level 1 Normal: the limits can be switched off and on any time. No challenge.
- Level 2 Strict: to turn the limits off or weaken them, the person types a random sequence of 10 to 20 characters (screen S12). The length (10 to 20) and the text are new on every attempt. Characters come from printable ASCII codes 33 to 126. No paste, no suggestions. Must match exactly. A wrong character can be deleted and corrected (no restart).
- Level 3 Restrict: when Restrict mode is turned on, the person sets an unlock timer (screen S11): 5, 10, 30, 60 minutes or Custom (1 to 180). To turn the limits off or weaken them, the person must request it, then wait that many minutes (screen S13). During the wait the limits stay fully ON. The person can cancel the request any time.
- "Weaken" means: switching a feature OFF, raising a watch time, shortening a waiting time, or lowering the mode (Level 3 to 2 or 1, Level 2 to 1). Strengthening is always instant and needs no challenge: switching a feature ON, lowering a watch time, lengthening a waiting time, raising the mode.
- A successful unlock (Level 2 typing, or Level 3 wait finished) puts BOTH features into the "limits OFF" state. While limits are OFF the person can change toggles, times and the mode freely. Limits stay OFF until the person taps "Turn limits back on" (in S8 and S13: "Turn Back On Immediately"). There is no automatic turn-on for Reels/Shorts.
- Switching to Level 3 from a lower level always opens S11 first (to set the timer). Switching from Level 1 to Level 2 is instant.

### 2.8 Security and state rules (apply to everything)
- SR-1 The enforcement state, the mode state machines, the timers, the unlock checks and the self-protection guard live in the native Android layer (Kotlin). JavaScript only asks for actions and reads status. JavaScript can never set enforcement state directly.
- SR-2 State is stored in encrypted native storage (Android Keystore / EncryptedSharedPreferences). If the state is missing or corrupted, fail closed: treat protection as ON.
- SR-3 All time logic uses a monotonic clock plus persisted values and handles reboot. Changing the phone clock must never help the person bypass anything.
- SR-4 Rate-limit unlock attempts and log failures (counts only).
- SR-5 No permanent lock-out: in every state at least one way to unlock must always work.
- SR-6 Everything works offline. Nothing the person does or sees leaves the phone. No analytics, no ads, no third-party trackers.
- SR-7 Debug switches (shorter timers, exit any mode, Detector inspector) exist only when `__DEV__` is true or in a debug build. They must be impossible to enable in a release build.
- SR-8 Never log or store URLs, page text, typed unlock text, or face data.

---

## 3. Design system ("Tactile Sanctuary")

### 3.1 Style in one paragraph
Light Neumorphism (soft UI) for the whole app: everything is the same soft grey-blue as the background and gets its depth only from two shadows (a white highlight on the top-left and a darker shadow on the bottom-right). Glassmorphism (frosted glass) is used ONLY for things that float above other content: bottom sheets, modals, dialogs and the time's-up overlay. The feeling is calm, soft and tactile. No hard edges, no harsh black, no pure white backgrounds.

### 3.2 Colours (put every value in `theme.ts`; never hard-code a colour in a screen)

| Token | Value | Use |
|---|---|---|
| `canvas` | `#E4E8EF` | App background AND the fill of every card, button, input and chip |
| `textPrimary` | `#2B3040` | Main text |
| `textSecondary` | `#64748B` | Descriptions, captions |
| `textMuted` | `#94A3B8` | Disabled text, scale labels |
| `shadowLight` | `#FFFFFF` | Highlight shadow (top-left) |
| `shadowDark` | `rgba(163,177,198,0.60)` | Dark shadow (bottom-right) |
| `indigo` | `#5B5FEF` | F1 accent, primary buttons, active tab |
| `indigoDark` | `#4143D5` | Pressed indigo, indigo text on light chips |
| `indigoTint` | `rgba(91,95,239,0.12)` | Indigo chip background |
| `pink` | `#E1306C` | F2 Instagram Reels accent |
| `pinkTint` | `rgba(225,48,108,0.12)` | Pink chip background |
| `red` | `#FF3B30` | F3 YouTube Shorts accent |
| `redDark` | `#BA1A1A` | Large filled red buttons, red timers |
| `normalGreen` | `#10B981` | Normal / Level 1, success glow |
| `normalGreenDark` | `#006C49` | Green text on light chips, "correct typed" text |
| `strictAmber` | `#F59E0B` | Strict / Level 2 |
| `strictAmberDark` | `#7E4F00` | Amber text on light chips |
| `strictAmberTint` | `#FFDDB8` | Amber chip background |
| `aggressiveRed` | `#EF4444` | Aggressive / Restrict / Level 3 |
| `aggressiveRedDark` | `#93000A` | Red text on light chips |
| `errorTint` | `#FFDAD6` | Error and red chip background |
| `successTint` | `#6CF8BB` | "Active", "Granted", "PASS" chip fill (text `#00714D`) |
| `warnButton` | `#FFB95F` | Amber "FIX NOW" button fill (text `#2A1700`) |
| `scrim` | `rgba(23,28,33,0.45)` | Dim layer behind sheets, modals and the overlay |

Glass (frosted) tokens:
- `glassFill` = `rgba(255,255,255,0.35)` for light glass (the S14 card uses 0.45).
- For glass that carries a lot of text (sheets and modals S4, S5, S11, S12, dialogs) use `glassFillText` = `rgba(247,249,255,0.80)` so the text stays readable (contrast at least 4.5:1).
- `glassBorder` = 1 dp `rgba(255,255,255,0.60)`.
- Backdrop blur 24 (`expo-blur` BlurView, intensity about 60). Radius 28 (sheets: only the top corners, 32).
- Glass shadow: `0 20 40 rgba(43,48,64,0.12)` plus `0 8 16 rgba(163,177,198,0.25)`.
- Behind every glass layer put the `scrim` over a blurred copy of the screen below.

### 3.3 Typography
Fonts: Inter (all normal text) and Space Mono (timers, counters, status chips, small section labels, the typing challenge). Load them with `expo-font` / `@expo-google-fonts/inter` and `@expo-google-fonts/space-mono`.

| Style | Font | Size / line height | Weight | Letter spacing |
|---|---|---|---|---|
| `headlineXl` | Inter | 28 / 36 | 700 | -0.02em (sheet titles) |
| `headlineLg` | Inter | 24 / 32 | 700 | -0.02em |
| `headlineMd` | Inter | 20 / 28 | 600 | -0.01em |
| `headlineSm` | Inter | 18 / 24 | 600 | 0 |
| `bodyLg` | Inter | 16 / 24 | 400 | 0 |
| `bodyMd` | Inter | 14 / 20 | 400 | 0 |
| `bodySm` | Inter | 12 / 16 | 400 | 0 |
| `labelLg` | Inter | 14 / 20 | 600 | 0 |
| `labelMd` | Space Mono | 12 / 16 | 500 (use Bold file for 700) | 0.04em |
| `labelXs` | Space Mono | 10 / 14 | 500 | 0.06em |
| `labelChallenge` | Space Mono | 15 / 22 | 700 | 0.06em |
| `timerXl` | Space Mono | 40 / 48 | 700 | 0 (big countdown) |
| `timerLg` | Space Mono | 32 / 40 | 700 | 0 |
| `timerMd` | Space Mono | 20 / 28 | 700 | 0 |
| `numberXl` | Inter | 48 / 56 | 700 | -0.02em (attempts number) |

Section labels such as "INTERVENTION PROFILE", "ACTIVE GUARDRAILS", "DUAL AUTHENTICATION" use `labelMd`, UPPERCASE, `textSecondary`.

### 3.4 Shape and spacing
- Cards, tiles, sheets, modals: radius 24 (glass 28, sheets top corners 32).
- Buttons, inputs, nested list rows: radius 20.
- Chips, dots, toggle thumbs, slider thumbs, round buttons: fully round (radius = half the size).
- 8 dp grid. Screen side margin 20. Gap between cards 16. Card inner padding 20 (24 for big cards). Soft shadows are wide (up to 14 dp), so never put two shadowed things closer than 12 dp and never put them against the screen edge.

### 3.5 Shadow recipes (light comes from the top-left). Build them once in `ui/shadows.ts`.

| Name | Layers | Use |
|---|---|---|
| `RAISED` | `-5 -5 12 #FFFFFF` + `5 5 14 rgba(163,177,198,0.60)` | cards, buttons |
| `RAISED_SM` | `-3 -3 8 #FFFFFF` + `3 3 8 rgba(163,177,198,0.60)` | chips, icon tiles, toggle thumbs, slider thumbs, small round buttons |
| `RAISED_LG` | `-8 -8 18 #FFFFFF` + `8 8 20 rgba(163,177,198,0.60)` | the big power button, countdown rings |
| `INSET` | `inset 4 4 8 rgba(163,177,198,0.60)` + `inset -4 -4 8 #FFFFFF` | inputs, wells, selected chips, mode-selector track, counters |
| `INSET_SM` | `inset 3 3 6 rgba(163,177,198,0.60)` + `inset -3 -3 6 #FFFFFF` | toggle tracks, slider tracks, progress bars |
| `PRIMARY(accent)` | `0 8 20 accent at 40% opacity` | filled accent buttons |
| `GLOW(color)` | `0 0 14 color at 45% opacity` | ON glow of toggles and the power button |

Pressed state: a raised element changes to the inset look immediately (no animation longer than 120 ms).

### 3.6 Icons
Use `@expo/vector-icons` (MaterialCommunityIcons). Suggested names (pick the nearest if one does not exist): Accessibility `eye-outline`, Notifications `bell-outline`, Battery `battery-heart-variant`, Restricted settings `lock-open-outline`, Display over apps `layers-outline`, Device admin `shield-outline`, shield `shield-outline` / `shield-check-outline`, power `power`, lock `lock-outline`, hourglass `timer-sand`, timer `timer-outline`, keyboard / code `console-line`, face `face-recognition`, Reels `movie-play-outline`, Shorts `play-circle-outline`, loop `sync`, info `information-outline`, warning `alert-outline`, check `check`, close `close`, bolt not used, camera flip `camera-flip-outline`, lotus (Home logo button) `meditation`, back `arrow-left`, chevron `chevron-right`. Icon size 24 inside tiles, 20 inside buttons, 16 inside chips.

---

## 4. Reusable components (build once in `src/ui/`, use everywhere)

4.1 `ScreenContainer`
- Background `canvas`. Status bar: dark icons, transparent. Respect safe-area insets (top and bottom). Side padding 20. Content scrolls vertically (`ScrollView`) when taller than the screen; bottom padding 120 on tab screens (room for the bottom nav).

4.2 `TopBar` (height 64)
- Left: a round raised button 48x48 (`RAISED_SM`). On the three tab screens (Home, 18+ Blocker, Short Video) it shows the lotus logo in indigo and is NOT tappable. On pushed screens it shows the back arrow and goes back.
- Title next to it (or centred on pushed screens), `headlineMd`, `textPrimary`.
- The right side is empty. (There is no profile button and no settings button.)

4.3 `NeuCard` : raised, radius 24, padding 20, `RAISED`, fill `canvas`. Optional `onPress`.

4.4 `NeuInset` : sunken well, radius 20, padding 12 to 16, `INSET`, fill `canvas`.

4.5 `IconTile` : 48x48 (small 40x40) raised rounded square, radius 16, `RAISED_SM`, icon 24 centred, icon colour passed in.

4.6 `NeuButton` : height 52, radius 20, `RAISED`, label `labelLg` `textPrimary`, optional icon 20 on the left, full width or hugging. Pressed = `INSET`. Disabled: label `textMuted`, no shadow change, no press effect.

4.7 `PrimaryButton(accent)` : height 56, radius 20, fill = accent, label white `bodyLg` weight 600, optional icon 20 on the left, `PRIMARY(accent)` shadow. Variants: indigo `#5B5FEF`, pink `#E1306C`, redDark `#BA1A1A`. Pressed: slightly darker fill and shadow off.

4.8 `NeuToggle` : track 58x32 radius 16, `INSET_SM`; thumb 26x26 circle `RAISED_SM`, 3 dp inside the track. OFF: thumb at the left, no icon. ON: thumb at the right, a small check icon (14) in the thumb, the track gets `GLOW` of the toggle colour (green for Normal / Level 1, amber for Strict / Level 2, red for Aggressive / Restrict / Level 3; F1 uses the mode colour, F2 and F3 use the mode colour of the common level). Locked (needs unlock): thumb shows a lock icon (14, `textMuted`), track stays flat. Animate the thumb 160 ms.

4.9 `StatusChip` : height 24, horizontal padding 10, radius full, `labelMd` (use 11 if it does not fit), UPPERCASE for status chips. Variants: `normal` (fill green at 18%, text `normalGreenDark`), `strict` (fill `strictAmberTint`, text `strictAmberDark`), `aggressive` (fill `errorTint`, text `aggressiveRedDark`), `success` (fill `successTint`, text `#00714D`), `indigo` (fill `indigoTint`, text `indigoDark`), `pink`, `neutral` (INSET_SM, text `textSecondary`). Optional leading dot 6 dp in the text colour.

4.10 `ModeSelector` : a sunken track (height 56, radius 20, `INSET`) with 3 equal segments. The selected segment is a raised thumb (radius 18, `RAISED_SM`) that slides (180 ms). Each segment: optional dot 6 dp in the mode colour on the left of the label, label `labelLg` (selected: `textPrimary`; others: `textSecondary`), optional second line `labelXs` (e.g. "Level 1"). For the Reels/Shorts selector the track height is 64 because of the second line.

4.11 `NeuSlider(min, max, value, accent)` : track height 10, radius full, `INSET_SM`; filled part = accent; thumb 28 circle `RAISED_SM` with an 8 dp accent dot in the middle. Under the track, scale labels (`labelXs`, `textMuted`) at 1m, 15m, 30m, 45m, 60m (Restrict timer slider: 5m, 30m, 60m). Step 1. Dragging updates the big value live.

4.14 `PresetChips` : one row of equal-width chips (flex 1, gap 4, height 36, radius full). Unselected: `RAISED_SM`, label `labelMd` `textSecondary`. Selected: `INSET`, label bold in the accent colour. Chips for watch time and waiting time (both features): 1, 2, 5, 10, 20, 30, 60 (labels "1m", "2m", "5m", "10m", "20m", "30m", "60m"). Tapping a chip sets the slider to that value. If the slider value is not a preset, no chip is selected.

4.15 `CountdownRing(size, accent, progress 0..1, children)` : outer raised circle (`RAISED_LG`), inside it an inset circle (`INSET`) with an SVG arc ring (react-native-svg): stroke 12, round caps, colour = accent, starting at the top and running clockwise; the arc SHRINKS as time passes (progress = remaining / total). Centre content is passed as children. Sizes: S6 270, S13 250, S14 160.

4.16 `GlassSheet` (bottom sheet) : fills the width, top corners 32, `glassFillText`, `glassBorder`, blur 24, glass shadow, grab handle 40x4 radius 2 colour `textMuted` at 40% centred 12 dp from the top. Slides up 240 ms. The screen behind is blurred and dimmed with `scrim`. Tapping the scrim does NOT close it (only the buttons do).

4.17 `GlassModal` (centred dialog) : width screen minus 40, radius 28, `glassFillText`, `glassBorder`, blur 24, padding 24, glass shadow, on `scrim`. Fades in 160 ms.

4.18 `BottomNav` : height 72 plus bottom inset, fill `canvas`, a soft top shadow (`0 -4 12 rgba(163,177,198,0.35)`). Three tabs with equal width: "Home" (`meditation`), "18+ Blocker" (`shield-outline`), "Short Video" (`play-circle-outline`). Inactive: icon 24 and label `labelMd` in `textSecondary`. Active: a sunken pill (96x52, radius 20, `INSET`) behind the icon and label, both coloured `indigo`.

4.19 `InfoStrip` : a `NeuInset` row with a leading icon 20 (`textSecondary`) and `bodySm` text `textSecondary`.

4.20 `ProgressBar` : height 8, radius full, `INSET_SM`, fill colour as given, fill width = fraction.

---

## 5. Navigation and screen map

- On first launch (or whenever a required permission is missing) show the onboarding stack: S1 Permissions & Setup. "Continue" goes to the tabs.
- Tabs: Home (S2), 18+ Blocker (S3), Short Video (S8).
- Pushed screens (back button returns): S9 Instagram Reels settings and S10 YouTube Shorts settings (from S8 cards), S5 Type to Unlock, S6 Waiting Period, S7 Two-person Face Verification (from S4), S13 Restrict countdown (from S8/S9/S10).
- Glass layers: S4 Unlock Protection sheet (over S3, also over S2 when the 18+ toggle is tapped in Strict/Aggressive), S11 Restrict timer sheet (over S8), S12 Strict challenge modal (over S8, S9, S10), S1 help dialog.
- S14 Time's-up overlay and S15 Block Screen appear over OTHER apps (browser, Instagram, YouTube). Use a transparent Habit activity or a native overlay window, whichever works reliably on Xiaomi HyperOS with the "Display over other apps" permission, and tell the person which one you used.
