import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { BottomTabBarProps } from 'expo-router/build/react-navigation/bottom-tabs';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { FontNames, Palette, Radii, Shadows } from '@/constants/theme';

type TabVisual = {
  label: string;
  icon: keyof typeof MaterialCommunityIcons.glyphMap;
  activeIcon: keyof typeof MaterialCommunityIcons.glyphMap;
};

const VISUALS: Record<string, TabVisual> = {
  index: { label: 'Home', icon: 'home-outline', activeIcon: 'home' },
  // A four-point sparkle keeps the same thin stroke weight as the house and the gear,
  // so the middle tab reads as AI without looking bulkier than its neighbours.
  apps: { label: 'AI', icon: 'star-four-points-outline', activeIcon: 'star-four-points' },
  settings: { label: 'Settings', icon: 'cog-outline', activeIcon: 'cog' },
};

/** 60dp floating dock, 16dp above the bottom edge, with a solid amber active tab. */
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
                  size={16}
                  color={focused ? Palette.canvas : Palette.textSecondary}
                />
                <Text
                  style={[styles.label, focused && styles.labelActive]}
                  maxFontSizeMultiplier={1}
                  allowFontScaling={false}>
                  {visual.label}
                </Text>
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
    left: 12,
    right: 12,
    borderRadius: Radii.pill,
    boxShadow: '0px 12px 32px rgba(0, 0, 0, 0.55)',
  },
  bar: {
    height: 60,
    borderRadius: Radii.pill,
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
  /**
   * Every tab owns an equal third of the dock and stretches its pill to that width, so a
   * label always has the whole segment to sit in: neither the inactive nor the active tab
   * can be squeezed into an ellipsis by a neighbour's padding.
   */
  tab: {
    flex: 1,
    minWidth: 0,
    height: '100%',
    alignItems: 'stretch',
    justifyContent: 'center',
  },
  pill: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    height: 40,
    paddingHorizontal: 6,
    borderRadius: Radii.pill,
  },
  pillActive: {
    backgroundColor: Palette.amber,
    boxShadow: Shadows.amberGlow,
  },
  label: {
    fontFamily: FontNames.interMedium,
    fontSize: 11,
    lineHeight: 15,
    letterSpacing: -0.1,
    color: Palette.textSecondary,
  },
  labelActive: {
    fontFamily: FontNames.interBold,
    color: Palette.canvas,
  },
});

