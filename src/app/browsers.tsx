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

import { NeuCard, NeuSwitch, StatusPill } from '@/components/neumorphic';
import { FontNames, Palette, Radii, Shadows, Space, Typography } from '@/constants/theme';
import { useProtection } from '@/hooks/use-protection';
import { canQueryInstalledPackages, getInstalledPackages } from '@/native/habit';

type BrowserInfo = {
  name: string;
  /** Compact label used by the standby grid. */
  short: string;
  /** One-line role shown under the name of an installed browser. */
  subtitle: string;
  /** Brand glyph shown inside the dark icon well (never a harsh white app tile). */
  icon: keyof typeof MaterialCommunityIcons.glyphMap;
  /** All package names the native filter watches for this browser. */
  packageNames: string[];
};

const BROWSERS: BrowserInfo[] = [
  {
    name: 'Google Chrome',
    short: 'Chrome',
    subtitle: 'Primary Browser',
    icon: 'google-chrome',
    packageNames: ['com.android.chrome'],
  },
  {
    name: 'Mi Browser',
    short: 'Mi Browser',
    subtitle: 'Stock Browser',
    icon: 'web',
    packageNames: ['com.mi.globalbrowser', 'com.android.browser'],
  },
  {
    name: 'Brave Browser',
    short: 'Brave',
    subtitle: 'Private Shield',
    icon: 'shield-outline',
    packageNames: ['com.brave.browser'],
  },
  {
    name: 'Firefox',
    short: 'Firefox',
    subtitle: 'Open Source',
    icon: 'firefox',
    packageNames: ['org.mozilla.firefox'],
  },
  {
    name: 'MS Edge',
    short: 'MS Edge',
    subtitle: 'Microsoft',
    icon: 'microsoft-edge',
    packageNames: ['com.microsoft.emmx'],
  },
  {
    name: 'Samsung Internet',
    short: 'Samsung',
    subtitle: 'Samsung',
    icon: 'compass-outline',
    packageNames: ['com.sec.android.app.sbrowser'],
  },
  {
    name: 'Opera',
    short: 'Opera',
    subtitle: 'Opera Browser',
    icon: 'opera',
    packageNames: ['com.opera.browser'],
  },
  {
    name: 'DuckDuckGo',
    short: 'DuckDuckGo',
    subtitle: 'Private Search',
    icon: 'duck',
    packageNames: ['com.duckduckgo.mobile.android'],
  },
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

  const isInstalled = (browser: BrowserInfo) =>
    browser.packageNames.some((pkg) => installed?.includes(pkg));

  const installedBrowsers = BROWSERS.filter(isInstalled);
  const standbyBrowsers = BROWSERS.filter((browser) => !isInstalled(browser));
  const statusLabel = enabled ? 'ACTIVE' : 'PAUSED';

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
              <MaterialCommunityIcons name="arrow-left" size={20} color={Palette.textPrimary} />
            </Pressable>
            <Text style={styles.title} numberOfLines={1}>
              Browser Blocking
            </Text>
            <StatusPill
              label={enabled ? 'ONLINE' : 'PAUSED'}
              tone={enabled ? 'success' : 'neutral'}
              dot
            />
          </View>

          <NeuCard style={styles.masterCard}>
            <View style={styles.grow}>
              <Text style={styles.cardTitle}>Accessibility Interceptor</Text>
              <Text style={styles.cardSubtitle}>
                {`Watching ${installedBrowsers.length} of ${BROWSERS.length} supported engines`}
              </Text>
            </View>
            {snapshot ? (
              <NeuSwitch
                value={enabled}
                onValueChange={handleToggle}
                disabled={busy}
                label="Accessibility Interceptor"
              />
            ) : (
              <ActivityIndicator color={Palette.amber} />
            )}
          </NeuCard>

          {!canQueryInstalledPackages && (
            <Text style={styles.note}>
              This build cannot check installed browsers. Install the latest development build
              to see which engines are on this phone.
            </Text>
          )}

          <View style={styles.sectionHeader}>
            <Text style={styles.sectionLabel}>INSTALLED ON DEVICE</Text>
            <Text
              style={[styles.sectionValue, enabled ? styles.sectionValueOn : undefined]}>
              {statusLabel}
            </Text>
          </View>

          <NeuCard padded={false} style={styles.installedCard}>
            {installed === null && canQueryInstalledPackages ? (
              <Text style={styles.empty}>Checking installed browsers…</Text>
            ) : installedBrowsers.length === 0 ? (
              <Text style={styles.empty}>
                No supported browser is installed on this phone yet.
              </Text>
            ) : (
              installedBrowsers.map((browser, index) => (
                <View
                  key={browser.packageNames[0]}
                  style={[styles.installedRow, index > 0 && styles.rowDivider]}>
                  <View style={styles.browserIcon}>
                    <MaterialCommunityIcons
                      name={browser.icon}
                      size={20}
                      color={Palette.amber}
                    />
                  </View>
                  <View style={styles.grow}>
                    <Text style={styles.browserName} numberOfLines={1}>
                      {browser.name}
                    </Text>
                    <Text style={styles.browserSubtitle} numberOfLines={1}>
                      {browser.subtitle}
                    </Text>
                  </View>
                  <StatusPill
                    label={statusLabel}
                    tone={enabled ? 'success' : 'neutral'}
                    dot={enabled}
                  />
                </View>
              ))
            )}
          </NeuCard>

          {standbyBrowsers.length > 0 && (
            <>
              <View style={styles.sectionHeader}>
                <Text style={styles.sectionLabel}>SUPPORTED STANDBY</Text>
                <Text style={styles.sectionValue}>
                  {`${standbyBrowsers.length} READY`}
                </Text>
              </View>

              <View style={styles.grid}>
                {standbyBrowsers.map((browser) => (
                  <View key={browser.packageNames[0]} style={styles.gridTile}>
                    <View style={styles.gridIcon}>
                      <MaterialCommunityIcons
                        name={browser.icon}
                        size={16}
                        color={Palette.textSecondary}
                      />
                    </View>
                    <Text style={styles.gridName} numberOfLines={1}>
                      {browser.short}
                    </Text>
                    <Text style={styles.gridStandby}>Standby</Text>
                  </View>
                ))}
              </View>
            </>
          )}
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
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: Palette.raised,
    boxShadow: Shadows.raisedSmall,
    alignItems: 'center',
    justifyContent: 'center',
  },
  title: {
    ...Typography.headlineLg,
    fontSize: 20,
    color: Palette.textPrimary,
    flex: 1,
    flexShrink: 1,
  },
  masterCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    paddingVertical: 14,
    paddingHorizontal: 16,
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
  note: {
    ...Typography.bodyMd,
    color: Palette.textMuted,
    fontSize: 12,
  },
  sectionHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: Space.sm,
    marginBottom: -4,
  },
  sectionLabel: {
    ...Typography.monoLabelSm,
    color: Palette.textSecondary,
  },
  sectionValue: {
    ...Typography.monoLabelSm,
    color: Palette.textMuted,
  },
  sectionValueOn: { color: Palette.success },
  installedCard: { overflow: 'hidden' },
  installedRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    paddingHorizontal: 14,
    paddingVertical: 12,
  },
  rowDivider: {
    borderTopWidth: 1,
    borderTopColor: Palette.glassBorder,
  },
  browserIcon: {
    width: 38,
    height: 38,
    borderRadius: 12,
    backgroundColor: '#131418',
    alignItems: 'center',
    justifyContent: 'center',
  },
  browserName: {
    ...Typography.headlineMd,
    fontSize: 15,
    lineHeight: 20,
    color: Palette.textPrimary,
  },
  browserSubtitle: {
    ...Typography.bodyMd,
    color: Palette.textSecondary,
  },
  grid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 10,
  },
  gridTile: {
    width: '31%',
    alignItems: 'center',
    gap: 4,
    paddingVertical: 12,
    paddingHorizontal: 6,
    borderRadius: Radii.control,
    backgroundColor: Palette.raised,
    boxShadow: Shadows.raisedSmall,
  },
  gridIcon: {
    width: 26,
    height: 26,
    borderRadius: 13,
    backgroundColor: '#131418',
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 2,
  },
  gridName: {
    ...Typography.bodyMd,
    fontFamily: FontNames.interMedium,
    fontSize: 12,
    lineHeight: 16,
    color: Palette.textPrimary,
    textAlign: 'center',
  },
  gridStandby: {
    ...Typography.bodySm,
    fontSize: 10,
    lineHeight: 13,
    color: Palette.textMuted,
  },
  empty: {
    ...Typography.bodyMd,
    color: Palette.textMuted,
    paddingHorizontal: 14,
    paddingVertical: 16,
  },
  pressed: { opacity: 0.7 },
});
