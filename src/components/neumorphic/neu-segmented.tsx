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
  /**
   * `raised` (default) lifts the active segment off the track; `solid` fills it with
   * amber and flips the label to dark text, as on the Home mode picker.
   */
  variant?: 'raised' | 'solid';
  /** 34 dp control with an amber-tinted active segment, for dense settings rows. */
  compact?: boolean;
};

/** Sunken pill track with one highlighted segment; used for modes and sensitivity. */
export function NeuSegmented<T extends string>({
  options,
  value,
  onChange,
  variant = 'raised',
  compact = false,
}: Props<T>) {
  const tall = options.some((option) => option.hint) && !compact;
  const solid = variant === 'solid';
  return (
    <View style={[styles.track, compact && styles.trackCompact, tall && styles.trackTall]}>
      {options.map((option) => {
        const active = option.id === value;
        return (
          <Pressable
            key={option.id}
            accessibilityRole="button"
            accessibilityState={{ selected: active }}
            onPress={() => onChange(option.id)}
            style={[
              styles.segment,
              compact && styles.segmentCompact,
              active &&
                (solid
                  ? styles.segmentSolid
                  : compact
                    ? styles.segmentCompactActive
                    : styles.segmentRaised),
            ]}>
            {/* Capped font scaling keeps "Aggressive" on one line on large-font phones. */}
            <Text
              numberOfLines={1}
              maxFontSizeMultiplier={1.15}
              style={[
                styles.label,
                compact && styles.labelCompact,
                active && (solid ? styles.labelSolid : styles.labelRaised),
              ]}>
              {option.label}
            </Text>
            {option.hint ? (
              <Text
                numberOfLines={1}
                maxFontSizeMultiplier={1.15}
                style={[styles.hint, active && (solid ? styles.hintSolid : styles.hintRaised)]}>
                {option.hint}
              </Text>
            ) : null}
          </Pressable>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  track: {
    width: '100%',
    flexDirection: 'row',
    height: 52,
    padding: 4,
    borderRadius: Radii.pill,
    backgroundColor: Palette.well,
    boxShadow: Shadows.sunken,
    gap: 2,
  },
  trackTall: { height: 64 },
  trackCompact: { height: 34, padding: 3 },
  segment: {
    flex: 1,
    minWidth: 0,
    // No horizontal padding: the segment width is the label's whole budget.
    paddingHorizontal: 0,
    borderRadius: Radii.pill,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 1,
  },
  segmentRaised: {
    backgroundColor: Palette.raised,
    boxShadow: Shadows.raisedSmall,
  },
  segmentCompact: {
    borderWidth: 1,
    borderColor: 'transparent',
  },
  segmentCompactActive: {
    backgroundColor: Palette.amberHighlight,
    borderColor: Palette.amber,
  },
  segmentSolid: {
    backgroundColor: Palette.amber,
    boxShadow: Shadows.amberGlow,
  },
  label: {
    fontFamily: FontNames.interMedium,
    fontSize: 12,
    lineHeight: 16,
    letterSpacing: 0,
    textAlign: 'center',
    width: '100%',
    color: Palette.textSecondary,
  },
  labelRaised: {
    fontFamily: FontNames.interSemiBold,
    color: Palette.amber,
  },
  labelSolid: {
    fontFamily: FontNames.interBold,
    color: Palette.canvas,
  },
  labelCompact: {
    fontSize: 11,
    lineHeight: 15,
  },
  hint: {
    fontFamily: FontNames.monoRegular,
    fontSize: 9,
    lineHeight: 12,
    color: Palette.textMuted,
  },
  hintRaised: { color: Palette.amber },
  hintSolid: { color: Palette.canvas },
});
