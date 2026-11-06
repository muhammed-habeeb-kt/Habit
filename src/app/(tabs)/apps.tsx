import { MaterialCommunityIcons } from '@expo/vector-icons';
import { router } from 'expo-router';
import { useEffect, useMemo, useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';

import { AiScannerStatusCard } from '@/components/ai-scanner-status-card';
import { AppTile, FrostedBanner, NeuCard, NeuSwitch, NeuWell } from '@/components/neumorphic';
import { TabScreen } from '@/components/tab-screen';
import { Palette, Radii, Shadows, Space, Typography } from '@/constants/theme';
import { useAiMonitoring } from '@/hooks/use-ai-monitoring';
import { useProtection } from '@/hooks/use-protection';
import {
  canListInstalledApps,
  getInstalledApps,
  isMonitoringLocked,
  type InstalledApp,
} from '@/native/habit';

type Filter = 'all' | 'selected';

export default function AppsScreen() {
  const { settings, busy, toggleMaster, toggleApp } = useAiMonitoring();
  const { snapshot } = useProtection();
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<Filter>('all');
  const [apps, setApps] = useState<InstalledApp[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let mounted = true;
    getInstalledApps()
      .then((next) => {
        if (mounted) setApps(next);
      })
      .finally(() => {
        if (mounted) setLoading(false);
      });
    return () => {
      mounted = false;
    };
  }, []);

  const watched = useMemo(() => settings?.monitoredAppPackages ?? [], [settings]);
  const enabled = settings?.enabled ?? false;
  const removalLocked = isMonitoringLocked(snapshot);

  // Removing an app or turning monitoring off lowers Strict/Aggressive protection, so
  // those taps open the unlock sheet instead. Adding protection is always instant.
  const handleToggleApp = (packageName: string) => {
    if (removalLocked && watched.includes(packageName)) {
      router.push('/unlock');
      return;
    }
    void toggleApp(packageName);
  };

  const handleMasterToggle = (next: boolean) => {
    if (!next && removalLocked) {
      router.push('/unlock');
      return;
    }
    void toggleMaster(next);
  };

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return apps;
    return apps.filter((app) =>
      `${app.label} ${app.packageName}`.toLowerCase().includes(needle)
    );
  }, [apps, query]);

  const selectedApps = useMemo(
    () => filtered.filter((app) => watched.includes(app.packageName)),
    [filtered, watched]
  );
  const unselectedApps = useMemo(
    () => filtered.filter((app) => !watched.includes(app.packageName)),
    [filtered, watched]
  );

  const filters: { id: Filter; label: string }[] = [
    { id: 'all', label: 'ALL' },
    { id: 'selected', label: `SELECTED (${watched.length})` },
  ];

  const renderRow = (app: InstalledApp) => (
    <AppRow
      key={app.packageName}
      app={app}
      selected={watched.includes(app.packageName)}
      onPress={() => handleToggleApp(app.packageName)}
    />
  );

  const noAppsAtAll = !loading && canListInstalledApps && apps.length === 0;
  const searching = query.trim().length > 0;

  return (
    <TabScreen>
      <View style={styles.header}>
        <Text style={styles.title}>Apps</Text>
        <Text style={styles.subtitle}>Choose the apps Habit should watch</Text>
      </View>

      <NeuCard style={styles.masterCard}>
        <View style={styles.iconWell}>
          <MaterialCommunityIcons name="shield-check-outline" size={22} color={Palette.amber} />
        </View>
        <View style={styles.grow}>
          <Text style={styles.cardTitle}>AI App Monitoring</Text>
          <Text style={styles.cardSubtitle}>
            {watched.length} {watched.length === 1 ? 'app' : 'apps'} watched
          </Text>
        </View>
        <NeuSwitch
          value={enabled}
          onValueChange={handleMasterToggle}
          disabled={busy || !settings}
          label="AI App Monitoring"
        />
      </NeuCard>

      <FrostedBanner
        icon="circle-outline"
        title="Habit scans these apps only while they are open."
        body="Nothing leaves your phone."
      />

      <AiScannerStatusCard />

      <NeuWell padded={false} style={styles.search}>
        <MaterialCommunityIcons name="magnify" size={20} color={Palette.textMuted} />
        <TextInput
          value={query}
          onChangeText={setQuery}
          placeholder="Search apps"
          placeholderTextColor={Palette.textMuted}
          selectionColor={Palette.amber}
          style={styles.searchInput}
          autoCorrect={false}
          autoCapitalize="none"
        />
      </NeuWell>

      <View style={styles.filters}>
        {filters.map((item) => {
          const active = item.id === filter;
          return (
            <Pressable
              key={item.id}
              accessibilityRole="button"
              accessibilityState={{ selected: active }}
              onPress={() => setFilter(item.id)}
              style={({ pressed }) => [
                styles.filterChip,
                active ? styles.filterChipActive : styles.filterChipIdle,
                pressed && styles.pressed,
              ]}>
              <Text style={[styles.filterLabel, active && styles.filterLabelActive]}>
                {item.label}
              </Text>
            </Pressable>
          );
        })}
      </View>

      <Text style={styles.count}>
        {watched.length} of {apps.length} apps selected
      </Text>

      {!canListInstalledApps && (
        <Text style={styles.empty}>
          The installed-app list needs the latest development build. Habit never shows apps that
          are not on this phone.
        </Text>
      )}

      {canListInstalledApps && loading && <Text style={styles.empty}>Loading installed apps…</Text>}

      {canListInstalledApps && noAppsAtAll && (
        <Text style={styles.empty}>
          No installed apps were returned by Android. Make sure Habit is allowed to query packages.
        </Text>
      )}

      {canListInstalledApps && !loading && filter === 'all' && selectedApps.length > 0 && (
        <View style={styles.group}>
          <Text style={styles.sectionLabel}>SELECTED</Text>
          <View style={styles.list}>{selectedApps.map(renderRow)}</View>
        </View>
      )}

      {canListInstalledApps && !loading && filter === 'all' && unselectedApps.length > 0 && (
        <View style={styles.group}>
          <Text style={styles.sectionLabel}>ALL APPS</Text>
          <View style={styles.list}>{unselectedApps.map(renderRow)}</View>
        </View>
      )}

      {canListInstalledApps && !loading && filter === 'selected' && selectedApps.length > 0 && (
        <View style={styles.group}>
          <Text style={styles.sectionLabel}>SELECTED</Text>
          <View style={styles.list}>{selectedApps.map(renderRow)}</View>
        </View>
      )}

      {canListInstalledApps && !loading && filtered.length === 0 && !noAppsAtAll && (
        <Text style={styles.empty}>
          {searching
            ? 'No apps match this search.'
            : filter === 'selected'
              ? 'No apps selected yet. Pick apps from ALL.'
              : 'No apps found.'}
        </Text>
      )}

      <Text style={styles.footer}>
        AI monitoring is on-device only. Habit never sends screenshots or screen text anywhere.
      </Text>
    </TabScreen>
  );
}

