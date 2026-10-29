import { CameraView, useCameraPermissions } from 'expo-camera';
import { router } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { MaxContentWidth, Radius, Spacing, Type } from '@/constants/theme';
import { useProtection } from '@/hooks/use-protection';
import { useTheme } from '@/hooks/use-theme';
import { cancelFaceUnlock, startFaceUnlock, submitFacePhoto } from '@/native/habit';

export default function FaceUnlockScreen() {
  const theme = useTheme();
  const { refresh } = useProtection();
  const [permission, requestPermission] = useCameraPermissions();
  const cameraRef = useRef<CameraView | null>(null);

  const [stage, setStage] = useState(1);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [ready, setReady] = useState(false);
  const [started, setStarted] = useState(false);

  useEffect(() => {
    let active = true;
    startFaceUnlock().then((ok) => {
      if (!active) return;
      setStarted(ok);
      if (!ok) {
        setMessage(
          'Face unlock is unavailable right now — too many failed attempts, or protection is already off.'
        );
      }
    });
    return () => {
      active = false;
      void cancelFaceUnlock();
    };
  }, []);

  const capture = async () => {
    if (busy || !started) return;
    const camera = cameraRef.current;
    if (!camera) {
      setMessage('The camera is not ready yet.');
      return;
    }
    setBusy(true);
    try {
      const photo = await camera.takePictureAsync({ quality: 0.5 });
      if (!photo?.uri) {
        setMessage('The camera did not return a photo. Try again.');
        return;
      }
      const result = await submitFacePhoto(photo.uri);
      if (!result) {
        setMessage('The face check failed. Try again.');
        return;
      }
      setMessage(result.message);
      if (result.status === 'first') {
        setStage(2);
        return;
      }
      if (result.status === 'unlocked') {
        await refresh();
        Alert.alert('Unlocked', 'Both faces confirmed. Protection is off.', [
          { text: 'OK', onPress: () => router.back() },
        ]);
      }
    } catch {
      setMessage('Could not take the photo. Try again.');
    } finally {
      setBusy(false);
    }
  };

  const permissionMissing = permission != null && !permission.granted;

  return (
    <View style={[styles.screen, { backgroundColor: theme.background }]}>
      <SafeAreaView style={styles.safe} edges={['top', 'left', 'right', 'bottom']}>
        <View style={styles.content}>
          <View style={styles.header}>
            <View style={styles.headerText}>
              <Text style={[styles.eyebrow, { color: theme.accent }]}>UNLOCK · METHOD C</Text>
              <Text style={[styles.title, { color: theme.text }]}>Two face checks</Text>
            </View>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Close face unlock"
              onPress={() => router.back()}
              style={({ pressed }) => [
                styles.closeButton,
                { backgroundColor: theme.surfaceAlt, opacity: pressed ? 0.7 : 1 },
              ]}>
              <Text style={[styles.closeLabel, { color: theme.textSecondary }]}>Close</Text>
            </Pressable>
          </View>

          <View
            style={[
              styles.stagePill,
              {
                backgroundColor: stage === 1 ? theme.accentSoft : theme.surfaceAlt,
              },
            ]}>
            <Text style={[styles.stageLabel, { color: theme.accent }]}>
              {stage === 1 ? 'STEP 1 OF 2 · FIRST PERSON' : 'STEP 2 OF 2 · SECOND PERSON'}
            </Text>
          </View>

          <Text style={[styles.instructions, { color: theme.textSecondary }]}>
            {stage === 1
              ? 'Hold the phone so the first person’s face fills the frame, then take the photo.'
              : 'Hand the phone to the second person. Wait a few seconds, then take the second photo.'}
          </Text>

          {!permission ? (
            <View
              style={[
                styles.cameraPlaceholder,
                { backgroundColor: theme.surfaceAlt, borderColor: theme.border },
              ]}>
              <ActivityIndicator color={theme.accent} />
            </View>
          ) : permissionMissing ? (
            <View
              style={[
                styles.permissionCard,
                { backgroundColor: theme.surface, borderColor: theme.border },
              ]}>
              <Text style={[styles.permissionTitle, { color: theme.text }]}>
                Camera access is needed
              </Text>
              <Text style={[styles.instructions, { color: theme.textSecondary }]}>
                Habit uses the camera only to count faces during this unlock. Photos are
                never saved.
              </Text>
              <Pressable
                accessibilityRole="button"
                onPress={requestPermission}
                style={({ pressed }) => [
                  styles.primaryButton,
                  { backgroundColor: theme.accent, opacity: pressed ? 0.85 : 1 },
                ]}>
                <Text style={[styles.primaryLabel, { color: theme.onAccent }]}>
                  Allow camera
                </Text>
              </Pressable>
            </View>
          ) : (
            <View style={[styles.cameraFrame, { borderColor: theme.border }]}>
              <CameraView
                ref={cameraRef}
                style={styles.camera}
                facing="front"
                onCameraReady={() => setReady(true)}
                onMountError={() => setMessage('The camera could not start on this device.')}
              />
            </View>
          )}

          {message && (
            <Text style={[styles.message, { color: theme.textSecondary }]}>{message}</Text>
          )}

          <Pressable
            accessibilityRole="button"
            disabled={busy || !started || !ready || permissionMissing || !permission}
            onPress={capture}
            style={({ pressed }) => [
              styles.primaryButton,
              {
                backgroundColor: theme.accent,
                opacity:
                  busy || !started || !ready || permissionMissing || !permission
                    ? 0.45
                    : pressed
                      ? 0.85
                      : 1,
              },
            ]}>
            <Text style={[styles.primaryLabel, { color: theme.onAccent }]}>
              {busy ? 'Checking…' : stage === 1 ? 'Take first photo' : 'Take second photo'}
            </Text>
          </Pressable>

          <Text style={[styles.note, { color: theme.textMuted }]}>
            Two separate face photos are required. Habit can’t tell people apart and never
            stores the photos — only a face count is read on this phone.
          </Text>
        </View>
      </SafeAreaView>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  safe: { flex: 1 },
  content: {
    flex: 1,
    paddingHorizontal: Spacing.four,
    paddingTop: Spacing.three,
    paddingBottom: Spacing.four,
    gap: Spacing.three,
    width: '100%',
    maxWidth: MaxContentWidth,
    alignSelf: 'center',
  },
  header: { flexDirection: 'row', alignItems: 'flex-start', gap: Spacing.three },
  headerText: { flex: 1 },
  eyebrow: {
    fontSize: Type.eyebrow.fontSize,
    lineHeight: Type.eyebrow.lineHeight,
    letterSpacing: Type.eyebrow.letterSpacing,
    fontWeight: '700',
  },
  title: {
    fontSize: Type.title.fontSize,
    lineHeight: Type.title.lineHeight,
    fontWeight: '700',
    marginTop: Spacing.one,
  },
  closeButton: {
    paddingHorizontal: Spacing.three,
    paddingVertical: Spacing.two,
    borderRadius: Radius.pill,
  },
  closeLabel: { fontSize: Type.small.fontSize, fontWeight: '600' },
  stagePill: {
    alignSelf: 'flex-start',
    paddingHorizontal: Spacing.three,
    paddingVertical: Spacing.one,
    borderRadius: Radius.pill,
  },
  stageLabel: { fontSize: 10, fontWeight: '700', letterSpacing: 1 },
  instructions: { fontSize: Type.small.fontSize, lineHeight: Type.small.lineHeight },
  cameraFrame: {
    height: 380,
    borderRadius: Radius.lg,
    borderWidth: 1,
    overflow: 'hidden',
  },
  camera: { flex: 1 },
  cameraPlaceholder: {
    height: 240,
    borderRadius: Radius.lg,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  permissionCard: {
    borderWidth: 1,
    borderRadius: Radius.lg,
    padding: Spacing.three,
    gap: Spacing.three,
  },
  permissionTitle: { fontSize: Type.bodyStrong.fontSize, fontWeight: '700' },
  message: { fontSize: Type.small.fontSize, lineHeight: Type.small.lineHeight },
  primaryButton: {
    paddingVertical: Spacing.three,
    borderRadius: Radius.md,
    alignItems: 'center',
  },
  primaryLabel: { fontSize: Type.body.fontSize, fontWeight: '700' },
  note: { fontSize: Type.micro.fontSize, lineHeight: 17 },
});
