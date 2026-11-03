import { useState } from 'react';
import { Image, Pressable, StyleSheet, Text, View } from 'react-native';

import { FontNames, Palette, Radii, Shadows } from '@/constants/theme';

type Props = {
  /** App or channel name; the tile falls back to its first letter. */
  name: string;
  /** `file://` URI of the app's launcher icon, when the native side provided one. */
  iconUri?: string;
  size?: number;
  selected?: boolean;
  muted?: boolean;
  onPress?: () => void;
};

/** 44x44 raised tile showing the real app logo, or a Space Mono letter without one. */
export function AppTile({ name, iconUri, size = Radii.tileSize, selected, muted, onPress }: Props) {
  const [iconFailed, setIconFailed] = useState(false);
  const letter = name.trim().charAt(0).toUpperCase() || '?';
  const showIcon = Boolean(iconUri) && !iconFailed;
  const content = (
    <View
      style={[
        styles.tile,
        { width: size, height: size, borderRadius: Radii.tile },
        selected && styles.selected,
        muted && styles.muted,
      ]}>
      {showIcon ? (
        <Image
          source={{ uri: iconUri }}
          style={styles.icon}
          resizeMode="cover"
          onError={() => setIconFailed(true)}
          accessibilityIgnoresInvertColors
        />
      ) : (
        <Text style={[styles.letter, muted && styles.letterMuted]}>{letter}</Text>
      )}
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
    overflow: 'hidden',
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
  icon: {
    width: '100%',
    height: '100%',
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
