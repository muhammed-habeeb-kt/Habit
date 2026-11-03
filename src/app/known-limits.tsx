import { MaterialCommunityIcons } from '@expo/vector-icons';
import { router } from 'expo-router';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { NeuCard } from '@/components/neumorphic';
import {
  FontNames,
  Palette,
  Radii,
  Shadows,
  Space,
  Typography,
} from '@/constants/theme';

type Limit = {
  icon: keyof typeof MaterialCommunityIcons.glyphMap;
  title: string;
  tag: string;
  description: string;
};

const LIMITS: Limit[] = [
  {
    icon: 'cellphone-off',
    title: 'Safe Mode',
    tag: 'OS LEVEL',
    description: 'Restarting the phone in Safe Mode switches Habit off.',
  },
  {
    icon: 'console-line',
    title: 'Developer tools (ADB)',
    tag: 'USB DEBUG',
    description: 'A computer connected with developer tools can remove Habit.',
  },
  {
    icon: 'delete-outline',
    title: 'Factory reset',
    tag: 'HARD WIPE',
    description: 'Resetting the phone erases Habit.',
  },
  {
    icon: 'account-group-outline',
    title: 'Other user profiles',
    tag: 'MULTI-USER',
    description: 'Habit does not protect another profile on the same phone.',
  },
  {
    icon: 'lock-outline',
    title: 'Protected screens',
    tag: 'FLAG_SECURE',
    description: 'Some app screens cannot be scanned by AI.',
  },
  {
    icon: 'lightning-bolt-outline',
    title: 'AI mistakes',
    tag: 'NEURAL EDGE',
    description: 'AI can miss content or block harmless content.',
  },
];

export default function KnownLimitsScreen() {
  return (
    <View style={styles.screen}>
      <SafeAreaView style={styles.safe} edges={['top', 'left', 'right', 'bottom']}>
        <ScrollView
          contentContainerStyle={styles.content}
          showsVerticalScrollIndicator={false}>
          <View style={styles.header}>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Go back"
              onPress={() => router.back()}
              style={({ pressed }) => [styles.backButton, pressed && styles.pressed]}>
              <MaterialCommunityIcons name="chevron-left" size={26} color={Palette.textPrimary} />
            </Pressable>
            <Text style={styles.title}>Known limits</Text>
          </View>

          <NeuCard style={styles.introCard} padded={false}>
            <View style={styles.iconTile}>
              <MaterialCommunityIcons
                name="information-outline"
                size={22}
                color={Palette.amber}
              />
            </View>
            <Text style={styles.introText}>
              No app can block everything. Here is what Habit cannot stop.
            </Text>
          </NeuCard>

          <View style={styles.list}>
            {LIMITS.map((limit) => (
              <NeuCard key={limit.title} style={styles.row} padded={false}>
                <View style={styles.iconTile}>
                  <MaterialCommunityIcons name={limit.icon} size={22} color={Palette.amber} />
                </View>
                <View style={styles.rowText}>
                  <View style={styles.rowTitleLine}>
                    <Text style={styles.rowTitle}>{limit.title}</Text>
                    <View style={styles.tag}>
                      <Text style={styles.tagLabel}>{limit.tag}</Text>
                    </View>
                  </View>
                  <Text style={styles.rowDescription}>{limit.description}</Text>
                </View>
              </NeuCard>
            ))}
          </View>

          <View style={styles.quoteCard}>
            <View style={styles.quoteIcon}>
              <MaterialCommunityIcons name="shield-check-outline" size={22} color={Palette.amber} />
            </View>
            <View style={styles.quoteText}>
              <Text style={styles.quote}>
                &ldquo;Habit makes this much harder, not impossible.&rdquo;
              </Text>
              <Text style={styles.quoteCaption}>PHILOSOPHY &amp; REALISTIC BOUNDARY</Text>
            </View>
          </View>
        </ScrollView>
      </SafeAreaView>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: Palette.canvas },
  safe: { flex: 1 },
  content: {
    paddingHorizontal: Space.margin,
    paddingTop: Space.sm,
    paddingBottom: Space.xl,
    gap: Space.md,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    paddingBottom: Space.xs,
  },
  backButton: {
    width: 44,
    height: 44,
    borderRadius: Radii.pill,
    backgroundColor: Palette.raised,
    boxShadow: Shadows.raisedSmall,
    alignItems: 'center',
    justifyContent: 'center',
  },
  title: {
    ...Typography.headlineLg,
    color: Palette.textPrimary,
  },
  iconTile: {
    width: Radii.tileSize,
    height: Radii.tileSize,
    borderRadius: Radii.tile,
    backgroundColor: Palette.amberHighlight,
    alignItems: 'center',
    justifyContent: 'center',
  },
  introCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    borderRadius: Radii.control,
    paddingHorizontal: 14,
    paddingVertical: 14,
  },
  introText: {
    flex: 1,
    ...Typography.bodyMd,
    fontSize: 14,
    lineHeight: 20,
    color: Palette.textPrimary,
  },
  list: { gap: Space.gutter },
  row: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: Space.gutter,
    borderRadius: Radii.control,
    paddingHorizontal: 14,
    paddingVertical: 14,
  },
  rowText: { flex: 1, gap: 4 },
  rowTitleLine: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.sm,
  },
  rowTitle: {
    flex: 1,
    ...Typography.headlineMd,
    fontSize: 15,
    lineHeight: 20,
    color: Palette.textPrimary,
  },
  tag: {
    borderRadius: Radii.pill,
    backgroundColor: Palette.surfaceHigh,
    paddingHorizontal: 8,
    paddingVertical: 3,
  },
  tagLabel: {
    ...Typography.monoLabelSm,
    fontSize: 9,
    lineHeight: 12,
    letterSpacing: 0.6,
    color: Palette.textSecondary,
  },
  rowDescription: {
    ...Typography.bodyMd,
    fontSize: 13,
    lineHeight: 19,
    color: Palette.textSecondary,
  },
  quoteCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    borderRadius: Radii.card,
    borderWidth: 1,
    borderColor: 'rgba(251, 191, 36, 0.35)',
    backgroundColor: Palette.amberHighlight,
    paddingHorizontal: 14,
    paddingVertical: 16,
    marginTop: Space.xs,
  },
  quoteIcon: {
    width: Radii.tileSize,
    height: Radii.tileSize,
    borderRadius: Radii.tile,
    backgroundColor: Palette.amberHighlight,
    borderWidth: 1,
    borderColor: 'rgba(251, 191, 36, 0.45)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  quoteText: { flex: 1, gap: 4 },
  quote: {
    fontFamily: FontNames.interSemiBold,
    fontSize: 14,
    lineHeight: 20,
    color: Palette.amber,
  },
  quoteCaption: {
    ...Typography.monoLabelSm,
    fontSize: 9,
    lineHeight: 12,
    letterSpacing: 0.7,
    color: Palette.textMuted,
  },
  pressed: { opacity: 0.7 },
});
