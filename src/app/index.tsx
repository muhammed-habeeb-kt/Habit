import { router } from 'expo-router';
import { useEffect, useRef } from 'react';
import {
  ActivityIndicator,
  Alert,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { formatDuration, useCountdown } from '@/components/countdown';
import { ProtectionToggle } from '@/components/protection-toggle';
import { MaxContentWidth, Radius, Spacing, Type, type Theme } from '@/constants/theme';
import { useProtection } from '@/hooks/use-protection';
import { useTheme } from '@/hooks/use-theme';
import { activateDeviceAdmin, isNativeAvailable, type ProtectionMode } from '@/native/habit';

type ModeRow = {
  id: ProtectionMode;
  name: string;
  description: string;
  comingSoon?: boolean;
};

const MODES: ModeRow[] = [
  {
    id: 'normal',
    name: 'Normal',
    description: 'Turn protection on or off whenever you like.',
  },
  {
    id: 'strict',
    name: 'Strict',
    description: 'Lowering protection needs a long typed string or a 3-hour cooldown.',
  },
  {
    id: 'aggressive',
    name: 'Aggressive',
    description: 'Strict plus device-admin uninstall protection.',
  },
];

export default function HomeScreen() {
  const theme = useTheme();
  const { snapshot, busy, toggle, changeMode } = useProtection();
  const askedForSetup = useRef(false);
  const pendingAggressive = useRef(false);

  const cooldownRemaining = useCountdown(snapshot?.unlock.cooldownRemainingMs ?? 0);
  const offWindowRemaining = useCountdown(snapshot?.unlock.offWindowRemainingMs ?? 0);

  useEffect(() => {
    if (!snapshot || askedForSetup.current) return;
    if (!snapshot.onboardingCompleted) {
      askedForSetup.current = true;
      router.push('/onboarding');
    }
  }, [snapshot]);

  // Finishes the Aggressive flow once Android has granted device admin.
  useEffect(() => {
    if (!pendingAggressive.current || !snapshot?.deviceAdminActive) return;
    pendingAggressive.current = false;
    changeMode('aggressive');
  }, [snapshot?.deviceAdminActive, changeMode]);

  if (!snapshot) {
    return (
      <View style={[styles.loading, { backgroundColor: theme.background }]}>
        <ActivityIndicator color={theme.accent} />
      </View>
    );
  }

  const enabled = snapshot.protectionEnabled;
  const strict = snapshot.mode !== 'normal';
  const accessibilityMissing = !snapshot.accessibilityEnabled;
  const offWindowActive = snapshot.unlock.offWindowActive && !enabled;
  const cooldownActive = snapshot.unlock.cooldownActive;

  const handleToggle = () => {
    if (strict && enabled) {
      router.push('/unlock');
      return;
    }
    toggle();
  };

  const handleMode = (id: ProtectionMode) => {
    if (id === snapshot.mode) return;

    if (id === 'aggressive') {
      if (snapshot.deviceAdminActive) {
        Alert.alert(
          'Enable Aggressive mode?',
          'Aggressive is Strict mode plus device-admin protection. Android will ask before Habit can be uninstalled. Lowering it still needs an unlock: a typed random string, or a 3-hour cooldown.',
          [
            { text: 'Cancel', style: 'cancel' },
            {
              text: 'Enable Aggressive',
              onPress: () => {
                changeMode('aggressive');
              },
            },
          ]
        );
        return;
      }
      Alert.alert(
        'Aggressive needs device admin',
        'Habit will ask Android to make it a device admin. It uses no admin powers — it only makes removing Habit require an extra confirmation. Enable device admin now?',
        [
          { text: 'Cancel', style: 'cancel' },
          {
            text: 'Continue',
            onPress: async () => {
              pendingAggressive.current = true;
              const launched = await activateDeviceAdmin();
              if (!launched) pendingAggressive.current = false;
            },
          },
        ]
      );
      return;
    }

    // Any other move away from a locked mode is a lowering: it needs an unlock.
    if (snapshot.mode !== 'normal') {
      router.push('/unlock');
      return;
    }

    Alert.alert(
      'Enable Strict mode?',
      'Protection turns on and stays on. To lower it later you must either type a 100, 200 or 300 character random string, or wait out a 3-hour cooldown (protection stays on during it).',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Enable Strict',
          onPress: () => {
            changeMode('strict');
          },
        },
      ]
    );
  };

  const statusText = () => {
    if (offWindowActive) {
      return 'Protection is off for a few minutes, then it turns back on by itself.';
    }
    if (snapshot.mode === 'aggressive' && enabled) {
      return 'Aggressive mode is on. Protection is locked and Habit is a device admin.';
    }
    if (strict && enabled) {
      return 'Strict mode is on. Lowering protection needs an unlock.';
    }
    if (enabled) {
      return 'Adult sites are being blocked in your browsers.';
    }
    return 'Protection is paused. Adult sites will load normally.';
  };

  return (
    <View style={[styles.screen, { backgroundColor: theme.background }]}>
      <SafeAreaView style={styles.safe} edges={['top', 'left', 'right']}>
        <ScrollView
          contentContainerStyle={styles.content}
          showsVerticalScrollIndicator={false}>
          <View style={styles.header}>
            <View>
              <Text style={[styles.eyebrow, { color: theme.accent }]}>HABIT</Text>
              <Text style={[styles.title, { color: theme.text }]}>Your quiet guard</Text>
            </View>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Open setup"
              onPress={() => router.push('/onboarding')}
              style={({ pressed }) => [
                styles.setupButton,
                {
                  backgroundColor: theme.surfaceAlt,
                  borderColor: theme.border,
                  opacity: pressed ? 0.7 : 1,
                },
              ]}>
              <Text style={[styles.setupLabel, { color: theme.textSecondary }]}>Setup</Text>
            </Pressable>
          </View>

          {!isNativeAvailable && (
            <Banner
              theme={theme}
              title="Native guard not found"
              body="Open Habit in a development build, not in Expo Go, so blocking can run."
            />
          )}

          {accessibilityMissing && (
            <Banner
              theme={theme}
              title="Accessibility is off"
              body="Habit can't read browser pages, so nothing is blocked right now."
              actionLabel="Fix it"
              onPress={() => router.push('/onboarding')}
            />
          )}

          {snapshot.mode === 'aggressive' && !snapshot.deviceAdminActive && (
            <Banner
              theme={theme}
              title="Device admin is off"
              body="Aggressive mode can no longer guard against uninstalling. Turn device admin back on to keep it intact."
              actionLabel="Turn on"
              onPress={() => {
                void activateDeviceAdmin();
              }}
            />
          )}

          <View style={styles.toggleWrap}>
            <ProtectionToggle enabled={enabled} busy={busy} theme={theme} onPress={handleToggle} />
            <Text style={[styles.toggleStatus, { color: theme.textSecondary }]}>
              {statusText()}
            </Text>
            {strict && (
              <View style={[styles.strictChip, { backgroundColor: theme.accentSoft }]}>
                <Text style={[styles.strictChipLabel, { color: theme.accent }]}>
                  {snapshot.mode === 'aggressive' ? 'AGGRESSIVE' : 'STRICT'} MODE
                </Text>
              </View>
            )}
          </View>

          {cooldownActive && (
            <View
              style={[
                styles.card,
                { backgroundColor: theme.surface, borderColor: theme.accent },
              ]}>
              <Text style={[styles.eyebrow, { color: theme.textMuted }]}>UNLOCK COOLDOWN</Text>
              <Text style={[styles.countdown, { color: theme.text }]}>
                {formatDuration(cooldownRemaining)}
              </Text>
              <Text style={[styles.cardNote, { color: theme.textSecondary }]}>
                Protection stays on until the cooldown ends.
              </Text>
              <Pressable
                accessibilityRole="button"
                onPress={() => router.push('/unlock')}
                style={({ pressed }) => [styles.inlineLink, { opacity: pressed ? 0.6 : 1 }]}>
                <Text style={[styles.inlineLinkLabel, { color: theme.accent }]}>
                  Manage cooldown
                </Text>
              </Pressable>
            </View>
          )}

          {offWindowActive && (
            <View
              style={[
                styles.card,
                { backgroundColor: theme.accentSoft, borderColor: theme.accent },
              ]}>
              <Text style={[styles.bannerTitle, { color: theme.text }]}>Protection is off</Text>
              <Text style={[styles.cardNote, { color: theme.textSecondary }]}>
                It turns back on automatically in {formatDuration(offWindowRemaining)}.
              </Text>
              <Pressable
                accessibilityRole="button"
                onPress={toggle}
                style={({ pressed }) => [
                  styles.smallButton,
                  { backgroundColor: theme.accent, opacity: pressed ? 0.8 : 1 },
                ]}>
                <Text style={[styles.smallButtonLabel, { color: theme.onAccent }]}>
                  Turn on now
                </Text>
              </Pressable>
            </View>
          )}

          <View
            style={[
              styles.card,
              { backgroundColor: theme.surface, borderColor: theme.border },
            ]}>
            <Text style={[styles.eyebrow, { color: theme.textMuted }]}>
              BLOCKED ATTEMPTS TODAY
            </Text>
            <View style={styles.attemptRow}>
              <Text style={[styles.attemptCount, { color: theme.text }]}>
                {snapshot.attemptsToday}
              </Text>
              <Text style={[styles.attemptHint, { color: theme.textSecondary }]}>
                Resets at midnight
              </Text>
            </View>
          </View>

          <View style={styles.section}>
            <Text style={[styles.sectionTitle, { color: theme.text }]}>Mode</Text>
            <View
              style={[
                styles.card,
                styles.modeCard,
                { backgroundColor: theme.surface, borderColor: theme.border },
              ]}>
              {MODES.map((mode, index) => {
                const active = mode.id === snapshot.mode;
                return (
                  <Pressable
                    key={mode.id}
                    accessibilityRole="button"
                    accessibilityState={{ selected: active, disabled: mode.comingSoon }}
                    disabled={mode.comingSoon}
                    onPress={() => handleMode(mode.id)}
                    style={({ pressed }) => [
                      styles.modeRow,
                      index < MODES.length - 1 && {
                        borderBottomWidth: StyleSheet.hairlineWidth,
                        borderBottomColor: theme.border,
                      },
                      mode.comingSoon && styles.modeRowMuted,
                      pressed && { opacity: 0.7 },
                    ]}>
                    <View
                      style={[
                        styles.modeDot,
                        {
                          borderColor: active ? theme.accent : theme.border,
                          backgroundColor: active ? theme.accent : 'transparent',
                        },
                      ]}
                    />
                    <View style={styles.modeText}>
                      <Text style={[styles.modeName, { color: theme.text }]}>
                        {mode.name}
                      </Text>
                      <Text style={[styles.modeDescription, { color: theme.textSecondary }]}>
                        {mode.description}
                      </Text>
                    </View>
                    {mode.comingSoon ? (
                      <View
                        style={[
                          styles.pill,
                          { backgroundColor: theme.surfaceAlt, borderColor: theme.border },
                        ]}>
                        <Text style={[styles.pillLabel, { color: theme.textMuted }]}>
                          COMING SOON
                        </Text>
                      </View>
                    ) : active ? (
                      <Text style={[styles.activeLabel, { color: theme.accent }]}>ACTIVE</Text>
                    ) : (
                      <Text style={[styles.activeLabel, { color: theme.textMuted }]}>CHOOSE</Text>
                    )}
                  </Pressable>
                );
              })}
            </View>
            {snapshot.mode !== 'normal' && (
              <Text style={[styles.helper, { color: theme.textMuted }]}>
                To leave {snapshot.mode === 'aggressive' ? 'Aggressive' : 'Strict'} mode, open
                Unlock and pick a method.
              </Text>
            )}
          </View>

          <Text style={[styles.footer, { color: theme.textMuted }]}>
            Habit never stores the pages you visit — only a daily count stays on this phone.
          </Text>
        </ScrollView>
      </SafeAreaView>
    </View>
  );
}

