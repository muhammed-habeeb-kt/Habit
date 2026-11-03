import { MaterialCommunityIcons } from '@expo/vector-icons';
import { CameraView, useCameraPermissions, type CameraType } from 'expo-camera';
import { router } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import Svg, { Circle } from 'react-native-svg';

import { formatDuration, useCountdown } from '@/components/countdown';
import {
  NeuCard,
  NeuWell,
  PrimaryGlowButton,
  SecondaryRaisedButton,
  StatusPill,
} from '@/components/neumorphic';
import { FontNames, Palette, Radii, Shadows, Space, Typography } from '@/constants/theme';
import { useProtection } from '@/hooks/use-protection';
import { cancelFaceUnlock, startFaceUnlock, submitFaceFrames } from '@/native/habit';

/**
 * Unlock method C — two-person face check.
 *
 * Person 1 blinks, hands the phone over, then Person 2 blinks. The native engine counts
 * faces in each captured frame, checks the eye band changed (a blink) and that Person 2's
 * face signature differs from Person 1's. Success starts the 30-minute OFF window.
 *
 * 100% on-device: frames are sampled in memory and deleted; nothing is saved or sent.
 */
type Stage = 'person1_scanning' | 'person1_verified' | 'person2_scanning' | 'both_verified';
type PersonState = 'VERIFYING' | 'VERIFIED' | 'WAITING';

const BURST_FRAMES = 8;
const BURST_GAP_MS = 180;
const HANDOVER_MS = 2_000;
const FACE_OFF_WINDOW_MS = 30 * 60 * 1000;
const VIEWPORT = 240;
const CAMERA_SIZE = VIEWPORT - 32;

function wait(ms: number) {
  return new Promise<void>((resolve) => setTimeout(resolve, ms));
}

function PersonCard({
  index,
  state,
  active,
}: {
  index: number;
  state: PersonState;
  active: boolean;
}) {
  const icon: keyof typeof MaterialCommunityIcons.glyphMap =
    state === 'VERIFIED'
      ? 'check-circle'
      : state === 'VERIFYING'
        ? 'eye-outline'
        : 'account-outline';
  const color =
    state === 'VERIFIED' ? Palette.success : state === 'VERIFYING' ? Palette.amber : Palette.textMuted;

  return (
    <View
      style={[
        styles.personCard,
        active && styles.personCardActive,
        state === 'WAITING' && styles.personCardIdle,
      ]}>
      <MaterialCommunityIcons name={icon} size={22} color={color} />
      <View style={styles.personText}>
        <Text style={styles.personName}>Person {index}</Text>
        <Text style={[styles.personState, { color }]}>{state}</Text>
      </View>
    </View>
  );
}

