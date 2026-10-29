/**
 * Single source of truth for Habit's look: colors (light + dark), spacing,
 * radii and type scale. Screens and components only reference these tokens.
 */

import { Platform } from 'react-native';

export const Colors = {
  light: {
    text: '#0E1A16',
    background: '#F3F6F4',
    backgroundElement: '#E7EEEA',
    backgroundSelected: '#DCE7E1',
    textSecondary: '#5C6F66',
    textMuted: '#8A9A93',
    surface: '#FFFFFF',
    surfaceAlt: '#EDF2EF',
    border: '#DDE7E2',
    accent: '#12876A',
    accentSoft: '#DCF1E9',
    onAccent: '#FFFFFF',
    danger: '#B3261E',
    dangerSoft: '#FBE7E5',
    dangerBorder: '#F2C7C2',
    success: '#12876A',
    ring: 'rgba(18, 135, 106, 0.16)',
    overlay: 'rgba(14, 26, 22, 0.45)',
  },
  dark: {
    text: '#F2F7F4',
    background: '#0B1310',
    backgroundElement: '#16241E',
    backgroundSelected: '#1B3027',
    textSecondary: '#A2B8AD',
    textMuted: '#71887D',
    surface: '#121F19',
    surfaceAlt: '#16261F',
    border: '#1F3129',
    accent: '#3ECF9E',
    accentSoft: '#173B31',
    onAccent: '#06120E',
    danger: '#FF9B90',
    dangerSoft: '#33201D',
    dangerBorder: '#4A2C27',
    success: '#3ECF9E',
    ring: 'rgba(62, 207, 158, 0.18)',
    overlay: 'rgba(0, 0, 0, 0.55)',
  },
} as const;

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
  lg: 22,
  xl: 28,
  pill: 999,
} as const;

export const Type = {
  display: { fontSize: 40, lineHeight: 46, fontWeight: '700' },
  title: { fontSize: 26, lineHeight: 32, fontWeight: '700' },
  subtitle: { fontSize: 19, lineHeight: 26, fontWeight: '600' },
  body: { fontSize: 16, lineHeight: 24, fontWeight: '400' },
  bodyStrong: { fontSize: 16, lineHeight: 24, fontWeight: '600' },
  small: { fontSize: 14, lineHeight: 20, fontWeight: '500' },
  micro: { fontSize: 12, lineHeight: 16, fontWeight: '600' },
  eyebrow: { fontSize: 11, lineHeight: 14, fontWeight: '700', letterSpacing: 1.4 },
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