interface AppRowProps {
  app: InstalledApp;
  selected: boolean;
  onPress: () => void;
}

function AppRow({ app, selected, onPress }: AppRowProps) {
  const category = app.isSystemApp ? 'System app' : 'Installed app';
  const subtitle = app.isBrowser
    ? `${app.packageName} · ${category} · Browser`
    : `${app.packageName} · ${category}`;

  return (
    <Pressable
      accessibilityRole="checkbox"
      accessibilityLabel={app.label}
      accessibilityState={{ checked: selected }}
      onPress={onPress}
      style={({ pressed }) => [
        styles.row,
        selected && styles.rowSelected,
        pressed && styles.pressed,
      ]}>
      <AppTile name={app.label} iconUri={app.iconUri} selected={selected} />
      <View style={styles.grow}>
        <Text style={styles.rowTitle} numberOfLines={1}>
          {app.label}
        </Text>
        <Text style={styles.rowSubtitle} numberOfLines={1}>
          {subtitle}
        </Text>
      </View>
      <View style={[styles.checkbox, selected && styles.checkboxOn]}>
        {selected && <MaterialCommunityIcons name="check" size={16} color={Palette.canvas} />}
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  header: { gap: 2, paddingTop: Space.sm },
  title: {
    ...Typography.displayMd,
    color: Palette.textPrimary,
  },
  subtitle: {
    ...Typography.bodyLg,
    color: Palette.textSecondary,
  },
  masterCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    paddingVertical: 14,
  },
  iconWell: {
    width: 44,
    height: 44,
    borderRadius: 22,
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
  search: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.sm,
    paddingHorizontal: 14,
    height: 44,
    borderRadius: Radii.input,
  },
  searchInput: {
    flex: 1,
    ...Typography.bodyLg,
    color: Palette.textPrimary,
    paddingVertical: 0,
  },
  filters: {
    flexDirection: 'row',
    gap: Space.sm,
  },
  filterChip: {
    borderRadius: Radii.pill,
    paddingHorizontal: 16,
    paddingVertical: 8,
    height: 32,
  },
  filterChipIdle: {
    backgroundColor: Palette.raised,
    boxShadow: Shadows.raisedSmall,
  },
  filterChipActive: {
    backgroundColor: Palette.amberHighlight,
    borderWidth: 1,
    borderColor: 'rgba(251, 191, 36, 0.4)',
  },
  filterLabel: {
    ...Typography.monoLabelSm,
    color: Palette.textSecondary,
    fontSize: 10,
    fontFamily: 'Space Mono',
  },
  filterLabelActive: { color: Palette.amber, fontSize: 10, fontFamily: 'Space Mono' },
  count: {
    ...Typography.bodyMd,
    color: Palette.textSecondary,
    marginTop: -4,
    fontSize: 10,
    fontFamily: 'Space Mono',
  },
  group: { gap: Space.sm },
  sectionLabel: {
    ...Typography.monoLabelSm,
    color: Palette.textSecondary,
    fontSize: 10,
    fontFamily: 'Space Mono',
    textTransform: 'uppercase',
  },
  list: { gap: Space.sm },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    backgroundColor: Palette.surface,
    borderRadius: Radii.control,
    paddingHorizontal: 12,
    paddingVertical: 12,
    boxShadow: Shadows.raisedSmall,
    borderWidth: 1,
    borderColor: 'transparent',
  },
  rowSelected: {
    borderColor: Palette.amber,
  },
  rowTitle: {
    ...Typography.headlineMd,
    fontSize: 15,
    lineHeight: 20,
    color: Palette.textPrimary,
  },
  rowSubtitle: {
    ...Typography.bodyMd,
    color: Palette.textSecondary,
    fontSize: 11,
  },
  checkbox: {
    width: 24,
    height: 24,
    borderRadius: 8,
    backgroundColor: Palette.well,
    boxShadow: Shadows.sunkenSmall,
    alignItems: 'center',
    justifyContent: 'center',
  },
  checkboxOn: {
    backgroundColor: Palette.amber,
    boxShadow: Shadows.amberHalo,
  },
  empty: {
    ...Typography.bodyMd,
    color: Palette.textMuted,
    textAlign: 'center',
    paddingVertical: Space.xl,
  },
  footer: {
    ...Typography.bodySm,
    color: Palette.textMuted,
    textAlign: 'center',
    paddingHorizontal: Space.md,
  },
  pressed: { opacity: 0.75 },
});
