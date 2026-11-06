import { MaterialCommunityIcons } from '@expo/vector-icons';
import { router } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Animated,
  Keyboard,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';

import {
  NeuCard,
  NeuWell,
  PrimaryGlowButton,
  SecondaryRaisedButton,
} from '@/components/neumorphic';
import { FontNames, Palette, Radii, Shadows, Space, Typography } from '@/constants/theme';
import { useProtection } from '@/hooks/use-protection';
import {
  startCharacterChallenge,
  submitCharacterChallenge,
  type CharacterChallenge,
} from '@/native/habit';

const SLAB_LENGTHS = [100, 200, 300] as const;
type SlabLength = (typeof SLAB_LENGTHS)[number];

const TOKEN_LENGTH = 10;

/** Tallest the challenge stream may get: it scrolls instead of pushing the input dock away. */
const GRID_MAX_HEIGHT = 230;
const GRID_MAX_HEIGHT_KEYBOARD = 124;

/** The lock always uses the length saved in the Configure Protection Lock sheet. */
function lockedLength(value: number | undefined): SlabLength {
  return value === 100 || value === 300 ? value : 200;
}

type Mistake = {
  wrongChar: string;
  expectedChar: string;
  nextChallenge: CharacterChallenge;
};

function normalize(value: string): string {
  return value.replace(/\s+/g, '');
}

function tokenize(text: string): string[] {
  const tokens: string[] = [];
  for (let index = 0; index < text.length; index += TOKEN_LENGTH) {
    tokens.push(text.slice(index, index + TOKEN_LENGTH));
  }
  return tokens;
}

