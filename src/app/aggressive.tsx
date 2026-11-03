import { MaterialCommunityIcons } from '@expo/vector-icons';
import { router } from 'expo-router';
import { useEffect, useState } from 'react';
import { AppState, Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { NeuCard, PrimaryGlowButton } from '@/components/neumorphic';
import { FontNames, Palette, Radii, Shadows, Space, Typography } from '@/constants/theme';
import { useProtection } from '@/hooks/use-protection';
import { activateDeviceAdmin, isDeviceAdminActive } from '@/native/habit';

/**
 * Bottom-sheet confirmation shown before Aggressive mode is switched on.
 *
 * Aggressive is Strict plus device-admin uninstall protection, so the sheet explains the
 * trade in plain language first. "Activate" asks Android for device admin when it is not
 * granted yet, waits for the grant, then switches the native mode.
 */
type InfoRow = {
  icon: keyof typeof MaterialCommunityIcons.glyphMap;
  title: string;
  subtitle?: string;
  badge?: string;
};

const ROWS: InfoRow[] = [
  {
    icon: 'key-outline',
    title: 'Needs the Device admin permission',
    badge: 'REQUIRED',
  },
  {
    icon: 'shield-lock-outline',
    title: 'Strict rules still apply',
    subtitle: 'Delayed unlocks & cooldown timers remain active',
  },
  {
    icon: 'information-outline',
    title: 'Safe Mode and factory reset can still remove Habit',
  },
];

export default function AggressiveModeScreen() {
  const { changeMode } = useProtection();
  const [busy, setBusy] = useState(false);
  const [waitingForAdmin, setWaitingForAdmin] = useState(false);
  const [hint, setHint] = useState<string | null>(null);

  // Android's admin dialog pauses Habit. When it comes back, either the admin is on and
  // Aggressive can be switched on, or the user backed out and can try again.
  useEffect(() => {
    if (!waitingForAdmin) return;
    const subscription = AppState.addEventListener('change', (state) => {
      if (state !== 'active') return;
      void (async () => {
        const active = await isDeviceAdminActive();
        if (!active) {
          setWaitingForAdmin(false);
          setHint('Device admin was not activated. Tap Activate to try again.');
          return;
        }
        await changeMode('aggressive');
        router.back();
      })();
    });
    return () => subscription.remove();
  }, [waitingForAdmin, changeMode]);

  const activate = async () => {
    if (busy) return;
    setHint(null);
    setBusy(true);
    try {
      if (await isDeviceAdminActive()) {
        await changeMode('aggressive');
        router.back();
        return;
      }
      setWaitingForAdmin(true);
      const launched = await activateDeviceAdmin();
      if (!launched) {
        setWaitingForAdmin(false);
        setHint('Android could not open the device admin screen. Try again.');
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <View style={styles.backdrop}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Close Aggressive mode sheet"
        onPress={() => router.back()}
        style={styles.dismissArea}
      />

      <SafeAreaView edges={['bottom']} style={styles.sheetSafe}>
        <View style={styles.sheet}>
          <View style={styles.handle} />

          <View style={styles.alertPill}>
            <View style={styles.alertDot} />
            <Text style={styles.alertLabel}>AGGRESSIVE</Text>
          </View>

          <Text style={styles.title}>Turn on Aggressive mode</Text>
          <Text style={styles.subtitle}>
            Habit protects itself: it can&apos;t be uninstalled, force-stopped or cleared
            until you unlock.
          </Text>

          <View style={styles.rows}>
            {ROWS.map((row) => (
              <NeuCard key={row.title} padded={false} style={styles.row}>
                <View style={styles.rowIcon}>
                  <MaterialCommunityIcons name={row.icon} size={20} color={Palette.amber} />
                </View>
                <View style={styles.rowText}>
                  <View style={styles.rowTitleLine}>
                    <Text style={styles.rowTitle}>{row.title}</Text>
                    {row.badge ? (
                      <View style={styles.rowBadge}>
                        <Text style={styles.rowBadgeLabel}>{row.badge}</Text>
                      </View>
                    ) : null}
                  </View>
                  {row.subtitle ? (
                    <Text style={styles.rowSubtitle}>{row.subtitle}</Text>
                  ) : null}
                </View>
              </NeuCard>
            ))}
          </View>

          <PrimaryGlowButton
            label={waitingForAdmin ? 'Waiting for Android…' : 'Activate'}
            onPress={activate}
            disabled={busy || waitingForAdmin}
            icon={<MaterialCommunityIcons name="lightning-bolt" size={20} color={Palette.canvas} />}
            style={styles.activate}
          />

          <Pressable
            accessibilityRole="button"
            onPress={() => router.back()}
            style={({ pressed }) => [styles.cancel, pressed && styles.pressed]}>
            <Text style={styles.cancelLabel}>Cancel</Text>
          </Pressable>

          {hint ? <Text style={styles.hint}>{hint}</Text> : null}

          <Text style={styles.footer}>HABIT TAMPER PROTECTION • ANDROID DEVICE ADMIN</Text>
        </View>
      </SafeAreaView>
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
  sheetSafe: { width: '100%' },
  sheet: {
    backgroundColor: Palette.surface,
    borderTopLeftRadius: 32,
    borderTopRightRadius: 32,
    borderWidth: 1,
    borderBottomWidth: 0,
    borderColor: Palette.glassBorder,
    paddingHorizontal: Space.lg,
    paddingTop: Space.md,
    paddingBottom: Space.md,
    gap: Space.md,
    alignItems: 'stretch',
    boxShadow: Shadows.raisedLarge,
  },
  handle: {
    width: 40,
    height: 4,
    borderRadius: Radii.pill,
    backgroundColor: Palette.surfaceHigh,
    alignSelf: 'center',
    marginBottom: Space.sm,
  },
  alertPill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    alignSelf: 'center',
    borderRadius: Radii.pill,
    borderWidth: 1,
    borderColor: 'rgba(248, 113, 113, 0.35)',
    backgroundColor: 'rgba(248, 113, 113, 0.14)',
    paddingHorizontal: 12,
    paddingVertical: 5,
  },
  alertDot: {
    width: 6,
    height: 6,
    borderRadius: 3,
    backgroundColor: Palette.danger,
    boxShadow: '0px 0px 6px rgba(248, 113, 113, 0.9)',
  },
  alertLabel: {
    ...Typography.monoLabelSm,
    color: Palette.danger,
    letterSpacing: 1,
  },
  title: {
    ...Typography.headlineLg,
    color: Palette.textPrimary,
    textAlign: 'center',
  },
  subtitle: {
    ...Typography.bodyMd,
    fontSize: 14,
    lineHeight: 20,
    color: Palette.textSecondary,
    textAlign: 'center',
    paddingHorizontal: Space.md,
  },
  rows: { gap: Space.gutter, paddingTop: Space.xs },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    borderRadius: 18,
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.06)',
    backgroundColor: Palette.surfaceContainer,
    paddingHorizontal: 12,
    paddingVertical: 12,
    boxShadow: undefined,
  },
  rowIcon: {
    width: 44,
    height: 44,
    borderRadius: Radii.tile,
    backgroundColor: Palette.amberHighlight,
    alignItems: 'center',
    justifyContent: 'center',
  },
  rowText: { flex: 1, gap: 2 },
  rowTitleLine: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.sm,
  },
  rowTitle: {
    flex: 1,
    ...Typography.bodyMd,
    fontFamily: FontNames.interSemiBold,
    fontSize: 14,
    lineHeight: 19,
    color: Palette.textPrimary,
  },
  rowSubtitle: {
    ...Typography.bodySm,
    color: Palette.textSecondary,
  },
  rowBadge: {
    borderRadius: Radii.pill,
    backgroundColor: Palette.surfaceHigh,
    paddingHorizontal: 8,
    paddingVertical: 3,
  },
  rowBadgeLabel: {
    ...Typography.monoLabelSm,
    fontSize: 9,
    lineHeight: 12,
    letterSpacing: 0.8,
    color: Palette.textSecondary,
  },
  activate: { width: '100%', marginTop: Space.xs },
  cancel: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: Space.sm,
  },
  cancelLabel: {
    ...Typography.bodyLg,
    fontFamily: FontNames.interSemiBold,
    color: Palette.textSecondary,
  },
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
    paddingBottom: Space.xs,
  },
  pressed: { opacity: 0.65 },
});
