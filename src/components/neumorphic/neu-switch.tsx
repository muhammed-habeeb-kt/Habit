import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useEffect, useState } from 'react';
import { Animated, Pressable, StyleSheet } from 'react-native';

import { Palette, Shadows } from '@/constants/theme';

type Props = {
  value: boolean;
  onValueChange: (value: boolean) => void;
  disabled?: boolean;
  /** Screen-reader label; falls back to a generic switch label. */
  label?: string;
};

/** 56x32 sunken track with a 24x24 thumb that slides right and glows when ON. */
export function NeuSwitch({ value, onValueChange, disabled, label }: Props) {
  const [progress] = useState(() => new Animated.Value(value ? 1 : 0));

  useEffect(() => {
    Animated.timing(progress, {
      toValue: value ? 1 : 0,
      duration: 160,
      useNativeDriver: true,
    }).start();
  }, [progress, value]);

  const translateX = progress.interpolate({ inputRange: [0, 1], outputRange: [4, 28] });

  return (
    <Pressable
      accessibilityRole="switch"
      accessibilityLabel={label ?? 'Toggle'}
      accessibilityState={{ checked: value, disabled }}
      disabled={disabled}
      onPress={() => onValueChange(!value)}
      hitSlop={8}
      style={({ pressed }) => [
        styles.track,
        value ? styles.trackOn : styles.trackOff,
        pressed && !disabled && styles.pressed,
        disabled && styles.disabled,
      ]}>
      <Animated.View
        style={[
          styles.thumb,
          value ? styles.thumbOn : styles.thumbOff,
          { transform: [{ translateX }] },
        ]}>
        {value && <MaterialCommunityIcons name="check" size={14} color={Palette.amber} />}
      </Animated.View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  track: {
    width: 56,
    height: 32,
    borderRadius: 16,
    justifyContent: 'center',
  },
  trackOff: {
    backgroundColor: Palette.well,
    boxShadow: Shadows.sunkenSmall,
  },
  trackOn: {
    backgroundColor: Palette.amber,
    boxShadow: Shadows.amberHalo,
  },
  thumb: {
    width: 24,
    height: 24,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  thumbOff: {
    backgroundColor: Palette.surfaceHigh,
    boxShadow: Shadows.raisedSmall,
  },
  thumbOn: {
    backgroundColor: Palette.canvas,
    boxShadow: Shadows.amberHalo,
  },
  pressed: { opacity: 0.85 },
  disabled: { opacity: 0.45 },
});
