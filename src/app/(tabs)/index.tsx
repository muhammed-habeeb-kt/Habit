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
import { useProtection } from '@/hooks/use-protection';
import { activateDeviceAdmin, getInstalledApps, type ProtectionMode } from '@/native/habit';

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

const BROWSERS = [
  { name: 'Chrome', packages: ['com.android.chrome'] },
  { name: 'Firefox', packages: ['org.mozilla.firefox'] },
  { name: 'Samsung Internet', packages: ['com.sec.android.app.sbrowser'] },
  { name: 'Brave', packages: ['com.brave.browser'] },
];

export default function HomeScreen() {
  const { snapshot, busy, toggle } = useProtection();
  const askedForSetup = useRef(false);
  const [browserIcons, setBrowserIcons] = useState<Record<string, string>>({});

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
  const locked = snapshot.mode !== 'normal' && enabled;
  const offWindowActive = snapshot.unlock.offWindowActive && !enabled;
  const cooldownActive = snapshot.unlock.cooldownActive;

  const handleToggle = () => {
    if (locked) {
      router.push('/unlock');
      return;
    }
    toggle();
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
        onPress={() => router.push('/onboarding')}
        style={({ pressed }) => [styles.setupLink, pressed && styles.pressed]}>
        <MaterialCommunityIcons name="cog-outline" size={16} color={Palette.textMuted} />
        <Text style={styles.setupLabel}>Permissions & setup</Text>
      </Pressable>

      <Text style={styles.footer}>
        Habit never stores the pages you visit — only a daily count stays on this phone.
      </Text>
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
