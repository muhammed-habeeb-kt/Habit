import { MaterialCommunityIcons } from '@expo/vector-icons';
import { router } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import { formatDuration, useCountdown } from '@/components/countdown';
import { formatClockMinutes } from '@/components/duration-wheels';
import { OffWindowSheet } from '@/components/off-window-sheet';
import {
  AppTile,
  FrostedBanner,
  MasterDial,
  NeuCard,
  NeuSegmented,
  NeuSwitch,
  NeuWell,
  PrimaryGlowButton,
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
import {
  activateDeviceAdmin,
  getInstalledApps,
  setProtectionEnabled,
  startTimedOffWindow,
  type ProtectionMode,
} from '@/native/habit';

const MODE_OPTIONS: SegmentOption<ProtectionMode>[] = [
  { id: 'normal', label: 'Normal' },
  { id: 'strict', label: 'Strict' },
  { id: 'aggressive', label: 'Aggressive' },
];

const MODE_DESCRIPTIONS: Record<ProtectionMode, string> = {
  normal: 'Protection can be switched on or off any time.',
  strict: "Strict: protection can't be turned off without unlocking.",
  aggressive: 'Aggressive adds device-admin uninstall protection.',
};

const DAY_MS = 24 * 60 * 60 * 1000;
/** How often Home re-reads the native snapshot so the live streak keeps counting. */
const STREAK_REFRESH_MS = 5_000;

/**
 * Streak display: `12d 06h`, `04h 18m`, or `42m` under an hour. The value is always whole
 * minutes, so the card never shows a jumpy seconds counter.
 */
function formatStreak(ms: number): string {
  const totalMinutes = Math.max(0, Math.floor(ms / 60_000));
  const days = Math.floor(totalMinutes / (24 * 60));
  const hours = Math.floor((totalMinutes % (24 * 60)) / 60);
  const minutes = totalMinutes % 60;
  const pad = (value: number) => String(value).padStart(2, '0');
  if (days >= 1) return `${days}d ${pad(hours)}h`;
  if (hours >= 1) return `${pad(hours)}h ${pad(minutes)}m`;
  return `${minutes}m`;
}

/** `• PAUSED` while protection is off, then the highest milestone reached. */
function streakMilestone(ms: number, protectionOn: boolean): string {
  if (!protectionOn) return '• PAUSED';
  if (ms >= 30 * DAY_MS) return '• 30 DAYS';
  if (ms >= 7 * DAY_MS) return '• 1 WEEK';
  if (ms >= 3 * DAY_MS) return '• 3 DAY STREAK';
  if (ms >= DAY_MS) return '• 1+ DAY';
  return '• ACTIVE';
}

const BROWSERS = [
  { name: 'Chrome', packages: ['com.android.chrome'] },
  { name: 'Firefox', packages: ['org.mozilla.firefox'] },
  { name: 'Samsung Internet', packages: ['com.sec.android.app.sbrowser'] },
  { name: 'Brave', packages: ['com.brave.browser'] },
];

export default function HomeScreen() {
  const { snapshot, busy, toggle, setSnapshot, refresh } = useProtection();
  // The AI summary mirrors the native settings, so the counts match the AI tab exactly.
  const {
    settings: aiSettings,
    busy: aiBusy,
    toggleMaster: toggleAiMaster,
  } = useAiMonitoring();
  const askedForSetup = useRef(false);
  const [browserIcons, setBrowserIcons] = useState<Record<string, string>>({});
  const [offSheetVisible, setOffSheetVisible] = useState(false);
  const [offBusy, setOffBusy] = useState(false);

  useEffect(() => {
    let mounted = true;
    getInstalledApps().then((apps) => {
      if (!mounted) return;
      const icons: Record<string, string> = {};
      for (const app of apps) {
        if (app.iconUri) icons[app.packageName] = app.iconUri;
      }
      setBrowserIcons(icons);
    });
    return () => {
      mounted = false;
    };
  }, []);

  const cooldownRemaining = useCountdown(snapshot?.unlock.cooldownRemainingMs ?? 0);
  const offWindowRemaining = useCountdown(snapshot?.unlock.offWindowRemainingMs ?? 0);

  // Native counts the streak every second while protection runs; this keeps the card in
  // step without a per-second bridge call. An OFF window pauses it, so no polling then.
  const streakRunning =
    snapshot?.protectionEnabled === true && snapshot.unlock.offWindowActive === false;
  useEffect(() => {
    if (!streakRunning) return;
    const interval = setInterval(() => {
      void refresh();
    }, STREAK_REFRESH_MS);
    return () => clearInterval(interval);
  }, [streakRunning, refresh]);

  useEffect(() => {
    if (!snapshot || askedForSetup.current) return;
    if (!snapshot.onboardingCompleted) {
      askedForSetup.current = true;
      router.push('/onboarding');
    }
  }, [snapshot]);

  if (!snapshot) {
    return (
      <View style={styles.loading}>
        <ActivityIndicator color={Palette.amber} />
      </View>
    );
  }

  const enabled = snapshot.protectionEnabled;
  // `?? 0` keeps an older development APK (no streak in its native snapshot) harmless.
  const currentStreakMs = Math.max(0, snapshot.currentStreakMs ?? 0);
  const bestStreakMs = Math.max(0, snapshot.bestStreakMs ?? 0);
  const locked = snapshot.mode !== 'normal' && enabled;
  const offWindowActive = snapshot.unlock.offWindowActive && !enabled;
  const cooldownActive = snapshot.unlock.cooldownActive;

  const handleToggle = () => {
    if (locked) {
      router.push('/unlock');
      return;
    }
    // Turning Normal-mode protection off lets the user pick its auto-restart window first.
    if (enabled) {
      setOffSheetVisible(true);
      return;
    }
    toggle();
  };

  const applyOffWindow = async (minutes: number | null) => {
    if (offBusy) return;
    setOffBusy(true);
    try {
      const next =
        minutes === null
          ? await setProtectionEnabled(false)
          : await startTimedOffWindow(minutes);
      setSnapshot(next);
      setOffSheetVisible(false);
    } finally {
      setOffBusy(false);
    }
  };

  const aiOn = aiSettings?.enabled ?? false;
  const aiSubtitle = aiOn
    ? `Active • ${aiSettings?.monitoredAppPackages.length ?? 0} apps • ${
        aiSettings?.monitoredDomains.length ?? 0
      } sites watched`
    : 'Paused';

  // Turning AI monitoring off removes protection, so a locked Strict/Aggressive setup asks
  // for the unlock first. Turning it on is always instant.
  const handleAiToggle = (next: boolean) => {
    if (!next && locked) {
      router.push('/unlock');
      return;
    }
    void toggleAiMaster(next);
  };

  const handleMode = (id: ProtectionMode) => {
    if (id === snapshot.mode) return;

    // Raising from Normal always configures the lock first: challenge length and the
    // wait-it-out timer are chosen before Strict/Aggressive can turn on.
    if (snapshot.mode === 'normal' && (id === 'strict' || id === 'aggressive')) {
      router.push({ pathname: '/configure-protection', params: { mode: id } });
      return;
    }

    if (id === 'aggressive') {
      // The bottom sheet explains device admin and requests it when needed.
      router.push('/aggressive');
      return;
    }

    // Lowering Strict/Aggressive needs an unlock (challenge or cooldown).
    if (snapshot.mode !== 'normal') {
      router.push('/unlock');
    }
  };

  return (
    <TabScreen>
      <View style={styles.header}>
        <View style={styles.logoTile}>
          <MaterialCommunityIcons name="shield-outline" size={22} color={Palette.amber} />
        </View>
        <Text style={styles.headerTitle} numberOfLines={1}>
          Habit
        </Text>
        <View style={styles.headerBadge}>
          <StatusPill
            label={enabled ? 'PROTECTION ON' : 'PROTECTION OFF'}
            tone={enabled ? 'amber' : 'neutral'}
            dot
          />
        </View>
      </View>

      {!snapshot.accessibilityEnabled && (
        <FrostedBanner
          title="Accessibility is off"
          body="Turn it on so Habit can protect you."
          actionLabel="Fix now"
          onPress={() => router.push('/onboarding')}
        />
      )}

      {snapshot.mode === 'aggressive' && !snapshot.deviceAdminActive && (
        <FrostedBanner
          title="Device admin is off"
          body="Aggressive mode can no longer stop a normal uninstall."
          icon="shield-alert-outline"
          actionLabel="Fix now"
          onPress={() => {
            void activateDeviceAdmin();
          }}
        />
      )}

      <View style={styles.dialWrap}>
        <MasterDial enabled={enabled} locked={locked} busy={busy} onPress={handleToggle} />
        <Text style={styles.dialCaption}>{enabled ? 'Protection ON' : 'Protection OFF'}</Text>
      </View>

      {offWindowActive && (
        <NeuCard style={styles.offWindow}>
          <View style={styles.offRow}>
            <View style={styles.offIconTile}>
              <MaterialCommunityIcons name="clock-outline" size={20} color={Palette.amber} />
            </View>
            <View style={styles.grow}>
              <Text style={styles.offLabel}>Back on in</Text>
              <Text style={styles.offCountdown}>{formatDuration(offWindowRemaining)}</Text>
              <Text style={styles.offHint}>
                {`Auto-restart timer · ${formatClockMinutes(
                  Math.round(snapshot.unlock.offWindowTotalMs / 60_000) ||
                    snapshot.configuredOffWindowMinutes
                )}`}
              </Text>
            </View>
            <PrimaryGlowButton
              label="Turn on now"
              onPress={toggle}
              style={styles.turnOnButton}
            />
          </View>
        </NeuCard>
      )}

      {cooldownActive && (
        <NeuWell style={styles.cooldown}>
          <View style={styles.rowBetween}>
            <View>
              <Text style={styles.monoLabel}>UNLOCK COOLDOWN</Text>
              <Text style={styles.cooldownValue}>{formatDuration(cooldownRemaining)}</Text>
            </View>
            <Pressable
              accessibilityRole="button"
              onPress={() => router.push('/unlock')}
              style={({ pressed }) => [styles.link, pressed && styles.pressed]}>
              <Text style={styles.linkLabel}>Manage</Text>
            </Pressable>
          </View>
        </NeuWell>
      )}

      <NeuCard style={styles.modeCard}>
        <NeuSegmented options={MODE_OPTIONS} value={snapshot.mode} onChange={handleMode} />
        <Text style={styles.modeHint}>{MODE_DESCRIPTIONS[snapshot.mode]}</Text>
      </NeuCard>

      <NeuCard style={styles.streakCard}>
        <View style={styles.streakRow}>
          <View style={styles.streakIconTile}>
            <MaterialCommunityIcons name="fire" size={22} color={Palette.amber} />
          </View>
          <View style={styles.grow}>
            <View style={styles.streakTitleRow}>
              <Text style={[styles.cardTitle, styles.streakTitle]}>Clean streak</Text>
              <View style={styles.streakMilestone}>
                <Text style={styles.streakMilestoneText}>
                  {streakMilestone(currentStreakMs, enabled)}
                </Text>
              </View>
            </View>
            <Text style={styles.cardSubtitle}>
              {`Best record: ${formatStreak(bestStreakMs)} • Resets on block or unlock`}
            </Text>
          </View>
          <Text style={styles.streakValue}>{formatStreak(currentStreakMs)}</Text>
        </View>
      </NeuCard>

      <NeuCard style={styles.metricCard}>
        <View style={styles.metricText}>
          <Text style={styles.cardTitle}>Blocked attempts today</Text>
          <Text style={styles.cardSubtitle}>All monitored channels</Text>
        </View>
        <Text style={styles.metricValue}>{snapshot.attemptsToday}</Text>
      </NeuCard>

      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Open Browser Blocking"
        onPress={() => router.push('/browsers')}
        style={({ pressed }) => [pressed && styles.pressed]}>
        <NeuCard style={styles.browserCard}>
          <View style={styles.rowBetween}>
            <View style={styles.browserLabel}>
              <View style={styles.iconWell}>
                <MaterialCommunityIcons
                  name="shield-outline"
                  size={20}
                  color={enabled ? Palette.amber : Palette.textMuted}
                />
              </View>
              <View style={styles.grow}>
                <Text style={styles.cardTitle}>Browser Blocking</Text>
                <Text style={styles.cardSubtitle}>
                  {enabled ? 'Active • Watching all browsers' : 'Paused'}
                </Text>
              </View>
            </View>
            <NeuSwitch
              value={enabled}
              onValueChange={handleToggle}
              disabled={busy}
              label="Browser Blocking"
            />
          </View>
          <View style={styles.tiles}>
            {BROWSERS.map((browser) => (
              <AppTile
                key={browser.name}
                name={browser.name}
                iconUri={browser.packages.map((pkg) => browserIcons[pkg]).find(Boolean)}
                muted={!enabled}
              />
            ))}
          </View>
        </NeuCard>
      </Pressable>

      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Open AI Monitoring"
        onPress={() => router.push('/apps')}
        style={({ pressed }) => [pressed && styles.pressed]}>
        <NeuCard style={styles.browserCard}>
          <View style={styles.rowBetween}>
            <View style={styles.browserLabel}>
              <View style={styles.iconWell}>
                <MaterialCommunityIcons
                  name="star-four-points-outline"
                  size={20}
                  color={aiOn ? Palette.amber : Palette.textMuted}
                />
              </View>
              <View style={styles.grow}>
                <Text style={styles.cardTitle}>AI Monitoring</Text>
                <Text style={styles.cardSubtitle}>{aiSubtitle}</Text>
              </View>
            </View>
            <NeuSwitch
              value={aiOn}
              onValueChange={handleAiToggle}
              disabled={aiBusy || !aiSettings}
              label="AI Monitoring"
            />
          </View>
        </NeuCard>
      </Pressable>

      <Pressable
        accessibilityRole="button"
        onPress={() => router.push('/onboarding')}
        style={({ pressed }) => [styles.setupLink, pressed && styles.pressed]}>
        <MaterialCommunityIcons name="cog-outline" size={16} color={Palette.textMuted} />
        <Text style={styles.setupLabel}>Permissions & setup</Text>
      </Pressable>

      <Text style={styles.footer}>
        Habit never stores the pages you visit — only a daily count stays on this phone.
      </Text>

      <OffWindowSheet
        visible={offSheetVisible}
        initialMinutes={snapshot.configuredOffWindowMinutes}
        busy={offBusy}
        onClose={() => setOffSheetVisible(false)}
        onConfirm={applyOffWindow}
      />
    </TabScreen>
  );
}

const styles = StyleSheet.create({
  loading: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: Palette.canvas,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.sm,
    paddingVertical: Space.sm,
  },
  /** Reserves the trailing slot so the badge never runs into another header icon. */
  headerBadge: {
    flexShrink: 0,
    marginLeft: 'auto',
  },
  logoTile: {
    width: Radii.tileSize,
    height: Radii.tileSize,
    borderRadius: Radii.tile,
    backgroundColor: Palette.raised,
    boxShadow: Shadows.raisedSmall,
    alignItems: 'center',
    justifyContent: 'center',
  },
  headerTitle: {
    ...Typography.headlineLg,
    color: Palette.textPrimary,
    flex: 1,
    flexShrink: 1,
  },
  dialWrap: { alignItems: 'center', gap: Space.md, paddingTop: Space.md },
  dialCaption: {
    ...Typography.headlineLg,
    color: Palette.textPrimary,
  },
  rowBetween: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: Space.gutter,
  },
  offWindow: { paddingVertical: 16 },
  offRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
  },
  offIconTile: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: Palette.amberHighlight,
    alignItems: 'center',
    justifyContent: 'center',
  },
  offLabel: {
    ...Typography.bodyMd,
    color: Palette.textSecondary,
  },
  offCountdown: {
    fontFamily: FontNames.monoBold,
    fontSize: 24,
    lineHeight: 30,
    color: Palette.amber,
  },
  offHint: {
    ...Typography.monoLabelSm,
    fontSize: 9,
    lineHeight: 12,
    color: Palette.textMuted,
  },
  turnOnButton: {
    minHeight: 40,
    paddingVertical: 8,
    paddingHorizontal: 14,
  },
  cooldown: { paddingVertical: 14 },
  monoLabel: {
    ...Typography.monoLabelSm,
    color: Palette.textMuted,
  },
  cooldownValue: {
    ...Typography.headlineMd,
    color: Palette.textPrimary,
    marginTop: 2,
  },
  link: { paddingVertical: 8, paddingHorizontal: 8 },
  linkLabel: {
    ...Typography.bodyMd,
    fontFamily: FontNames.interSemiBold,
    color: Palette.amber,
  },
  modeCard: { gap: Space.md },
  modeHint: {
    ...Typography.bodyMd,
    color: Palette.textSecondary,
  },
  streakCard: {
    paddingVertical: 18,
  },
  streakRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
  },
  /** Raised amber tile with the flame; the halo is what makes it glow. */
  streakIconTile: {
    width: Radii.tileSize,
    height: Radii.tileSize,
    borderRadius: Radii.tile,
    backgroundColor: Palette.amberHighlight,
    boxShadow: `${Shadows.raisedSmall}, ${Shadows.amberHalo}`,
    alignItems: 'center',
    justifyContent: 'center',
  },
  streakTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    flexWrap: 'wrap',
  },
  streakTitle: {
    fontFamily: FontNames.interBold,
  },
  streakMilestone: {
    borderRadius: Radii.pill,
    backgroundColor: Palette.amberHighlight,
    paddingHorizontal: 8,
    paddingVertical: 2,
  },
  streakMilestoneText: {
    ...Typography.monoLabelSm,
    color: Palette.amber,
  },
  streakValue: {
    fontFamily: FontNames.monoBold,
    fontSize: 26,
    lineHeight: 32,
    letterSpacing: -0.5,
    color: Palette.amber,
    flexShrink: 0,
  },
  metricCard: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 18,
  },
  metricText: { flex: 1, gap: 3 },
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
  metricValue: {
    ...Typography.displayMd,
    color: Palette.amber,
  },
  browserCard: { gap: Space.md },
  browserLabel: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    flex: 1,
  },
  iconWell: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: Palette.amberHighlight,
    alignItems: 'center',
    justifyContent: 'center',
  },
  grow: { flex: 1, gap: 2 },
  tiles: {
    flexDirection: 'row',
    gap: Space.sm,
  },
  setupLink: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    paddingVertical: Space.sm,
  },
  setupLabel: {
    ...Typography.bodyMd,
    color: Palette.textMuted,
  },
  footer: {
    ...Typography.bodySm,
    color: Palette.textMuted,
    textAlign: 'center',
    paddingHorizontal: Space.md,
  },
  pressed: { opacity: 0.6 },
});
