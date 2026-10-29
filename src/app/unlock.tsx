import { router } from 'expo-router';
import { useCallback, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { formatDuration, useCountdown } from '@/components/countdown';
import { MaxContentWidth, Radius, Spacing, Type } from '@/constants/theme';
import { useProtection } from '@/hooks/use-protection';
import { useTheme } from '@/hooks/use-theme';
import {
  cancelUnlockTimer,
  debugExitStrict,
  requestUnlockTimer,
  setFastCooldown,
  startCharacterChallenge,
  submitCharacterChallenge,
  type CharacterChallenge,
} from '@/native/habit';

const SLAB_LENGTHS = [100, 200, 300] as const;

function normalize(value: string): string {
  return value.replace(/\s+/g, '');
}

function commonPrefixLength(a: string, b: string): number {
  let index = 0;
  while (index < a.length && index < b.length && a[index] === b[index]) index += 1;
  return index;
}

function chunked(text: string): string {
  return text.replace(/(.{10})/g, '$1 ').trim();
}

export default function UnlockScreen() {
  const theme = useTheme();
  const { snapshot, refresh } = useProtection();

  const [challenge, setChallenge] = useState<CharacterChallenge | null>(null);
  const [typed, setTyped] = useState('');
  const [feedback, setFeedback] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const submitting = useRef(false);

  const unlock = snapshot?.unlock;
  const cooldownRemaining = useCountdown(unlock?.cooldownRemainingMs ?? 0);
  const offWindowRemaining = useCountdown(unlock?.offWindowRemainingMs ?? 0);
  const lockRemaining = useCountdown(unlock?.lockRemainingMs ?? 0);

  const sendAttempt = useCallback(
    async (value: string, challengeId: string) => {
      if (submitting.current) return;
      submitting.current = true;
      try {
        const result = await submitCharacterChallenge(challengeId, value);
        if (!result) return;
        if (result.success) {
          setChallenge(null);
          setTyped('');
          setFeedback(null);
          await refresh();
          Alert.alert('Unlocked', 'Strict mode is off and protection is paused.', [
            { text: 'OK', onPress: () => router.back() },
          ]);
          return;
        }
        setTyped('');
        if (result.text && result.challengeId) {
          setChallenge({
            challengeId: result.challengeId,
            text: result.text,
            length: result.length,
          });
          setFeedback(
            result.lockRemainingMs > 0
              ? 'Too many mistakes — attempts paused briefly. A new string is ready.'
              : 'Mistake — here is a brand-new string.'
          );
        } else {
          setChallenge(null);
          setFeedback('Too many mistakes. Unlock attempts are paused for a moment.');
        }
        await refresh();
      } finally {
        submitting.current = false;
      }
    },
    [refresh]
  );

  const beginChallenge = useCallback(
    async (length: number) => {
      if (starting) return;
      setStarting(true);
      setFeedback(null);
      try {
        const next = await startCharacterChallenge(length);
        if (!next) {
          setFeedback('Too many attempts right now. Wait for the pause to end, then try again.');
          await refresh();
          return;
        }
        setChallenge(next);
        setTyped('');
      } finally {
        setStarting(false);
      }
    },
    [refresh, starting]
  );

  const handleChange = (value: string) => {
    if (!challenge) return;
    const growth = value.length - typed.length;
    if (growth > 5) {
      setTyped('');
      setFeedback('Pasting is not allowed — type the string character by character.');
      return;
    }
    setTyped(value);
    const normalized = normalize(value);
    if (normalized.length === 0) return;
    const correctPrefix = challenge.text.startsWith(normalized);
    if (!correctPrefix || normalized.length >= challenge.length) {
      void sendAttempt(normalized, challenge.challengeId);
    }
  };

  const startTimer = () => {
    const label = snapshot?.unlock.fastCooldown && snapshot.debugBuild ? '1 minute' : '3 hours';
    Alert.alert(
      'Start the cooldown?',
      `Protection stays fully on for ${label}. When it ends, protection turns off and turns back on by itself about 10 minutes later. You can cancel the cooldown at any time.`,
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
        text: 'Cancel cooldown',
        style: 'destructive',
        onPress: async () => {
          await cancelUnlockTimer();
          await refresh();
        },
      },
    ]);
  };

  const toggleFastCooldown = async (value: boolean) => {
    await setFastCooldown(value);
    await refresh();
  };

  const exitStrictDebug = () => {
    Alert.alert(
      'Exit Strict now?',
      'Debug builds only. This unlocks immediately so you can test.',
      [
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
      ]
    );
  };

  if (!snapshot || !unlock) {
    return (
      <View style={[styles.loading, { backgroundColor: theme.background }]}>
        <ActivityIndicator color={theme.accent} />
      </View>
    );
  }

  const locked = unlock.lockRemainingMs > 0;
  const normalizedTyped = normalize(typed);
  const matched = challenge ? commonPrefixLength(normalizedTyped, challenge.text) : 0;
  const percent = challenge ? Math.min(100, (matched / challenge.length) * 100) : 0;
  const cooldownLabel =
    unlock.fastCooldown && snapshot.debugBuild ? '1 minute' : '3 hours';
  const alreadyNormal = snapshot.mode === 'normal' && !snapshot.protectionEnabled;

  return (
    <View style={[styles.screen, { backgroundColor: theme.background }]}>
      <SafeAreaView style={styles.safe} edges={['top', 'left', 'right', 'bottom']}>
        <ScrollView
          contentContainerStyle={styles.content}
          showsVerticalScrollIndicator={false}
          keyboardShouldPersistTaps="handled">
          <View style={styles.header}>
            <View style={styles.headerText}>
              <Text style={[styles.eyebrow, { color: theme.accent }]}>UNLOCK</Text>
              <Text style={[styles.title, { color: theme.text }]}>Lower protection</Text>
            </View>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Close unlock screen"
              onPress={() => router.back()}
              style={({ pressed }) => [
                styles.closeButton,
                { backgroundColor: theme.surfaceAlt, opacity: pressed ? 0.7 : 1 },
              ]}>
              <Text style={[styles.closeLabel, { color: theme.textSecondary }]}>Close</Text>
            </Pressable>
          </View>

          <Text style={[styles.intro, { color: theme.textSecondary }]}>
            {alreadyNormal
              ? 'Protection is already off and the mode is Normal.'
              : 'Strict and Aggressive modes keep protection on. Complete either method below to unlock.'}
          </Text>

          {locked && (
            <View
              style={[
                styles.card,
                { backgroundColor: theme.dangerSoft, borderColor: theme.dangerBorder },
              ]}>
              <Text style={[styles.cardTitle, { color: theme.danger }]}>
                Too many mistakes
              </Text>
              <Text style={[styles.body, { color: theme.textSecondary }]}>
                Unlock attempts are paused for {formatDuration(lockRemaining)}. This pause is
                never permanent.
              </Text>
            </View>
          )}

          {unlock.offWindowActive && (
            <View
              style={[
                styles.card,
                { backgroundColor: theme.accentSoft, borderColor: theme.accent },
              ]}>
              <Text style={[styles.cardTitle, { color: theme.text }]}>Protection is off</Text>
              <Text style={[styles.bigCountdown, { color: theme.text }]}>
                {formatDuration(offWindowRemaining)}
              </Text>
              <Text style={[styles.body, { color: theme.textSecondary }]}>
                It turns back on automatically when this reaches zero. You can turn it on now
                from the home screen.
              </Text>
            </View>
          )}

          <View
            style={[
              styles.card,
              { backgroundColor: theme.surface, borderColor: theme.border },
            ]}>
            <Text style={[styles.eyebrow, { color: theme.textMuted }]}>METHOD A</Text>
            <Text style={[styles.cardTitle, { color: theme.text }]}>
              Type a long random string
            </Text>
            <Text style={[styles.body, { color: theme.textSecondary }]}>
              Pick a slab and type it exactly. One wrong character discards it and generates a
              brand-new string. Spaces and line breaks are ignored.
            </Text>

            {!challenge ? (
              <View style={styles.lengthRow}>
                {SLAB_LENGTHS.map((length) => (
                  <Pressable
                    key={length}
                    accessibilityRole="button"
                    disabled={locked || starting}
                    onPress={() => beginChallenge(length)}
                    style={({ pressed }) => [
                      styles.lengthButton,
                      {
                        backgroundColor: theme.surfaceAlt,
                        borderColor: theme.border,
                        opacity: locked ? 0.45 : pressed ? 0.7 : 1,
                      },
                    ]}>
                    <Text style={[styles.lengthButtonLabel, { color: theme.text }]}>
                      {length}
                    </Text>
                    <Text style={[styles.lengthButtonHint, { color: theme.textMuted }]}>
                      chars
                    </Text>
                  </Pressable>
                ))}
              </View>
            ) : (
              <View style={styles.challengeArea}>
                <View
                  style={[
                    styles.slabBox,
                    { backgroundColor: theme.surfaceAlt, borderColor: theme.border },
                  ]}>
                  <Text
                    selectable={false}
                    style={[styles.slab, { color: theme.text }]}>
                    {chunked(challenge.text)}
                  </Text>
                </View>

                <View style={styles.progressHeader}>
                  <Text style={[styles.progressLabel, { color: theme.textSecondary }]}>
                    Typed {normalizedTyped.length} / {challenge.length}
                  </Text>
                  <Text style={[styles.progressLabel, { color: theme.accent }]}>
                    {matched} in a row
                  </Text>
                </View>
                <View style={[styles.track, { backgroundColor: theme.surfaceAlt }]}>
                  <View
                    style={[
                      styles.fill,
                      { width: `${percent}%`, backgroundColor: theme.accent },
                    ]}
                  />
                </View>

                <TextInput
                  value={typed}
                  onChangeText={handleChange}
                  editable={!locked}
                  multiline
                  autoCapitalize="none"
                  autoCorrect={false}
                  spellCheck={false}
                  autoComplete="off"
                  textContentType="none"
                  importantForAutofill="no"
                  keyboardType="visible-password"
                  contextMenuHidden
                  selectTextOnFocus={false}
                  placeholder="Type the string here"
                  placeholderTextColor={theme.textMuted}
                  style={[
                    styles.input,
                    {
                      color: theme.text,
                      backgroundColor: theme.surfaceAlt,
                      borderColor: theme.border,
                    },
                  ]}
                />

                {feedback && (
                  <Text style={[styles.feedback, { color: theme.danger }]}>{feedback}</Text>
                )}

                <Pressable
                  accessibilityRole="button"
                  onPress={() => beginChallenge(challenge.length)}
                  style={({ pressed }) => [styles.linkButton, { opacity: pressed ? 0.6 : 1 }]}>
                  <Text style={[styles.linkLabel, { color: theme.textSecondary }]}>
                    Get a different string
                  </Text>
                </Pressable>
              </View>
            )}

            {!challenge && feedback && (
              <Text style={[styles.feedback, { color: theme.danger }]}>{feedback}</Text>
            )}
            <Text style={[styles.hint, { color: theme.textMuted }]}>
              Mistakes today: {unlock.failuresToday}
            </Text>
          </View>

          <View
            style={[
              styles.card,
              { backgroundColor: theme.surface, borderColor: theme.border },
            ]}>
            <Text style={[styles.eyebrow, { color: theme.textMuted }]}>METHOD B</Text>
            <Text style={[styles.cardTitle, { color: theme.text }]}>Wait out a cooldown</Text>
            <Text style={[styles.body, { color: theme.textSecondary }]}>
              Protection stays fully active for the whole {cooldownLabel}. When the countdown
              ends you get a notification, protection turns off, and it turns back on by itself
              after about 10 minutes.
            </Text>

            {unlock.cooldownActive ? (
              <>
                <Text style={[styles.bigCountdown, { color: theme.text }]}>
                  {formatDuration(cooldownRemaining)}
                </Text>
                <Text style={[styles.progressLabel, { color: theme.textMuted }]}>
                  remaining — protection is still on
                </Text>
                <Pressable
                  accessibilityRole="button"
                  onPress={cancelTimer}
                  style={({ pressed }) => [
                    styles.secondaryButton,
                    {
                      borderColor: theme.border,
                      backgroundColor: theme.surfaceAlt,
                      opacity: pressed ? 0.7 : 1,
                    },
                  ]}>
                  <Text style={[styles.secondaryLabel, { color: theme.textSecondary }]}>
                    Cancel cooldown
                  </Text>
                </Pressable>
              </>
            ) : (
              <Pressable
                accessibilityRole="button"
                disabled={locked}
                onPress={startTimer}
                style={({ pressed }) => [
                  styles.primaryButton,
                  {
                    backgroundColor: theme.accent,
                    opacity: locked ? 0.45 : pressed ? 0.85 : 1,
                  },
                ]}>
                <Text style={[styles.primaryLabel, { color: theme.onAccent }]}>
                  Start {cooldownLabel} cooldown
                </Text>
              </Pressable>
            )}
          </View>

          <View
            style={[
              styles.card,
              { backgroundColor: theme.surface, borderColor: theme.border },
            ]}>
            <Text style={[styles.eyebrow, { color: theme.textMuted }]}>METHOD C</Text>
            <Text style={[styles.cardTitle, { color: theme.text }]}>
              Face verification (two people)
            </Text>
            <Text style={[styles.body, { color: theme.textSecondary }]}>
              Two face checks, one after another. Both people must be present.
            </Text>
            <Text style={[styles.hint, { color: theme.textMuted }]}>
              Habit can’t tell people apart — it requires two separate faces.
            </Text>
            <Pressable
              accessibilityRole="button"
              disabled={locked}
              onPress={() => router.push('/face-unlock')}
              style={({ pressed }) => [
                styles.primaryButton,
                {
                  backgroundColor: theme.accent,
                  opacity: locked ? 0.45 : pressed ? 0.85 : 1,
                },
              ]}>
              <Text style={[styles.primaryLabel, { color: theme.onAccent }]}>
                Start face check
              </Text>
            </Pressable>
          </View>

          {snapshot.debugBuild && (
            <View
              style={[
                styles.card,
                { backgroundColor: theme.surfaceAlt, borderColor: theme.border },
              ]}>
              <Text style={[styles.eyebrow, { color: theme.textMuted }]}>DEBUG BUILD ONLY</Text>
              <View style={styles.debugRow}>
                <View style={styles.debugText}>
                  <Text style={[styles.cardTitle, { color: theme.text }]}>
                    Short cooldown
                  </Text>
                  <Text style={[styles.body, { color: theme.textSecondary }]}>
                    Make the cooldown about 1 minute instead of 3 hours.
                  </Text>
                </View>
                <Switch
                  value={unlock.fastCooldown}
                  onValueChange={toggleFastCooldown}
                  trackColor={{ false: theme.border, true: theme.accent }}
                  thumbColor={theme.surface}
                />
              </View>
              <Pressable
                accessibilityRole="button"
                onPress={exitStrictDebug}
                style={({ pressed }) => [
                  styles.secondaryButton,
                  {
                    borderColor: theme.dangerBorder,
                    backgroundColor: theme.dangerSoft,
                    opacity: pressed ? 0.7 : 1,
                  },
                ]}>
                <Text style={[styles.secondaryLabel, { color: theme.danger }]}>
                  Exit Strict now
                </Text>
              </Pressable>
              <Text style={[styles.hint, { color: theme.textMuted }]}>
                These tools do not exist in release builds.
              </Text>
            </View>
          )}

          <Text style={[styles.footer, { color: theme.textMuted }]}>
            There is no permanent lock-out: the cooldown always works, and mistakes only pause
            the character challenge for a few minutes.
          </Text>
        </ScrollView>
      </SafeAreaView>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  safe: { flex: 1 },
  loading: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  content: {
    paddingHorizontal: Spacing.four,
    paddingTop: Spacing.three,
    paddingBottom: Spacing.five,
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
  intro: { fontSize: Type.small.fontSize, lineHeight: Type.small.lineHeight },
  card: {
    borderWidth: 1,
    borderRadius: Radius.lg,
    padding: Spacing.three,
    gap: Spacing.two,
  },
  cardTitle: { fontSize: Type.bodyStrong.fontSize, fontWeight: '700' },
  body: { fontSize: Type.small.fontSize, lineHeight: Type.small.lineHeight },
  bigCountdown: {
    fontSize: 34,
    lineHeight: 40,
    fontWeight: '700',
    fontVariant: ['tabular-nums'],
  },
  lengthRow: { flexDirection: 'row', gap: Spacing.two, marginTop: Spacing.two },
  lengthButton: {
    flex: 1,
    borderWidth: 1,
    borderRadius: Radius.md,
    paddingVertical: Spacing.two,
    alignItems: 'center',
  },
  lengthButtonLabel: { fontSize: Type.subtitle.fontSize, fontWeight: '700' },
  lengthButtonHint: { fontSize: Type.micro.fontSize, marginTop: 2 },
  challengeArea: { gap: Spacing.two, marginTop: Spacing.two },
  slabBox: {
    borderWidth: 1,
    borderRadius: Radius.md,
    padding: Spacing.three,
  },
  slab: {
    fontSize: 13,
    lineHeight: 20,
    letterSpacing: 1.1,
    fontFamily: 'monospace',
  },
  progressHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: Spacing.one,
  },
  progressLabel: { fontSize: Type.micro.fontSize, fontWeight: '600' },
  track: { height: 6, borderRadius: Radius.pill, overflow: 'hidden' },
  fill: { height: 6, borderRadius: Radius.pill },
  input: {
    minHeight: 92,
    borderWidth: 1,
    borderRadius: Radius.md,
    padding: Spacing.three,
    fontSize: 14,
    lineHeight: 20,
    letterSpacing: 1,
    fontFamily: 'monospace',
    textAlignVertical: 'top',
    marginTop: Spacing.one,
  },
  feedback: { fontSize: Type.small.fontSize, lineHeight: Type.small.lineHeight },
  hint: { fontSize: Type.micro.fontSize, lineHeight: 17 },
  linkButton: { alignSelf: 'flex-start', paddingVertical: Spacing.one },
  linkLabel: { fontSize: Type.small.fontSize, fontWeight: '600' },
  primaryButton: {
    paddingVertical: Spacing.three,
    borderRadius: Radius.md,
    alignItems: 'center',
    marginTop: Spacing.two,
  },
  primaryLabel: { fontSize: Type.body.fontSize, fontWeight: '700' },
  secondaryButton: {
    paddingVertical: Spacing.two,
    paddingHorizontal: Spacing.three,
    borderRadius: Radius.pill,
    borderWidth: 1,
    alignItems: 'center',
    alignSelf: 'flex-start',
    marginTop: Spacing.two,
  },
  secondaryLabel: { fontSize: Type.small.fontSize, fontWeight: '600' },
  debugRow: { flexDirection: 'row', alignItems: 'center', gap: Spacing.three },
  debugText: { flex: 1, gap: 2 },
  footer: {
    fontSize: Type.micro.fontSize,
    lineHeight: 18,
    textAlign: 'center',
    paddingHorizontal: Spacing.three,
  },
});
