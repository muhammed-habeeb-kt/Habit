import { MaterialCommunityIcons } from '@expo/vector-icons';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { FontNames, Palette, Radii } from '@/constants/theme';

type Props = {
  title: string;
  body: string;
  icon?: keyof typeof MaterialCommunityIcons.glyphMap;
  actionLabel?: string;
  onPress?: () => void;
};

/**
 * Floating frosted banner: amber icon, title + description, optional compact
 * raised action. Used for permission and self-protection warnings.
 *
 * The frost is layered translucency instead of a native blur module, so the banner
 * renders on any development build without adding a native dependency.
 */
export function FrostedBanner({
  title,
  body,
  icon = 'alert-outline',
  actionLabel,
  onPress,
}: Props) {
  return (
    <View style={styles.container}>
      <View style={styles.iconWell}>
        <MaterialCommunityIcons name={icon} size={20} color={Palette.amber} />
      </View>
      <View style={styles.text}>
        <Text style={styles.title}>{title}</Text>
        <Text style={styles.body}>{body}</Text>
      </View>
      {actionLabel && onPress && (
        <Pressable
          accessibilityRole="button"
          onPress={onPress}
          style={({ pressed }) => [styles.action, pressed && styles.actionPressed]}>
          <Text style={styles.actionLabel}>{actionLabel}</Text>
        </Pressable>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    borderRadius: Radii.card,
    borderWidth: 1,
    borderColor: Palette.glassBorder,
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
    paddingHorizontal: 16,
    paddingVertical: 14,
  },
  iconWell: {
    width: 36,
    height: 36,
    borderRadius: 18,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: Palette.amberHighlight,
  },
  text: { flex: 1, gap: 2 },
  title: {
    fontFamily: FontNames.interSemiBold,
    fontSize: 14,
    lineHeight: 19,
    color: Palette.textPrimary,
  },
  body: {
    fontFamily: FontNames.interRegular,
    fontSize: 12,
    lineHeight: 16,
    color: Palette.textSecondary,
  },
  action: {
    backgroundColor: Palette.raised,
    borderRadius: Radii.pill,
    paddingHorizontal: 14,
    paddingVertical: 8,
  },
  actionPressed: { backgroundColor: Palette.well },
  actionLabel: {
    fontFamily: FontNames.interSemiBold,
    fontSize: 12,
    lineHeight: 16,
    color: Palette.amber,
  },
});
