import { MaterialCommunityIcons } from '@expo/vector-icons';
import { router } from 'expo-router';
import { useEffect, useMemo, useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';

import { AiScannerStatusCard } from '@/components/ai-scanner-status-card';
import { AppTile, FrostedBanner, NeuCard, NeuSwitch, NeuWell } from '@/components/neumorphic';
import { TabScreen } from '@/components/tab-screen';
import { FontNames, Palette, Radii, Shadows, Space, Typography } from '@/constants/theme';
import { useAiMonitoring } from '@/hooks/use-ai-monitoring';
import { useProtection } from '@/hooks/use-protection';
import {
  canListInstalledApps,
  canSetAiDomains,
  getInstalledApps,
  isMonitoringLocked,
  normalizeDomain,
  type InstalledApp,
} from '@/native/habit';

type Filter = 'all' | 'selected';
type Mode = 'apps' | 'browser';

/** Mixed-content sites where the visual scanner usually earns its keep. */
const QUICK_DOMAINS = [
  'web.telegram.org',
  'reddit.com',
  'x.com',
  'instagram.com',
  'discord.com',
  'terabox.com',
];

export default function AppsScreen() {
  const { settings, busy, toggleMaster, toggleApp, addDomain, removeDomain } = useAiMonitoring();
  const { snapshot } = useProtection();
  const [mode, setMode] = useState<Mode>('apps');
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<Filter>('all');
  const [domainInput, setDomainInput] = useState('');
  const [domainNote, setDomainNote] = useState<string | null>(null);
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
  // Watch the persisted list: the native store is the source of truth, so the Browser box
  // shows the saved websites even if the JS settings object arrives empty on a reload.
  const domains = useMemo(() => {
    const fromSettings = settings?.monitoredDomains ?? [];
    return fromSettings.length > 0
      ? fromSettings
      : (snapshot?.aiMonitoredDomains ?? []);
  }, [settings, snapshot]);
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

  const addSite = (input: string) => {
    const domain = normalizeDomain(input);
    if (!domain) {
      setDomainNote('Enter a domain like example.com');
      return;
    }
    if (domains.includes(domain)) {
      setDomainNote(`${domain} is already watched`);
      return;
    }
    setDomainNote(null);
    setDomainInput('');
    void addDomain(domain);
  };

  const handleRemoveDomain = (domain: string) => {
    // Same rule as apps: removing a watch lowers a lock, adding one never does.
    if (removalLocked) {
      router.push('/unlock');
      return;
    }
    void removeDomain(domain);
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
        <Text style={styles.title}>AI Monitoring</Text>
        <Text style={styles.subtitle}>Real-time on-device visual screen protection</Text>
      </View>

      <NeuCard style={styles.masterCard}>
        <View style={styles.iconWell}>
          <MaterialCommunityIcons name="star-four-points-outline" size={22} color={Palette.amber} />
        </View>
        <View style={styles.grow}>
          <Text style={styles.cardTitle}>AI Visual Protection</Text>
          <Text style={styles.cardSubtitle}>
            {watched.length} apps • {domains.length} browser sites
          </Text>
        </View>
        <NeuSwitch
          value={enabled}
          onValueChange={handleMasterToggle}
          disabled={busy || !settings}
          label="AI Visual Protection"
        />
      </NeuCard>

      <View style={styles.divider} />

      <View style={styles.modes}>
        <ModeBox
          active={mode === 'apps'}
          icon="apps"
          title="Apps"
          badge={`${watched.length} selected`}
          onPress={() => setMode('apps')}
        />
        <ModeBox
          active={mode === 'browser'}
          icon="globe-model"
          title="Browser"
          badge={`${domains.length} sites`}
          onPress={() => setMode('browser')}
        />
      </View>

      {mode === 'apps' ? (
        <>
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
              The installed-app list needs the latest development build. Habit never shows apps
              that are not on this phone.
            </Text>
          )}

          {canListInstalledApps && loading && (
            <Text style={styles.empty}>Loading installed apps…</Text>
          )}

          {canListInstalledApps && noAppsAtAll && (
            <Text style={styles.empty}>
              No installed apps were returned by Android. Make sure Habit is allowed to query
              packages.
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

          {canListInstalledApps &&
            !loading &&
            filter === 'selected' &&
            selectedApps.length > 0 && (
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
        </>
      ) : (
        <>
          <NeuCard style={styles.addCard}>
            <View style={styles.addRow}>
              <NeuWell padded={false} style={styles.domainWell}>
                <MaterialCommunityIcons name="web" size={18} color={Palette.textMuted} />
                <TextInput
                  value={domainInput}
                  onChangeText={(text) => {
                    setDomainInput(text);
                    if (domainNote) setDomainNote(null);
                  }}
                  placeholder="Enter website domain (e.g. web.telegram.org)"
                  placeholderTextColor={Palette.textMuted}
                  selectionColor={Palette.amber}
                  style={styles.domainInput}
                  autoCapitalize="none"
                  autoCorrect={false}
                  keyboardType="url"
                  returnKeyType="done"
                  onSubmitEditing={() => addSite(domainInput)}
                />
              </NeuWell>
              <Pressable
                accessibilityRole="button"
                accessibilityLabel="Add website"
                onPress={() => addSite(domainInput)}
                style={({ pressed }) => [
                  styles.addButton,
                  pressed && styles.addButtonPressed,
                ]}>
                <MaterialCommunityIcons name="plus" size={16} color={Palette.canvas} />
                <Text style={styles.addButtonLabel}>Add</Text>
              </Pressable>
            </View>
            <Text style={styles.addHint}>
              AI screenshots run while this site is open in a watched browser. The scheme,
              `www.` and paths are removed automatically.
            </Text>
          </NeuCard>

          <View style={styles.chips}>
            {QUICK_DOMAINS.map((domain) => {
              const added = domains.includes(domain);
              return (
                <Pressable
                  key={domain}
                  accessibilityRole="button"
                  accessibilityLabel={
                    added ? `${domain} is already watched` : `Add ${domain}`
                  }
                  onPress={() => addSite(domain)}
                  style={({ pressed }) => [
                    styles.chip,
                    added && styles.chipAdded,
                    pressed && styles.pressed,
                  ]}>
                  {added && (
                    <MaterialCommunityIcons name="check" size={12} color={Palette.amber} />
                  )}
                  <Text style={[styles.chipLabel, added && styles.chipLabelAdded]}>
                    {domain}
                  </Text>
                </Pressable>
              );
            })}
          </View>

          {domainNote && <Text style={styles.note}>{domainNote}</Text>}

          {!canSetAiDomains && (
            <Text style={styles.empty}>
              This build cannot watch websites yet. Install the latest development build to
              switch this on.
            </Text>
          )}

          <View style={styles.group}>
            <Text style={styles.sectionLabel}>AI WATCHED WEBSITES</Text>

            {domains.length === 0 ? (
              <Text style={styles.empty}>
                No websites yet. Add a domain above, or tap a quick-add chip.
              </Text>
            ) : (
              <View style={styles.list}>
                {domains.map((domain) => (
                  <View key={domain} style={styles.siteRow}>
                    <View style={styles.siteTile}>
                      <Text style={styles.siteLetter}>{domain.slice(0, 1).toUpperCase()}</Text>
                    </View>
                    <View style={styles.grow}>
                      <Text style={styles.siteDomain} numberOfLines={1}>
                        {domain}
                      </Text>
                      <Text style={styles.watchLabel}>• AI WATCHING</Text>
                    </View>
                    <Pressable
                      accessibilityRole="button"
                      accessibilityLabel={`Remove ${domain}`}
                      hitSlop={8}
                      onPress={() => handleRemoveDomain(domain)}
                      style={({ pressed }) => [
                        styles.removeButton,
                        pressed && styles.pressed,
                      ]}>
                      <MaterialCommunityIcons
                        name="trash-can-outline"
                        size={18}
                        color={Palette.danger}
                      />
                    </Pressable>
                  </View>
                ))}
              </View>
            )}

            {domains.length > 0 && (
              <Text style={styles.lockHint}>
                {removalLocked
                  ? 'Removal is locked until Strict/Aggressive is unlocked. Adding new sites still works.'
                  : 'Subdomains count too: watching telegram.org also covers web.telegram.org.'}
              </Text>
            )}
          </View>
        </>
      )}

      <Text style={styles.footer}>
        AI monitoring is on-device only. Habit never sends screenshots or screen text anywhere.
      </Text>
    </TabScreen>
  );
}

interface ModeBoxProps {
  active: boolean;
  icon: keyof typeof MaterialCommunityIcons.glyphMap;
  title: string;
  badge: string;
  onPress: () => void;
}

/** One of the two side-by-side mode boxes: Apps or Browser. */
function ModeBox({ active, icon, title, badge, onPress }: ModeBoxProps) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={title}
      accessibilityState={{ selected: active }}
      onPress={onPress}
      style={({ pressed }) => [
        styles.modeBox,
        active ? styles.modeBoxActive : styles.modeBoxIdle,
        pressed && styles.pressed,
      ]}>
      <View style={[styles.modeIconTile, active && styles.modeIconTileActive]}>
        <MaterialCommunityIcons
          name={icon}
          size={20}
          color={active ? Palette.amber : Palette.textSecondary}
        />
      </View>
      <Text style={[styles.modeTitle, active && styles.modeTitleActive]}>{title}</Text>
      <Text style={[styles.modeBadge, active && styles.modeBadgeActive]}>{badge}</Text>
    </Pressable>
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
  divider: {
    height: 1,
    backgroundColor: Palette.glassBorder,
    marginVertical: Space.xs,
  },
  modes: {
    flexDirection: 'row',
    gap: Space.gutter,
  },
  modeBox: {
    flex: 1,
    gap: 6,
    padding: 14,
    borderRadius: Radii.card,
    borderWidth: 1.5,
    backgroundColor: Palette.raised,
  },
  modeBoxActive: {
    borderColor: Palette.amber,
    boxShadow: Shadows.raised,
  },
  modeBoxIdle: {
    borderColor: 'transparent',
    boxShadow: Shadows.raisedSmall,
  },
  modeIconTile: {
    width: 34,
    height: 34,
    borderRadius: 17,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: Palette.well,
  },
  modeIconTileActive: {
    backgroundColor: Palette.amberHighlight,
  },
  modeTitle: {
    ...Typography.headlineMd,
    fontSize: 15,
    lineHeight: 20,
    color: Palette.textPrimary,
  },
  modeTitleActive: { color: Palette.amber },
  modeBadge: {
    ...Typography.bodyMd,
    color: Palette.textSecondary,
    fontSize: 11,
  },
  modeBadgeActive: { color: Palette.amber },
  addCard: { gap: Space.sm, paddingVertical: 14 },
  addRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.sm,
  },
  domainWell: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.sm,
    paddingHorizontal: 12,
    height: 46,
    borderRadius: Radii.input,
  },
  domainInput: {
    flex: 1,
    ...Typography.bodyMd,
    color: Palette.textPrimary,
    paddingVertical: 0,
  },
  addButton: {
    height: 46,
    borderRadius: Radii.control,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 4,
    paddingHorizontal: 16,
    backgroundColor: Palette.amber,
    boxShadow: Shadows.amberGlow,
  },
  addButtonPressed: {
    backgroundColor: Palette.amberPressed,
    boxShadow: undefined,
  },
  addButtonLabel: {
    fontFamily: FontNames.interBold,
    fontSize: 14,
    lineHeight: 20,
    color: Palette.canvas,
  },
  addHint: {
    ...Typography.bodySm,
    color: Palette.textMuted,
  },
  chips: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: Space.sm,
  },
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    borderRadius: Radii.pill,
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderWidth: 1,
    borderColor: 'transparent',
    backgroundColor: Palette.raised,
    boxShadow: Shadows.raisedSmall,
  },
  chipAdded: {
    backgroundColor: Palette.amberHighlight,
    borderColor: 'rgba(251, 191, 36, 0.4)',
  },
  chipLabel: {
    fontFamily: FontNames.monoRegular,
    fontSize: 11,
    lineHeight: 14,
    color: Palette.textSecondary,
  },
  chipLabelAdded: { color: Palette.amber },
  note: {
    ...Typography.bodyMd,
    color: Palette.amber,
    fontSize: 12,
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
  siteRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    backgroundColor: Palette.raised,
    borderRadius: Radii.card,
    paddingHorizontal: 14,
    paddingVertical: 12,
    boxShadow: Shadows.raised,
  },
  siteTile: {
    width: 44,
    height: 44,
    borderRadius: Radii.tile,
    backgroundColor: Palette.amberHighlight,
    alignItems: 'center',
    justifyContent: 'center',
  },
  siteLetter: {
    fontFamily: FontNames.monoBold,
    fontSize: 18,
    lineHeight: 22,
    color: Palette.amber,
  },
  siteDomain: {
    ...Typography.headlineMd,
    fontSize: 15,
    lineHeight: 20,
    color: Palette.textPrimary,
  },
  watchLabel: {
    ...Typography.monoLabelSm,
    color: Palette.success,
    fontSize: 9,
    lineHeight: 13,
  },
  removeButton: {
    width: 36,
    height: 36,
    borderRadius: 18,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: Palette.well,
    boxShadow: Shadows.sunkenSmall,
  },
  lockHint: {
    ...Typography.bodySm,
    color: Palette.textMuted,
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
