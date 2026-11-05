import { MaterialCommunityIcons } from '@expo/vector-icons';
import { router, useLocalSearchParams } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import {
  Animated,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

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

const PRESETS = [
  { minutes: 180, label: '3h' },
  { minutes: 300, label: '5h' },
  { minutes: 600, label: '10h' },
  { minutes: 720, label: '12h' },
  { minutes: 1440, label: '24h' },
];

const ROW_HEIGHT = 36;
const VISIBLE_ROWS = 5;
const WHEEL_PADDING = (ROW_HEIGHT * (VISIBLE_ROWS - 1)) / 2;

const MIN_HOURS = 3;
const HOURS = Array.from({ length: 24 - MIN_HOURS + 1 }, (_, index) => index + MIN_HOURS);
const MINUTES = Array.from({ length: 12 }, (_, index) => index * 5);

function pad(value: number): string {
  return value.toString().padStart(2, '0');
}

function clampMinutes(total: number): number {
  return Math.min(MAX_MINUTES, Math.max(MIN_MINUTES, total));
}

function formatClock(total: number): string {
  return `${pad(Math.floor(total / 60))}:${pad(total % 60)}`;
}

function formatLong(total: number): string {
  const hours = Math.floor(total / 60);
  const minutes = total % 60;
  const hourLabel = `${hours} ${hours === 1 ? 'hour' : 'hours'}`;
  if (minutes === 0) return hourLabel;
  return `${hourLabel} ${minutes} min`;
}

function normalizeLength(value: number | undefined): LockLength {
  return value === 100 || value === 300 ? value : 200;
}

type WheelProps = {
  values: readonly number[];
  selectedIndex: number;
  onSelect: (index: number) => void;
  scrollRef: React.RefObject<ScrollView | null>;
};

/** One snapping vertical wheel column. Scrolling it reports the centred row. */
function Wheel({ values, selectedIndex, onSelect, scrollRef }: WheelProps) {
  const settleTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    scrollRef.current?.scrollTo({ y: selectedIndex * ROW_HEIGHT, animated: false });
  }, [scrollRef, selectedIndex]);

  useEffect(
    () => () => {
      if (settleTimer.current) clearTimeout(settleTimer.current);
    },
    []
  );

  // Debounced so a flick isn't read halfway through its momentum.
  const handleScroll = (offsetY: number) => {
    if (settleTimer.current) clearTimeout(settleTimer.current);
    settleTimer.current = setTimeout(() => {
      const index = Math.min(
        values.length - 1,
        Math.max(0, Math.round(offsetY / ROW_HEIGHT))
      );
      if (index !== selectedIndex) onSelect(index);
    }, 140);
  };

  return (
    <View style={styles.wheel}>
      <View style={styles.wheelHighlight} pointerEvents="none" />
      <ScrollView
        ref={scrollRef}
        style={styles.wheelScroll}
        contentContainerStyle={styles.wheelContent}
        showsVerticalScrollIndicator={false}
        snapToInterval={ROW_HEIGHT}
        decelerationRate="fast"
        nestedScrollEnabled
        scrollEventThrottle={16}
        onScroll={(event) => handleScroll(event.nativeEvent.contentOffset.y)}
        onMomentumScrollEnd={(event) => handleScroll(event.nativeEvent.contentOffset.y)}>
        {values.map((value, index) => (
          <View key={value} style={styles.wheelRow}>
            <Text
              style={[styles.wheelValue, index === selectedIndex && styles.wheelValueActive]}>
              {pad(value)}
            </Text>
          </View>
        ))}
      </ScrollView>
    </View>
  );
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
  const [saving, setSaving] = useState(false);
  const [hint, setHint] = useState<string | null>(null);
  const seeded = useRef(false);
  /** True once the user picks a value, so a late snapshot can never reset their choice. */
  const touched = useRef(false);
  const hoursRef = useRef<ScrollView | null>(null);
  const minutesRef = useRef<ScrollView | null>(null);

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
    const saved = clampMinutes(snapshot.configuredCooldownMinutes || MIN_MINUTES);
    // The wheels move in 5-minute steps, so land on the nearest one.
    setTotal(clampMinutes(Math.round(saved / 5) * 5));
  }, [snapshot]);

  const hours = Math.floor(total / 60);
  const minutes = total % 60;

  const selectHours = (index: number) => {
    touched.current = true;
    setTotal((previous) => clampMinutes((index + MIN_HOURS) * 60 + (previous % 60)));
  };

  const selectMinutes = (index: number) => {
    touched.current = true;
    const next = clampMinutes(Math.floor(total / 60) * 60 + index * 5);
    setTotal(next);
    if (next === total) {
      // Already at 24:00: the wheel can't move past the limit, so snap it back.
      minutesRef.current?.scrollTo({ y: (total % 60) / 5 * ROW_HEIGHT, animated: true });
    }
  };

  const chooseLength = (option: LockLength) => {
    touched.current = true;
    setLength(option);
  };

  const chooseTotal = (minutes: number) => {
    touched.current = true;
    setTotal(minutes);
  };

  const confirm = async () => {
    if (saving) return;
    setSaving(true);
    setHint(null);
    try {
      await configureProtection(length, total);
      if (targetMode === 'aggressive') {
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
              {`These two limits apply the moment ${targetLabel} mode turns on. They can't be lowered while protection is active.`}
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
                <Text style={styles.totalValue}>{formatClock(total)}</Text>
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

              <View style={styles.wheels}>
                <View style={styles.wheelColumn}>
                  <Text style={styles.wheelLabel}>HOURS</Text>
                  <Wheel
                    values={HOURS}
                    selectedIndex={hours - MIN_HOURS}
                    onSelect={selectHours}
                    scrollRef={hoursRef}
                  />
                </View>
                <Text style={styles.wheelColon}>:</Text>
                <View style={styles.wheelColumn}>
                  <Text style={styles.wheelLabel}>MINUTES</Text>
                  <Wheel
                    values={MINUTES}
                    selectedIndex={minutes / 5}
                    onSelect={selectMinutes}
                    scrollRef={minutesRef}
                  />
                </View>
              </View>

              <Text style={styles.timerNote}>
                {`Protection stays fully on for ${formatLong(total)}. It then switches off for 10 minutes and turns back on by itself.`}
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
  wheels: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    justifyContent: 'center',
    gap: 8,
  },
  wheelColumn: { flex: 1, alignItems: 'center', gap: 4 },
  wheelLabel: {
    ...Typography.monoLabelSm,
    fontSize: 9,
    lineHeight: 12,
    color: Palette.textMuted,
  },
  wheel: {
    width: '100%',
    height: ROW_HEIGHT * VISIBLE_ROWS,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: Palette.surfaceHigh,
    backgroundColor: Palette.well,
    overflow: 'hidden',
  },
  wheelHighlight: {
    position: 'absolute',
    top: WHEEL_PADDING,
    left: 6,
    right: 6,
    height: ROW_HEIGHT,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: 'rgba(251, 191, 36, 0.45)',
    backgroundColor: Palette.amberHighlight,
  },
  wheelScroll: { flex: 1 },
  wheelContent: { paddingVertical: WHEEL_PADDING },
  wheelRow: {
    height: ROW_HEIGHT,
    alignItems: 'center',
    justifyContent: 'center',
  },
  wheelValue: {
    fontFamily: FontNames.monoBold,
    fontSize: 17,
    lineHeight: 22,
    color: Palette.textMuted,
    fontVariant: ['tabular-nums'],
  },
  wheelValueActive: { color: Palette.amber },
  wheelColon: {
    fontFamily: FontNames.monoBold,
    fontSize: 20,
    lineHeight: 26,
    color: Palette.textSecondary,
    marginTop: WHEEL_PADDING + 16,
  },
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