function Banner({
  theme,
  title,
  body,
  actionLabel,
  onPress,
}: {
  theme: Theme;
  title: string;
  body: string;
  actionLabel?: string;
  onPress?: () => void;
}) {
  return (
    <View
      style={[
        styles.banner,
        { backgroundColor: theme.dangerSoft, borderColor: theme.dangerBorder },
      ]}>
      <Text style={[styles.bannerTitle, { color: theme.danger }]}>{title}</Text>
      <Text style={[styles.bannerBody, { color: theme.textSecondary }]}>{body}</Text>
      {actionLabel && onPress && (
        <Pressable
          accessibilityRole="button"
          onPress={onPress}
          style={({ pressed }) => [
            styles.bannerButton,
            { backgroundColor: theme.danger, opacity: pressed ? 0.8 : 1 },
          ]}>
          <Text style={[styles.bannerButtonLabel, { color: theme.surface }]}>
            {actionLabel}
          </Text>
        </Pressable>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  safe: { flex: 1 },
  loading: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  content: {
    paddingHorizontal: Spacing.four,
    paddingBottom: Spacing.six,
    paddingTop: Spacing.three,
    gap: Spacing.four,
    width: '100%',
    maxWidth: MaxContentWidth,
    alignSelf: 'center',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
    gap: Spacing.three,
  },
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
  setupButton: {
    paddingHorizontal: Spacing.three,
    paddingVertical: Spacing.two,
    borderRadius: Radius.pill,
    borderWidth: 1,
  },
  setupLabel: { fontSize: Type.small.fontSize, fontWeight: '600' },
  banner: {
    borderWidth: 1,
    borderRadius: Radius.lg,
    padding: Spacing.three,
    gap: Spacing.two,
  },
  bannerTitle: { fontSize: Type.bodyStrong.fontSize, fontWeight: '700' },
  bannerBody: { fontSize: Type.small.fontSize, lineHeight: Type.small.lineHeight },
  bannerButton: {
    alignSelf: 'flex-start',
    paddingHorizontal: Spacing.four,
    paddingVertical: Spacing.two,
    borderRadius: Radius.pill,
    marginTop: Spacing.one,
  },
  bannerButtonLabel: { fontSize: Type.small.fontSize, fontWeight: '700' },
  toggleWrap: { alignItems: 'center', gap: Spacing.three, paddingVertical: Spacing.two },
  toggleStatus: {
    fontSize: Type.small.fontSize,
    lineHeight: Type.small.lineHeight,
    textAlign: 'center',
    maxWidth: 300,
  },
  strictChip: {
    paddingHorizontal: Spacing.three,
    paddingVertical: Spacing.one,
    borderRadius: Radius.pill,
  },
  strictChipLabel: { fontSize: 10, fontWeight: '700', letterSpacing: 1 },
  card: {
    borderWidth: 1,
    borderRadius: Radius.lg,
    padding: Spacing.three,
  },
  countdown: {
    fontSize: 34,
    lineHeight: 40,
    fontWeight: '700',
    marginTop: Spacing.one,
    fontVariant: ['tabular-nums'],
  },
  cardNote: {
    fontSize: Type.small.fontSize,
    lineHeight: Type.small.lineHeight,
    marginTop: Spacing.one,
  },
  inlineLink: { alignSelf: 'flex-start', paddingTop: Spacing.two },
  inlineLinkLabel: { fontSize: Type.small.fontSize, fontWeight: '700' },
  smallButton: {
    alignSelf: 'flex-start',
    paddingHorizontal: Spacing.four,
    paddingVertical: Spacing.two,
    borderRadius: Radius.pill,
    marginTop: Spacing.two,
  },
  smallButtonLabel: { fontSize: Type.small.fontSize, fontWeight: '700' },
  attemptRow: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    justifyContent: 'space-between',
    marginTop: Spacing.one,
  },
  attemptCount: {
    fontSize: 46,
    lineHeight: 52,
    fontWeight: '700',
  },
  attemptHint: { fontSize: Type.small.fontSize, marginBottom: Spacing.two },
  section: { gap: Spacing.two },
  sectionTitle: { fontSize: Type.subtitle.fontSize, fontWeight: '700' },
  modeCard: { padding: 0, overflow: 'hidden' },
  modeRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Spacing.three,
    padding: Spacing.three,
  },
  modeRowMuted: { opacity: 0.55 },
  modeDot: { width: 14, height: 14, borderRadius: Radius.pill, borderWidth: 2 },
  modeText: { flex: 1, gap: 2 },
  modeName: { fontSize: Type.bodyStrong.fontSize, fontWeight: '600' },
  modeDescription: { fontSize: Type.small.fontSize, lineHeight: Type.small.lineHeight },
  pill: {
    paddingHorizontal: Spacing.two,
    paddingVertical: Spacing.half,
    borderRadius: Radius.pill,
    borderWidth: 1,
  },
  pillLabel: { fontSize: 9, fontWeight: '700', letterSpacing: 0.8 },
  activeLabel: { fontSize: 10, fontWeight: '700', letterSpacing: 1 },
  helper: { fontSize: Type.micro.fontSize, lineHeight: 17, paddingHorizontal: Spacing.one },
  footer: {
    fontSize: Type.micro.fontSize,
    lineHeight: 18,
    textAlign: 'center',
    paddingHorizontal: Spacing.three,
  },
});
