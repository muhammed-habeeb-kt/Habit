import { MaterialCommunityIcons } from '@expo/vector-icons';
import Constants from 'expo-constants';
import { router } from 'expo-router';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import {
  NeuCard,
  NeuSegmented,
  NeuSwitch,
  StatusPill,
  type SegmentOption,
} from '@/components/neumorphic';
import { TabScreen } from '@/components/tab-screen';
import {
  FontNames,
  Palette,
  Radii,
  Shadows,
  Space,
  Typography,
} from '@/constants/theme';
import { useAiMonitoring } from '@/hooks/use-ai-monitoring';
import { useProtection } from '@/hooks/use-protection';
import type { AiSensitivity } from '@/native/habit';

const SENSITIVITY_OPTIONS: SegmentOption<AiSensitivity>[] = [
  { id: 'low', label: 'Low' },
  { id: 'balanced', label: 'Balanced' },
  { id: 'high', label: 'High' },
];

const MODE_PILL: Record<string, { label: string; tone: 'amber' | 'neutral' }> = {
  normal: { label: 'NORMAL', tone: 'neutral' },
  strict: { label: 'STRICT', tone: 'amber' },
  aggressive: { label: 'AGGRESSIVE', tone: 'amber' },
};

const SENSITIVITY_NOTES: Record<AiSensitivity, string> = {
  low: 'Blocks only clear, confident matches.',
  balanced: 'A careful balance between misses and false blocks.',
  high: 'Catches more, including borderline content.',
};

export default function SettingsScreen() {
  const { snapshot } = useProtection();
  const { settings, busy, changeSensitivity, toggleBlockAlerts } = useAiMonitoring();

  const mode = snapshot?.mode ?? 'normal';
  const pill = MODE_PILL[mode] ?? MODE_PILL.normal;
  const accessibilityOn = snapshot?.accessibilityEnabled ?? false;
  const notificationsOn = snapshot?.foregroundServiceRunning ?? false;
  // grantedCount represents how many of the 5 required permissions are granted
  const grantedCount = (accessibilityOn ? 1 : 0) + (notificationsOn ? 1 : 0);
  const sensitivity = settings?.aiSensitivity ?? 'balanced';
  const version = Constants.expoConfig?.version ?? '1.0.0';

  const blockAlertsEnabled = settings?.blockAlertsEnabled ?? true;

  return (
    <TabScreen>
      <View style={styles.header}>
        <Text style={styles.title}>Settings</Text>
      </View>

      <Text style={styles.sectionLabel}>PROTECTION</Text>

      <Pressable
        accessibilityRole="button"
        onPress={() => router.navigate('/')}
        style={({ pressed }) => [pressed && styles.pressed]}>
        <NeuCard style={styles.rowCard}>
          <Text style={styles.rowTitle}>Protection mode</Text>
          <View style={styles.rowRight}>
            <StatusPill label={pill.label} tone={pill.tone} />
            <MaterialCommunityIcons name="chevron-right" size={20} color={Palette.textMuted} />
          </View>
        </NeuCard>
      </Pressable>

      <NeuCard style={styles.sensitivityCard}>
        <Text style={styles.cardTitle}>AI detection sensitivity</Text>
        <Text style={styles.cardSubtitle}>How careful Habit is before blocking</Text>
        <View style={styles.sensitivityControl}>
          <NeuSegmented
            options={SENSITIVITY_OPTIONS}
            value={sensitivity}
            onChange={(next) => {
              if (!busy) changeSensitivity(next);
            }}
          />
        </View>
        <Text style={styles.note}>{SENSITIVITY_NOTES[sensitivity]}</Text>
      </NeuCard>

      <Text style={styles.sectionLabel}>PERMISSIONS</Text>
      <NeuCard style={styles.permissionsCard}>
        <View style={styles.permissionRow}>
          <View style={styles.iconWell}>
            <MaterialCommunityIcons name="shield-check" size={24} color={Palette.amber} />
          </View>
          <View style={styles.grow}>
            <Text style={styles.cardTitle}>Permissions</Text>
            <Text style={styles.cardSubtitle}>
              {grantedCount} of 5 required permissions granted
            </Text>
          </View>
          <Pressable
            accessibilityRole="button"
            onPress={() => router.push('/onboarding')}
            style={({ pressed }) => [styles.fixButton, pressed && styles.pressed]}>
            <Text style={styles.fixLabel}>Fix</Text>
          </Pressable>
        </View>
        <View style={styles.track}>
          <View style={[styles.fill, { width: `${(grantedCount / 5) * 100}%` }]} />
        </View>
      </NeuCard>

      <Text style={styles.sectionLabel}>ALERTS</Text>
      <NeuCard style={styles.alertsCard}>
        <View style={styles.grow}>
          <Text style={styles.alertTitle}>Block alerts</Text>
          <Text style={styles.cardSubtitle}>Tell me when something is blocked</Text>
        </View>
        <NeuSwitch
          label="Block alerts"
          value={blockAlertsEnabled}
          onValueChange={(next) => {
            if (!busy) toggleBlockAlerts(next);
          }}
        />
      </NeuCard>

      <Text style={styles.sectionLabel}>PRIVACY</Text>
      <View style={styles.privacyCard}>
        <View style={styles.iconWell}>
          <MaterialCommunityIcons name="lock" size={24} color={Palette.amber} />
        </View>
        <View style={styles.grow}>
          <Text style={styles.cardTitle}>Everything stays on this phone</Text>
          <Text style={styles.cardSubtitle}>Habit never saves or sends what it sees.</Text>
        </View>
      </View>

      <Text style={styles.sectionLabel}>ABOUT</Text>
      <Pressable
        accessibilityRole="button"
        onPress={() => router.push('/known-limits')}
        style={({ pressed }) => [pressed && styles.pressed]}>
        <NeuCard style={styles.rowCard}>
          <Text style={styles.rowTitle}>Known limits</Text>
          <MaterialCommunityIcons name="chevron-right" size={20} color={Palette.textMuted} />
        </NeuCard>
      </Pressable>

      <NeuCard style={styles.rowCard}>
        <View style={styles.versionRow}>
          <Text style={styles.rowTitle}>Version</Text>
          <Text style={styles.version}>{version}</Text>
        </View>
      </NeuCard>
    </TabScreen>
  );
}

