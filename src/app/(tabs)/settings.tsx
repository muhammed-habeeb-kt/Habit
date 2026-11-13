import { MaterialCommunityIcons } from '@expo/vector-icons';
import Constants from 'expo-constants';
import { router, useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import {
  NeuCard,
  NeuSegmented,
  NeuSwitch,
  type SegmentOption,
} from '@/components/neumorphic';
import { TabScreen } from '@/components/tab-screen';
import { FontNames, Palette, Radii, Space, Typography } from '@/constants/theme';
import { useAiMonitoring } from '@/hooks/use-ai-monitoring';
import { useProtection } from '@/hooks/use-protection';
import {
  canQueryInstalledPackages,
  getInstalledPackages,
  isMonitoringLocked,
  type AiSensitivity,
} from '@/native/habit';

const SENSITIVITY_OPTIONS: SegmentOption<AiSensitivity>[] = [
  { id: 'low', label: 'Low' },
  { id: 'balanced', label: 'Balanced' },
  { id: 'high', label: 'High' },
];

const MODE_TITLES: Record<string, string> = {
  normal: 'Normal',
  strict: 'Strict',
  aggressive: 'Aggressive',
};

/** Package groups for the eight supported browsers, matching the Browser Blocking screen. */
const BROWSER_GROUPS: string[][] = [
  ['com.android.chrome'],
  ['com.mi.globalbrowser', 'com.android.browser'],
  ['com.brave.browser'],
  ['org.mozilla.firefox'],
  ['com.microsoft.emmx'],
  ['com.sec.android.app.sbrowser'],
  ['com.opera.browser'],
  ['com.duckduckgo.mobile.android'],
];

const BROWSER_PACKAGES = BROWSER_GROUPS.flat();

const PERMISSION_COUNT = 5;
const DIVIDER = 'rgba(255, 255, 255, 0.06)';

/** `3h`, `3h 30m` or `10m` — compact enough for a one-line row subtitle. */
function formatLimit(minutes: number): string {
  const total = Math.max(0, Math.round(minutes));
  const hours = Math.floor(total / 60);
  const rest = total % 60;
  if (hours === 0) return `${rest}m`;
  return rest === 0 ? `${hours}h` : `${hours}h ${rest}m`;
}

export default function SettingsScreen() {
  const { snapshot } = useProtection();
  const {
    settings,
    busy,
    changeSensitivity,
    toggleBlockAlerts,
    toggleIncognitoBlocking,
  } = useAiMonitoring();
  const [installedBrowserCount, setInstalledBrowserCount] = useState(0);

  // Browsers can be installed or removed while Habit is in the background, so the count is
  // re-read every time Settings is shown rather than cached for the whole session.
  useFocusEffect(
    useCallback(() => {
      if (!canQueryInstalledPackages) return;
      let active = true;
      getInstalledPackages(BROWSER_PACKAGES).then((packages) => {
        if (!active) return;
        setInstalledBrowserCount(
          BROWSER_GROUPS.filter((group) => group.some((pkg) => packages.includes(pkg))).length
        );
      });
      return () => {
        active = false;
      };
    }, [])
  );

  const mode = snapshot?.mode ?? 'normal';
  const modeTitle = MODE_TITLES[mode] ?? 'Normal';
  // Same shared native snapshot the Setup screen uses: all five are live Android checks.
  const grantedCount =
    (snapshot?.accessibilityEnabled ? 1 : 0) +
    (snapshot?.notificationsGranted ? 1 : 0) +
    (snapshot?.batteryOptimizationIgnored ? 1 : 0) +
    (snapshot?.restrictedSettingsAllowed ? 1 : 0) +
    (snapshot?.deviceAdminActive ? 1 : 0);
  const sensitivity = settings?.aiSensitivity ?? 'balanced';
  const version = Constants.expoConfig?.version ?? '1.0.0';

  const blockAlertsEnabled = settings?.blockAlertsEnabled ?? true;
  const incognitoBlocking =
    settings?.incognitoBlockingEnabled ?? snapshot?.incognitoBlockingEnabled ?? true;

  const watchedAppsCount = settings?.monitoredAppPackages.length ?? 0;
  const watchedDomainsCount =
    settings?.monitoredDomains.length ?? snapshot?.aiMonitoredDomains?.length ?? 0;

  const removalLocked = isMonitoringLocked(snapshot);
  const challengeLength = snapshot?.configuredChallengeLength ?? 200;
  const waitText = formatLimit(snapshot?.configuredCooldownMinutes ?? 180);
  const offWindowText = formatLimit(snapshot?.configuredOffWindowMinutes ?? 10);

  // Tapping the mode row opens the switcher on Home; while Strict/Aggressive protects the
  // phone the only way to lower it is the Unlock screen, so route there instead.
  const openModeRow = () => {
    if (removalLocked) {
      router.push('/unlock');
      return;
    }
    router.navigate('/');
  };

  const openLockLimits = () => {
    router.push({
      pathname: '/configure-protection',
      params: { mode: mode === 'aggressive' ? 'aggressive' : 'strict' },
    });
  };

  // Turning it ON adds protection and is instant; turning it OFF lowers a
  // Strict/Aggressive lock, so native refuses it and this opens Unlock instead.
  const handleIncognitoToggle = (next: boolean) => {
    if (!next && removalLocked) {
      router.push('/unlock');
      return;
    }
    void toggleIncognitoBlocking(next);
  };

  return (
    <TabScreen>
      <View style={styles.header}>
        <Text style={styles.title}>Settings</Text>
        <Text style={styles.version}>{`Habit v${version}`}</Text>
      </View>

      <Text style={styles.sectionLabel}>PROTECTION RULES</Text>
      <NeuCard padded={false} style={styles.group}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Protection mode"
          onPress={openModeRow}
          style={({ pressed }) => [styles.row, pressed && styles.pressed]}>
          <Text style={styles.rowTitle}>Protection Mode</Text>
          <View style={styles.rowRight}>
            <Text style={styles.valueAmber}>{modeTitle}</Text>
            <MaterialCommunityIcons name="chevron-right" size={16} color={Palette.amber} />
          </View>
        </Pressable>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Configure lock limits"
          onPress={openLockLimits}
          style={({ pressed }) => [
            styles.row,
            styles.rowDivider,
            pressed && styles.pressed,
          ]}>
          <View style={styles.grow}>
            <Text style={styles.rowTitle}>Configure Lock Limits</Text>
            <Text style={styles.rowSubtitle} numberOfLines={1}>
              {`${challengeLength} chars • ${waitText} wait • ${offWindowText} auto`}
            </Text>
          </View>
          <MaterialCommunityIcons name="chevron-right" size={18} color={Palette.textMuted} />
        </Pressable>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel="System permissions"
          onPress={() => router.push('/onboarding')}
          style={({ pressed }) => [
            styles.row,
            styles.rowDivider,
            pressed && styles.pressed,
          ]}>
          <Text style={styles.rowTitle}>System Permissions</Text>
          <View style={styles.rowRight}>
            <Text style={styles.valueGreen}>{`${grantedCount} of ${PERMISSION_COUNT} granted`}</Text>
            <MaterialCommunityIcons name="chevron-right" size={16} color={Palette.success} />
          </View>
        </Pressable>
      </NeuCard>

      <Text style={styles.sectionLabel}>INTERCEPTION ENGINE</Text>
      <NeuCard padded={false} style={styles.group}>
        <View style={styles.row}>
          <View style={styles.sensitivityHeader}>
            <Text style={styles.rowTitle}>AI Sensitivity</Text>
            <Text style={styles.rowHint}>Borderline detection</Text>
          </View>
          <View style={styles.sensitivityControl}>
            <NeuSegmented
              compact
              options={SENSITIVITY_OPTIONS}
              value={sensitivity}
              onChange={(next) => {
                if (!busy) changeSensitivity(next);
              }}
            />
          </View>
        </View>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel="AI Visual Protection"
          onPress={() => router.push('/apps')}
          style={({ pressed }) => [
            styles.row,
            styles.rowDivider,
            pressed && styles.pressed,
          ]}>
          <View style={styles.grow}>
            <Text style={styles.rowTitle}>AI Visual Protection</Text>
            <Text style={styles.rowSubtitle}>
              {`${watchedAppsCount} app • ${watchedDomainsCount} sites watched`}
            </Text>
          </View>
          <View style={styles.rowRight}>
            <Text style={styles.valueAmber}>Manage</Text>
            <MaterialCommunityIcons name="chevron-right" size={16} color={Palette.amber} />
          </View>
        </Pressable>

        <View style={[styles.row, styles.rowDivider]}>
          <View style={styles.grow}>
            <Text style={styles.rowTitle}>Block Incognito & Private</Text>
            <Text style={styles.rowSubtitle}>Across all browsers</Text>
          </View>
          <NeuSwitch
            label="Block Incognito & Private"
            value={incognitoBlocking}
            onValueChange={handleIncognitoToggle}
            disabled={busy || !settings}
          />
        </View>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Protected browsers"
          onPress={() => router.push('/browsers')}
          style={({ pressed }) => [
            styles.row,
            styles.rowDivider,
            pressed && styles.pressed,
          ]}>
          <Text style={styles.rowTitle}>Protected Browsers</Text>
          <View style={styles.rowRight}>
            <Text style={styles.valueGreen}>{`${installedBrowserCount} Protected`}</Text>
            <MaterialCommunityIcons name="chevron-right" size={16} color={Palette.success} />
          </View>
        </Pressable>
      </NeuCard>

      <Text style={styles.sectionLabel}>ALERTS</Text>
      <NeuCard padded={false} style={styles.group}>
        <View style={styles.row}>
          <View style={styles.grow}>
            <Text style={styles.rowTitle}>Block Alerts</Text>
            <Text style={styles.rowSubtitle}>Instant tamper & block alerts</Text>
          </View>
          <NeuSwitch
            label="Block Alerts"
            value={blockAlertsEnabled}
            onValueChange={(next) => {
              if (!busy) toggleBlockAlerts(next);
            }}
          />
        </View>
      </NeuCard>

      <Text style={styles.sectionLabel}>PRIVACY & ARCHITECTURE</Text>
      <NeuCard padded={false} style={styles.group}>
        <View style={styles.row}>
          <Text style={styles.rowTitle}>On-Device Privacy</Text>
          <Text style={styles.valueGreen}>100% Zero-Telemetry</Text>
        </View>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Known limits"
          onPress={() => router.push('/known-limits')}
          style={({ pressed }) => [
            styles.row,
            styles.rowDivider,
            pressed && styles.pressed,
          ]}>
          <Text style={styles.rowTitle}>Known Limits</Text>
          <MaterialCommunityIcons name="chevron-right" size={18} color={Palette.textMuted} />
        </Pressable>
      </NeuCard>
    </TabScreen>
  );
}

const styles = StyleSheet.create({
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: Space.sm,
    paddingTop: Space.sm,
  },
  title: {
    fontFamily: FontNames.interBold,
    fontSize: 24,
    lineHeight: 30,
    color: Palette.textPrimary,
  },
  version: {
    fontFamily: FontNames.monoBold,
    fontSize: 12,
    lineHeight: 16,
    color: Palette.amber,
  },
  sectionLabel: {
    ...Typography.monoLabelSm,
    color: Palette.textSecondary,
    marginBottom: -4,
  },
  group: {
    borderRadius: Radii.control,
    overflow: 'hidden',
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: Space.gutter,
    paddingHorizontal: 16,
    paddingVertical: 14,
  },
  rowDivider: {
    borderTopWidth: 1,
    borderTopColor: DIVIDER,
  },
  rowTitle: {
    ...Typography.bodyLg,
    fontFamily: FontNames.interSemiBold,
    color: Palette.textPrimary,
  },
  rowSubtitle: {
    ...Typography.monoCode,
    fontSize: 10,
    lineHeight: 14,
    color: Palette.textMuted,
    marginTop: 2,
  },
  rowHint: {
    ...Typography.bodySm,
    color: Palette.textMuted,
  },
  rowRight: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 2,
    flexShrink: 0,
  },
  valueAmber: {
    fontFamily: FontNames.interBold,
    fontSize: 13,
    lineHeight: 18,
    color: Palette.amber,
  },
  valueGreen: {
    fontFamily: FontNames.monoBold,
    fontSize: 11,
    lineHeight: 15,
    color: Palette.success,
  },
  grow: { flex: 1, minWidth: 0 },
  sensitivityHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: Space.sm,
  },
  sensitivityControl: { marginTop: Space.sm },
  pressed: { opacity: 0.7 },
});
