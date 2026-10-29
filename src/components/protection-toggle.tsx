import { Pressable, StyleSheet, Text, View } from 'react-native';

import { Radius, Spacing, Type, type Theme } from '@/constants/theme';

type Props = {
  enabled: boolean;
  busy?: boolean;
  theme: Theme;
  onPress: () => void;
};

export function ProtectionToggle({ enabled, busy, theme, onPress }: Props) {
  return (
    <Pressable
      accessibilityRole="switch"
      accessibilityState={{ checked: enabled, busy }}
      accessibilityLabel={enabled ? 'Turn protection off' : 'Turn protection on'}
      disabled={busy}
      onPress={onPress}
      style={({ pressed }) => [
        styles.outer,
        {
          borderColor: enabled ? theme.accent : theme.border,
          backgroundColor: enabled ? theme.accentSoft : theme.surface,
          opacity: pressed || busy ? 0.85 : 1,
        },
      ]}>
      <View
        style={[
          styles.inner,
          { backgroundColor: enabled ? theme.accent : theme.surfaceAlt },
        ]}>
        <Text
          style={[
            styles.state,
            { color: enabled ? theme.onAccent : theme.textMuted },
          ]}>
          {enabled ? 'ON' : 'OFF'}
        </Text>
        <Text
          style={[
            styles.caption,
            { color: enabled ? theme.onAccent : theme.textSecondary },
          ]}>
          {enabled ? 'Protection active' : 'Tap to protect'}
        </Text>
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  outer: {
    width: 212,
    height: 212,
    borderRadius: Radius.pill,
    borderWidth: 2,
    alignItems: 'center',
    justifyContent: 'center',
    alignSelf: 'center',
  },
  inner: {
    width: 168,
    height: 168,
    borderRadius: Radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
    gap: Spacing.one,
  },
  state: {
    fontSize: Type.display.fontSize,
    lineHeight: Type.display.lineHeight,
    fontWeight: '700',
    letterSpacing: 1,
  },
  caption: {
    fontSize: Type.micro.fontSize,
    fontWeight: '600',
  },
});
