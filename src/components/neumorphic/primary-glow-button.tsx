import { type ReactNode } from 'react';
import { Pressable, StyleSheet, Text, type StyleProp, type ViewStyle } from 'react-native';

import { FontNames, Palette, Radii, Shadows } from '@/constants/theme';

type Props = {
  label: string;
  onPress: () => void;
  icon?: ReactNode;
  disabled?: boolean;
  style?: StyleProp<ViewStyle>;
};

/** Raised amber action button with a soft amber glow. */
export function PrimaryGlowButton({ label, onPress, icon, disabled, style }: Props) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [
        styles.button,
        pressed && styles.pressed,
        disabled && styles.disabled,
        style,
      ]}>
      {icon}
      <Text style={styles.label}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    minHeight: 52,
    borderRadius: Radii.control,
    backgroundColor: Palette.amber,
    boxShadow: Shadows.amberGlow,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    paddingHorizontal: 20,
    paddingVertical: 12,
  },
  pressed: { backgroundColor: Palette.amberPressed, boxShadow: undefined },
  disabled: { opacity: 0.4 },
  label: {
    fontFamily: FontNames.interBold,
    fontSize: 15,
    lineHeight: 22,
    color: Palette.canvas,
  },
});
