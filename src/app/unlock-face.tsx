import { MaterialCommunityIcons } from '@expo/vector-icons';
import { CameraView, useCameraPermissions, type CameraType } from 'expo-camera';
import { router } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
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
import {
  analyzeFaceFrame,
  canAnalyzeFaceFrames,
  cancelFaceUnlock,
  completeFaceUnlock,
  startFaceUnlock,
  type FaceFrameAnalysis,
} from '@/native/habit';

/**
 * Unlock method C — two-person face check.
 *
 * The camera streams silent low-res frames every ~450 ms. Each one is analyzed natively
 * by the bundled offline ML Kit detector, which reports the face, the eye-open
 * probabilities (blinks), head pose, a tracking id and an illumination-normalized identity
 * signature built from inner features only, so blinking cannot change it.
 *
 * Person 1 is averaged over 3 frontal open-eye frames. Person 2 must first pass 3
 * consecutive frontal open-eye frames with a different tracking id and signature; only
 * then is a blink accepted, and the post-blink frame is checked again. Success calls
 * `completeFaceUnlock()`, which starts the 30-minute OFF window.
 *
 * 100% on-device: frames are downscaled, measured and deleted; nothing is saved or sent.
 */
type Stage = 'person1_scanning' | 'handover' | 'person2_scanning' | 'both_verified';
type PersonState = 'VERIFYING' | 'VERIFIED' | 'WAITING';

const FRAME_INTERVAL_MS = 450;
const HANDOVER_MS = 2_000;
const FACE_OFF_WINDOW_MS = 30 * 60 * 1000;
/** Weighted mean absolute signature difference below this is treated as the same person. */
const SAME_FACE_THRESHOLD = 0.05;
/** The native signature starts with this many scale-free geometry ratios. */
const GEOMETRY_FEATURES = 7;
/** Geometry separates people better than skin colour, so it is weighted heavier. */
const GEOMETRY_WEIGHT = 4;
/** Strict open-eye gate; a mid-blink frame never counts for identity. */
const OPEN_EYE_PROBABILITY = 0.72;
/** A face must be within this many degrees on all three axes to count as frontal. */
const FRONTAL_LIMIT_DEG = 12;
/** Open-eye frames required to record Person 1 and to lock in Person 2. */
const BASELINE_FRAMES = 3;
const PERSON2_STREAK = 3;
/** A same-face frame blocks blink acceptance for this long. */
const SAME_FACE_LOCK_MS = 2_500;
const VIEWPORT = 240;
const CAMERA_SIZE = VIEWPORT - 32;

function wait(ms: number) {
  return new Promise<void>((resolve) => setTimeout(resolve, ms));
}

/** Weighted mean absolute difference between two signatures (0 = identical). */
function signatureDistance(first: number[], second: number[]): number {
  const size = Math.min(first.length, second.length);
  if (size === 0) return 1;
  let total = 0;
  let weight = 0;
  for (let index = 0; index < size; index++) {
    const featureWeight = index < GEOMETRY_FEATURES ? GEOMETRY_WEIGHT : 1;
    total += featureWeight * Math.abs((first[index] ?? 0) - (second[index] ?? 0));
    weight += featureWeight;
  }
  return weight > 0 ? total / weight : 1;
}

/** Element-wise mean of the Person 1 baseline frames. */
function averageSignatures(signatures: number[][]): number[] {
  const size = Math.min(...signatures.map((signature) => signature.length));
  if (!Number.isFinite(size) || size === 0) return [];
  const average = new Array<number>(size).fill(0);
  for (const signature of signatures) {
    for (let index = 0; index < size; index++) {
      average[index] += signature[index] ?? 0;
    }
  }
  return average.map((value) => value / signatures.length);
}

