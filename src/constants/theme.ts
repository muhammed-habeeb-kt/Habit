/**
 * Habit design system: "Soft Black & Amber".
 *
 * Every color, radius, shadow, spacing and type value used by the UI lives here.
 * Screens and components must reference these tokens instead of hard-coding values.
 */

import { Platform, type TextStyle, type ViewStyle } from 'react-native';

/* -------------------------------------------------------------------------- */
/* Canvas, surfaces and shadows                                               */
/* -------------------------------------------------------------------------- */

export const Palette = {
  /** App foundation background. */
  canvas: '#101114',
  /** Standard card surface. */
  surface: '#121316',
  /** Convex, raised material (cards, buttons, tiles). */
  raised: '#121417',
  /** Concave, sunken well (tracks, inputs, dial housing). */
  well: '#0D0E10',
  surfaceContainer: '#1f1f23',
  surfaceHigh: '#292a2d',

  shadowTopLeft: '#1A1C21',
  shadowBottomRight: '#060607',

  amber: '#FBBF24',
  amberPressed: '#D99A06',
  amberGlow: 'rgba(251, 191, 36, 0.25)',
  amberHighlight: 'rgba(251, 191, 36, 0.14)',

  textPrimary: '#F2F2F4',
  textSecondary: '#9A9CA6',
  textMuted: '#5E606A',

  success: '#34D399',
  danger: '#F87171',

  /** Frosted glass layers that float above content. */
  glassFill: 'rgba(255, 255, 255, 0.08)',
  glassBorder: 'rgba(255, 255, 255, 0.14)',
  scrim: 'rgba(0, 0, 0, 0.55)',
} as const;

/** Dual-directional soft-UI shadows (top-left cool light, bottom-right deep shade). */
export const Shadows = {
  raised: `-6px -6px 14px ${Palette.shadowTopLeft}, 6px 6px 14px ${Palette.shadowBottomRight}`,
  raisedSmall: `-3px -3px 8px ${Palette.shadowTopLeft}, 3px 3px 8px ${Palette.shadowBottomRight}`,
  raisedLarge: `-8px -8px 18px ${Palette.shadowTopLeft}, 8px 8px 20px ${Palette.shadowBottomRight}`,
  sunken: `inset -4px -4px 8px ${Palette.shadowTopLeft}, inset 4px 4px 8px ${Palette.shadowBottomRight}`,
  sunkenSmall: `inset -3px -3px 6px ${Palette.shadowTopLeft}, inset 3px 3px 6px ${Palette.shadowBottomRight}`,
  amberGlow: '0px 8px 24px rgba(251, 191, 36, 0.25)',
  amberHalo: '0px 0px 18px rgba(251, 191, 36, 0.35)',
} as const satisfies Record<string, ViewStyle['boxShadow']>;

/* -------------------------------------------------------------------------- */
/* Radii, spacing and sizing                                                  */
/* -------------------------------------------------------------------------- */

export const Radii = {
  card: 24,
  control: 20,
  input: 20,
  well: 20,
  tile: 14,
  tileSize: 44,
  pill: 9999,
} as const;

export const Space = {
  gutter: 12,
  margin: 16,
  xs: 4,
  sm: 8,
  md: 16,
  lg: 20,
  xl: 24,
  xxl: 32,
  /** Room reserved at the bottom of tab screens for the floating nav bar. */
  tabBar: 110,
} as const;

export const Typography = {
  displayLg: {
    fontFamily: 'SpaceMono_700Bold',
    fontSize: 36,
    lineHeight: 40,
    letterSpacing: -0.72,
  },
  displayMd: {
    fontFamily: 'SpaceMono_700Bold',
    fontSize: 28,
    lineHeight: 32,
    letterSpacing: -0.28,
  },
  headlineLg: {
    fontFamily: 'Inter_700Bold',
    fontSize: 22,
    lineHeight: 28,
    letterSpacing: -0.22,
  },
  headlineMd: {
    fontFamily: 'Inter_600SemiBold',
    fontSize: 18,
    lineHeight: 24,
  },
  bodyLg: {
    fontFamily: 'Inter_500Medium',
    fontSize: 15,
    lineHeight: 22,
  },
  bodyMd: {
    fontFamily: 'Inter_400Regular',
    fontSize: 13,
    lineHeight: 18,
  },
  bodySm: {
    fontFamily: 'Inter_400Regular',
    fontSize: 11,
    lineHeight: 16,
    letterSpacing: 0.11,
  },
  monoLabelLg: {
    fontFamily: 'SpaceMono_700Bold',
    fontSize: 13,
    lineHeight: 16,
    letterSpacing: 0.78,
  },
  monoLabelSm: {
    fontFamily: 'SpaceMono_700Bold',
    fontSize: 10,
    lineHeight: 14,
    letterSpacing: 1.2,
  },
  monoCode: {
    fontFamily: 'SpaceMono_400Regular',
    fontSize: 12,
    lineHeight: 18,
  },
} as const satisfies Record<string, TextStyle>;