export default function UnlockCodeScreen() {
  const { snapshot } = useProtection();
  const insets = useSafeAreaInsets();
  const configuredLength = lockedLength(snapshot?.configuredChallengeLength);
  const [challenge, setChallenge] = useState<CharacterChallenge | null>(null);
  const [typed, setTyped] = useState('');
  const [feedback, setFeedback] = useState<string | null>(null);
  const [mistake, setMistake] = useState<Mistake | null>(null);
  const [starting, setStarting] = useState(false);
  const [keyboardHeight, setKeyboardHeight] = useState(0);
  const [gridViewportHeight, setGridViewportHeight] = useState(0);
  /** Laid-out heights of the root view, to see whether Android itself shrank for the IME. */
  const [screenHeight, setScreenHeight] = useState(0);
  const [idleScreenHeight, setIdleScreenHeight] = useState(0);
  const [cursorOpacity] = useState(() => new Animated.Value(1));
  const submitting = useRef(false);
  const startingChallenge = useRef(false);
  const bootstrapped = useRef(false);
  const tokenScrollRef = useRef<ScrollView>(null);
  const tokenOffsets = useRef<Record<number, number>>({});

  const keyboardVisible = keyboardHeight > 0;

  /* ------------------------------------------------------------------ */
  /* Keyboard: compact the header area and keep the input dock above it  */
  /* ------------------------------------------------------------------ */

  useEffect(() => {
    const show = Keyboard.addListener('keyboardDidShow', (event) => {
      setKeyboardHeight(event.endCoordinates?.height ?? 0);
    });
    const hide = Keyboard.addListener('keyboardDidHide', () => setKeyboardHeight(0));
    return () => {
      show.remove();
      hide.remove();
    };
  }, []);

  // Edge-to-edge Android windows do not resize for the keyboard, older configurations do.
  // Android reports the IME height without the system bar, so the bar inset is added here;
  // whatever the OS already took away through a resize is subtracted again. The dock then
  // sits directly on top of the keyboard in both cases.
  const layoutShrink =
    idleScreenHeight > 0 ? Math.max(0, idleScreenHeight - screenHeight) : 0;
  const coveredByKeyboard = keyboardVisible
    ? Math.max(0, keyboardHeight + insets.bottom - layoutShrink)
    : 0;

  useEffect(() => {
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(cursorOpacity, {
          toValue: 0.15,
          duration: 520,
          useNativeDriver: true,
        }),
        Animated.timing(cursorOpacity, {
          toValue: 1,
          duration: 520,
          useNativeDriver: true,
        }),
      ])
    );
    loop.start();
    return () => loop.stop();
  }, [cursorOpacity]);

  /* ------------------------------------------------------------------ */
  /* Challenge lifecycle                                                 */
  /* ------------------------------------------------------------------ */

  const beginChallenge = useCallback(async (nextLength: SlabLength) => {
    if (startingChallenge.current) return;
    startingChallenge.current = true;
    setStarting(true);
    setFeedback(null);
    try {
      const next = await startCharacterChallenge(nextLength);
      if (!next) {
        setChallenge(null);
        setFeedback('Could not start a challenge right now. Wait a moment, then try again.');
        return;
      }
      setChallenge(next);
      setTyped('');
    } finally {
      startingChallenge.current = false;
      setStarting(false);
    }
  }, []);

  useEffect(() => {
    if (bootstrapped.current || !snapshot) return;
    bootstrapped.current = true;
    void beginChallenge(configuredLength);
  }, [beginChallenge, configuredLength, snapshot]);

  const sendAttempt = useCallback(
    async (value: string, challengeId: string, mismatch?: { wrongChar: string; expectedChar: string }) => {
      if (submitting.current) return;
      submitting.current = true;
      try {
        const result = await submitCharacterChallenge(challengeId, value);
        if (!result) return;
        if (result.success) {
          setChallenge(null);
          setTyped('');
          setFeedback(null);
          setMistake(null);
          Alert.alert('Unlocked', 'Strict mode is off and protection is paused.', [
            { text: 'OK', onPress: () => router.dismissTo('/') },
          ]);
          return;
        }
        setTyped('');
        if (result.text && result.challengeId) {
          const nextChallenge: CharacterChallenge = {
            challengeId: result.challengeId,
            text: result.text,
            length: result.length,
          };
          if (mismatch) {
            setMistake({ ...mismatch, nextChallenge });
          } else {
            setChallenge(nextChallenge);
          }
        } else {
          setChallenge(null);
          setFeedback('Too many mistakes. Unlock attempts are paused for a moment.');
        }
      } finally {
        submitting.current = false;
      }
    },
    []
  );

  const handleChange = (value: string) => {
    if (!challenge || mistake) return;
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
    if (!correctPrefix) {
      const index = normalized.split('').findIndex((char, i) => char !== challenge.text[i]);
      void sendAttempt(normalized, challenge.challengeId, {
        wrongChar: normalized[index] ?? '',
        expectedChar: challenge.text[index] ?? '',
      });
      return;
    }
    if (normalized.length >= challenge.length) {
      void sendAttempt(normalized, challenge.challengeId);
    }
  };

  const tryAgain = () => {
    if (mistake) {
      setChallenge(mistake.nextChallenge);
    }
    setTyped('');
    setFeedback(null);
    setMistake(null);
  };

  const leaveLocked = () => {
    setMistake(null);
    setTyped('');
    router.dismissTo('/');
  };

  /* ------------------------------------------------------------------ */
  /* Derived typing state                                                */
  /* ------------------------------------------------------------------ */

  const typedNormalized = normalize(typed);
  const typedLength = typedNormalized.length;
  const targetLength = challenge?.length ?? configuredLength;
  const percent =
    targetLength > 0 ? Math.round((Math.min(typedLength, targetLength) / targetLength) * 100) : 0;
  const tokens = challenge ? tokenize(challenge.text) : [];
  const activeTokenIndex =
    tokens.length > 0
      ? Math.min(Math.floor(typedLength / TOKEN_LENGTH), tokens.length - 1)
      : 0;

  // The current 10-character chunk, mirrored inside the input well so every keystroke is
  // visible without reading the (possibly scrolled) stream above.
  const typedChunkStart = Math.floor(typedLength / TOKEN_LENGTH) * TOKEN_LENGTH;
  const chunkTyped = typedNormalized.slice(typedChunkStart, typedChunkStart + TOKEN_LENGTH);
  const chunkTarget = challenge
    ? challenge.text.slice(typedChunkStart, typedChunkStart + TOKEN_LENGTH)
    : '';
  const chunkRemaining = chunkTarget.slice(chunkTyped.length);
  const nextChar = chunkRemaining.slice(0, 1);
  const remainingChars = chunkRemaining.slice(1);
  const chunkNumber = tokens.length > 0 ? Math.min(typedChunkStart / TOKEN_LENGTH + 1, tokens.length) : 0;

  // Keep the active amber chunk (and the next row) centred in the stream viewport.
  useEffect(() => {
    if (!challenge || gridViewportHeight === 0) return;
    const offset = tokenOffsets.current[activeTokenIndex];
    if (offset == null) return;
    tokenScrollRef.current?.scrollTo({
      y: Math.max(0, offset - gridViewportHeight / 2 + 14),
      animated: true,
    });
  }, [activeTokenIndex, challenge, gridViewportHeight, keyboardVisible]);

  useEffect(() => {
    tokenOffsets.current = {};
  }, [challenge?.challengeId]);

  const gridMaxHeight = keyboardVisible ? GRID_MAX_HEIGHT_KEYBOARD : GRID_MAX_HEIGHT;

  return (
    <View
      style={styles.screen}
      onLayout={(event) => {
        const next = event.nativeEvent.layout.height;
        setScreenHeight(next);
        // A resized window is never taller than the keyboard-free one (portrait-locked).
        if (!keyboardVisible) setIdleScreenHeight((previous) => Math.max(previous, next));
      }}>
      <SafeAreaView style={styles.safe} edges={['top', 'left', 'right']}>
        <View
          style={[
            styles.body,
            { gap: keyboardVisible ? Space.sm : Space.md },
            coveredByKeyboard > 0 && { paddingBottom: coveredByKeyboard },
          ]}>
          <View style={styles.header}>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Go back"
              onPress={() => router.back()}
              style={({ pressed }) => [styles.backButton, pressed && styles.pressed]}>
              <MaterialCommunityIcons name="chevron-left" size={26} color={Palette.textPrimary} />
            </Pressable>
            <Text style={styles.headerTitle}>Type to Unlock</Text>
          </View>

          {/* Read-only on purpose: the configured length is locked while protection is on.
              While typing it collapses to one slim badge to give the keyboard the room. */}
          {keyboardVisible ? (
            <View style={styles.compactLockRow}>
              <View style={styles.compactLockBadge}>
                <MaterialCommunityIcons name="lock" size={12} color={Palette.amber} />
                <Text style={styles.compactLockLabel}>
                  Locked at {configuredLength} characters
                </Text>
              </View>
            </View>
          ) : (
            <>
              <View style={styles.selectorTrack}>
                {SLAB_LENGTHS.map((option) => {
                  const active = option === configuredLength;
                  return (
                    <View
                      key={option}
                      accessibilityState={{ selected: active, disabled: !active }}
                      style={[
                        styles.selectorCard,
                        active && styles.selectorCardActive,
                        !active && styles.selectorCardLocked,
                      ]}>
                      <View style={styles.selectorValueRow}>
                        {active && (
                          <MaterialCommunityIcons name="lock" size={11} color={Palette.amber} />
                        )}
                        <Text style={[styles.selectorValue, active && styles.selectorValueActive]}>
                          {option}
                        </Text>
                      </View>
                      <Text
                        style={[styles.selectorCaption, active && styles.selectorCaptionActive]}>
                        CHARACTERS
                      </Text>
                    </View>
                  );
                })}
              </View>
              <Text style={styles.lockedNote}>
                Length locked at {configuredLength} characters by your protection setup.
              </Text>
            </>
          )}

          <NeuCard style={[styles.challengeCard, keyboardVisible && styles.challengeCardCompact]}>
            <View style={styles.challengeHeader}>
              <View style={styles.challengeLabelRow}>
                <MaterialCommunityIcons
                  name="lock-outline"
                  size={14}
                  color={Palette.textSecondary}
                />
                <Text style={styles.challengeLabel}>TARGET CHALLENGE STREAM</Text>
              </View>
              <View style={styles.strictBadge}>
                <Text style={styles.strictBadgeLabel}>STRICT VERIFICATION</Text>
              </View>
            </View>

            {challenge ? (
              <ScrollView
                ref={tokenScrollRef}
                style={[styles.tokenScroll, { maxHeight: gridMaxHeight }]}
                contentContainerStyle={styles.tokenGridContent}
                showsVerticalScrollIndicator
                keyboardShouldPersistTaps="always"
                onLayout={(event) => setGridViewportHeight(event.nativeEvent.layout.height)}>
                <View style={styles.tokenGrid}>
                  {tokens.map((token, index) => {
                    const start = index * TOKEN_LENGTH;
                    const complete = start + token.length <= typedLength;
                    const active = !complete && start <= typedLength;
                    return (
                      <Text
                        key={`${start}-${token}`}
                        onLayout={(event) => {
                          tokenOffsets.current[index] = event.nativeEvent.layout.y;
                        }}
                        style={[
                          styles.token,
                          complete && styles.tokenComplete,
                          active && styles.tokenActive,
                        ]}>
                        {token}
                      </Text>
                    );
                  })}
                </View>
              </ScrollView>
            ) : (
              <View style={styles.challengePlaceholder}>
                {starting ? (
                  <ActivityIndicator color={Palette.amber} />
                ) : (
                  <Text style={styles.placeholderText}>
                    Preparing your locked challenge…
                  </Text>
                )}
              </View>
            )}
          </NeuCard>

          <View style={styles.spacer} />

          <View
            style={[
              styles.dock,
              { paddingBottom: keyboardVisible ? Space.sm : Math.max(insets.bottom, Space.md) },
            ]}>
            {feedback && (
              <Text style={styles.feedback} numberOfLines={2}>
                {feedback}
              </Text>
            )}

            <NeuWell padded={false} style={styles.inputWell}>
              <View style={styles.streamRow}>
                <Text style={styles.streamTyped} numberOfLines={1}>
                  {chunkTyped}
                </Text>
                <Animated.View style={[styles.streamCursor, { opacity: cursorOpacity }]} />
                <Text style={styles.streamNext} numberOfLines={1}>
                  {nextChar ? (
                    <Text style={styles.streamNextChar}>{nextChar}</Text>
                  ) : null}
                  {remainingChars}
                </Text>
              </View>
              <TextInput
                value={typed}
                onChangeText={handleChange}
                editable={challenge != null}
                autoCapitalize="none"
                autoCorrect={false}
                spellCheck={false}
                autoComplete="off"
                textContentType="none"
                importantForAutofill="no"
                keyboardType="visible-password"
                contextMenuHidden
                selectTextOnFocus={false}
                placeholder="Type the shown text here"
                placeholderTextColor={Palette.textMuted}
                style={styles.input}
              />
              <View style={styles.chunkMetaRow}>
                <Text style={styles.chunkMeta}>
                  CHUNK <Text style={styles.chunkMetaStrong}>{chunkNumber || '—'}</Text>
                  {tokens.length > 0 ? ` / ${tokens.length}` : ''}
                </Text>
                <Text style={styles.chunkMeta}>
                  <Text style={styles.chunkMetaStrong}>{chunkTyped.length}</Text> / {TOKEN_LENGTH}{' '}
                  IN CHUNK
                </Text>
              </View>
            </NeuWell>

            <View style={styles.progressHeader}>
              <Text style={styles.progressLabel}>
                Typed {typedLength} / {targetLength}
              </Text>
              <Text style={styles.progressPercent}>{percent}%</Text>
            </View>
            <View style={styles.track}>
              <View style={[styles.fill, { width: `${percent}%` }]} />
            </View>

            {!keyboardVisible && (
              <>
                <View style={styles.warningRow}>
                  <MaterialCommunityIcons name="alert-outline" size={14} color={Palette.amber} />
                  <Text style={styles.warningText}>One mistake restarts with a new text.</Text>
                </View>

                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel="Leave locked and return home"
                  onPress={() => router.dismissTo('/')}
                  style={({ pressed }) => [styles.leaveButton, pressed && styles.pressed]}>
                  <MaterialCommunityIcons
                    name="lock-outline"
                    size={16}
                    color={Palette.textSecondary}
                  />
                  <Text style={styles.leaveLabel}>Leave locked</Text>
                </Pressable>
              </>
            )}
          </View>
        </View>
      </SafeAreaView>

      <Modal
        visible={mistake != null}
        transparent
        animationType="fade"
        statusBarTranslucent
        onRequestClose={tryAgain}>
        <View style={styles.modalBackdrop}>
          <View style={styles.modalCard}>
            <View style={styles.modalIconTile}>
              <MaterialCommunityIcons name="alert" size={24} color={Palette.danger} />
            </View>

            <Text style={styles.modalTitle}>Mistake Detected</Text>

            <View style={styles.comparisonRow}>
              <Text style={styles.comparisonText}>You typed </Text>
              <View style={[styles.keycap, styles.keycapWrong]}>
                <Text style={[styles.keycapLabel, styles.keycapLabelWrong]}>
                  [{mistake?.wrongChar}]
                </Text>
              </View>
              <Text style={styles.comparisonText}> instead of </Text>
              <View style={[styles.keycap, styles.keycapExpected]}>
                <Text style={[styles.keycapLabel, styles.keycapLabelExpected]}>
                  [{mistake?.expectedChar}]
                </Text>
              </View>
              <Text style={styles.comparisonText}>
                . The challenge restarts with a new text.
              </Text>
            </View>

            <NeuWell padded={false} style={styles.progressWell}>
              <View style={styles.progressLeft}>
                <MaterialCommunityIcons
                  name="delete-outline"
                  size={16}
                  color={Palette.danger}
                />
                <Text style={styles.progressCleared}>Progress cleared</Text>
              </View>
              <View style={styles.progressRight}>
                <Text style={styles.progressCount}>
                  0 / {mistake?.nextChallenge.length ?? 0}
                </Text>
                <View style={styles.progressDot} />
              </View>
            </NeuWell>

            <PrimaryGlowButton
              label="Try Again"
              onPress={tryAgain}
              icon={<MaterialCommunityIcons name="refresh" size={18} color={Palette.canvas} />}
              style={styles.primaryAction}
            />
            <SecondaryRaisedButton
              label="Leave Locked"
              onPress={leaveLocked}
              icon={
                <MaterialCommunityIcons
                  name="lock-outline"
                  size={16}
                  color={Palette.textPrimary}
                />
              }
              style={styles.secondaryAction}
            />
          </View>
        </View>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: Palette.canvas },
  safe: { flex: 1 },
  body: {
    flex: 1,
    paddingHorizontal: Space.margin,
    paddingTop: Space.sm,
    gap: Space.md,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: 48,
    marginBottom: Space.xs,
  },
  backButton: {
    position: 'absolute',
    left: 0,
    width: 44,
    height: 44,
    borderRadius: Radii.pill,
    backgroundColor: Palette.raised,
    boxShadow: Shadows.raisedSmall,
    alignItems: 'center',
    justifyContent: 'center',
  },
  headerTitle: {
    fontFamily: FontNames.interBold,
    fontSize: 18,
    lineHeight: 24,
    color: Palette.textPrimary,
  },
  selectorTrack: {
    flexDirection: 'row',
    gap: 4,
    padding: 4,
    height: 68,
    borderRadius: Radii.well,
    backgroundColor: Palette.well,
    boxShadow: Shadows.sunken,
  },
  selectorCard: {
    flex: 1,
    borderRadius: 16,
    borderWidth: 1.5,
    borderColor: 'transparent',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 1,
  },
  selectorCardActive: {
    backgroundColor: Palette.raised,
    borderColor: Palette.amber,
    boxShadow: Shadows.raisedSmall,
  },
  selectorCardLocked: { opacity: 0.4 },
  selectorValueRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  lockedNote: {
    ...Typography.bodySm,
    color: Palette.textMuted,
    textAlign: 'center',
    marginTop: -Space.sm,
  },
  compactLockRow: {
    flexDirection: 'row',
    justifyContent: 'center',
  },
  compactLockBadge: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    height: 26,
    paddingHorizontal: 12,
    borderRadius: Radii.pill,
    borderWidth: 1,
    borderColor: 'rgba(251, 191, 36, 0.45)',
    backgroundColor: Palette.amberHighlight,
  },
  compactLockLabel: {
    ...Typography.monoLabelSm,
    fontSize: 9,
    lineHeight: 12,
    letterSpacing: 0.6,
    color: Palette.amber,
  },
  selectorValue: {
    fontFamily: FontNames.monoBold,
    fontSize: 13,
    lineHeight: 16,
    color: Palette.textPrimary,
  },
  selectorValueActive: { color: Palette.amber },
  selectorCaption: {
    fontFamily: FontNames.monoRegular,
    fontSize: 8,
    lineHeight: 11,
    letterSpacing: 1,
    color: Palette.textMuted,
  },
  selectorCaptionActive: { color: Palette.amber },
  challengeCard: { gap: Space.md, flexShrink: 1, minHeight: 0 },
  challengeCardCompact: { padding: 14, gap: Space.sm },
  challengeHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: Space.sm,
  },
  challengeLabelRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    flexShrink: 1,
  },
  challengeLabel: {
    ...Typography.monoLabelSm,
    fontSize: 10,
    letterSpacing: 1,
    color: Palette.textSecondary,
  },
  strictBadge: {
    height: 24,
    paddingHorizontal: 8,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: 'rgba(251, 191, 36, 0.45)',
    backgroundColor: Palette.amberHighlight,
    alignItems: 'center',
    justifyContent: 'center',
  },
  strictBadgeLabel: {
    ...Typography.monoLabelSm,
    fontSize: 9,
    lineHeight: 12,
    letterSpacing: 0.8,
    color: Palette.amber,
  },
  tokenScroll: { flexShrink: 1 },
  tokenGridContent: { paddingBottom: 2 },
  tokenGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    columnGap: 8,
    rowGap: 10,
  },
  token: {
    fontFamily: FontNames.monoBold,
    fontSize: 14,
    lineHeight: 20,
    letterSpacing: 0.4,
    color: Palette.textSecondary,
    paddingHorizontal: 3,
    paddingVertical: 2,
    borderRadius: 6,
    borderWidth: 1.5,
    borderColor: 'transparent',
  },
  tokenComplete: { color: Palette.success },
  tokenActive: {
    color: Palette.amber,
    borderColor: Palette.amber,
    backgroundColor: Palette.amberHighlight,
  },
  challengePlaceholder: {
    minHeight: 120,
    alignItems: 'center',
    justifyContent: 'center',
  },
  placeholderText: {
    ...Typography.bodyMd,
    color: Palette.textMuted,
    textAlign: 'center',
  },
  spacer: { flexGrow: 1, minHeight: Space.xs },
  dock: { gap: Space.sm },
  inputWell: {
    borderRadius: Radii.input,
    paddingHorizontal: 14,
    paddingTop: 10,
    paddingBottom: 8,
    justifyContent: 'center',
  },
  streamRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 1,
    minHeight: 22,
  },
  streamTyped: {
    fontFamily: FontNames.monoBold,
    fontSize: 16,
    lineHeight: 22,
    letterSpacing: 0.8,
    color: Palette.success,
    flexShrink: 0,
  },
  streamCursor: {
    width: 2,
    height: 18,
    borderRadius: 1,
    marginHorizontal: 1,
    backgroundColor: Palette.amber,
    boxShadow: Shadows.amberHalo,
  },
  streamNext: {
    flexShrink: 1,
    fontFamily: FontNames.monoRegular,
    fontSize: 16,
    lineHeight: 22,
    letterSpacing: 0.8,
    color: Palette.textMuted,
  },
  streamNextChar: {
    color: Palette.amber,
    backgroundColor: Palette.amberHighlight,
  },
  input: {
    minHeight: 38,
    paddingVertical: 6,
    paddingHorizontal: 0,
    fontFamily: FontNames.monoRegular,
    fontSize: 14,
    lineHeight: 20,
    letterSpacing: 0.6,
    color: Palette.success,
  },
  chunkMetaRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: Space.sm,
  },
  chunkMeta: {
    fontFamily: FontNames.monoRegular,
    fontSize: 9,
    lineHeight: 13,
    letterSpacing: 0.8,
    color: Palette.textMuted,
  },
  chunkMetaStrong: {
    fontFamily: FontNames.monoBold,
    color: Palette.amber,
  },
  progressHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  progressLabel: {
    ...Typography.monoLabelSm,
    fontSize: 10,
    letterSpacing: 0.6,
    color: Palette.textSecondary,
  },
  progressPercent: {
    ...Typography.monoLabelSm,
    fontSize: 10,
    letterSpacing: 0.6,
    color: Palette.amber,
  },
  track: {
    height: 4,
    borderRadius: Radii.pill,
    backgroundColor: Palette.surfaceHigh,
    overflow: 'hidden',
  },
  fill: {
    height: 4,
    borderRadius: Radii.pill,
    backgroundColor: Palette.amber,
  },
  feedback: {
    ...Typography.bodyMd,
    color: Palette.amber,
  },
  warningRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  warningText: {
    ...Typography.bodyMd,
    fontSize: 12,
    color: Palette.textSecondary,
  },
  leaveButton: {
    paddingTop: Space.xs,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
  },
  leaveLabel: {
    fontFamily: FontNames.interSemiBold,
    fontSize: 13,
    lineHeight: 18,
    color: Palette.textSecondary,
  },
  modalBackdrop: {
    flex: 1,
    backgroundColor: 'rgba(8, 9, 12, 0.74)',
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: Space.lg,
  },
  modalCard: {
    width: '100%',
    maxWidth: 342,
    borderRadius: 28,
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.12)',
    backgroundColor: '#18191D',
    padding: 24,
    alignItems: 'center',
    boxShadow: '0px 24px 48px rgba(0, 0, 0, 0.45)',
  },
  modalIconTile: {
    width: 52,
    height: 52,
    borderRadius: 16,
    backgroundColor: '#241619',
    borderWidth: 1,
    borderColor: 'rgba(248, 113, 113, 0.3)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  modalTitle: {
    fontFamily: FontNames.interBold,
    fontSize: 22,
    lineHeight: 28,
    color: Palette.textPrimary,
    textAlign: 'center',
    marginTop: 16,
    marginBottom: 12,
  },
  comparisonRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    justifyContent: 'center',
  },
  comparisonText: {
    fontFamily: FontNames.interRegular,
    fontSize: 13,
    lineHeight: 21,
    color: Palette.textSecondary,
  },
  keycap: {
    borderRadius: 5,
    borderWidth: 1,
    paddingHorizontal: 5,
    paddingVertical: 1,
    marginHorizontal: 1,
  },
  keycapWrong: {
    backgroundColor: 'rgba(248, 113, 113, 0.12)',
    borderColor: 'rgba(248, 113, 113, 0.35)',
  },
  keycapExpected: {
    backgroundColor: 'rgba(52, 211, 153, 0.12)',
    borderColor: 'rgba(52, 211, 153, 0.35)',
  },
  keycapLabel: {
    fontFamily: FontNames.monoBold,
    fontSize: 12,
    lineHeight: 17,
  },
  keycapLabelWrong: { color: Palette.danger },
  keycapLabelExpected: { color: Palette.success },
  progressWell: {
    width: '100%',
    borderRadius: 16,
    paddingHorizontal: 14,
    paddingVertical: 14,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: 20,
  },
  progressLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  progressCleared: {
    fontFamily: FontNames.interMedium,
    fontSize: 13,
    lineHeight: 18,
    color: Palette.textSecondary,
  },
  progressRight: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  progressCount: {
    fontFamily: FontNames.monoBold,
    fontSize: 13,
    lineHeight: 18,
    color: Palette.danger,
  },
  progressDot: {
    width: 5,
    height: 5,
    borderRadius: Radii.pill,
    backgroundColor: Palette.danger,
    boxShadow: '0px 0px 6px rgba(248, 113, 113, 0.9)',
  },
  primaryAction: { width: '100%', marginTop: 20 },
  secondaryAction: { width: '100%', marginTop: 10 },
  pressed: { opacity: 0.75 },
});
