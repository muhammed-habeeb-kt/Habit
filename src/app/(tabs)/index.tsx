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
  Shadows,
  Space,
  Typography,
} from '@/constants/theme';
import { useAiMonitoring } from '@/hooks/use-ai-monitoring';
import { useProtection } from '@/hooks/use-protection';
import {
  activateDeviceAdmin,
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

const MODE_LINES: Record<ProtectionMode, string> = {
  normal: 'Normal mode · no restrictions',
  strict: 'Strict mode · unlock required to lower',
  aggressive: 'Aggressive mode · uninstall armoured',
};

/** How often Home re-reads the native snapshot so the live streak keeps counting. */
const STREAK_REFRESH_MS = 5_000;

/**
 * Streak display: `12d 06h`, `06h 05m`, or `42m` under an hour. The value is always whole
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

/** Tiny 22 dp browser chips: just the first letter, never a heavy icon tile. */
const BROWSERS = [
  { name: 'Chrome', letter: 'C' },
  { name: 'Firefox', letter: 'F' },
  { name: 'Samsung Internet', letter: 'S' },
  { name: 'Brave', letter: 'B' },
];

export default function HomeScreen() {
  const { snapshot, busy, toggle, setSnapshot, changeMode, refresh } = useProtection();
  // The AI summary mirrors the native settings, so the counts match the AI tab exactly.
  const {
    settings: aiSettings,
    busy: aiBusy,
    toggleMaster: toggleAiMaster,
  } = useAiMonitoring();
  const askedForSetup = useRef(false);
  const [offSheetVisible, setOffSheetVisible] = useState(false);
  const [offBusy, setOffBusy] = useState(false);

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
  const watchedApps = aiSettings?.monitoredAppPackages.length ?? 0;
  const watchedSites = aiSettings?.monitoredDomains.length ?? 0;
  const aiSubtitle = aiOn
    ? `Active • ${watchedApps} ${watchedApps === 1 ? 'app' : 'apps'} • ${watchedSites} ${
        watchedSites === 1 ? 'site' : 'sites'
      } watched`
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
      // Device admin is a one-time grant. When it is already active, raise the mode
      // straight away instead of showing the activation sheet again.
      if (snapshot.deviceAdminActive) {
        void changeMode('aggressive');
        return;
      }
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
          <MaterialCommunityIcons name="shield-outline" size={20} color={Palette.amber} />
        </View>
        <Text style={styles.headerTitle} numberOfLines={1}>
          Habit
        </Text>
        <StatusPill
          label={enabled ? 'PROTECTION ON' : 'PROTECTION OFF'}
          tone={enabled ? 'amber' : 'neutral'}
          dot
        />
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

      <View style={styles.hero}>
        <MasterDial enabled={enabled} locked={locked} busy={busy} onPress={handleToggle} />
        <View style={styles.heroCaption}>
          <Text style={styles.heroTitle}>{enabled ? 'Shield Active' : 'Protection Off'}</Text>
          <Text style={styles.heroSubtitle} numberOfLines={1}>
            {MODE_LINES[snapshot.mode]}
          </Text>
        </View>
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

      <NeuCard style={styles.modeCard}>
        <NeuSegmented
          options={MODE_OPTIONS}
          value={snapshot.mode}
          onChange={handleMode}
          variant="solid"
        />
        <Text style={styles.modeHint} numberOfLines={1}>
          {MODE_DESCRIPTIONS[snapshot.mode]}
        </Text>
      </NeuCard>

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

      <View style={styles.metricsRow}>
        <NeuCard style={styles.metricCard}>
          <View style={styles.metricTopRow}>
            <Text style={styles.metricLabel}>Clean Streak</Text>
            <View style={styles.metricBadge}>
              <MaterialCommunityIcons name="fire" size={14} color={Palette.amber} />
            </View>
          </View>
          <Text
            style={styles.metricValue}
            numberOfLines={1}
            adjustsFontSizeToFit
            minimumFontScale={0.7}>
            {formatStreak(currentStreakMs)}
          </Text>
          <View style={styles.metricFooter}>
            <Text style={styles.metricFoot} numberOfLines={1}>
              {`Best: ${formatStreak(bestStreakMs)}`}
            </Text>
            <Text style={styles.metricFoot} numberOfLines={1}>
              Resets on block
            </Text>
          </View>
        </NeuCard>

        <NeuCard style={styles.metricCard}>
          <View style={styles.metricTopRow}>
            <Text style={styles.metricLabel}>Blocked Today</Text>
            <View style={styles.metricBadge}>
              <MaterialCommunityIcons name="shield-outline" size={14} color={Palette.amber} />
            </View>
          </View>
          <Text
            style={styles.metricValue}
            numberOfLines={1}
            adjustsFontSizeToFit
            minimumFontScale={0.7}>
            {snapshot.attemptsToday}
          </Text>
          <View style={styles.metricFooter}>
            <Text style={styles.metricFoot}>All monitored</Text>
            <Text style={styles.metricFoot}>channels secure</Text>
          </View>
        </NeuCard>
      </View>

      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Open Browser Blocking"
        onPress={() => router.push('/browsers')}
        style={({ pressed }) => [pressed && styles.pressed]}>
        <NeuCard style={styles.channelCard}>
          <View style={styles.channelTile}>
            <MaterialCommunityIcons
              name="shield-outline"
              size={20}
              color={enabled ? Palette.amber : Palette.textMuted}
            />
          </View>
          <View style={styles.grow}>
            <Text style={styles.cardTitle}>Browser Blocking</Text>
            <Text style={styles.cardSubtitle} numberOfLines={1}>
              {enabled ? `Active • Watching ${BROWSERS.length} browsers` : 'Paused'}
            </Text>
            <View style={styles.browserRow}>
              {BROWSERS.map((browser) => (
                <View key={browser.name} style={styles.browserBadge}>
                  <Text style={styles.browserLetter}>{browser.letter}</Text>
                </View>
              ))}
            </View>
          </View>
          <NeuSwitch
            value={enabled}
            onValueChange={handleToggle}
            disabled={busy}
            label="Browser Blocking"
          />
        </NeuCard>
      </Pressable>

      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Open AI Content Guard"
        onPress={() => router.push('/apps')}
        style={({ pressed }) => [pressed && styles.pressed]}>
        <NeuCard style={styles.channelCard}>
          <View style={styles.channelTile}>
            <MaterialCommunityIcons
              name="star-four-points-outline"
              size={20}
              color={aiOn ? Palette.amber : Palette.textMuted}
            />
          </View>
          <View style={styles.grow}>
            <Text style={styles.cardTitle}>AI Content Guard</Text>
            <Text style={styles.cardSubtitle} numberOfLines={2}>
              {aiSubtitle}
            </Text>
          </View>
          <NeuSwitch
            value={aiOn}
            onValueChange={handleAiToggle}
            disabled={aiBusy || !aiSettings}
            label="AI Content Guard"
          />
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
  logoTile: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: Palette.raised,
    boxShadow: Shadows.raisedSmall,
    alignItems: 'center',
    justifyContent: 'center',
  },
  headerTitle: {
    ...Typography.headlineLg,
    fontSize: 20,
    color: Palette.textPrimary,
    flex: 1,
    flexShrink: 1,
  },
  hero: { alignItems: 'center', gap: Space.md, paddingTop: Space.xs },
  heroCaption: { alignItems: 'center', gap: 2 },
  heroTitle: {
    ...Typography.headlineLg,
    color: Palette.textPrimary,
  },
  heroSubtitle: {
    ...Typography.bodyMd,
    color: Palette.textSecondary,
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
  modeCard: {
    gap: Space.gutter,
    paddingVertical: 16,
  },
  modeHint: {
    ...Typography.bodyMd,
    color: Palette.textSecondary,
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
  metricsRow: {
    flexDirection: 'row',
    gap: Space.gutter,
  },
  metricCard: {
    flex: 1,
    minWidth: 0,
    padding: 16,
    gap: Space.sm,
  },
  metricTopRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 4,
  },
  metricLabel: {
    ...Typography.bodyMd,
    fontFamily: FontNames.interMedium,
    color: Palette.textSecondary,
    flexShrink: 1,
  },
  metricBadge: {
    width: 24,
    height: 24,
    borderRadius: 12,
    backgroundColor: Palette.amberHighlight,
    alignItems: 'center',
    justifyContent: 'center',
  },
  metricValue: {
    ...Typography.displayMd,
    fontSize: 26,
    lineHeight: 32,
    color: Palette.amber,
  },
  metricFooter: { gap: 1 },
  metricFoot: {
    ...Typography.bodySm,
    fontSize: 10,
    lineHeight: 14,
    color: Palette.textMuted,
  },
  channelCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    paddingVertical: 14,
    paddingHorizontal: 16,
  },
  channelTile: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: Palette.amberHighlight,
    alignItems: 'center',
    justifyContent: 'center',
  },
  cardTitle: {
    ...Typography.headlineMd,
    fontSize: 15,
    lineHeight: 20,
    color: Palette.textPrimary,
  },
  cardSubtitle: {
    ...Typography.bodyMd,
    color: Palette.textSecondary,
  },
  browserRow: {
    flexDirection: 'row',
    gap: 4,
    marginTop: 2,
  },
  browserBadge: {
    width: 22,
    height: 22,
    borderRadius: 11,
    backgroundColor: Palette.raised,
    boxShadow: Shadows.raisedSmall,
    alignItems: 'center',
    justifyContent: 'center',
  },
  browserLetter: {
    fontFamily: FontNames.monoBold,
    fontSize: 10,
    lineHeight: 14,
    color: Palette.textPrimary,
  },
  grow: { flex: 1, gap: 2 },
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