const styles = StyleSheet.create({
  header: { paddingTop: Space.sm },
  title: {
    fontFamily: FontNames.interBold,
    fontSize: 24,
    color: Palette.textPrimary,
  },
  sectionLabel: {
    ...Typography.monoLabelSm,
    color: Palette.textSecondary,
    marginTop: Space.sm,
    marginBottom: -4,
  },
  rowCard: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: Space.gutter,
    paddingVertical: 16,
  },
  rowRight: { flexDirection: 'row', alignItems: 'center', gap: Space.sm },
  rowTitle: {
    ...Typography.bodyLg,
    fontFamily: FontNames.interSemiBold,
    color: Palette.textPrimary,
  },
  sensitivityCard: { gap: Space.xs, paddingVertical: 18 },
  cardTitle: {
    ...Typography.headlineMd,
    fontSize: 16,
    lineHeight: 21,
    color: Palette.textPrimary,
  },
  cardSubtitle: {
    ...Typography.bodyMd,
    color: Palette.textSecondary,
  },
  sensitivityControl: { marginTop: Space.gutter },
  note: {
    ...Typography.bodySm,
    color: Palette.textMuted,
    marginTop: Space.sm,
  },
  permissionsCard: { gap: Space.md },
  row: { flexDirection: 'row', alignItems: 'center', gap: Space.gutter },
  permissionRow: { flexDirection: 'row', alignItems: 'center', gap: Space.gutter },
  iconWell: {
    width: 44,
    height: 44,
    borderRadius: Radii.tile,
    backgroundColor: Palette.amberHighlight,
    alignItems: 'center',
    justifyContent: 'center',
  },
  grow: { flex: 1, gap: 2 },
  fixButton: {
    minWidth: 52,
    height: 36,
    borderRadius: Radii.pill,
    backgroundColor: Palette.amber,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 16,
    boxShadow: Shadows.amberGlow,
  },
  fixLabel: {
    ...Typography.bodyMd,
    fontFamily: FontNames.interBold,
    color: Palette.canvas,
  },
  track: {
    height: 8,
    borderRadius: Radii.pill,
    backgroundColor: Palette.well,
    overflow: 'hidden',
  },
  fill: {
    height: '100%',
    borderRadius: Radii.pill,
    backgroundColor: Palette.amber,
  },
  pressed: { opacity: 0.75 },

  alertsCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    paddingVertical: 16,
  },
  alertTitle: {
    ...Typography.bodyLg,
    fontFamily: FontNames.interBold,
    color: Palette.textPrimary,
  },

  privacyCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    padding: 20,
    borderRadius: Radii.card,
    backgroundColor: 'rgba(255,255,255,0.04)',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.12)',
  },

  versionRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: Space.gutter,
  },
  version: {
    fontFamily: FontNames.monoBold,
    fontSize: 14,
    lineHeight: 20,
    color: Palette.textSecondary,
  },
});