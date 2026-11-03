import { Pressable, StyleSheet, Text, View } from 'react-native';

import { FontNames, Palette, Radii, Shadows } from '@/constants/theme';

export type SegmentOption<T extends string> = {
  id: T;
  label: string;
  /** Optional second line, e.g. "Level 1". */
  hint?: string;
};

type Props<T extends string> = {
  options: readonly SegmentOption<T>[];
  value: T;
  onChange: (value: T) => void;
};

/** Sunken track with one raised amber segment; used for the protection mode. */
export function NeuSegmented<T extends string>({ options, value, onChange }: Props<T>) {
  const tall = options.some((option) => option.hint);
  return (
    <View style={[styles.track, tall && styles.trackTall]}>
      {options.map((option) => {
        const active = option.id === value;
        return (
          <Pressable
            key={option.id}
            accessibilityRole="button"
            accessibilityState={{ selected: active }}
            onPress={() => onChange(option.id)}
            style={[styles.segment, active && styles.segmentActive]}>
            <Text style={[styles.label, active && styles.labelActive]}>{option.label}</Text>
            {option.hint ? (
              <Text style={[styles.hint, active && styles.hintActive]}>{option.hint}</Text>
            ) : null}
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  track: {
    flexDirection: 'row',
    height: 56,
    padding: 4,
    borderRadius: Radii.well,
    backgroundColor: Palette.well,
    boxShadow: Shadows.sunken,
    gap: 4,
  },
  trackTall: { height: 64 },
  segment: {
    flex: 1,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 1,
  },
  segmentActive: {
    backgroundColor: Palette.raised,
    boxShadow: Shadows.raisedSmall,
  },
  label: {
    fontFamily: FontNames.interMedium,
    fontSize: 13,
    lineHeight: 18,
    color: Palette.textSecondary,
  },
  labelActive: {
    fontFamily: FontNames.interSemiBold,
    color: Palette.amber,
  },
  hint: {
    fontFamily: FontNames.monoRegular,
    fontSize: 9,
    lineHeight: 12,
    color: Palette.textMuted,
  },
  hintActive: { color: Palette.amber },
});