export default function FaceUnlockScreen() {
  const { snapshot, refresh } = useProtection();
  const [permission, requestPermission] = useCameraPermissions();
  const cameraRef = useRef<CameraView | null>(null);
  const mounted = useRef(true);
  const busyRef = useRef(false);
  const handoverTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const [stage, setStage] = useState<Stage>('person1_scanning');
  const [facing, setFacing] = useState<CameraType>('front');
  const [started, setStarted] = useState(false);
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState(false);
  const [hint, setHint] = useState('Looking for a blink...');

  const offWindowMs = snapshot?.unlock.offWindowActive
    ? snapshot.unlock.offWindowRemainingMs
    : FACE_OFF_WINDOW_MS;
  const autoLock = useCountdown(stage === 'both_verified' ? offWindowMs : 0);

  useEffect(() => {
    mounted.current = true;
    startFaceUnlock().then((ok) => {
      if (!mounted.current) return;
      setStarted(ok);
      if (!ok) {
        setHint(
          'Face unlock is unavailable right now — too many failed attempts, or protection is already off.'
        );
      }
    });
    return () => {
      mounted.current = false;
      if (handoverTimer.current) clearTimeout(handoverTimer.current);
      void cancelFaceUnlock();
    };
  }, []);

  const runCheck = async () => {
    if (busyRef.current || !started) return;
    const camera = cameraRef.current;
    if (!camera) {
      setHint('The camera is not ready yet.');
      return;
    }

    busyRef.current = true;
    setBusy(true);
    setHint('Looking for a blink...');
    try {
      const frames: string[] = [];
      for (let index = 0; index < BURST_FRAMES; index++) {
        if (!mounted.current) return;
        const photo = await camera.takePictureAsync({
          quality: 0.35,
          shutterSound: false,
        });
        if (photo?.uri) frames.push(photo.uri);
        if (index < BURST_FRAMES - 1) await wait(BURST_GAP_MS);
      }
      if (!mounted.current) return;
      if (frames.length === 0) {
        setHint('The camera did not return a frame. Try again.');
        return;
      }

      const result = await submitFaceFrames(frames);
      if (!mounted.current) return;
      if (!result) {
        setHint('The face check is unavailable in this build. Try again.');
        return;
      }

      setHint(result.message);
      if (result.status === 'first') {
        setStage('person1_verified');
        return;
      }
      if (result.status === 'unlocked') {
        await refresh();
        if (mounted.current) setStage('both_verified');
      }
    } catch {
      if (mounted.current) setHint('Could not use the camera. Try again.');
    } finally {
      busyRef.current = false;
      if (mounted.current) setBusy(false);
    }
  };

  const handOver = () => {
    setStage('person2_scanning');
    setHint('Looking for a blink...');
    if (handoverTimer.current) clearTimeout(handoverTimer.current);
    handoverTimer.current = setTimeout(() => {
      void runCheck();
    }, HANDOVER_MS);
  };

  const flipCamera = () => {
    setReady(false);
    setFacing((current) => (current === 'front' ? 'back' : 'front'));
  };

  const step = stage === 'person1_scanning' || stage === 'person1_verified' ? 1 : 2;
  const person1State: PersonState = stage === 'person1_scanning' ? 'VERIFYING' : 'VERIFIED';
  const person2State: PersonState =
    stage === 'both_verified'
      ? 'VERIFIED'
      : stage === 'person2_scanning'
        ? 'VERIFYING'
        : 'WAITING';
  const cameraGranted = permission?.granted === true;
  const canScan = started && ready && cameraGranted && !busy;
  const handoff = stage === 'person1_verified';
  const blinkLabel = busy ? 'Checking…' : handoff ? 'Pass to Person 2' : 'Blink slowly';

  if (stage === 'both_verified') {
    return (
      <View style={styles.screen}>
        <View style={styles.modalRoot}>
          <View style={styles.scrim} />
          <View style={styles.modalCard}>
            <View style={styles.successBadge}>
              <MaterialCommunityIcons name="check" size={30} color={Palette.amber} />
            </View>
            <Text style={styles.successTitle}>Two faces verified</Text>
            <Text style={styles.successBody}>
              Protection is OFF for 30 minutes, then turns back on by itself.
            </Text>

            <NeuWell style={styles.reactivation}>
              <View style={styles.reactivationLeft}>
                <MaterialCommunityIcons
                  name="timer-outline"
                  size={20}
                  color={Palette.textPrimary}
                />
                <Text style={styles.reactivationLabel}>Reactivation</Text>
              </View>
              <View style={styles.reactivationRight}>
                <Text style={styles.reactivationHint}>Auto-lock in</Text>
                <Text style={styles.reactivationValue}>{formatDuration(autoLock)}</Text>
              </View>
            </NeuWell>

            <PrimaryGlowButton
              label="Done →"
              onPress={() => router.dismissTo('/')}
              style={styles.doneButton}
            />
          </View>
        </View>
      </View>
    );
  }

  return (
    <View style={styles.screen}>
      <SafeAreaView style={styles.safe} edges={['top', 'left', 'right', 'bottom']}>
        <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
          <View style={styles.header}>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Cancel face check"
              onPress={() => router.back()}
              style={({ pressed }) => [styles.backButton, pressed && styles.pressed]}>
              <MaterialCommunityIcons name="chevron-left" size={26} color={Palette.textPrimary} />
            </Pressable>
            <Text style={styles.title}>Two-person check</Text>
            <StatusPill label={`STEP ${step} OF 2`} tone="neutral" />
          </View>

          <View style={styles.peopleRow}>
            <PersonCard
              index={1}
              state={person1State}
              active={stage === 'person1_scanning' && !handoff}
            />
            <PersonCard index={2} state={person2State} active={stage === 'person2_scanning'} />
          </View>

          <Pressable
            accessibilityRole="button"
            accessibilityLabel={handoff ? 'Pass the phone to Person 2' : 'Start blink check'}
            disabled={!canScan}
            onPress={() => (handoff ? handOver() : runCheck())}
            style={({ pressed }) => [styles.viewport, pressed && styles.viewportPressed]}>
            <Svg width={VIEWPORT} height={VIEWPORT} style={StyleSheet.absoluteFill}>
              <Circle
                cx={VIEWPORT / 2}
                cy={VIEWPORT / 2}
                r={VIEWPORT / 2 - 2}
                stroke={Palette.amber}
                strokeWidth={2}
                strokeDasharray="12 11"
                strokeLinecap="round"
                fill="none"
              />
            </Svg>

            <View style={[styles.cameraClip, busy && styles.cameraBusy]}>
              {cameraGranted ? (
                <CameraView
                  ref={cameraRef}
                  style={StyleSheet.absoluteFill}
                  facing={facing}
                  mirror={facing === 'front'}
                  onCameraReady={() => setReady(true)}
                  onMountError={() => setHint('The camera could not start on this device.')}
                />
              ) : (
                <View style={styles.cameraIdle}>
                  {permission ? (
                    <MaterialCommunityIcons
                      name="camera-off-outline"
                      size={40}
                      color={Palette.textMuted}
                    />
                  ) : (
                    <ActivityIndicator color={Palette.amber} />
                  )}
                </View>
              )}
            </View>

            {busy && (
              <View style={styles.scanOverlay} pointerEvents="none">
                <ActivityIndicator color={Palette.amber} />
              </View>
            )}

            <Text pointerEvents="none" style={styles.liveLabel}>
              LIVE CAMERA
            </Text>
          </Pressable>

          <View style={styles.prompt}>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={blinkLabel}
              disabled={!canScan}
              onPress={() => (handoff ? handOver() : runCheck())}
              style={({ pressed }) => [
                styles.blinkPill,
                pressed && styles.blinkPillPressed,
                !canScan && !handoff && styles.blinkPillDisabled,
              ]}>
              <MaterialCommunityIcons name="eye-outline" size={18} color={Palette.textPrimary} />
              <Text style={styles.blinkLabel}>{blinkLabel}</Text>
            </Pressable>
            <Text style={styles.blinkHint}>{hint}</Text>
          </View>

          <NeuCard style={styles.privacyCard} padded={false}>
            <View style={styles.privacyIcon}>
              <MaterialCommunityIcons name="lock" size={20} color={Palette.amber} />
            </View>
            <Text style={styles.privacyText}>
              Faces are checked on this phone only and are never saved or sent.
            </Text>
          </NeuCard>

          {!cameraGranted && permission ? (
            <PrimaryGlowButton
              label="Allow camera"
              onPress={requestPermission}
              icon={<MaterialCommunityIcons name="camera" size={18} color={Palette.canvas} />}
            />
          ) : null}

          <View style={styles.controls}>
            <SecondaryRaisedButton
              label="Flip camera"
              onPress={flipCamera}
              icon={
                <MaterialCommunityIcons
                  name="camera-flip-outline"
                  size={18}
                  color={Palette.textPrimary}
                />
              }
              style={styles.controlButton}
            />
            <SecondaryRaisedButton
              label="Cancel"
              onPress={() => router.back()}
              style={styles.controlButton}
            />
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
    gap: Space.lg,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
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
    fontSize: 18,
    lineHeight: 24,
    letterSpacing: -0.18,
    color: Palette.textPrimary,
  },
  peopleRow: {
    flexDirection: 'row',
    gap: Space.gutter,
  },
  personCard: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.sm,
    borderRadius: Radii.control,
    backgroundColor: Palette.raised,
    boxShadow: Shadows.raisedSmall,
    paddingHorizontal: 12,
    paddingVertical: 10,
  },
  personCardActive: {
    backgroundColor: Palette.amberHighlight,
    boxShadow: `inset 0px 0px 0px 1px ${Palette.amber}, ${Shadows.raisedSmall}`,
  },
  personCardIdle: { opacity: 0.6 },
  personText: { flex: 1, gap: 1 },
  personName: {
    ...Typography.bodyMd,
    fontFamily: FontNames.interSemiBold,
    color: Palette.textPrimary,
  },
  personState: {
    ...Typography.monoLabelSm,
    letterSpacing: 0.6,
  },
  viewport: {
    width: VIEWPORT,
    height: VIEWPORT,
    alignSelf: 'center',
    alignItems: 'center',
    justifyContent: 'center',
  },
  viewportPressed: { opacity: 0.85 },
  cameraClip: {
    width: CAMERA_SIZE,
    height: CAMERA_SIZE,
    borderRadius: CAMERA_SIZE / 2,
    overflow: 'hidden',
    backgroundColor: Palette.well,
    boxShadow: Shadows.sunken,
  },
  cameraBusy: { opacity: 0.7 },
  cameraIdle: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: Palette.well,
  },
  scanOverlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    alignItems: 'center',
    justifyContent: 'center',
  },
  liveLabel: {
    ...Typography.monoLabelSm,
    color: Palette.amber,
    position: 'absolute',
    top: 18,
    backgroundColor: Palette.canvas,
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: Radii.pill,
  },
  prompt: {
    alignItems: 'center',
    gap: Space.sm,
  },
  blinkPill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    borderRadius: Radii.pill,
    backgroundColor: Palette.raised,
    boxShadow: Shadows.raisedSmall,
    paddingHorizontal: 18,
    paddingVertical: 10,
  },
  blinkPillPressed: {
    backgroundColor: Palette.well,
    boxShadow: Shadows.sunkenSmall,
  },
  blinkPillDisabled: { opacity: 0.5 },
  blinkLabel: {
    fontFamily: FontNames.interSemiBold,
    fontSize: 15,
    lineHeight: 20,
    color: Palette.textPrimary,
  },
  blinkHint: {
    ...Typography.bodyMd,
    color: Palette.textSecondary,
    textAlign: 'center',
    paddingHorizontal: Space.md,
  },
  privacyCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    borderRadius: Radii.control,
    paddingHorizontal: 14,
    paddingVertical: 14,
  },
  privacyIcon: {
    width: 40,
    height: 40,
    borderRadius: Radii.tile,
    backgroundColor: Palette.amberHighlight,
    alignItems: 'center',
    justifyContent: 'center',
  },
  privacyText: {
    flex: 1,
    ...Typography.bodyMd,
    color: Palette.textSecondary,
  },
  controls: {
    flexDirection: 'row',
    gap: Space.gutter,
  },
  controlButton: { flex: 1, minHeight: 48, paddingVertical: 10, paddingHorizontal: 12 },
  pressed: { opacity: 0.7 },
  modalRoot: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: Space.xl,
  },
  scrim: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: Palette.scrim,
  },
  modalCard: {
    width: '100%',
    maxWidth: 360,
    borderRadius: 28,
    borderWidth: 1,
    borderColor: Palette.glassBorder,
    backgroundColor: Palette.surface,
    padding: Space.xl,
    gap: Space.md,
    alignItems: 'center',
    boxShadow: Shadows.raisedLarge,
  },
  successBadge: {
    width: 64,
    height: 64,
    borderRadius: Radii.pill,
    backgroundColor: Palette.amberHighlight,
    borderWidth: 1,
    borderColor: Palette.amber,
    alignItems: 'center',
    justifyContent: 'center',
    boxShadow: Shadows.amberHalo,
  },
  successTitle: {
    ...Typography.headlineLg,
    color: Palette.textPrimary,
    textAlign: 'center',
  },
  successBody: {
    ...Typography.bodyMd,
    fontSize: 14,
    lineHeight: 20,
    color: Palette.textSecondary,
    textAlign: 'center',
  },
  reactivation: {
    width: '100%',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: Space.gutter,
    borderRadius: Radii.control,
    paddingHorizontal: 14,
    paddingVertical: 14,
    marginTop: Space.xs,
  },
  reactivationLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.sm,
  },
  reactivationLabel: {
    ...Typography.bodyMd,
    fontFamily: FontNames.interMedium,
    fontSize: 14,
    color: Palette.textPrimary,
  },
  reactivationRight: { alignItems: 'flex-end', gap: 1 },
  reactivationHint: {
    ...Typography.bodySm,
    color: Palette.textSecondary,
  },
  reactivationValue: {
    fontFamily: FontNames.monoBold,
    fontSize: 22,
    lineHeight: 28,
    color: Palette.textPrimary,
  },
  doneButton: { width: '100%', marginTop: Space.xs },
});
