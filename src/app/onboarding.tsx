import { router } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import {
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { MaxContentWidth, Radius, Spacing, Type, type Theme } from '@/constants/theme';
import { useProtection } from '@/hooks/use-protection';
import { useTheme } from '@/hooks/use-theme';
import {
  openAccessibilitySettings,
  openAppDetails,
  openAutostartSettings,
  openBatterySettings,
  openNotificationSettings,
  requestNotificationPermission,
  setOnboardingCompleted,
} from '@/native/habit';

export default function OnboardingScreen() {
  const theme = useTheme();
  const { snapshot, notificationsEnabled, refresh } = useProtection();
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    const interval = setInterval(() => {
      refresh();
    }, 1500);
    return () => clearInterval(interval);
  }, [refresh]);

  const finish = useCallback(async () => {
    if (saving) return;
    setSaving(true);
    try {
      await setOnboardingCompleted(true);
    } finally {
      setSaving(false);
      router.back();
    }
  }, [saving]);

  const accessibilityOn = snapshot?.accessibilityEnabled ?? false;
  const notificationsOn = notificationsEnabled ?? false;
  const android13Plus =
    Platform.OS === 'android' && typeof Platform.Version === 'number' && Platform.Version >= 33;

  return (
    <View style={[styles.screen, { backgroundColor: theme.background }]}>
      <SafeAreaView style={styles.safe} edges={['top', 'left', 'right', 'bottom']}>
        <ScrollView
          contentContainerStyle={styles.content}
          showsVerticalScrollIndicator={false}>
          <View style={styles.header}>
            <View style={styles.headerText}>
              <Text style={[styles.eyebrow, { color: theme.accent }]}>SETUP</Text>
              <Text style={[styles.title, { color: theme.text }]}>Two minutes to lock in</Text>
            </View>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Close setup"
              onPress={() => router.back()}
              style={({ pressed }) => [
                styles.closeButton,
                { backgroundColor: theme.surfaceAlt, opacity: pressed ? 0.7 : 1 },
              ]}>
              <Text style={[styles.closeLabel, { color: theme.textSecondary }]}>Close</Text>
            </Pressable>
          </View>

          <Text style={[styles.intro, { color: theme.textSecondary }]}>
            Habit only works with these permissions. Nothing leaves your phone.
          </Text>

          <StepCard
            theme={theme}
            index={1}
            title="Accessibility access"
            body="This is how Habit reads browser addresses and closes adult pages. Required."
            done={accessibilityOn}
            actionLabel={accessibilityOn ? 'Enabled' : 'Open accessibility settings'}
            onPress={() => openAccessibilitySettings()}
            hint="Turn on Habit in Settings → Accessibility → Installed apps."
          />

          <StepCard
            theme={theme}
            index={2}
            title="Notifications"
            body="Keeps the protection service running with a quiet, permanent notification."
            done={notificationsOn}
            actionLabel={notificationsOn ? 'Enabled' : 'Allow notifications'}
            onPress={async () => {
              const granted = await requestNotificationPermission();
              if (!granted) await openNotificationSettings();
              refresh();
            }}
          />

          <StepCard
            theme={theme}
            index={3}
            title="Battery & autostart"
            body="On HyperOS/Redmi, Android otherwise stops protection when the screen is off."
            actionLabel="Open autostart settings"
            onPress={() => openAutostartSettings()}
            secondaryLabel="Battery settings"
            onSecondaryPress={() => openBatterySettings()}
            hint="Allow autostart, then set battery saver to “No restrictions”."
          />

          {android13Plus && (
            <StepCard
              theme={theme}
              index={4}
              title="Allow restricted settings"
              body="Android hides Accessibility from apps installed outside the Play Store until you allow it."
              actionLabel="Open app info"
              onPress={() => openAppDetails()}
              hint="In app info tap ⋮ (top right) → “Allow restricted settings”."
            />
          )}

          <Pressable
            accessibilityRole="button"
            disabled={saving}
            onPress={finish}
            style={({ pressed }) => [
              styles.primaryButton,
              { backgroundColor: theme.accent, opacity: pressed || saving ? 0.85 : 1 },
            ]}>
            <Text style={[styles.primaryLabel, { color: theme.onAccent }]}>
              {accessibilityOn ? 'Start protecting' : 'Finish setup anyway'}
            </Text>
          </Pressable>

          <Text style={[styles.footer, { color: theme.textMuted }]}>
            You can reopen this setup any time from the Setup button on the home screen.
          </Text>
        </ScrollView>
      </SafeAreaView>
    </View>
  );
}

