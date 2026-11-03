import { Pressable, StyleSheet, Text, View } from 'react-native';

import { FontNames, Palette, Radii, Shadows } from '@/constants/theme';

type Props = {
  /** App or channel name; the tile shows its first letter. */
  name: string;
  size?: number;
  selected?: boolean;
  muted?: boolean;
  onPress?: () => void;
};

/** 44x44 raised icon tile with a single Space Mono character. */
export function AppTile({ name, size = Radii.tileSize, selected, muted, onPress }: Props) {
  const letter = name.trim().charAt(0).toUpperCase() || '?';
  const content = (
    <View
      style={[
        styles.tile,
        { width: size, height: size, borderRadius: Radii.tile },
        selected && styles.selected,
        muted && styles.muted,
      ]}>
      <Text style={[styles.letter, muted && styles.letterMuted]}>{letter}</Text>
    </View>
  );

  if (!onPress) return content;

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={name}
      onPress={onPress}
      style={({ pressed }) => [pressed && styles.pressed]}>
      {content}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  tile: {
    backgroundColor: Palette.raised,
    boxShadow: Shadows.raisedSmall,
    alignItems: 'center',
    justifyContent: 'center',
  },
  selected: {
    backgroundColor: Palette.amberHighlight,
    boxShadow: Shadows.raisedSmall,
    borderWidth: 1,
    borderColor: Palette.amber,
  },
  muted: {
    backgroundColor: Palette.surface,
    boxShadow: undefined,
    opacity: 0.5,
  },
  letter: {
    fontFamily: FontNames.monoBold,
    fontSize: 16,
    lineHeight: 20,
    color: Palette.textPrimary,
  },
  letterMuted: { color: Palette.textMuted },
  pressed: { opacity: 0.75 },
});
