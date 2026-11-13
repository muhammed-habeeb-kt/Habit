import { MaterialCommunityIcons } from '@expo/vector-icons';
import { router, useFocusEffect, useLocalSearchParams } from 'expo-router';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  AppState,
  Image,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';

import {
  FrostedBanner,
  NeuCard,
  NeuSwitch,
  NeuWell,
  StatusPill,
} from '@/components/neumorphic';
import { TabScreen } from '@/components/tab-screen';
import { FontNames, Palette, Radii, Shadows, Space, Typography } from '@/constants/theme';
import { useAiMonitoring } from '@/hooks/use-ai-monitoring';
import { useProtection } from '@/hooks/use-protection';
import {
  canDrawOverlays,
  canListInstalledApps,
  canSetAiDomains,
  getInstalledApps,
  isMonitoringLocked,
  isNativeAvailable,
  normalizeDomain,
  openOverlaySettings,
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
  const {
    settings,
    busy,
    toggleMaster,
    toggleApp,
    toggleIncognitoBlocking,
    addDomain,
    removeDomain,
  } = useAiMonitoring();
  const { snapshot } = useProtection();
  // `/apps?tab=browser` lands straight on the Browser box (the Settings screen uses it).
  const params = useLocalSearchParams<{ tab?: string }>();
  const [mode, setMode] = useState<Mode>(params.tab === 'browser' ? 'browser' : 'apps');
  // Applied on every focus (this tab stays mounted, so the param has to be consumed each
  // time Settings links here) and then cleared, so the same link works again later while a
  // manual Apps/Browser tap still sticks.
  useFocusEffect(
    useCallback(() => {
      if (params.tab !== 'browser' && params.tab !== 'apps') return;
      setMode(params.tab);
      router.setParams({ tab: undefined });
    }, [params.tab])
  );
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<Filter>('all');
  const [domainInput, setDomainInput] = useState('');
  const [domainNote, setDomainNote] = useState<string | null>(null);
  const [apps, setApps] = useState<InstalledApp[]>([]);
  const [loading, setLoading] = useState(true);
  const [overlayAllowed, setOverlayAllowed] = useState(true);

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

  // "Display over other apps" is the fallback path for the block screen on HyperOS. It is
  // the only diagnostic left in production, shown as a compact fix banner like Home.
  useEffect(() => {
    let mounted = true;
    const check = () => {
      canDrawOverlays().then((allowed) => {
        if (mounted) setOverlayAllowed(allowed);
      });
    };
    check();
    const subscription = AppState.addEventListener('change', (state) => {
      if (state === 'active') check();
    });
    return () => {
      mounted = false;
      subscription.remove();
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
  const suggestions = useMemo(
    () => QUICK_DOMAINS.filter((domain) => !domains.includes(domain)),
    [domains]
  );
  const enabled = settings?.enabled ?? false;
  const removalLocked = isMonitoringLocked(snapshot);
  const incognitoBlocking =
    settings?.incognitoBlockingEnabled ?? snapshot?.incognitoBlockingEnabled ?? true;

  // Turning incognito blocking ON adds protection (instant). Turning it OFF lowers a
  // Strict/Aggressive lock, so that tap opens the Unlock sheet instead.
  const handleIncognitoToggle = (next: boolean) => {
    if (!next && removalLocked) {
      router.push('/unlock');
      return;
    }
    void toggleIncognitoBlocking(next);
  };

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
    { id: 'all', label: `ALL (${apps.length})` },
    { id: 'selected', label: `SEL (${watched.length})` },
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
      {isNativeAvailable && !overlayAllowed && (
        <FrostedBanner
          icon="shield-alert-outline"
          title="Overlay permission is off"
          body="Habit needs it to draw the block screen over apps."
          actionLabel="Fix now"
          onPress={() => {
            void openOverlaySettings();
          }}
        />
      )}

      <NeuCard style={styles.masterCard}>
        <View style={styles.iconWell}>
          <MaterialCommunityIcons name="shield-outline" size={20} color={Palette.amber} />
        </View>
        <View style={styles.grow}>
          <View style={styles.masterTitleRow}>
            <Text style={styles.cardTitle} numberOfLines={1}>
              {mode === 'apps' ? 'AI Visual Protection' : 'AI Web Guard'}
            </Text>
            {mode === 'browser' && enabled && (
              <StatusPill label="ACTIVE" tone="success" dot />
            )}
          </View>
          <Text style={styles.cardSubtitle}>Real-time on-device screen scan</Text>
        </View>
        <NeuSwitch
          value={enabled}
          onValueChange={handleMasterToggle}
          disabled={busy || !settings}
          label="AI Visual Protection"
        />
      </NeuCard>

      <View style={styles.segments}>
        <SegmentPill
          active={mode === 'apps'}
          icon="view-grid-outline"
          label={`Apps (${apps.length})`}
          onPress={() => setMode('apps')}
        />
        <SegmentPill
          active={mode === 'browser'}
          icon="web"
          label={`Websites (${domains.length} Active)`}
          onPress={() => setMode('browser')}
        />
      </View>

      {mode === 'apps' ? (
        <>
          <NeuWell padded={false} style={styles.privacyBar}>
            <MaterialCommunityIcons name="lock" size={12} color={Palette.textSecondary} />
            <Text style={styles.privacyText} numberOfLines={1}>
              Scans only while open • Nothing leaves phone
            </Text>
          </NeuWell>

          <View style={styles.searchRow}>
            <NeuWell padded={false} style={styles.search}>
              <MaterialCommunityIcons name="magnify" size={17} color={Palette.textMuted} />
              <TextInput
                value={query}
                onChangeText={setQuery}
                placeholder={`Search ${apps.length} apps…`}
                placeholderTextColor={Palette.textMuted}
                selectionColor={Palette.amber}
                style={styles.searchInput}
                autoCorrect={false}
                autoCapitalize="none"
              />
            </NeuWell>
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
                    active && styles.filterChipActive,
                    pressed && styles.pressed,
                  ]}>
                  <Text style={[styles.filterLabel, active && styles.filterLabelActive]}>
                    {item.label}
                  </Text>
                </Pressable>
              );
            })}
          </View>

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
              <View style={styles.sectionRow}>
                <Text style={styles.sectionLabel}>
                  {`SELECTED APPS (${selectedApps.length})`}
                </Text>
                <Text style={styles.sectionCount}>
                  {`${selectedApps.length} PROTECTED`}
                </Text>
              </View>
              <View style={styles.list}>{selectedApps.map(renderRow)}</View>
            </View>
          )}

          {canListInstalledApps && !loading && filter === 'all' && unselectedApps.length > 0 && (
            <View style={styles.group}>
              <View style={styles.sectionRow}>
                <Text style={styles.sectionLabel}>
                  {`ALL APPS (${unselectedApps.length} REMAINING)`}
                </Text>
              </View>
              <View style={styles.list}>{unselectedApps.map(renderRow)}</View>
            </View>
          )}

          {canListInstalledApps &&
            !loading &&
            filter === 'selected' &&
            selectedApps.length > 0 && (
              <View style={styles.group}>
                <View style={styles.sectionRow}>
                  <Text style={styles.sectionLabel}>
                    {`SELECTED APPS (${selectedApps.length})`}
                  </Text>
                  <Text style={styles.sectionCount}>
                    {`${selectedApps.length} PROTECTED`}
                  </Text>
                </View>
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
          <NeuCard style={styles.incognitoCard}>
            <View style={styles.incognitoTile}>
              <MaterialCommunityIcons name="shield-outline" size={18} color={Palette.amber} />
            </View>
            <View style={styles.grow}>
              <Text style={styles.cardTitle}>Incognito Shield</Text>
              <Text style={styles.cardSubtitle} numberOfLines={1}>
                Zero-trace tabs suppressed system-wide
              </Text>
            </View>
            <NeuSwitch
              value={incognitoBlocking}
              onValueChange={handleIncognitoToggle}
              disabled={busy || !settings}
              label="Incognito Shield"
            />
          </NeuCard>

          <NeuWell padded={false} style={styles.capsule}>
            <TextInput
              value={domainInput}
              onChangeText={(text) => {
                setDomainInput(text);
                if (domainNote) setDomainNote(null);
              }}
              placeholder="+ Add domain to AI watch…"
              placeholderTextColor={Palette.textMuted}
              selectionColor={Palette.amber}
              style={styles.capsuleInput}
              autoCapitalize="none"
              autoCorrect={false}
              keyboardType="url"
              returnKeyType="done"
              onSubmitEditing={() => addSite(domainInput)}
            />
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Watch domain"
              onPress={() => addSite(domainInput)}
              style={({ pressed }) => [
                styles.watchButton,
                pressed && styles.watchButtonPressed,
              ]}>
              <MaterialCommunityIcons name="plus" size={13} color={Palette.canvas} />
              <Text style={styles.watchButtonLabel}>Watch</Text>
            </Pressable>
          </NeuWell>

          {domainNote && <Text style={styles.note}>{domainNote}</Text>}

          {suggestions.length > 0 && (
            <View style={styles.suggestedRow}>
              <Text style={styles.suggestedLabel}>Suggested:</Text>
              <ScrollView
                horizontal
                showsHorizontalScrollIndicator={false}
                keyboardShouldPersistTaps="handled"
                contentContainerStyle={styles.suggestedContent}>
                {suggestions.map((domain) => (
                  <Pressable
                    key={domain}
                    accessibilityRole="button"
                    accessibilityLabel={`Add ${domain}`}
                    onPress={() => addSite(domain)}
                    style={({ pressed }) => [
                      styles.suggestedChip,
                      pressed && styles.pressed,
                    ]}>
                    <Text style={styles.suggestedChipLabel}>{`+ ${domain}`}</Text>
                  </Pressable>
                ))}
              </ScrollView>
            </View>
          )}

          {!canSetAiDomains && (
            <Text style={styles.empty}>
              This build cannot watch websites yet. Install the latest development build to
              switch this on.
            </Text>
          )}

          <NeuCard padded={false} style={styles.targetsCard}>
            <View style={styles.targetsHeader}>
              <Text style={styles.targetsTitle}>{`ACTIVE TARGETS (${domains.length})`}</Text>
              {removalLocked && (
                <Text style={styles.targetsLock}>Strict Lock Enabled</Text>
              )}
            </View>

            {domains.length === 0 ? (
              <Text style={styles.targetsEmpty}>
                No websites watched yet. Add one above.
              </Text>
            ) : (
              <View style={styles.targetsList}>
                {domains.map((domain) => (
                  <View key={domain} style={styles.targetRow}>
                    <View style={styles.grow}>
                      <Text style={styles.targetDomain} numberOfLines={1}>
                        {domain}
                      </Text>
                      <Text style={styles.targetStatus}>• ACTIVE • AI WATCHING</Text>
                    </View>
                    {removalLocked ? (
                      <View style={styles.targetLock}>
                        <MaterialCommunityIcons
                          name="lock"
                          size={15}
                          color={Palette.amber}
                        />
                      </View>
                    ) : (
                      <Pressable
                        accessibilityRole="button"
                        accessibilityLabel={`Remove ${domain}`}
                        hitSlop={8}
                        onPress={() => handleRemoveDomain(domain)}
                        style={({ pressed }) => [
                          styles.targetRemove,
                          pressed && styles.pressed,
                        ]}>
                        <MaterialCommunityIcons
                          name="trash-can-outline"
                          size={16}
                          color={Palette.textSecondary}
                        />
                      </Pressable>
                    )}
                  </View>
                ))}
              </View>
            )}

            {removalLocked && domains.length > 0 && (
              <View style={styles.targetsFooter}>
                <MaterialCommunityIcons name="lock" size={11} color={Palette.textMuted} />
                <Text style={styles.targetsFooterText}>De-listing locked: Strict Mode</Text>
              </View>
            )}
          </NeuCard>
        </>
      )}
    </TabScreen>
  );
}

