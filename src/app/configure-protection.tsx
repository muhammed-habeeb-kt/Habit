import { MaterialCommunityIcons } from '@expo/vector-icons';
import { router, useLocalSearchParams } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { Animated, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import {
  DurationWheels,
  formatClockMinutes,
  formatLongMinutes,
} from '@/components/duration-wheels';
import {
  NeuCard,
  PrimaryGlowButton,
  SecondaryRaisedButton,
} from '@/components/neumorphic';
import { FontNames, Palette, Radii, Shadows, Space, Typography } from '@/constants/theme';
import { useProtection } from '@/hooks/use-protection';
import { useSheetDismiss } from '@/hooks/use-sheet-dismiss';
import { configureProtection } from '@/native/habit';

const LENGTHS = [100, 200, 300] as const;
type LockLength = (typeof LENGTHS)[number];

/** Minimum and maximum for the wait-it-out timer, in minutes. */
const MIN_MINUTES = 180;
const MAX_MINUTES = 1440;

/** Auto-restart OFF window: 00:01 .. 23:59, every minute selectable. */
const MIN_OFF_MINUTES = 1;
const MAX_OFF_MINUTES = 1439;

const PRESETS = [
  { minutes: 180, label: '3h' },
  { minutes: 300, label: '5h' },
  { minutes: 600, label: '10h' },
  { minutes: 720, label: '12h' },
  { minutes: 1440, label: '24h' },
];

const OFF_PRESETS = [
  { minutes: 1, label: '1m' },
  { minutes: 5, label: '5m' },
  { minutes: 10, label: '10m' },
  { minutes: 30, label: '30m' },
  { minutes: 60, label: '1h' },
  { minutes: 360, label: '6h' },
  { minutes: 720, label: '12h' },
  { minutes: 1439, label: '23h 59m' },
];

function clampMinutes(total: number): number {
  return Math.min(MAX_MINUTES, Math.max(MIN_MINUTES, total));
}

function clampOffMinutes(total: number): number {
  return Math.min(MAX_OFF_MINUTES, Math.max(MIN_OFF_MINUTES, total));
}

function normalizeLength(value: number | undefined): LockLength {
  return value === 100 || value === 300 ? value : 200;
}

/**
 * Shown before Strict or Aggressive turns on. Both limits are required and saved
 * natively, so the unlock screens can't be talked into a weaker challenge later.
 */
export default function ConfigureProtectionScreen() {
  const { mode } = useLocalSearchParams<{ mode?: string }>();
  const targetMode = mode === 'aggressive' ? 'aggressive' : 'strict';
  const targetLabel = targetMode === 'aggressive' ? 'Aggressive' : 'Strict';

  const { snapshot, changeMode } = useProtection();
  const [length, setLength] = useState<LockLength>(200);
  const [total, setTotal] = useState(MIN_MINUTES);
  const [offTotal, setOffTotal] = useState(10);
  const [saving, setSaving] = useState(false);
  const [hint, setHint] = useState<string | null>(null);
  const seeded = useRef(false);
  /** True once the user picks a value, so a late snapshot can never reset their choice. */
  const touched = useRef(false);

  const { panHandlers, animatedStyle, dismiss } = useSheetDismiss(() => {
    if (router.canGoBack()) router.back();
    else router.dismissTo('/');
  });

  // Prefill from the last saved setup; first-time users start at 200 / 03:00.
  useEffect(() => {
    if (!snapshot || seeded.current) return;
    seeded.current = true;
    // Never overwrite a choice the user already made while the snapshot was loading.
    if (touched.current) return;
    setLength(normalizeLength(snapshot.configuredChallengeLength));
    setTotal(clampMinutes(snapshot.configuredCooldownMinutes || MIN_MINUTES));
    const savedOffWindow = Number(snapshot.configuredOffWindowMinutes);
    setOffTotal(
      clampOffMinutes(Number.isFinite(savedOffWindow) && savedOffWindow > 0 ? savedOffWindow : 10)
    );
  }, [snapshot]);

  const chooseLength = (option: LockLength) => {
    touched.current = true;
    setLength(option);
  };

  const chooseTotal = (minutes: number) => {
    touched.current = true;
    setTotal(minutes);
  };

  const chooseOffTotal = (minutes: number) => {
    touched.current = true;
    setOffTotal(clampOffMinutes(minutes));
  };

  const selectTotal = (minutes: number) => {
    touched.current = true;
    setTotal(minutes);
  };

  const selectOffTotal = (minutes: number) => {
    touched.current = true;
    setOffTotal(minutes);
  };

  const confirm = async () => {
    if (saving) return;
    setSaving(true);
    setHint(null);
    try {
      await configureProtection(length, total, offTotal);
      if (targetMode === 'aggressive') {
        // Device admin is already granted: no second explanation or Android dialog, just
        // raise the mode. Only a missing grant opens the Aggressive sheet.
        if (snapshot?.deviceAdminActive) {
          await changeMode('aggressive');
          if (router.canGoBack()) router.back();
          else router.dismissTo('/');
          return;
        }
        // The Aggressive sheet explains device admin and then activates the mode.
        router.replace('/aggressive');
        return;
      }
      await changeMode('strict');
      if (router.canGoBack()) router.back();
      else router.dismissTo('/');
    } catch {
      setHint('Could not save this setup. Try again.');
      setSaving(false);
    }
  };

  return (
    <View style={styles.backdrop}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Close protection setup"
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
            showsVerticalScrollIndicator={false}
            nestedScrollEnabled>
            <View style={styles.badge}>
              <View style={styles.badgeDot} />
              <Text style={styles.badgeLabel}>BEFORE YOU LOCK IN</Text>
            </View>

            <Text style={styles.title}>Configure Protection Lock</Text>
            <Text style={styles.subtitle}>
              {`These limits apply the moment ${targetLabel} mode turns on. They can't be weakened while protection is active.`}
            </Text>

            <NeuCard style={styles.card}>
              <View style={styles.cardHeader}>
                <View style={styles.iconTile}>
                  <MaterialCommunityIcons
                    name="keyboard-outline"
                    size={20}
                    color={Palette.amber}
                  />
                </View>
                <View style={styles.grow}>
                  <Text style={styles.cardTitle}>Character challenge length</Text>
                  <Text style={styles.cardSubtitle}>
                    How many characters you type to unlock.
                  </Text>
                </View>
              </View>

              <View style={styles.lengths}>
                {LENGTHS.map((option) => {
                  const active = option === length;
                  return (
                    <Pressable
                      key={option}
                      accessibilityRole="button"
                      accessibilityState={{ selected: active }}
                      onPress={() => chooseLength(option)}
                      style={[styles.lengthCard, active && styles.lengthCardActive]}>
                      <Text
                        style={[styles.lengthValue, active && styles.lengthValueActive]}>
                        {option}
                      </Text>
                      <Text
                        style={[
                          styles.lengthCaption,
                          active && styles.lengthCaptionActive,
                        ]}>
                        CHARACTERS
                      </Text>
                    </Pressable>
                  );
                })}
              </View>
            </NeuCard>

            <NeuCard style={styles.card}>
              <View style={styles.cardHeader}>
                <View style={styles.iconTile}>
                  <MaterialCommunityIcons
                    name="clock-outline"
                    size={20}
                    color={Palette.amber}
                  />
                </View>
                <View style={styles.grow}>
                  <Text style={styles.cardTitle}>Wait-it-out timer</Text>
                  <Text style={styles.cardSubtitle}>
                    Minimum 3 hours, maximum 24 hours.
                  </Text>
                </View>
                <Text style={styles.totalValue}>{formatClockMinutes(total)}</Text>
              </View>

              <View style={styles.presets}>
                {PRESETS.map((preset) => {
                  const active = preset.minutes === total;
                  return (
                    <Pressable
                      key={preset.minutes}
                      accessibilityRole="button"
                      accessibilityState={{ selected: active }}
                      onPress={() => chooseTotal(preset.minutes)}
                      style={[styles.preset, active && styles.presetActive]}>
                      <Text
                        style={[styles.presetLabel, active && styles.presetLabelActive]}>
                        {preset.label}
                      </Text>
                    </Pressable>
                  );
                })}
              </View>

              <DurationWheels
                total={total}
                onChange={selectTotal}
                minMinutes={MIN_MINUTES}
                maxMinutes={MAX_MINUTES}
              />

              <Text style={styles.timerNote}>
                {`Protection stays fully on for ${formatLongMinutes(total)}. It then switches off for ${formatLongMinutes(offTotal)} and turns back on by itself.`}
              </Text>
            </NeuCard>

            <NeuCard style={styles.card}>
              <View style={styles.cardHeader}>
                <View style={styles.iconTile}>
                  <MaterialCommunityIcons
                    name="restart"
                    size={20}
                    color={Palette.amber}
                  />
                </View>
                <View style={styles.grow}>
                  <Text style={styles.cardTitle}>Auto-restart timer</Text>
                  <Text style={styles.cardSubtitle}>
                    How long protection stays OFF before turning back on (1m to 23h 59m).
                  </Text>
                </View>
                <Text style={styles.totalValue}>{formatClockMinutes(offTotal)}</Text>
              </View>

              <View style={styles.presets}>
                {OFF_PRESETS.map((preset) => {
                  const active = preset.minutes === offTotal;
                  return (
                    <Pressable
                      key={preset.minutes}
                      accessibilityRole="button"
                      accessibilityState={{ selected: active }}
                      onPress={() => chooseOffTotal(preset.minutes)}
                      style={[styles.preset, active && styles.presetActive]}>
                      <Text
                        style={[styles.presetLabel, active && styles.presetLabelActive]}>
                        {preset.label}
                      </Text>
                    </Pressable>
                  );
                })}
              </View>

              <DurationWheels
                total={offTotal}
                onChange={selectOffTotal}
                minMinutes={MIN_OFF_MINUTES}
                maxMinutes={MAX_OFF_MINUTES}
              />

              <Text style={styles.timerNote}>
                {`Protection stays fully on for ${formatLongMinutes(total)}. It then switches off for ${formatLongMinutes(offTotal)} and turns back on by itself.`}
              </Text>
            </NeuCard>

            <PrimaryGlowButton
              label={saving ? 'Saving…' : `Turn on ${targetLabel}`}
              onPress={confirm}
              disabled={saving}
              icon={
                <MaterialCommunityIcons
                  name="shield-lock-outline"
                  size={20}
                  color={Palette.canvas}
                />
              }
              style={styles.action}
            />

            <SecondaryRaisedButton
              label="Cancel"
              onPress={dismiss}
              disabled={saving}
              style={styles.cancel}
            />

            {hint ? <Text style={styles.hint}>{hint}</Text> : null}

            <Text style={styles.footer}>CONFIGURED ON DEVICE • CANNOT BE LOWERED WHILE LOCKED</Text>
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
  dismissArea: { flex: 1 },
  sheet: {
    width: '100%',
    maxHeight: '94%',
    backgroundColor: Palette.surface,
    borderTopLeftRadius: 32,
    borderTopRightRadius: 32,
  },
  sheetSafe: { flexShrink: 1 },
  sheetHeader: {
    paddingTop: 12,
    paddingBottom: 8,
    alignItems: 'center',
  },
  handle: {
    width: 40,
    height: 4,
    borderRadius: Radii.pill,
    backgroundColor: Palette.surfaceHigh,
  },
  content: {
    paddingHorizontal: Space.margin,
    paddingBottom: Space.lg,
    gap: Space.md,
  },
  badge: {
    alignSelf: 'center',
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    height: 24,
    paddingHorizontal: 12,
    borderRadius: Radii.pill,
    backgroundColor: Palette.amberHighlight,
    borderWidth: 1,
    borderColor: 'rgba(251, 191, 36, 0.35)',
  },
  badgeDot: {
    width: 5,
    height: 5,
    borderRadius: Radii.pill,
    backgroundColor: Palette.amber,
  },
  badgeLabel: {
    ...Typography.monoLabelSm,
    color: Palette.amber,
  },
  title: {
    fontFamily: FontNames.interBold,
    fontSize: 23,
    lineHeight: 29,
    color: Palette.textPrimary,
    textAlign: 'center',
  },
  subtitle: {
    ...Typography.bodyMd,
    fontSize: 14,
    lineHeight: 20,
    color: Palette.textSecondary,
    textAlign: 'center',
    marginTop: -8,
  },
  card: { gap: Space.md, paddingVertical: 16 },
  cardHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
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
  grow: { flex: 1, gap: 2 },
  cardTitle: {
    fontFamily: FontNames.interSemiBold,
    fontSize: 16,
    lineHeight: 21,
    color: Palette.textPrimary,
  },
  cardSubtitle: {
    ...Typography.bodyMd,
    color: Palette.textSecondary,
  },
  totalValue: {
    fontFamily: FontNames.monoBold,
    fontSize: 18,
    lineHeight: 24,
    color: Palette.amber,
    fontVariant: ['tabular-nums'],
  },
  lengths: { flexDirection: 'row', gap: 8 },
  lengthCard: {
    flex: 1,
    borderRadius: 16,
    borderWidth: 1.5,
    borderColor: 'transparent',
    backgroundColor: Palette.well,
    boxShadow: Shadows.sunkenSmall,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 1,
    paddingVertical: 12,
  },
  lengthCardActive: {
    backgroundColor: Palette.raised,
    borderColor: Palette.amber,
    boxShadow: Shadows.raisedSmall,
  },
  lengthValue: {
    fontFamily: FontNames.monoBold,
    fontSize: 20,
    lineHeight: 26,
    color: Palette.textPrimary,
  },
  lengthValueActive: { color: Palette.amber },
  lengthCaption: {
    fontFamily: FontNames.monoRegular,
    fontSize: 8,
    lineHeight: 11,
    letterSpacing: 0.8,
    color: Palette.textMuted,
  },
  lengthCaptionActive: { color: Palette.amber },
  presets: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  preset: {
    flex: 1,
    minWidth: 52,
    height: 36,
    borderRadius: Radii.pill,
    borderWidth: 1,
    borderColor: Palette.surfaceHigh,
    backgroundColor: Palette.well,
    alignItems: 'center',
    justifyContent: 'center',
  },
  presetActive: {
    borderColor: Palette.amber,
    backgroundColor: Palette.amberHighlight,
  },
  presetLabel: {
    fontFamily: FontNames.monoBold,
    fontSize: 12,
    lineHeight: 16,
    color: Palette.textSecondary,
  },
  presetLabelActive: { color: Palette.amber },
  timerNote: {
    ...Typography.bodySm,
    color: Palette.textMuted,
  },
  action: { width: '100%', marginTop: Space.xs },
  cancel: { width: '100%' },
  hint: {
    ...Typography.bodySm,
    color: Palette.danger,
    textAlign: 'center',
  },
  footer: {
    ...Typography.monoLabelSm,
    fontSize: 9,
    lineHeight: 12,
    letterSpacing: 0.8,
    color: Palette.textMuted,
    textAlign: 'center',
  },
});
