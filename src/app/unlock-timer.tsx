import { MaterialCommunityIcons } from '@expo/vector-icons';
import { router } from 'expo-router';
import { ActivityIndicator, Alert, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import Svg, { Circle } from 'react-native-svg';

import { useCountdown } from '@/components/countdown';
import {
  NeuCard,
  NeuSwitch,
  PrimaryGlowButton,
  SecondaryRaisedButton,
} from '@/components/neumorphic';
import { FontNames, Palette, Radii, Shadows, Space, Typography } from '@/constants/theme';
import { useProtection } from '@/hooks/use-protection';
import {
  cancelUnlockTimer,
  debugExitStrict,
  requestUnlockTimer,
  setFastCooldown,
  setProtectionEnabled,
} from '@/native/habit';

const DIAL_SIZE = 240;
const DIAL_CENTER = DIAL_SIZE / 2;
const ARC_RADIUS = 103;
const ARC_CIRCUMFERENCE = 2 * Math.PI * ARC_RADIUS;

const FAST_COOLDOWN_MS = 60_000;
const OFF_WINDOW_MS = 10 * 60 * 1000;

/** "5 hours 30 min" for body copy. */
function formatLongDuration(minutes: number): string {
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  const hourLabel = `${hours} ${hours === 1 ? 'hour' : 'hours'}`;
  return mins === 0 ? hourLabel : `${hourLabel} ${mins} min`;
}

/** "5h 30m" for tight labels such as the pipeline steps. */
function formatShortDuration(minutes: number): string {
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  return mins === 0 ? `${hours}h` : `${hours}h ${mins}m`;
}

/** "24-hour" / "5h 30m" for the start button: "Start 24-hour wait". */
function formatWaitAdjective(minutes: number): string {
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  return mins === 0 ? `${hours}-hour` : `${hours}h ${mins}m`;
}

type StepStatus = 'done' | 'active' | 'todo';

type PipelineStep = {
  index: number;
  caption: string;
  status: StepStatus;
};

function pad(value: number): string {
  return value.toString().padStart(2, '0');
}

function formatClock(ms: number, withHours: boolean): string {
  const totalSeconds = Math.max(0, Math.ceil(ms / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (withHours) return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`;
  return `${pad(minutes)}:${pad(seconds)}`;
}

export default function UnlockTimerScreen() {
  const { snapshot, refresh } = useProtection();

  const unlock = snapshot?.unlock;
  const cooldownRemaining = useCountdown(unlock?.cooldownRemainingMs ?? 0);
  const offWindowRemaining = useCountdown(unlock?.offWindowRemainingMs ?? 0);

  if (!snapshot || !unlock) {
    return (
      <View style={styles.loading}>
        <ActivityIndicator color={Palette.amber} />
      </View>
    );
  }

  const offWindow = unlock.offWindowActive;
  const waiting = !offWindow && unlock.cooldownActive;

  const fastCooldown = unlock.fastCooldown && snapshot.debugBuild;
  // The wait is whatever the user configured before turning protection on (3h..24h).
  const configuredMinutes = snapshot.configuredCooldownMinutes ?? 180;
  const cooldownText = formatLongDuration(configuredMinutes);
  const cooldownShort = formatShortDuration(configuredMinutes);
  // A running cooldown keeps its own duration; otherwise the dial starts at the configured
  // wait. The debug switch always wins so the dial matches its "1 minute" labels.
  const cooldownTotalMs = fastCooldown
    ? FAST_COOLDOWN_MS
    : unlock.cooldownTotalMs > 0
      ? unlock.cooldownTotalMs
      : configuredMinutes * 60_000;
  const cooldownLabel = fastCooldown ? '1 minute' : cooldownText;
  const waitLabel = fastCooldown
    ? '1-minute wait'
    : `${formatWaitAdjective(configuredMinutes)} wait`;

  const displayRemaining = offWindow
    ? offWindowRemaining
    : waiting
      ? cooldownRemaining
      : cooldownTotalMs;
  const totalMs = offWindow
    ? unlock.offWindowTotalMs > 0
      ? unlock.offWindowTotalMs
      : OFF_WINDOW_MS
    : cooldownTotalMs;
  const fraction =
    totalMs > 0 ? Math.min(1, Math.max(0, displayRemaining / totalMs)) : 0;
  const dashOffset = ARC_CIRCUMFERENCE * (1 - fraction);

  const timeLabel = formatClock(displayRemaining, !offWindow);
  const dialIcon = offWindow ? 'lock-open-variant' : 'clock-outline';
  const dialLabel = offWindow ? 'BACK ON IN' : 'REMAINING WAIT';

  const pillLabel = offWindow ? 'PROTECTION OFF' : waiting ? 'WAITING' : 'READY';
  const pillTone: 'amber' | 'neutral' = offWindow ? 'neutral' : 'amber';

  const steps: PipelineStep[] = offWindow
    ? [
        { index: 1, caption: `Waited ${cooldownShort}`, status: 'done' },
        { index: 2, caption: 'Off 10m window', status: 'active' },
        { index: 3, caption: 'Back on', status: 'todo' },
      ]
    : [
        { index: 1, caption: `Wait ${cooldownShort}`, status: 'active' },
        { index: 2, caption: 'Off 10m window', status: 'todo' },
        { index: 3, caption: 'Back on', status: 'todo' },
      ];

  const startTimer = () => {
    Alert.alert(
      'Start the cooldown?',
      `Protection stays fully on for ${cooldownLabel}. When it ends, protection turns off and turns back on by itself about 10 minutes later. You can cancel the cooldown at any time.`,
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Start cooldown',
          onPress: async () => {
            await requestUnlockTimer();
            await refresh();
          },
        },
      ]
    );
  };

  const cancelTimer = () => {
    Alert.alert('Cancel the cooldown?', 'Strict mode stays on and nothing is unlocked.', [
      { text: 'Keep waiting', style: 'cancel' },
      {
        text: 'Cancel request',
        style: 'destructive',
        onPress: async () => {
          await cancelUnlockTimer();
          await refresh();
        },
      },
    ]);
  };

  const turnOnNow = async () => {
    await setProtectionEnabled(true);
    await refresh();
  };

  const toggleFastCooldown = async (value: boolean) => {
    await setFastCooldown(value);
    await refresh();
  };

  const exitStrictDebug = () => {
    Alert.alert('Exit Strict now?', 'Debug builds only. This unlocks immediately so you can test.', [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Exit Strict',
        style: 'destructive',
        onPress: async () => {
          await debugExitStrict();
          await refresh();
          router.back();
        },
      },
    ]);
  };

  return (
    <View style={styles.screen}>
      <SafeAreaView style={styles.safe} edges={['top', 'left', 'right', 'bottom']}>
        <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
          <View style={styles.header}>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Go back"
              onPress={() => router.back()}
              style={({ pressed }) => [styles.backButton, pressed && styles.pressed]}>
              <MaterialCommunityIcons name="chevron-left" size={26} color={Palette.textPrimary} />
            </Pressable>
            <Text style={styles.title}>Wait it out</Text>
            <View
              style={[
                styles.statusPill,
                pillTone === 'amber' ? styles.statusPillAmber : styles.statusPillNeutral,
              ]}>
              <View
                style={[
                  styles.statusDot,
                  pillTone === 'amber' ? styles.statusDotAmber : styles.statusDotNeutral,
                ]}
              />
              <Text
                style={[
                  styles.statusLabel,
                  pillTone === 'amber' ? styles.statusLabelAmber : styles.statusLabelNeutral,
                ]}>
                {pillLabel}
              </Text>
            </View>
          </View>

          <View style={styles.dialArea}>
            <View style={styles.dialHousing}>
              <Svg
                width={DIAL_SIZE}
                height={DIAL_SIZE}
                style={styles.dialSvg}
                pointerEvents="none">
                <Circle
                  cx={DIAL_CENTER}
                  cy={DIAL_CENTER}
                  r={ARC_RADIUS}
                  stroke="#232429"
                  strokeWidth={5}
                  fill="none"
                />
                <Circle
                  cx={DIAL_CENTER}
                  cy={DIAL_CENTER}
                  r={ARC_RADIUS}
                  stroke={Palette.amber}
                  strokeWidth={12}
                  strokeOpacity={0.16}
                  strokeLinecap="round"
                  fill="none"
                  strokeDasharray={`${ARC_CIRCUMFERENCE} ${ARC_CIRCUMFERENCE}`}
                  strokeDashoffset={dashOffset}
                  transform={`rotate(-90 ${DIAL_CENTER} ${DIAL_CENTER})`}
                />
                <Circle
                  cx={DIAL_CENTER}
                  cy={DIAL_CENTER}
                  r={ARC_RADIUS}
                  stroke={Palette.amber}
                  strokeWidth={5}
                  strokeLinecap="round"
                  fill="none"
                  strokeDasharray={`${ARC_CIRCUMFERENCE} ${ARC_CIRCUMFERENCE}`}
                  strokeDashoffset={dashOffset}
                  transform={`rotate(-90 ${DIAL_CENTER} ${DIAL_CENTER})`}
                />
              </Svg>

              <View style={styles.dialInner}>
                <View style={styles.dialIconTile}>
                  <MaterialCommunityIcons name={dialIcon} size={20} color={Palette.amber} />
                </View>
                <Text style={styles.dialTime}>{timeLabel}</Text>
                <Text style={styles.dialLabel}>{dialLabel}</Text>
              </View>
            </View>
          </View>

          <NeuCard style={styles.statusCard}>
            <View style={styles.statusIconTile}>
              <MaterialCommunityIcons
                name={offWindow ? 'flash' : 'shield-check-outline'}
                size={22}
                color={Palette.amber}
              />
            </View>
            <View style={styles.statusText}>
              <Text style={styles.statusTitle}>
                {offWindow ? 'Protection is OFF' : 'Protection stays on'}
              </Text>
              <Text style={styles.statusBody}>
                {offWindow
                  ? 'It turns back on by itself when the timer ends.'
                  : 'Protection stays fully on until the timer ends. Then it switches off for 10 minutes and turns back on by itself.'}
              </Text>
            </View>
          </NeuCard>

          <View style={styles.stepsRow}>
            {steps.map((step) => {
              const active = step.status === 'active';
              const done = step.status === 'done';
              return (
                <View key={step.index} style={[styles.stepCard, active && styles.stepCardActive]}>
                  <View style={styles.stepLabelRow}>
                    {done && (
                      <MaterialCommunityIcons
                        name="check"
                        size={10}
                        color={Palette.success}
                      />
                    )}
                    <Text
                      style={[
                        styles.stepLabel,
                        done && styles.stepLabelDone,
                        active && styles.stepLabelActive,
                      ]}>
                      STEP {step.index}
                    </Text>
                  </View>
                  <Text
                    style={[
                      styles.stepCaption,
                      done && styles.stepCaptionDone,
                      active && styles.stepCaptionActive,
                    ]}>
                    {step.caption}
                  </Text>
                  {active && <View style={styles.stepDot} />}
                </View>
              );
            })}
          </View>

          {offWindow ? (
            <PrimaryGlowButton
              label="Turn on now"
              onPress={turnOnNow}
              icon={
                <MaterialCommunityIcons
                  name="shield-check-outline"
                  size={18}
                  color={Palette.canvas}
                />
              }
              style={styles.action}
            />
          ) : waiting ? (
            <SecondaryRaisedButton
              label="Cancel request"
              onPress={cancelTimer}
              icon={
                <MaterialCommunityIcons name="close" size={16} color={Palette.textPrimary} />
              }
              style={styles.action}
            />
          ) : (
            <PrimaryGlowButton
              label={`Start ${waitLabel}`}
              onPress={startTimer}
              icon={
                <MaterialCommunityIcons name="clock-outline" size={18} color={Palette.canvas} />
              }
              style={styles.action}
            />
          )}

          {snapshot.debugBuild && (
            <NeuCard style={styles.debugCard}>
              <Text style={styles.debugLabel}>DEBUG BUILD ONLY</Text>
              <View style={styles.debugRow}>
                <View style={styles.debugText}>
                  <Text style={styles.debugTitle}>Short cooldown</Text>
                  <Text style={styles.statusBody}>
                    {`Make the cooldown about 1 minute instead of ${cooldownText}.`}
                  </Text>
                </View>
                <NeuSwitch
                  value={unlock.fastCooldown}
                  onValueChange={toggleFastCooldown}
                  label="Short cooldown"
                />
              </View>
              <SecondaryRaisedButton
                label="Exit Strict now"
                onPress={exitStrictDebug}
                style={styles.debugAction}
              />
            </NeuCard>
          )}
        </ScrollView>
      </SafeAreaView>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: Palette.canvas },
  safe: { flex: 1 },
  loading: {
    flex: 1,
    backgroundColor: Palette.canvas,
    alignItems: 'center',
    justifyContent: 'center',
  },
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
    marginBottom: Space.xs,
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
    flex: 1,
    fontFamily: FontNames.interBold,
    fontSize: 20,
    lineHeight: 26,
    color: Palette.textPrimary,
  },
  statusPill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    height: 26,
    paddingHorizontal: 10,
    borderRadius: Radii.pill,
    borderWidth: 1,
  },
  statusPillAmber: {
    backgroundColor: Palette.amberHighlight,
    borderColor: 'rgba(251, 191, 36, 0.35)',
  },
  statusPillNeutral: {
    backgroundColor: 'rgba(255, 255, 255, 0.06)',
    borderColor: 'rgba(255, 255, 255, 0.10)',
  },
  statusDot: {
    width: 5,
    height: 5,
    borderRadius: Radii.pill,
  },
  statusDotAmber: {
    backgroundColor: Palette.amber,
    boxShadow: '0px 0px 6px rgba(251, 191, 36, 0.9)',
  },
  statusDotNeutral: { backgroundColor: Palette.textSecondary },
  statusLabel: {
    ...Typography.monoLabelSm,
    fontSize: 9,
    lineHeight: 13,
    letterSpacing: 0.8,
  },
  statusLabelAmber: { color: Palette.amber },
  statusLabelNeutral: { color: Palette.textSecondary },
  dialArea: {
    alignItems: 'center',
    justifyContent: 'center',
    marginVertical: Space.xl,
  },
  dialHousing: {
    width: DIAL_SIZE,
    height: DIAL_SIZE,
    borderRadius: DIAL_SIZE / 2,
    backgroundColor: Palette.well,
    boxShadow: Shadows.sunken,
    alignItems: 'center',
    justifyContent: 'center',
  },
  dialSvg: {
    position: 'absolute',
    top: 0,
    left: 0,
  },
  dialInner: {
    width: 190,
    height: 190,
    borderRadius: 95,
    backgroundColor: Palette.raised,
    boxShadow: Shadows.raisedLarge,
    alignItems: 'center',
    justifyContent: 'center',
  },
  dialIconTile: {
    width: 40,
    height: 40,
    borderRadius: 12,
    backgroundColor: Palette.well,
    borderWidth: 1,
    borderColor: 'rgba(251, 191, 36, 0.22)',
    boxShadow: Shadows.sunkenSmall,
    alignItems: 'center',
    justifyContent: 'center',
  },
  dialTime: {
    fontFamily: FontNames.monoBold,
    fontSize: 36,
    lineHeight: 44,
    letterSpacing: -1,
    color: Palette.textPrimary,
    fontVariant: ['tabular-nums'],
    marginTop: 10,
  },
  dialLabel: {
    ...Typography.monoLabelSm,
    fontSize: 10,
    letterSpacing: 1.2,
    color: Palette.textSecondary,
    marginTop: 4,
  },
  statusCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    paddingVertical: 16,
  },
  statusIconTile: {
    width: Radii.tileSize,
    height: Radii.tileSize,
    borderRadius: Radii.tile,
    backgroundColor: Palette.well,
    boxShadow: Shadows.sunkenSmall,
    alignItems: 'center',
    justifyContent: 'center',
  },
  statusText: { flex: 1, gap: 4 },
  statusTitle: {
    fontFamily: FontNames.interSemiBold,
    fontSize: 16,
    lineHeight: 22,
    color: Palette.textPrimary,
  },
  statusBody: {
    ...Typography.bodyMd,
    color: Palette.textSecondary,
  },
  stepsRow: {
    flexDirection: 'row',
    gap: 10,
  },
  stepCard: {
    flex: 1,
    minHeight: 68,
    borderRadius: 16,
    borderWidth: 1.5,
    borderColor: 'transparent',
    backgroundColor: Palette.raised,
    boxShadow: Shadows.raisedSmall,
    paddingHorizontal: 8,
    paddingTop: 10,
    paddingBottom: 14,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 3,
  },
  stepCardActive: {
    borderColor: Palette.amber,
    backgroundColor: 'rgba(251, 191, 36, 0.08)',
  },
  stepLabelRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 3,
  },
  stepLabel: {
    ...Typography.monoLabelSm,
    fontSize: 9,
    lineHeight: 13,
    letterSpacing: 0.8,
    color: Palette.textMuted,
  },
  stepLabelDone: { color: Palette.success },
  stepLabelActive: { color: Palette.amber },
  stepCaption: {
    fontFamily: FontNames.interMedium,
    fontSize: 11,
    lineHeight: 15,
    textAlign: 'center',
    color: Palette.textSecondary,
  },
  stepCaptionDone: { color: Palette.success },
  stepCaptionActive: { color: Palette.amber },
  stepDot: {
    position: 'absolute',
    bottom: 4,
    width: 4,
    height: 4,
    borderRadius: Radii.pill,
    backgroundColor: Palette.amber,
    boxShadow: '0px 0px 6px rgba(251, 191, 36, 0.9)',
  },
  action: { width: '100%' },
  debugCard: { gap: Space.sm },
  debugLabel: {
    ...Typography.monoLabelSm,
    color: Palette.textMuted,
  },
  debugRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
  },
  debugText: { flex: 1, gap: 2 },
  debugTitle: {
    fontFamily: FontNames.interSemiBold,
    fontSize: 16,
    lineHeight: 22,
    color: Palette.textPrimary,
  },
  debugAction: { marginTop: Space.sm, alignSelf: 'flex-start' },
  pressed: { opacity: 0.75 },
});