/** Old APKs analyze faces but cannot report pose/tracking; the screen must say so. */
function supportsPose(frame: FaceFrameAnalysis): boolean {
  return (
    typeof frame.eulerX === 'number' &&
    typeof frame.eulerY === 'number' &&
    typeof frame.eulerZ === 'number' &&
    typeof frame.trackingId === 'number'
  );
}

function isFrontal(frame: FaceFrameAnalysis): boolean {
  return (
    Math.abs(frame.eulerX) <= FRONTAL_LIMIT_DEG &&
    Math.abs(frame.eulerY) <= FRONTAL_LIMIT_DEG &&
    Math.abs(frame.eulerZ) <= FRONTAL_LIMIT_DEG
  );
}

function hasOpenEyes(frame: FaceFrameAnalysis): boolean {
  return (
    frame.leftEyeProb > OPEN_EYE_PROBABILITY && frame.rightEyeProb > OPEN_EYE_PROBABILITY
  );
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
      <View style={[styles.personIcon, active && styles.personIconActive]}>
        <MaterialCommunityIcons name={icon} size={18} color={color} />
      </View>
      <View style={styles.personText}>
        <Text style={styles.personName} numberOfLines={1} maxFontSizeMultiplier={1.15}>
          Person {index}
        </Text>
        <Text
          style={[styles.personState, { color }]}
          numberOfLines={1}
          maxFontSizeMultiplier={1.15}>
          {state}
        </Text>
      </View>
    </View>
  );
}

