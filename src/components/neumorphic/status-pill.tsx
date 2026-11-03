import { StyleSheet, Text, View } from 'react-native';

import { FontNames, Palette, Radii } from '@/constants/theme';

type Tone = 'amber' | 'success' | 'danger' | 'neutral';

type Props = {
  label: string;
  tone?: Tone;
  dot?: boolean;
};

const TONES: Record<Tone, { background: string; text: string }> = {
  amber: { background: Palette.amberHighlight, text: Palette.amber },
  success: { background: 'rgba(52, 211, 153, 0.14)', text: Palette.success },
  danger: { background: 'rgba(248, 113, 113, 0.14)', text: Palette.danger },
  neutral: { background: Palette.surfaceHigh, text: Palette.textSecondary },
};

/** Uppercase Space Mono chip for modes, statuses and counters. */
export function StatusPill({ label, tone = 'neutral', dot }: Props) {
  const colors = TONES[tone];
  return (
    <View style={[styles.pill, { backgroundColor: colors.background }]}>
      {dot && (
        <View
          style={[
            styles.dot,
            { backgroundColor: colors.text },
            tone === 'amber' && styles.dotGlow,
          ]}
        />
      )}
      <Text style={[styles.label, { color: colors.text }]}>{label}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  pill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    alignSelf: 'flex-start',
    borderRadius: Radii.pill,
    paddingHorizontal: 10,
    paddingVertical: 4,
  },
  dot: { width: 6, height: 6, borderRadius: 3 },
  dotGlow: { boxShadow: '0px 0px 6px rgba(251, 191, 36, 0.9)' },
  label: {
    fontFamily: FontNames.monoBold,
    fontSize: 10,
    lineHeight: 14,
    letterSpacing: 0.8,
  },
});