interface SegmentPillProps {
  active: boolean;
  icon: keyof typeof MaterialCommunityIcons.glyphMap;
  label: string;
  onPress: () => void;
}

/** One 40 dp segment of the compact Apps / Browser switcher. */
function SegmentPill({ active, icon, label, onPress }: SegmentPillProps) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ selected: active }}
      onPress={onPress}
      style={({ pressed }) => [
        styles.segment,
        active ? styles.segmentActive : styles.segmentIdle,
        pressed && styles.pressed,
      ]}>
      <MaterialCommunityIcons
        name={icon}
        size={14}
        color={active ? Palette.amber : Palette.textSecondary}
      />
      <Text
        style={[styles.segmentLabel, active && styles.segmentLabelActive]}
        numberOfLines={1}>
        {label}
      </Text>
    </Pressable>
  );
}

interface AppRowProps {
  app: InstalledApp;
  selected: boolean;
  onPress: () => void;
}

/** 60 dp installed-app row: real icon, label, monospace package, checkbox. */
function AppRow({ app, selected, onPress }: AppRowProps) {
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
      <AppIcon label={app.label} iconUri={app.iconUri} />
      <View style={styles.grow}>
        <Text style={styles.rowTitle} numberOfLines={1}>
          {app.label}
        </Text>
        <Text style={styles.rowSubtitle} numberOfLines={1}>
          {app.packageName}
        </Text>
      </View>
      <View style={[styles.checkbox, selected && styles.checkboxOn]}>
        {selected && <MaterialCommunityIcons name="check" size={16} color={Palette.canvas} />}
      </View>
    </Pressable>
  );
}

