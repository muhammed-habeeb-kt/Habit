import { MaterialCommunityIcons } from '@expo/vector-icons';
import { router, useFocusEffect } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  AppState,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import {
  NeuCard,
  NeuWell,
  PrimaryGlowButton,
  StatusPill,
} from '@/components/neumorphic';
import {
  FontNames,
  MaxContentWidth,
  Palette,
  Radii,
  Shadows,
  Space,
  Typography,
} from '@/constants/theme';
import { useProtection } from '@/hooks/use-protection';
import {
  activateDeviceAdmin,
  openAccessibilitySettings,
  openAppDetails,
  openAutostartSettings,
  openBatterySettings,
  openNotificationSettings,
  requestNotificationPermission,
  setOnboardingCompleted,
  setRestrictedSettingsVisited,
} from '@/native/habit';

const PERMISSION_COUNT = 5;

const ACCESSIBILITY_EXPLANATION =
  'Android requires it so Habit can read browser address bars and recognise the screens of the apps you chose. Habit works only on this phone and never stores or sends what it sees.';

export default function OnboardingScreen() {
  const { snapshot, refresh } = useProtection();
  const [saving, setSaving] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);
  // True when the modal was opened by "Fix", so Understood continues into Settings.
  const [helpOpensAccessibility, setHelpOpensAccessibility] = useState(false);
  // Set when App Info was opened for restricted settings; the grant is only credited
  // after Habit becomes active again, never on the tap itself.
  const restrictedReturn = useRef(false);

  // Every value below is a live Android check from the shared native snapshot. Nothing
  // here is local "granted" state, so leaving and returning to this screen cannot reset
  // or fake the count.
  const accessibilityOn = snapshot?.accessibilityEnabled ?? false;
  const notificationsOn = snapshot?.notificationsGranted ?? false;
  const batteryOn = snapshot?.batteryOptimizationIgnored ?? false;
  const restrictedOn = snapshot?.restrictedSettingsAllowed ?? false;
  const deviceAdminOn = snapshot?.deviceAdminActive ?? false;
  const grantedCount =
    (accessibilityOn ? 1 : 0) +
    (notificationsOn ? 1 : 0) +
    (batteryOn ? 1 : 0) +
    (restrictedOn ? 1 : 0) +
    (deviceAdminOn ? 1 : 0);

  // Settings and system dialogs return without remounting this screen, so re-check on
  // focus and whenever the app comes back to the foreground.
  useFocusEffect(
    useCallback(() => {
      void refresh();
    }, [refresh])
  );

  useEffect(() => {
    const subscription = AppState.addEventListener('change', (state) => {
      if (state !== 'active') return;
      if (!restrictedReturn.current) {
        void refresh();
        return;
      }
      restrictedReturn.current = false;
      void setRestrictedSettingsVisited(true).then(() => refresh());
    });
    return () => subscription.remove();
  }, [refresh]);

  // Device admin and the notification permission use system dialogs that keep the app
  // active, so a light poll keeps those cards truthful while this screen is open.
  useEffect(() => {
    const interval = setInterval(() => {
      void refresh();
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

  const openHelp = () => {
    setHelpOpensAccessibility(false);
    setHelpOpen(true);
  };

  const fixAccessibility = () => {
    setHelpOpensAccessibility(true);
    setHelpOpen(true);
  };

  const dismissHelp = async () => {
    setHelpOpen(false);
    if (!helpOpensAccessibility) return;
    setHelpOpensAccessibility(false);
    await openAccessibilitySettings();
  };

  const fixNotifications = async () => {
    const granted = await requestNotificationPermission();
    if (!granted) await openNotificationSettings();
    refresh();
  };

  const fixBattery = async () => {
    // Opens Android's own battery-optimisation request, then falls back to the vendor
    // autostart screen. Nothing is marked granted here; the snapshot decides.
    const opened = await openBatterySettings();
    if (!opened) await openAutostartSettings();
  };

  const fixRestricted = async () => {
    restrictedReturn.current = true;
    await openAppDetails();
  };

  const fixDeviceAdmin = async () => {
    await activateDeviceAdmin();
    refresh();
  };

  return (
    <View style={styles.screen}>
      <SafeAreaView style={styles.safe} edges={['top', 'left', 'right', 'bottom']}>
        <ScrollView
          contentContainerStyle={styles.content}
          showsVerticalScrollIndicator={false}>
          <View style={styles.header}>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Go back"
              onPress={() => router.back()}
              style={({ pressed }) => [styles.backButton, pressed && styles.pressed]}>
              <MaterialCommunityIcons
                name="chevron-left"
                size={26}
                color={Palette.textPrimary}
              />
            </Pressable>
            <Text style={styles.title}>Set up Habit</Text>
          </View>

          <NeuCard style={styles.progressCard}>
            <View style={styles.progressTop}>
              <StatusPill label="SETUP" tone="amber" />
              <View style={styles.progressCountBlock}>
                <Text style={styles.progressFraction}>
                  {grantedCount} / {PERMISSION_COUNT}
                </Text>
                <Text style={styles.progressGranted}>granted</Text>
              </View>
            </View>
            <View style={styles.progressTrack}>
              <View
                style={[
                  styles.progressFill,
                  { width: `${(grantedCount / PERMISSION_COUNT) * 100}%` },
                ]}
              />
            </View>
            <Text style={styles.progressNote}>
              Habit needs these permissions to protect you.
            </Text>
          </NeuCard>

          <Text style={styles.sectionLabel}>REQUIRED PERMISSIONS</Text>

          <PermissionCard
            icon="eye-outline"
            title="Accessibility service"
            badge={{ label: 'REQUIRED', tone: 'danger' }}
            description="Lets Habit see browser addresses and the screens of the apps you chose."
            granted={accessibilityOn}
            onHelp={openHelp}
            onFix={fixAccessibility}
          />

          <PermissionCard
            icon="bell-outline"
            title="Notifications"
            description="Shows that Habit is protecting you."
            granted={notificationsOn}
            onFix={fixNotifications}
          />

          <PermissionCard
            icon="lightning-bolt"
            title="Battery & autostart"
            badge={{ label: 'CRUCIAL', tone: 'amber' }}
            description="Keeps Habit running in the background."
            granted={batteryOn}
            onFix={fixBattery}
          />

          <PermissionCard
            icon="lock-outline"
            title="Allow restricted settings"
            description="Needed on Android 13+ for apps installed outside the Play Store."
            granted={restrictedOn}
            onFix={fixRestricted}
          />

          <PermissionCard
            icon="shield-outline"
            title="Device admin"
            description="Protects Habit from being force-stopped or uninstalled in Aggressive mode."
            granted={deviceAdminOn}
            onFix={fixDeviceAdmin}
          />

          <PrimaryGlowButton
            label={accessibilityOn ? 'Start protecting' : 'Finish setup anyway'}
            onPress={finish}
            disabled={saving}
            style={styles.finishButton}
          />
        </ScrollView>
      </SafeAreaView>

      <Modal
        visible={helpOpen}
        transparent
        animationType="fade"
        statusBarTranslucent
        onRequestClose={() => setHelpOpen(false)}>
        <View style={styles.modalRoot}>
          <View style={styles.scrim} />
          <View style={styles.glassCard}>
            <View style={styles.shieldTile}>
              <MaterialCommunityIcons name="shield-check" size={24} color={Palette.amber} />
            </View>
            <Text style={styles.modalTitle}>Why does Habit need Accessibility?</Text>
            <Text style={styles.modalBody}>{ACCESSIBILITY_EXPLANATION}</Text>
            <NeuWell padded={false} style={styles.onDevicePill}>
              <MaterialCommunityIcons name="lock" size={13} color={Palette.success} />
              <Text style={styles.onDeviceLabel}>100% ON-DEVICE • NEVER TRANSMITTED</Text>
            </NeuWell>
            <PrimaryGlowButton
              label="Understood"
              onPress={dismissHelp}
              style={styles.modalButton}
            />
          </View>
        </View>
      </Modal>
    </View>
  );
}

type PermissionCardProps = {
  icon: keyof typeof MaterialCommunityIcons.glyphMap;
  title: string;
  description: string;
  /** Required/Crucial marker. Sits in a wrapping row with the title so it never covers it. */
  badge?: { label: string; tone: 'danger' | 'amber' };
  granted: boolean;
  onFix: () => void;
  onHelp?: () => void;
};

function PermissionCard({
  icon,
  title,
  description,
  badge,
  granted,
  onFix,
  onHelp,
}: PermissionCardProps) {
  return (
    <NeuCard style={[styles.permissionCard, !granted && styles.permissionCardAttention]}>
      <View style={styles.permissionRow}>
        <View style={styles.iconTile}>
          <MaterialCommunityIcons
            name={icon}
            size={20}
            color={granted ? Palette.textPrimary : Palette.amber}
          />
        </View>
        <View style={styles.permissionContent}>
          <View style={styles.titleRow}>
            {/* Title and badge wrap together inside this group; nothing is absolutely
                positioned, so the badge can never cover the title text. */}
            <View style={styles.titleGroup}>
              <Text style={styles.permissionTitle}>{title}</Text>
              {badge && <StatusPill label={badge.label} tone={badge.tone} />}
            </View>
            <View style={styles.permissionActions}>
              {onHelp && (
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel="Why does Habit need this permission?"
                  onPress={onHelp}
                  hitSlop={8}
                  style={({ pressed }) => [styles.helpButton, pressed && styles.pressed]}>
                  <Text style={styles.helpLabel}>?</Text>
                </Pressable>
              )}
              {granted ? (
                <StatusPill label="✓ Granted" tone="success" />
              ) : (
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel={`Fix ${title}`}
                  onPress={onFix}
                  style={({ pressed }) => [styles.fixButton, pressed && styles.fixPressed]}>
                  <Text style={styles.fixLabel}>Fix</Text>
                </Pressable>
              )}
            </View>
          </View>

          <Text style={styles.permissionDescription}>{description}</Text>
        </View>
      </View>
    </NeuCard>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: Palette.canvas },
  safe: { flex: 1 },
  content: {
    width: '100%',
    maxWidth: MaxContentWidth,
    alignSelf: 'center',
    paddingHorizontal: Space.margin,
    paddingTop: Space.sm,
    paddingBottom: Space.xxl,
    gap: Space.md,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    paddingBottom: Space.xs,
  },
  backButton: {
    width: 48,
    height: 48,
    borderRadius: Radii.pill,
    backgroundColor: Palette.raised,
    boxShadow: Shadows.raisedSmall,
    alignItems: 'center',
    justifyContent: 'center',
  },
  title: {
    ...Typography.headlineLg,
    color: Palette.textPrimary,
    flex: 1,
  },
  pressed: { opacity: 0.7 },

  progressCard: { gap: Space.gutter, paddingVertical: 18 },
  progressTop: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
    gap: Space.gutter,
  },
  progressCountBlock: { alignItems: 'flex-end', gap: 2 },
  progressFraction: {
    ...Typography.monoLabelLg,
    color: Palette.amber,
  },
  progressGranted: {
    ...Typography.bodySm,
    color: Palette.textSecondary,
  },
  progressTrack: {
    height: 8,
    borderRadius: Radii.pill,
    backgroundColor: Palette.well,
    boxShadow: Shadows.sunkenSmall,
    overflow: 'hidden',
  },
  progressFill: {
    height: '100%',
    borderRadius: Radii.pill,
    backgroundColor: Palette.amber,
  },
  progressNote: {
    ...Typography.bodySm,
    color: Palette.textSecondary,
    marginTop: -Space.xs,
  },

  sectionLabel: {
    ...Typography.monoLabelSm,
    color: Palette.textMuted,
    marginTop: Space.sm,
    marginBottom: -4,
  },

  permissionCard: {
    paddingVertical: 16,
    paddingHorizontal: 16,
    borderWidth: 1,
    borderColor: 'transparent',
  },
  permissionCardAttention: { borderColor: Palette.amberHighlight },
  permissionRow: { flexDirection: 'row', gap: Space.gutter },
  iconTile: {
    width: Radii.tileSize,
    height: Radii.tileSize,
    borderRadius: Radii.tile,
    backgroundColor: Palette.amberHighlight,
    alignItems: 'center',
    justifyContent: 'center',
  },
  permissionContent: { flex: 1, minWidth: 0 },
  titleRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: Space.sm,
    minHeight: 32,
  },
  titleGroup: {
    flexDirection: 'row',
    alignItems: 'center',
    flexWrap: 'wrap',
    gap: 6,
    flexShrink: 1,
    minWidth: 0,
    flexGrow: 1,
  },
  permissionTitle: {
    ...Typography.bodyLg,
    fontFamily: FontNames.interBold,
    color: Palette.textPrimary,
    flexShrink: 1,
  },
  permissionActions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.sm,
    flexShrink: 0,
  },
  helpButton: {
    width: 24,
    height: 24,
    borderRadius: Radii.pill,
    backgroundColor: Palette.surfaceHigh,
    alignItems: 'center',
    justifyContent: 'center',
  },
  helpLabel: {
    fontFamily: FontNames.monoBold,
    fontSize: 11,
    lineHeight: 14,
    color: Palette.textSecondary,
  },
  fixButton: {
    minWidth: 52,
    height: 32,
    borderRadius: Radii.pill,
    backgroundColor: Palette.amber,
    boxShadow: Shadows.amberGlow,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 16,
  },
  fixPressed: { backgroundColor: Palette.amberPressed, boxShadow: undefined },
  fixLabel: {
    fontFamily: FontNames.interBold,
    fontSize: 13,
    lineHeight: 18,
    color: Palette.canvas,
  },
  permissionDescription: {
    ...Typography.bodyMd,
    color: Palette.textSecondary,
    marginTop: 6,
  },

  finishButton: { marginTop: Space.sm },

  modalRoot: {
    flex: 1,
    justifyContent: 'flex-end',
    padding: Space.md,
  },
  scrim: {
    position: 'absolute',
    top: 0,
    right: 0,
    bottom: 0,
    left: 0,
    backgroundColor: Palette.scrim,
  },
  glassCard: {
    borderRadius: Radii.card,
    borderWidth: 1,
    borderColor: Palette.glassBorder,
    backgroundColor: Palette.glassFill,
    padding: Space.lg,
    gap: Space.gutter,
    alignItems: 'center',
    boxShadow: Shadows.raisedLarge,
  },
  shieldTile: {
    width: 52,
    height: 52,
    borderRadius: Radii.pill,
    backgroundColor: Palette.raised,
    borderWidth: 1,
    borderColor: Palette.amberGlow,
    boxShadow: Shadows.amberHalo,
    alignItems: 'center',
    justifyContent: 'center',
  },
  modalTitle: {
    ...Typography.headlineLg,
    fontSize: 20,
    lineHeight: 26,
    color: Palette.textPrimary,
    textAlign: 'center',
  },
  modalBody: {
    ...Typography.bodyMd,
    color: Palette.textSecondary,
    textAlign: 'center',
  },
  onDevicePill: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    borderRadius: Radii.pill,
    paddingHorizontal: 14,
    paddingVertical: 8,
  },
  onDeviceLabel: {
    fontFamily: FontNames.monoBold,
    fontSize: 9,
    lineHeight: 13,
    letterSpacing: 0.6,
    color: Palette.textSecondary,
    flexShrink: 1,
    textAlign: 'center',
  },
  modalButton: { alignSelf: 'stretch' },
});