export const FontNames = {
  interRegular: 'Inter_400Regular',
  interMedium: 'Inter_500Medium',
  interSemiBold: 'Inter_600SemiBold',
  interBold: 'Inter_700Bold',
  monoRegular: 'SpaceMono_400Regular',
  monoBold: 'SpaceMono_700Bold',
} as const;

/* -------------------------------------------------------------------------- */
/* Legacy compatibility layer                                                 */
/* -------------------------------------------------------------------------- */
/*
 * The remaining screens (onboarding, unlock, face-unlock, protection toggle)
 * still read the flat theme object. They are restyled in later prompts, so they
 * keep working against the same dark palette in the meantime.
 */

const LegacyColors = {
  text: Palette.textPrimary,
  background: Palette.canvas,
  backgroundElement: Palette.surfaceContainer,
  backgroundSelected: Palette.surfaceHigh,
  textSecondary: Palette.textSecondary,
  textMuted: Palette.textMuted,
  surface: Palette.surface,
  surfaceAlt: Palette.raised,
  border: '#26272B',
  accent: Palette.amber,
  accentSoft: Palette.amberHighlight,
  onAccent: Palette.canvas,
  danger: Palette.danger,
  dangerSoft: 'rgba(248, 113, 113, 0.14)',
  dangerBorder: 'rgba(248, 113, 113, 0.30)',
  success: Palette.success,
  ring: Palette.amberGlow,
  overlay: Palette.scrim,
};

/** The app is dark-only ("Soft Black & Amber"); both scheme keys share one palette. */
export const Colors = { light: LegacyColors, dark: LegacyColors } as const;

export type Theme = { [K in keyof typeof Colors.light]: string };
export type ThemeColor = keyof Theme;

export const Spacing = {
  half: 2,
  one: 4,
  two: 8,
  three: 16,
  four: 24,
  five: 32,
  six: 48,
  seven: 64,
} as const;

export const Radius = {
  sm: 10,
  md: 16,
  lg: Radii.card,
  xl: 28,
  card: Radii.card,
  control: Radii.control,
  tile: Radii.tile,
  pill: Radii.pill,
} as const;

export const Type = {
  display: {
    fontFamily: FontNames.monoBold,
    fontSize: 40,
    lineHeight: 46,
    fontWeight: '700' as const,
  },
  title: {
    fontFamily: FontNames.interBold,
    fontSize: 26,
    lineHeight: 32,
    fontWeight: '700' as const,
  },
  subtitle: {
    fontFamily: FontNames.interSemiBold,
    fontSize: 19,
    lineHeight: 26,
    fontWeight: '600' as const,
  },
  body: {
    fontFamily: FontNames.interRegular,
    fontSize: 16,
    lineHeight: 24,
    fontWeight: '400' as const,
  },
  bodyStrong: {
    fontFamily: FontNames.interSemiBold,
    fontSize: 16,
    lineHeight: 24,
    fontWeight: '600' as const,
  },
  small: {
    fontFamily: FontNames.interMedium,
    fontSize: 14,
    lineHeight: 20,
    fontWeight: '500' as const,
  },
  micro: {
    fontFamily: FontNames.interSemiBold,
    fontSize: 12,
    lineHeight: 16,
    fontWeight: '600' as const,
  },
  eyebrow: {
    fontFamily: FontNames.monoBold,
    fontSize: 11,
    lineHeight: 14,
    fontWeight: '700' as const,
    letterSpacing: 1.4,
  },
} as const;

export const Fonts = Platform.select({
  ios: {
    sans: 'system-ui',
    serif: 'ui-serif',
    rounded: 'ui-rounded',
    mono: 'ui-monospace',
  },
  default: {
    sans: 'normal',
    serif: 'serif',
    rounded: 'normal',
    mono: 'monospace',
  },
  web: {
    sans: 'var(--font-display)',
    serif: 'var(--font-serif)',
    rounded: 'var(--font-rounded)',
    mono: 'var(--font-mono)',
  },
});

export const BottomTabInset = Platform.select({ ios: 50, android: 80 }) ?? 0;
export const MaxContentWidth = 560;