/** 38 dp real launcher icon, falling back to the app's first letter. */
function AppIcon({ label, iconUri }: { label: string; iconUri?: string }) {
  const [failed, setFailed] = useState(false);
  if (!iconUri || failed) {
    return (
      <View style={[styles.appIcon, styles.appIconFallback]}>
        <Text style={styles.appIconLetter}>{label.trim().charAt(0).toUpperCase() || '?'}</Text>
      </View>
    );
  }
  return (
    <Image
      source={{ uri: iconUri }}
      style={styles.appIcon}
      resizeMode="cover"
      onError={() => setFailed(true)}
      accessibilityIgnoresInvertColors
    />
  );
}

const styles = StyleSheet.create({
  masterCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    paddingVertical: 14,
    paddingHorizontal: 16,
  },
  masterTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
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
  cardTitle: {
    ...Typography.headlineMd,
    fontSize: 16,
    lineHeight: 21,
    color: Palette.textPrimary,
    flexShrink: 1,
  },
  cardSubtitle: {
    ...Typography.bodyMd,
    color: Palette.textSecondary,
  },
  segments: {
    flexDirection: 'row',
    gap: Space.sm,
  },
  segment: {
    flex: 1,
    minWidth: 0,
    height: 40,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    borderRadius: Radii.pill,
    paddingHorizontal: 8,
    borderWidth: 1.5,
  },
  segmentActive: {
    backgroundColor: Palette.amberHighlight,
    borderColor: Palette.amber,
  },
  segmentIdle: {
    backgroundColor: Palette.surface,
    borderColor: 'transparent',
  },
  segmentLabel: {
    ...Typography.bodyMd,
    fontFamily: FontNames.interSemiBold,
    fontSize: 12,
    color: Palette.textSecondary,
  },
  segmentLabelActive: {
    fontFamily: FontNames.interBold,
    color: Palette.amber,
  },
  privacyBar: {
    height: 30,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 12,
    borderRadius: Radii.pill,
  },
  privacyText: {
    ...Typography.bodySm,
    fontSize: 11,
    color: Palette.textSecondary,
  },
  searchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.sm,
  },
  search: {
    flex: 1,
    minWidth: 0,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 12,
    height: 40,
    borderRadius: Radii.pill,
  },
  searchInput: {
    flex: 1,
    ...Typography.bodyMd,
    color: Palette.textPrimary,
    paddingVertical: 0,
  },
  filterChip: {
    height: 40,
    justifyContent: 'center',
    borderRadius: Radii.pill,
    paddingHorizontal: 10,
    borderWidth: 1,
    borderColor: 'transparent',
    backgroundColor: Palette.surface,
  },
  filterChipActive: {
    backgroundColor: Palette.amberHighlight,
    borderColor: Palette.amber,
  },
  filterLabel: {
    ...Typography.monoLabelSm,
    fontSize: 9,
    letterSpacing: 0.4,
    color: Palette.textSecondary,
  },
  filterLabelActive: { color: Palette.amber },
  group: { gap: Space.sm },
  sectionRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: Space.sm,
  },
  sectionLabel: {
    ...Typography.monoLabelSm,
    fontSize: 10,
    color: Palette.textSecondary,
  },
  sectionCount: {
    ...Typography.monoLabelSm,
    fontSize: 10,
    color: Palette.amber,
  },
  list: { gap: 6 },
  row: {
    height: 60,
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    backgroundColor: Palette.raised,
    borderRadius: Radii.control,
    paddingHorizontal: 12,
    boxShadow: Shadows.raisedSmall,
    borderWidth: 1,
    borderColor: 'transparent',
  },
  rowSelected: {
    borderColor: Palette.amber,
  },
  appIcon: {
    width: 38,
    height: 38,
    borderRadius: 10,
    backgroundColor: Palette.well,
  },
  appIconFallback: {
    alignItems: 'center',
    justifyContent: 'center',
  },
  appIconLetter: {
    fontFamily: FontNames.monoBold,
    fontSize: 15,
    lineHeight: 20,
    color: Palette.textPrimary,
  },
  rowTitle: {
    ...Typography.headlineMd,
    fontSize: 14,
    lineHeight: 19,
    color: Palette.textPrimary,
  },
  rowSubtitle: {
    ...Typography.monoCode,
    fontSize: 10,
    lineHeight: 14,
    color: Palette.textMuted,
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
  incognitoCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    paddingVertical: 14,
    paddingHorizontal: 16,
  },
  incognitoTile: {
    width: 38,
    height: 38,
    borderRadius: 12,
    backgroundColor: Palette.well,
    alignItems: 'center',
    justifyContent: 'center',
  },
  capsule: {
    height: 48,
    flexDirection: 'row',
    alignItems: 'center',
    borderRadius: Radii.control,
    borderWidth: 1,
    borderColor: Palette.glassBorder,
    paddingLeft: 14,
    paddingRight: 6,
  },
  capsuleInput: {
    flex: 1,
    ...Typography.bodyMd,
    color: Palette.textPrimary,
    paddingVertical: 0,
  },
  watchButton: {
    height: 34,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 3,
    paddingHorizontal: 12,
    borderRadius: Radii.pill,
    backgroundColor: Palette.amber,
    boxShadow: Shadows.amberGlow,
  },
  watchButtonPressed: {
    backgroundColor: Palette.amberPressed,
    boxShadow: undefined,
  },
  watchButtonLabel: {
    fontFamily: FontNames.interBold,
    fontSize: 12,
    lineHeight: 16,
    color: Palette.canvas,
  },
  note: {
    ...Typography.bodyMd,
    color: Palette.amber,
    fontSize: 12,
  },
  suggestedRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.sm,
    marginTop: -4,
  },
  suggestedLabel: {
    ...Typography.bodySm,
    color: Palette.textMuted,
    flexShrink: 0,
  },
  suggestedContent: {
    gap: 6,
    paddingRight: Space.md,
  },
  suggestedChip: {
    height: 28,
    justifyContent: 'center',
    paddingHorizontal: 10,
    borderRadius: Radii.pill,
    borderWidth: 1,
    borderColor: 'rgba(251, 191, 36, 0.4)',
    backgroundColor: Palette.amberHighlight,
  },
  suggestedChipLabel: {
    fontFamily: FontNames.monoRegular,
    fontSize: 10,
    lineHeight: 14,
    color: Palette.amber,
  },
  targetsCard: {
    padding: 14,
    gap: Space.sm,
    borderRadius: Radii.control,
  },
  targetsHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: Space.sm,
  },
  targetsTitle: {
    ...Typography.monoLabelSm,
    fontSize: 10,
    color: Palette.textSecondary,
  },
  targetsLock: {
    ...Typography.monoLabelSm,
    fontSize: 10,
    color: Palette.amber,
  },
  targetsEmpty: {
    ...Typography.bodyMd,
    color: Palette.textMuted,
    paddingVertical: Space.sm,
  },
  targetsList: { gap: 6 },
  targetRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: Space.gutter,
    backgroundColor: Palette.well,
    borderRadius: Radii.tile,
    paddingHorizontal: 12,
    paddingVertical: 10,
    boxShadow: Shadows.sunkenSmall,
  },
  targetDomain: {
    fontFamily: FontNames.monoBold,
    fontSize: 12,
    lineHeight: 16,
    color: Palette.textPrimary,
  },
  targetStatus: {
    fontFamily: FontNames.monoRegular,
    fontSize: 9,
    lineHeight: 13,
    letterSpacing: 0.3,
    color: Palette.success,
  },
  targetLock: {
    width: 28,
    height: 28,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: Palette.amberHighlight,
  },
  targetRemove: {
    width: 28,
    height: 28,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  targetsFooter: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    paddingTop: 2,
  },
  targetsFooterText: {
    ...Typography.bodySm,
    fontSize: 10,
    color: Palette.textMuted,
  },
  empty: {
    ...Typography.bodyMd,
    color: Palette.textMuted,
    textAlign: 'center',
    paddingVertical: Space.xl,
  },
  pressed: { opacity: 0.75 },
});
