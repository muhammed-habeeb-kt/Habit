import { MaterialCommunityIcons } from '@expo/vector-icons';
import { router } from 'expo-router';
import { useCallback } from 'react';
import { Animated, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { formatLongMinutes } from '@/components/duration-wheels';
import { NeuCard, SecondaryRaisedButton } from '@/components/neumorphic';
import { FontNames, Palette, Radii, Shadows, Space, Typography } from '@/constants/theme';
import { useProtection } from '@/hooks/use-protection';
import { useSheetDismiss } from '@/hooks/use-sheet-dismiss';

type IconName = React.ComponentProps<typeof MaterialCommunityIcons>['name'];

type TagTone = 'amber' | 'neutral' | 'success';

type UnlockMethod = {
  key: string;
  title: string;
  tag: string;
  tagTone: TagTone;
  subtitle: string;
  icon: IconName;
  href: '/unlock-code' | '/unlock-timer' | '/unlock-face';
};

/** "3 HOURS" for the tag and "3 hours" for the sentence, from the configured timer. */
function cooldownLabels(minutes: number): { tag: string; text: string } {
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  if (mins === 0) {
    const unit = hours === 1 ? 'hour' : 'hours';
    return { tag: `${hours} ${unit.toUpperCase()}`, text: `${hours} ${unit}` };
  }
  return { tag: `${hours}H ${mins}M`, text: `${hours}h ${mins}m` };
}

function buildMethods(
  cooldownMinutes: number,
  challengeLength: number,
  offWindowMinutes: number
): UnlockMethod[] {
  const cooldown = cooldownLabels(cooldownMinutes);
  const offText = formatLongMinutes(offWindowMinutes);
  return [
    {
      key: 'code',
      title: 'Type the code',
      tag: 'DELIBERATE',
      tagTone: 'amber',
      subtitle: `Type a long scrambled text: ${challengeLength} characters.`,
      icon: 'keyboard-outline',
      href: '/unlock-code',
    },
    {
      key: 'timer',
      title: 'Wait it out',
      tag: cooldown.tag,
      tagTone: 'neutral',
      subtitle: `Protection stays on for ${cooldown.text}, then switches off for ${offText}.`,
      icon: 'clock-outline',
      href: '/unlock-timer',
    },
    {
      key: 'face',
      title: 'Two-person face check',
      tag: 'INSTANT',
      tagTone: 'success',
      subtitle: `Two different people verify their faces. Protection is off for ${offText}.`,
      icon: 'account-multiple-outline',
      href: '/unlock-face',
    },
  ];
}

const TAG_COLORS: Record<TagTone, { text: string; background: string; border: string }> = {
  amber: {
    text: Palette.amber,
    background: Palette.amberHighlight,
    border: 'rgba(251, 191, 36, 0.32)',
  },
  neutral: {
    text: Palette.textSecondary,
    background: 'rgba(255, 255, 255, 0.06)',
    border: 'rgba(255, 255, 255, 0.10)',
  },
  success: {
    text: Palette.success,
    background: 'rgba(52, 211, 153, 0.12)',
    border: 'rgba(52, 211, 153, 0.32)',
  },
};

function modeLabel(mode: 'normal' | 'strict' | 'aggressive' | undefined): string {
  if (mode === 'aggressive') return 'AGGRESSIVE MODE';
  if (mode === 'normal') return 'NORMAL MODE';
  return 'STRICT MODE';
}

function MethodCard({ method }: { method: UnlockMethod }) {
  const tag = TAG_COLORS[method.tagTone];

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${method.title}. ${method.subtitle}`}
      onPress={() => router.push(method.href)}
      style={({ pressed }) => [pressed && styles.pressed]}>
      <NeuCard style={styles.methodCard}>
        <View style={styles.iconTile}>
          <MaterialCommunityIcons name={method.icon} size={22} color={Palette.amber} />
        </View>

        <View style={styles.methodText}>
          <View style={styles.titleRow}>
            <Text style={styles.methodTitle}>{method.title}</Text>
            <View
              style={[
                styles.tag,
                { backgroundColor: tag.background, borderColor: tag.border },
              ]}>
              <Text style={[styles.tagLabel, { color: tag.text }]}>{method.tag}</Text>
            </View>
          </View>
          <Text style={styles.methodSubtitle}>{method.subtitle}</Text>
        </View>

        <View style={styles.chevronButton}>
          <MaterialCommunityIcons
            name="chevron-right"
            size={20}
            color={Palette.textSecondary}
          />
        </View>
      </NeuCard>
    </Pressable>
  );
}

export default function UnlockScreen() {
  const { snapshot } = useProtection();
  const closeSheet = useCallback(() => router.back(), []);
  const { panHandlers, animatedStyle, dismiss } = useSheetDismiss(closeSheet);

  const methods = buildMethods(
    snapshot?.configuredCooldownMinutes ?? 180,
    snapshot?.configuredChallengeLength ?? 200,
    snapshot?.configuredOffWindowMinutes ?? 10
  );

  return (
    <View style={styles.backdrop}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Close unlock sheet"
        onPress={dismiss}
        style={styles.dismissArea}
      />

      <Animated.View style={[styles.sheet, animatedStyle]}>
        <SafeAreaView edges={['bottom']} style={styles.sheetSafe}>
          <View style={styles.sheetHeader} {...panHandlers}>
            <View style={styles.handle} />
          </View>

          <ScrollView
            contentContainerStyle={styles.content}
            showsVerticalScrollIndicator={false}>
            <View style={styles.badge}>
              <View style={styles.badgeDot} />
              <Text style={styles.badgeLabel}>{modeLabel(snapshot?.mode)}</Text>
            </View>

            <Text style={styles.title}>Unlock Protection</Text>
            <Text style={styles.subtitle}>Any one method is enough</Text>

            <View style={styles.methods}>
              {methods.map((method) => (
                <MethodCard key={method.key} method={method} />
              ))}
            </View>

            <SecondaryRaisedButton
              label="Stay Protected"
              onPress={closeSheet}
              icon={
                <MaterialCommunityIcons
                  name="shield-check-outline"
                  size={20}
                  color={Palette.amber}
                />
              }
              style={styles.stayButton}
            />

            <Text style={styles.footer}>PROTECTION KEEPS RUNNING WHILE YOU DECIDE</Text>
          </ScrollView>
        </SafeAreaView>
      </Animated.View>
    </View>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: Palette.scrim,
    justifyContent: 'flex-end',
  },
  /** Fills the area above the sheet so a backdrop tap always reaches it. */
  dismissArea: { flex: 1 },
  sheet: {
    width: '100%',
    backgroundColor: Palette.surface,
    borderTopLeftRadius: 32,
    borderTopRightRadius: 32,
    maxHeight: '94%',
  },
  sheetSafe: { flexShrink: 1 },
  sheetHeader: {
    paddingTop: 12,
    paddingBottom: Space.md,
    alignItems: 'center',
  },
  content: {
    paddingHorizontal: Space.margin,
    paddingBottom: Space.sm,
    gap: Space.sm,
  },
  handle: {
    width: 40,
    height: 4,
    borderRadius: Radii.pill,
    backgroundColor: Palette.surfaceHigh,
  },
  badge: {
    alignSelf: 'center',
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    height: 24,
    paddingHorizontal: 12,
    borderRadius: Radii.pill,
    backgroundColor: 'rgba(251, 191, 36, 0.10)',
    borderWidth: 1,
    borderColor: 'rgba(251, 191, 36, 0.35)',
  },
  badgeDot: {
    width: 5,
    height: 5,
    borderRadius: Radii.pill,
    backgroundColor: Palette.amber,
    boxShadow: '0px 0px 6px rgba(251, 191, 36, 0.9)',
  },
  badgeLabel: {
    ...Typography.monoLabelSm,
    color: Palette.amber,
  },
  title: {
    fontFamily: FontNames.interBold,
    fontSize: 24,
    lineHeight: 30,
    color: Palette.textPrimary,
    textAlign: 'center',
    marginTop: 4,
  },
  subtitle: {
    ...Typography.bodyLg,
    fontSize: 14,
    lineHeight: 20,
    color: Palette.textSecondary,
    textAlign: 'center',
    marginTop: -6,
    marginBottom: Space.sm,
  },
  methods: { gap: Space.gutter },
  methodCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    paddingVertical: 14,
    paddingHorizontal: 14,
  },
  iconTile: {
    width: Radii.tileSize,
    height: Radii.tileSize,
    borderRadius: Radii.tile,
    backgroundColor: Palette.well,
    boxShadow: Shadows.sunkenSmall,
    alignItems: 'center',
    justifyContent: 'center',
  },
  methodText: { flex: 1, gap: 3 },
  titleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    flexWrap: 'wrap',
    gap: 6,
  },
  methodTitle: {
    fontFamily: FontNames.interSemiBold,
    fontSize: 15,
    lineHeight: 20,
    color: Palette.textPrimary,
  },
  tag: {
    height: 18,
    paddingHorizontal: 7,
    borderRadius: Radii.pill,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  tagLabel: {
    ...Typography.monoLabelSm,
    fontSize: 9,
    lineHeight: 12,
    letterSpacing: 0.8,
  },
  methodSubtitle: {
    ...Typography.bodyMd,
    fontSize: 12.5,
    lineHeight: 17,
    color: Palette.textSecondary,
  },
  chevronButton: {
    width: 36,
    height: 36,
    borderRadius: Radii.pill,
    backgroundColor: Palette.raised,
    boxShadow: Shadows.raisedSmall,
    alignItems: 'center',
    justifyContent: 'center',
  },
  stayButton: { marginTop: Space.sm },
  footer: {
    ...Typography.monoLabelSm,
    fontSize: 10,
    color: Palette.textMuted,
    textAlign: 'center',
    letterSpacing: 1.4,
    marginTop: Space.sm,
  },
  pressed: { opacity: 0.85 },
});
