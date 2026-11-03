import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { BottomTabBarProps } from 'expo-router/build/react-navigation/bottom-tabs';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { FontNames, Palette, Radii, Space } from '@/constants/theme';

type TabVisual = {
  label: string;
  icon: keyof typeof MaterialCommunityIcons.glyphMap;
  activeIcon: keyof typeof MaterialCommunityIcons.glyphMap;
};

const VISUALS: Record<string, TabVisual> = {
  index: { label: 'Home', icon: 'home-outline', activeIcon: 'home' },
  apps: { label: 'Apps', icon: 'view-grid-outline', activeIcon: 'view-grid' },
  settings: { label: 'Settings', icon: 'cog-outline', activeIcon: 'cog' },
};

/** 64dp frosted bar, floating 16dp above the bottom edge, with an amber active pill. */
export function FloatingTabBar({ state, descriptors, navigation }: BottomTabBarProps) {
  const insets = useSafeAreaInsets();

  return (
    <View style={[styles.frame, { bottom: insets.bottom + 16 }]}>
      <View style={styles.bar}>
        {/*
         * Frosted surface without a native blur module: a translucent dark layer plus a
         * faint top sheen. This renders on any dev build and cannot crash the app if the
         * native blur package is missing.
         */}
        <View style={styles.frost} />
        <View style={styles.sheen} />
        {state.routes.map((route, index) => {
          const visual = VISUALS[route.name];
          if (!visual) return null;
          const focused = state.index === index;
          const onPress = () => {
            const event = navigation.emit({
              type: 'tabPress',
              target: route.key,
              canPreventDefault: true,
            });
            if (!focused && !event.defaultPrevented) {
              navigation.navigate(route.name, route.params);
            }
          };

          return (
            <Pressable
              key={route.key}
              accessibilityRole="tab"
              accessibilityState={{ selected: focused }}
              accessibilityLabel={
                descriptors[route.key].options.tabBarAccessibilityLabel ?? visual.label
              }
              onPress={onPress}
              style={styles.tab}>
              <View style={[styles.pill, focused && styles.pillActive]}>
                <MaterialCommunityIcons
                  name={focused ? visual.activeIcon : visual.icon}
                  size={20}
                  color={focused ? Palette.amber : Palette.textSecondary}
                />
                <Text style={[styles.label, focused && styles.labelActive]} numberOfLines={1}>
                  {visual.label}
                </Text>
                {focused && <View style={styles.glowDot} />}
              </View>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  frame: {
    position: 'absolute',
    // Slightly wider than the 14dp tile inset so the "Settings" label never clips.
    left: Space.sm,
    right: Space.sm,
    borderRadius: 24,
    boxShadow: '0px 12px 32px rgba(0, 0, 0, 0.55)',
  },
  bar: {
    height: 64,
    borderRadius: 24,
    borderWidth: 1,
    borderColor: Palette.glassBorder,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 6,
    overflow: 'hidden',
    backgroundColor: 'rgba(18, 19, 22, 0.94)',
  },
  frost: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(255, 255, 255, 0.04)',
  },
  sheen: {
    position: 'absolute',
    top: 0,
    left: 16,
    right: 16,
    height: 1,
    backgroundColor: 'rgba(255, 255, 255, 0.10)',
  },
  tab: {
    flex: 1,
    height: '100%',
    alignItems: 'center',
    justifyContent: 'center',
  },
  pill: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    minWidth: 96,
    height: 44,
    paddingHorizontal: 12,
    borderRadius: Radii.pill,
  },
  pillActive: {
    backgroundColor: Palette.amberHighlight,
    borderWidth: 1,
    borderColor: 'rgba(251, 191, 36, 0.35)',
  },
  label: {
    fontFamily: FontNames.interMedium,
    fontSize: 11,
    lineHeight: 15,
    color: Palette.textSecondary,
  },
  labelActive: {
    fontFamily: FontNames.interSemiBold,
    color: Palette.amber,
  },
  glowDot: {
    position: 'absolute',
    bottom: 3,
    width: 4,
    height: 4,
    borderRadius: 2,
    backgroundColor: Palette.amber,
    boxShadow: '0px 0px 6px rgba(251, 191, 36, 0.9)',
  },
});
