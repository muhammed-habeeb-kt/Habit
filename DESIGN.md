---
name: Habit Soft Black & Amber
colors:
  surface: '#121316'
  surface-dim: '#121316'
  surface-bright: '#38393c'
  surface-container-lowest: '#0d0e11'
  surface-container-low: '#1b1b1f'
  surface-container: '#1f1f23'
  surface-container-high: '#292a2d'
  surface-container-highest: '#343538'
  on-surface: '#e3e2e6'
  on-surface-variant: '#d3c5ac'
  inverse-surface: '#e3e2e6'
  inverse-on-surface: '#2f3034'
  outline: '#9c8f79'
  outline-variant: '#4f4633'
  surface-tint: '#f9bd22'
  primary: '#ffe1a7'
  on-primary: '#402d00'
  primary-container: '#fbbf24'
  on-primary-container: '#6c4f00'
  inverse-primary: '#795900'
  secondary: '#45dfa4'
  on-secondary: '#003825'
  secondary-container: '#00bd85'
  on-secondary-container: '#00452e'
  tertiary: '#ffdddb'
  on-tertiary: '#670211'
  tertiary-container: '#ffb6b3'
  on-tertiary-container: '#9a2b30'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#ffdf9f'
  primary-fixed-dim: '#f9bd22'
  on-primary-fixed: '#261a00'
  on-primary-fixed-variant: '#5c4300'
  secondary-fixed: '#68fcbf'
  secondary-fixed-dim: '#45dfa4'
  on-secondary-fixed: '#002114'
  on-secondary-fixed-variant: '#005137'
  tertiary-fixed: '#ffdad8'
  tertiary-fixed-dim: '#ffb3b0'
  on-tertiary-fixed: '#410006'
  on-tertiary-fixed-variant: '#881d24'
  background: '#121316'
  on-background: '#e3e2e6'
  surface-variant: '#343538'
typography:
  display-lg:
    fontFamily: Space Mono
    fontSize: 36px
    fontWeight: '700'
    lineHeight: 40px
    letterSpacing: -0.02em
  display-md:
    fontFamily: Space Mono
    fontSize: 28px
    fontWeight: '700'
    lineHeight: 32px
    letterSpacing: -0.01em
  headline-lg:
    fontFamily: Inter
    fontSize: 22px
    fontWeight: '700'
    lineHeight: 28px
    letterSpacing: -0.01em
  headline-md:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
    letterSpacing: '0'
  body-lg:
    fontFamily: Inter
    fontSize: 15px
    fontWeight: '500'
    lineHeight: 22px
    letterSpacing: '0'
  body-md:
    fontFamily: Inter
    fontSize: 13px
    fontWeight: '400'
    lineHeight: 18px
    letterSpacing: '0'
  body-sm:
    fontFamily: Inter
    fontSize: 11px
    fontWeight: '400'
    lineHeight: 16px
    letterSpacing: 0.01em
  mono-label-lg:
    fontFamily: Space Mono
    fontSize: 13px
    fontWeight: '700'
    lineHeight: 16px
    letterSpacing: 0.06em
  mono-label-sm:
    fontFamily: Space Mono
    fontSize: 10px
    fontWeight: '700'
    lineHeight: 14px
    letterSpacing: 0.12em
  mono-code:
    fontFamily: Space Mono
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 18px
    letterSpacing: '0'
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  gutter: 12px
  margin: 16px
  space-xs: 4px
  space-sm: 8px
  space-md: 16px
  space-lg: 20px
  space-xl: 24px
  space-2xl: 32px
---

## Brand & Style

This design system expresses disciplined focus, silent protection, and deliberate restraint. Built for an Android self-control and habit guardian environment, the visual aesthetic merges soft-tactile dark neumorphism with subtle frosted glass overlays.

The style departs from standard flat interfaces by treating every surface as physical, molded matte plastic extruded directly from a void-black substrate. Dual-directional lighting (top-left cool illumination, bottom-right deep obsidian shadow) establishes realistic tactile depth. This is paired with an energetic amber illumination that signals active restraint, vigilant monitoring, and authority. The experience feels engineered, quiet, and unyielding—giving users a sense of absolute security and frictionless control over digital habits.

## Colors

The palette is tuned around a dark field where elements emerge via micro-contrast and directional light rather than hard strokes.

- **Canvas & Surfaces:** The foundation is `#101114`. Convex surfaces lift subtly into `#121417`, while concave wells drop into `#0D0E10`.
- **Directional Shadows:** Cast-shadow mechanics utilize `#1A1C21` for top-left diffused highlight extraction and `#060607` for bottom-right anchor depth.
- **Amber Accent:** `#FBBF24` serves as the primary system feedback: active shields, critical switches, toggle glows, and numeric counters. Under pressed conditions, it transitions to `#D99A06`. Subtle structural highlights take the form of `rgba(251, 191, 36, 0.14)`.
- **Text & Hierarchy:** The high-readability tiered text structure uses `#F2F2F4` (Primary headers, active metrics), `#9A9CA6` (Secondary explanations, subtitles), and `#5E606A` (Muted hints, monospaced metadata, inactive states).
- **Semantics:** `#34D399` denotes validated security and positive milestones; `#F87171` warns against disablement attempts and critical system alerts.

