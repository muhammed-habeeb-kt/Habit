import { MaterialCommunityIcons } from '@expo/vector-icons';
import { router } from 'expo-router';
import { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  AppState,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import {
  AppTile,
  NeuCard,
  NeuSwitch,
  StatusPill,
} from '@/components/neumorphic';
import { Palette, Radii, Shadows, Space, Typography } from '@/constants/theme';
import { useProtection } from '@/hooks/use-protection';
import { canQueryInstalledPackages, getInstalledPackages } from '@/native/habit';

type BrowserInfo = {
  name: string;
  /** All package names the native filter watches for this browser. */
  packageNames: string[];
};

const BROWSERS: BrowserInfo[] = [
  { name: 'Chrome', packageNames: ['com.android.chrome'] },
  { name: 'Mi Browser', packageNames: ['com.mi.globalbrowser', 'com.android.browser'] },
  { name: 'Brave', packageNames: ['com.brave.browser'] },
  { name: 'Firefox', packageNames: ['org.mozilla.firefox'] },
  { name: 'Edge', packageNames: ['com.microsoft.emmx'] },
  { name: 'Samsung Internet', packageNames: ['com.sec.android.app.sbrowser'] },
  { name: 'Opera', packageNames: ['com.opera.browser'] },
  { name: 'DuckDuckGo', packageNames: ['com.duckduckgo.mobile.android'] },
];

export default function BrowserBlockingScreen() {
  const { snapshot, busy, toggle } = useProtection();
  const [installed, setInstalled] = useState<string[] | null>(null);

  useEffect(() => {
    if (!canQueryInstalledPackages) return;
    let mounted = true;
    const packages = BROWSERS.flatMap((browser) => browser.packageNames);
    const refresh = () => {
      getInstalledPackages(packages).then((installedPackages) => {
        if (mounted) setInstalled(installedPackages);
      });
    };
    refresh();
    // A browser may have been installed while Habit was in the background.
    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active') refresh();
    });
    return () => {
      mounted = false;
      subscription.remove();
    };
  }, []);

  const enabled = snapshot?.protectionEnabled ?? false;

  const handleToggle = () => {
    if (snapshot && snapshot.mode !== 'normal' && snapshot.protectionEnabled) {
      router.push('/unlock');
      return;
    }
    toggle();
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
              <MaterialCommunityIcons name="chevron-left" size={26} color={Palette.textPrimary} />
            </Pressable>
            <Text style={styles.title}>Browser Blocking</Text>
          </View>

          <NeuCard style={styles.masterCard}>
            <View style={styles.iconTile}>
              <MaterialCommunityIcons name="shield-outline" size={22} color={Palette.amber} />
            </View>
            <View style={styles.grow}>
              <Text style={styles.cardTitle}>Browser Blocking</Text>
              <Text style={styles.cardSubtitle}>
                {enabled ? 'Active • Watching all browsers' : 'Paused'}
              </Text>
            </View>
            {snapshot ? (
              <NeuSwitch
                value={enabled}
                onValueChange={handleToggle}
                disabled={busy}
                label="Browser Blocking"
              />
            ) : (
              <ActivityIndicator color={Palette.amber} />
            )}
          </NeuCard>

          <Text style={styles.sectionLabel}>BROWSERS PROTECTED</Text>

          <View style={styles.list}>
            {BROWSERS.map((browser) => {
              const isInstalled = browser.packageNames.some((pkg) =>
                installed?.includes(pkg)
              );
              const knownMissing = installed !== null && !isInstalled;
              let label = 'UNAVAILABLE';
              let tone: 'success' | 'neutral' = 'neutral';
              let dot = false;
              if (canQueryInstalledPackages) {
                if (installed === null) {
                  label = 'CHECKING';
                } else if (!isInstalled) {
                  label = 'NOT INSTALLED';
                } else if (!enabled) {
                  label = 'PAUSED';
                } else {
                  label = 'PROTECTED';
                  tone = 'success';
                  dot = true;
                }
              }

              return (
                <NeuCard
                  key={browser.packageNames[0]}
                  padded={false}
                  style={[styles.row, knownMissing && styles.rowMuted]}>
                  <AppTile name={browser.name} muted={knownMissing} />
                  <Text style={styles.rowTitle}>{browser.name}</Text>
                  <StatusPill label={label} tone={tone} dot={dot} />
                </NeuCard>
              );
            })}
          </View>
        </ScrollView>
      </SafeAreaView>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: Palette.canvas },
  safe: { flex: 1 },
  content: {
    paddingHorizontal: Space.margin,
    paddingTop: Space.sm,
    paddingBottom: Space.xl,
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
  },
  masterCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    paddingVertical: 14,
  },
  iconTile: {
    width: Radii.tileSize,
    height: Radii.tileSize,
    borderRadius: Radii.tile,
    backgroundColor: Palette.amberHighlight,
    alignItems: 'center',
    justifyContent: 'center',
  },
  grow: { flex: 1, gap: 2 },
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
  sectionLabel: {
    ...Typography.monoLabelSm,
    color: Palette.textSecondary,
    marginTop: Space.sm,
    marginBottom: -4,
  },
  list: { gap: Space.gutter },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    paddingHorizontal: 12,
    paddingVertical: 12,
    borderRadius: Radii.control,
  },
  rowMuted: { opacity: 0.5 },
  rowTitle: {
    ...Typography.headlineMd,
    fontSize: 15,
    lineHeight: 20,
    color: Palette.textPrimary,
    flex: 1,
  },
  pressed: { opacity: 0.7 },
});