type StepCardProps = {
  theme: Theme;
  index: number;
  title: string;
  body: string;
  done?: boolean;
  actionLabel: string;
  onPress: () => void;
  secondaryLabel?: string;
  onSecondaryPress?: () => void;
  hint?: string;
};

function StepCard({
  theme,
  index,
  title,
  body,
  done,
  actionLabel,
  onPress,
  secondaryLabel,
  onSecondaryPress,
  hint,
}: StepCardProps) {
  return (
    <View
      style={[
        styles.card,
        {
          backgroundColor: theme.surface,
          borderColor: done ? theme.accent : theme.border,
        },
      ]}>
      <View style={styles.cardHeader}>
        <View
          style={[
            styles.indexBadge,
            { backgroundColor: done ? theme.accent : theme.surfaceAlt },
          ]}>
          <Text
            style={[
              styles.indexLabel,
              { color: done ? theme.onAccent : theme.textSecondary },
            ]}>
            {done ? '✓' : String(index)}
          </Text>
        </View>
        <View style={styles.cardText}>
          <Text style={[styles.cardTitle, { color: theme.text }]}>{title}</Text>
          <Text style={[styles.cardBody, { color: theme.textSecondary }]}>{body}</Text>
        </View>
      </View>

      {hint && (
        <Text style={[styles.hint, { color: theme.textMuted }]}>{hint}</Text>
      )}

      <View style={styles.actions}>
        <Pressable
          accessibilityRole="button"
          onPress={onPress}
          style={({ pressed }) => [
            styles.actionButton,
            {
              backgroundColor: done ? theme.accentSoft : theme.surfaceAlt,
              borderColor: done ? theme.accent : theme.border,
              opacity: pressed ? 0.75 : 1,
            },
          ]}>
          <Text
            style={[
              styles.actionLabel,
              { color: done ? theme.accent : theme.text },
            ]}>
            {actionLabel}
          </Text>
        </Pressable>

        {secondaryLabel && onSecondaryPress && (
          <Pressable
            accessibilityRole="button"
            onPress={onSecondaryPress}
            style={({ pressed }) => [
              styles.linkButton,
              { opacity: pressed ? 0.6 : 1 },
            ]}>
            <Text style={[styles.linkLabel, { color: theme.textSecondary }]}>
              {secondaryLabel}
            </Text>
          </Pressable>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  safe: { flex: 1 },
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
  intro: {
    fontSize: Type.small.fontSize,
    lineHeight: Type.small.lineHeight,
    marginTop: -Spacing.one,
  },
  card: {
    borderWidth: 1,
    borderRadius: Radius.lg,
    padding: Spacing.three,
    gap: Spacing.three,
  },
  cardHeader: { flexDirection: 'row', gap: Spacing.three },
  indexBadge: {
    width: 28,
    height: 28,
    borderRadius: Radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
  },
  indexLabel: { fontSize: Type.micro.fontSize, fontWeight: '700' },
  cardText: { flex: 1, gap: 4 },
  cardTitle: { fontSize: Type.bodyStrong.fontSize, fontWeight: '700' },
  cardBody: { fontSize: Type.small.fontSize, lineHeight: Type.small.lineHeight },
  hint: { fontSize: Type.micro.fontSize, lineHeight: 17, marginTop: -Spacing.two },
  actions: { flexDirection: 'row', alignItems: 'center', gap: Spacing.three },
  actionButton: {
    paddingHorizontal: Spacing.three,
    paddingVertical: Spacing.two,
    borderRadius: Radius.pill,
    borderWidth: 1,
  },
  actionLabel: { fontSize: Type.small.fontSize, fontWeight: '600' },
  linkButton: { paddingVertical: Spacing.two },
  linkLabel: { fontSize: Type.small.fontSize, fontWeight: '600' },
  primaryButton: {
    paddingVertical: Spacing.three,
    borderRadius: Radius.md,
    alignItems: 'center',
    marginTop: Spacing.two,
  },
  primaryLabel: { fontSize: Type.body.fontSize, fontWeight: '700' },
  footer: {
    fontSize: Type.micro.fontSize,
    lineHeight: 18,
    textAlign: 'center',
    paddingHorizontal: Spacing.three,
  },
});