export default function FaceUnlockScreen() {
  const { snapshot, refresh } = useProtection();
  const [permission, requestPermission] = useCameraPermissions();
  const cameraRef = useRef<CameraView | null>(null);
  const mounted = useRef(true);
  const analyzing = useRef(false);
  const verifying = useRef(false);
  const stageRef = useRef<Stage>('person1_scanning');
  /** Blink state: a blink is open → closed, or closed → open, never a single frame. */
  const seenOpen = useRef(false);
  const seenClosed = useRef(false);
  const firstSignature = useRef<number[]>([]);
  const baselineSignatures = useRef<number[][]>([]);
  const baselineTrackingIds = useRef<Set<number>>(new Set());
  const person2Streak = useRef(0);
  const person2LockedIn = useRef(false);
  const sameFaceLockUntil = useRef(0);

  const [stage, setStage] = useState<Stage>('person1_scanning');
  const [facing, setFacing] = useState<CameraType>('front');
  const [started, setStarted] = useState(false);
  const [ready, setReady] = useState(false);
  const [sameFaceWarning, setSameFaceWarning] = useState(false);
  const [hint, setHint] = useState(
    canAnalyzeFaceFrames
      ? "Position Person 1's face inside the circle"
      : 'This build cannot check faces yet — install the updated APK.'
  );

  const cameraGranted = permission?.granted === true;
  const scanning =
    started && ready && cameraGranted && stage !== 'handover' && stage !== 'both_verified';

  const offWindowMs = snapshot?.unlock.offWindowActive
    ? snapshot.unlock.offWindowRemainingMs
    : FACE_OFF_WINDOW_MS;
  const autoLock = useCountdown(stage === 'both_verified' ? offWindowMs : 0);

  const setPhase = useCallback((next: Stage) => {
    stageRef.current = next;
    setStage(next);
  }, []);

  useEffect(() => {
    mounted.current = true;
    if (canAnalyzeFaceFrames) {
      startFaceUnlock().then((ok) => {
        if (!mounted.current) return;
        setStarted(ok);
        if (!ok) {
          setHint(
            'Face unlock is unavailable right now — too many failed attempts, or protection is already off.'
          );
        }
      });
    }
    return () => {
      mounted.current = false;
      void cancelFaceUnlock();
    };
  }, []);

  const resetPerson2 = useCallback(() => {
    person2Streak.current = 0;
    person2LockedIn.current = false;
    seenOpen.current = false;
    seenClosed.current = false;
  }, []);

  /** Same person still in front: drop Person 2's progress and block blinks for a moment. */
  const flagSameFace = useCallback(() => {
    sameFaceLockUntil.current = Date.now() + SAME_FACE_LOCK_MS;
    resetPerson2();
    setSameFaceWarning(true);
    setHint('Same face detected — a second person must verify');
  }, [resetPerson2]);

  const finishUnlock = useCallback(async () => {
    if (verifying.current) return;
    verifying.current = true;
    try {
      const unlocked = await completeFaceUnlock();
      if (!mounted.current) return;
      if (!unlocked) {
        setHint('The face check expired. Start a new one from Unlock Protection.');
        return;
      }
      await refresh();
      if (!mounted.current) return;
      setSameFaceWarning(false);
      setPhase('both_verified');
      setHint('Two faces verified');
    } finally {
      verifying.current = false;
    }
  }, [refresh, setPhase]);

  /** Person 1: average three frontal, open-eye frames into the baseline identity. */
  const handlePerson1 = useCallback(
    (frame: FaceFrameAnalysis) => {
      if (!supportsPose(frame)) {
        setHint('This build cannot verify faces yet — install the updated APK.');
        return;
      }
      if (!frame.faceDetected) {
        setHint("Position Person 1's face inside the circle");
        return;
      }
      if (!isFrontal(frame) || !hasOpenEyes(frame)) {
        setHint('Look straight at the camera with both eyes open');
        return;
      }
      const signature = frame.faceSignature ?? [];
      if (signature.length === 0) return;

      baselineSignatures.current.push(signature);
      if (frame.trackingId >= 0) baselineTrackingIds.current.add(frame.trackingId);
      const collected = baselineSignatures.current.length;
      if (collected < BASELINE_FRAMES) {
        const remaining = BASELINE_FRAMES - collected;
        setHint(`Hold still — ${remaining} more scan${remaining === 1 ? '' : 's'}`);
        return;
      }

      firstSignature.current = averageSignatures(baselineSignatures.current);
      seenOpen.current = false;
      seenClosed.current = false;
      setSameFaceWarning(false);
      setPhase('handover');
      setHint('Person 1 verified. Pass the phone to Person 2');
    },
    [setPhase]
  );

  /** Person 2: open-eye identity gate first, blink second, post-blink recheck last. */
  const handlePerson2 = useCallback(
    (frame: FaceFrameAnalysis) => {
      if (!supportsPose(frame)) {
        setHint('This build cannot verify faces yet — install the updated APK.');
        return;
      }
      if (!frame.faceDetected) {
        resetPerson2();
        setHint("Position Person 2's face inside the circle");
        return;
      }

      // Tracking ids survive a blink and a head tilt, so sharing Person 1's id proves the
      // same person is still there. This check runs even on closed-eye frames.
      if (frame.trackingId >= 0 && baselineTrackingIds.current.has(frame.trackingId)) {
        flagSameFace();
        return;
      }

      // Identity is never evaluated on a blink frame. A closed frame may only start the
      // blink after Person 2 has already passed the open-eye identity gate.
      if (!hasOpenEyes(frame) || frame.eyesClosed) {
        if (person2LockedIn.current && seenOpen.current && frame.eyesClosed) {
          seenClosed.current = true;
          setHint('Blink detected — now look at the camera');
        } else if (!person2LockedIn.current) {
          // A blink frame breaks the "3 consecutive open-eye frames" run.
          person2Streak.current = 0;
        }
        return;
      }

      if (!isFrontal(frame)) {
        person2Streak.current = 0;
        setHint('Face the camera straight on');
        return;
      }

      // A same-face frame keeps blink acceptance closed for a moment, even if the face
      // would now look slightly different.
      if (Date.now() < sameFaceLockUntil.current) return;

      const signature = frame.faceSignature ?? [];
      if (signature.length === 0) return;
      const baseline = firstSignature.current;
      if (baseline.length > 0 && signatureDistance(baseline, signature) < SAME_FACE_THRESHOLD) {
        flagSameFace();
        return;
      }

      if (!person2LockedIn.current) {
        person2Streak.current += 1;
        setSameFaceWarning(false);
        if (person2Streak.current < PERSON2_STREAK) {
          const remaining = PERSON2_STREAK - person2Streak.current;
          setHint(`Hold still — ${remaining} more open-eye scan${remaining === 1 ? '' : 's'}`);
          return;
        }
        person2LockedIn.current = true;
        seenOpen.current = true;
        seenClosed.current = false;
        setHint('Person 2 verified. Now blink slowly...');
        return;
      }

      if (seenClosed.current) {
        // This open-eye frame already passed the identity gate above.
        seenClosed.current = false;
        void finishUnlock();
        return;
      }
      seenOpen.current = true;
      setHint('Now blink slowly...');
    },
    [finishUnlock, flagSameFace, resetPerson2]
  );

  const handleFrame = useCallback(
    (frame: FaceFrameAnalysis) => {
      if (!mounted.current) return;
      if (stageRef.current === 'person1_scanning') handlePerson1(frame);
      else if (stageRef.current === 'person2_scanning') handlePerson2(frame);
    },
    [handlePerson1, handlePerson2]
  );

  // Sample a silent low-res frame every ~450 ms while a person is being scanned.
  useEffect(() => {
    if (!scanning) return;
    let cancelled = false;

    const capture = async () => {
      const camera = cameraRef.current;
      if (!camera || analyzing.current) return;
      analyzing.current = true;
      try {
        const photo = await camera.takePictureAsync({
          quality: 0.25,
          skipProcessing: true,
          shutterSound: false,
        });
        if (!photo?.uri || cancelled || !mounted.current) return;
        const frame = await analyzeFaceFrame(photo.uri);
        if (!cancelled && mounted.current && frame) handleFrame(frame);
      } catch {
        if (!cancelled && mounted.current) {
          setHint('The camera missed a frame. Hold the phone still.');
        }
      } finally {
        analyzing.current = false;
      }
    };

    const loop = async () => {
      while (!cancelled) {
        await capture();
        await wait(FRAME_INTERVAL_MS);
      }
    };
    void loop();

    return () => {
      cancelled = true;
    };
  }, [facing, handleFrame, scanning]);

  // Two seconds to hand the phone over before Person 2 is scanned.
  useEffect(() => {
    if (stage !== 'handover') return;
    const timer = setTimeout(() => {
      if (!mounted.current) return;
      resetPerson2();
      setPhase('person2_scanning');
      setHint("Position Person 2's face inside the circle");
    }, HANDOVER_MS);
    return () => clearTimeout(timer);
  }, [resetPerson2, setPhase, stage]);

  const flipCamera = () => {
    setReady(false);
    setFacing((current) => (current === 'front' ? 'back' : 'front'));
  };

  const step = stage === 'person1_scanning' ? 1 : 2;
  const person1State: PersonState = stage === 'person1_scanning' ? 'VERIFYING' : 'VERIFIED';
  const person2State: PersonState =
    stage === 'both_verified'
      ? 'VERIFIED'
      : stage === 'person2_scanning'
        ? 'VERIFYING'
        : 'WAITING';
  const handoff = stage === 'handover';
  const statusLabel = handoff
    ? 'Pass to Person 2'
    : stage === 'person1_scanning'
      ? 'Hold still'
      : 'Blink slowly';
  const statusIcon: keyof typeof MaterialCommunityIcons.glyphMap = sameFaceWarning
    ? 'account-alert-outline'
    : handoff
      ? 'account-arrow-right-outline'
      : 'eye-outline';

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
              active={stage === 'person1_scanning'}
            />
            <PersonCard index={2} state={person2State} active={stage === 'person2_scanning'} />
          </View>

          <View style={styles.viewport}>
            <Svg width={VIEWPORT} height={VIEWPORT} style={StyleSheet.absoluteFill}>
              <Circle
                cx={VIEWPORT / 2}
                cy={VIEWPORT / 2}
                r={VIEWPORT / 2 - 2}
                stroke={sameFaceWarning ? Palette.danger : Palette.amber}
                strokeWidth={2}
                strokeDasharray="12 11"
                strokeLinecap="round"
                fill="none"
              />
            </Svg>

            <View style={styles.cameraClip}>
              {cameraGranted ? (
                <CameraView
                  ref={cameraRef}
                  style={StyleSheet.absoluteFill}
                  facing={facing}
                  mirror={facing === 'front'}
                  animateShutter={false}
                  flash="off"
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

            <View pointerEvents="none" style={styles.livePill}>
              <View style={[styles.liveDot, scanning && styles.liveDotActive]} />
              <Text style={styles.liveLabel}>{scanning ? 'LIVE SCAN' : 'LIVE CAMERA'}</Text>
            </View>
          </View>

          <View style={styles.prompt}>
            <View style={[styles.blinkPill, sameFaceWarning && styles.blinkPillWarning]}>
              <MaterialCommunityIcons name={statusIcon} size={18} color={Palette.textPrimary} />
              <Text style={styles.blinkLabel}>{statusLabel}</Text>
            </View>
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
    gap: Space.sm,
  },
  personCard: {
    flex: 1,
    minWidth: 0,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    borderRadius: Radii.control,
    backgroundColor: Palette.raised,
    boxShadow: Shadows.raisedSmall,
    paddingHorizontal: 10,
    paddingVertical: 9,
  },
  personCardActive: {
    backgroundColor: Palette.amberHighlight,
    boxShadow: `inset 0px 0px 0px 1px ${Palette.amber}, ${Shadows.raisedSmall}`,
  },
  personCardIdle: { opacity: 0.6 },
  personIcon: {
    width: 30,
    height: 30,
    borderRadius: 15,
    backgroundColor: Palette.well,
    alignItems: 'center',
    justifyContent: 'center',
    flexShrink: 0,
  },
  personIconActive: { backgroundColor: Palette.canvas },
  personText: { flex: 1, minWidth: 0, gap: 2 },
  personName: {
    fontFamily: FontNames.interSemiBold,
    fontSize: 13,
    lineHeight: 17,
    color: Palette.textPrimary,
  },
  personState: {
    ...Typography.monoLabelSm,
    fontSize: 9,
    lineHeight: 12,
    letterSpacing: 0.4,
  },
  viewport: {
    width: VIEWPORT,
    height: VIEWPORT,
    alignSelf: 'center',
    alignItems: 'center',
    justifyContent: 'center',
  },
  cameraClip: {
    width: CAMERA_SIZE,
    height: CAMERA_SIZE,
    borderRadius: CAMERA_SIZE / 2,
    overflow: 'hidden',
    backgroundColor: Palette.well,
    boxShadow: Shadows.sunken,
  },
  cameraIdle: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: Palette.well,
  },
  livePill: {
    position: 'absolute',
    top: 16,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: Palette.canvas,
    paddingHorizontal: 10,
    paddingVertical: 3,
    borderRadius: Radii.pill,
  },
  liveDot: {
    width: 5,
    height: 5,
    borderRadius: Radii.pill,
    backgroundColor: Palette.textMuted,
  },
  liveDotActive: {
    backgroundColor: Palette.amber,
    boxShadow: Shadows.amberHalo,
  },
  liveLabel: {
    ...Typography.monoLabelSm,
    fontSize: 9,
    lineHeight: 12,
    letterSpacing: 1,
    color: Palette.amber,
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
  blinkPillWarning: {
    backgroundColor: Palette.amberHighlight,
    boxShadow: `inset 0px 0px 0px 1px ${Palette.danger}, ${Shadows.raisedSmall}`,
  },
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
