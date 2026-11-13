import { MaterialCommunityIcons } from '@expo/vector-icons';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { FontNames, Palette, Shadows } from '@/constants/theme';

type Props = {
  enabled: boolean;
  locked?: boolean;
  busy?: boolean;
  onPress: () => void;
};

/** 148dp circular master control: sunken housing, raised puck, amber power glyph. */
export function MasterDial({ enabled, locked, busy, onPress }: Props) {
  return (
    <Pressable
      accessibilityRole="switch"
      accessibilityLabel={enabled ? 'Turn protection off' : 'Turn protection on'}
      accessibilityState={{ checked: enabled, busy }}
      disabled={busy}
      onPress={onPress}
      style={({ pressed }) => [styles.housing, pressed && styles.pressed, busy && styles.busy]}>
      <View style={[styles.puck, enabled && styles.puckOn]}>
        <MaterialCommunityIcons
          name="power"
          size={40}
          color={enabled ? Palette.amber : Palette.textMuted}
          style={enabled ? styles.glyphOn : undefined}
        />
        <Text style={[styles.state, enabled && styles.stateOn]}>{enabled ? 'ON' : 'OFF'}</Text>
      </View>
      {locked && (
        <View style={styles.lockBadge}>
          <MaterialCommunityIcons name="lock" size={15} color={Palette.canvas} />
        </View>
      )}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  housing: {
    width: 148,
    height: 148,
    borderRadius: 74,
    backgroundColor: Palette.well,
    boxShadow: Shadows.sunken,
    alignItems: 'center',
    justifyContent: 'center',
  },
  puck: {
    width: 104,
    height: 104,
    borderRadius: 52,
    backgroundColor: Palette.raised,
    boxShadow: Shadows.raisedLarge,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 2,
  },
  puckOn: {
    boxShadow: `${Shadows.raisedLarge}, inset 0px 0px 18px rgba(251, 191, 36, 0.10)`,
  },
  glyphOn: {
    textShadowColor: 'rgba(251, 191, 36, 0.6)',
    textShadowRadius: 12,
  },
  state: {
    fontFamily: FontNames.monoBold,
    fontSize: 11,
    lineHeight: 15,
    letterSpacing: 1.4,
    color: Palette.textMuted,
  },
  stateOn: { color: Palette.amber },
  lockBadge: {
    position: 'absolute',
    right: 6,
    bottom: 6,
    width: 30,
    height: 30,
    borderRadius: 15,
    backgroundColor: Palette.amber,
    alignItems: 'center',
    justifyContent: 'center',
    boxShadow: Shadows.amberHalo,
  },
  pressed: { opacity: 0.9 },
  busy: { opacity: 0.6 },
});