## Typography

The typography couples the human-centric functional clarity of Inter with the technical precision of Space Mono.

- **Inter:** Drives all primary UI language, conversational dialogs, explanatory notes, and screen headlines. It ensures immediate legibility and calm balance on dark backgrounds.
- **Space Mono:** Applied strictly to telemetry, operational badges, timers, numeric data metrics (such as blocked attempts), section caps, and tactical chip tags.
- **Micro Typography:** Uppercase tracking on mono labels creates an authentic terminal-like feedback loop, ensuring low-density peripheral cues are rapidly parseable without competing with main headlines.

## Layout & Spacing

Designed for the standard 390x844 dp Android form factor, the spatial rhythm uses an 8dp/4dp base modular grid:

- **Canvas Padding:** Outer edges maintain a strict `16px` horizontal margin to maximize screen real estate while protecting tactile neumorphic shadows from clipping against the display perimeter.
- **Vertical Rhythm:** Components stack with `12px` to `16px` gaps. Major conceptual boundaries (e.g., between the primary power dial and configuration cards) deploy `24px` to `32px` intervals.
- **Component Breathing Room:** Cards feature generous internal padding (`16px` to `20px`) to insulate tactile child items (such as app tiles and nested segment pickers) from outer borders.

## Shapes

Corner radii are structured proportionally according to element scale to maintain visual harmony:

- **Cards & Primary Modules:** Fixed `24px` radius. Large corner sweeps soften high-contrast shadow transitions.
- **Buttons, Wells & Inputs:** Standardized `20px` radius.
- **Icon Tiles:** Sized precisely at `44x44 dp` with a `14px` radius to nest inside cards without touching borders.
- **Chips, Badges & Toggles:** Fully rounded pills (`9999px`) provide contrast against squarer module containers.

## Components

### Master Control Dial
- **Geometry:** 180x180 dp circular housing.
- **Styling:** Recessed sunken well with an inner raised circular puck.
- **Indicator:** Centered power glyph colored in `#FBBF24` with an amber drop blur `0 0 12px rgba(251, 191, 36, 0.6)`. Below the glyph, a Space Mono indicator reads `ON` or `OFF`.
- **Floating Badge:** 32x32 dp lock indicator anchored to the bottom-right rim with a raised amber treatment.

### Segmented Mode Selector
- **Container:** Inset sunken well (`radius: 20px`, padding: `4px`).
- **Segments:** 3 options (`Normal`, `Strict`, `Aggressive`).
- **Active Segment:** Extruded raised surface with `#FBBF24` label; inactive segments feature flat, transparent backgrounds with `#9A9CA6` typography.

### Neumorphic Switch
- **Dimensions:** 56x32 dp track with an inset sunken well.
- **Thumb:** 24x24 dp raised circular button.
- **States:** Inactive thumb rests left in neutral tone; active thumb slides right, accompanied by an internal amber rim glow.

### Action Buttons
- **Primary Glow Button:** Raised `#FBBF24` fill, text `#101114` (Inter bold), casting an amber glow `0 8px 24px rgba(251, 191, 36, 0.25)`.
- **Secondary Raised Button:** Raised neumorphic base `#121417`, text `#F2F2F4`, dual `#1A1C21` / `#060607` shadow pair. When pressed, shifts immediately to sunken inset shadow.

### App & Channel Tiles
- **Geometry:** 44x44 dp raised tile with `14px` border radius.
- **Content:** Centered single-character app icon or acronym in Space Mono bold (`#F2F2F4`).

### Metric Cards
- **Structure:** 24px corner-radius raised module.
- **Left Column:** Inter headline and subtitle.
- **Right Column:** Prominent metric value set in Space Mono bold `28px` colored in `#FBBF24`.

### Frosted Alert Banner
- **Structure:** Floating card with `backdrop-filter: blur(24px)`, filled with `rgba(255, 255, 255, 0.08)` and bordered with `1px solid rgba(255, 255, 255, 0.14)`.
- **Content:** Amber warning icon left, two-line title/description center, compact raised button ("Fix now") right.

### Bottom Navigation Bar
- **Dimensions:** 64dp height, floating 16dp above screen bottom.
- **Style:** Subtle frosted glass with raised icons for Home, Apps, and Settings.
- **Active State:** Amber glow dot anchored beneath the active tab item.