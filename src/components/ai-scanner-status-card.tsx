import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Alert, StyleSheet, Text, View } from 'react-native';

import {
  NeuCard,
  NeuSwitch,
  SecondaryRaisedButton,
  StatusPill,
} from '@/components/neumorphic';
import { FontNames, Palette, Radii, Space, Typography } from '@/constants/theme';
import {
  canDebugAiSession,
  canReadAiScannerStatus,
  clearAiDebugSession,
  exportAiDebugSession,
  getAiScannerStatus,
  openAccessibilitySettings,
  openOverlaySettings,
  setAiDebugDryRun,
  setAiDebugRecording,
  testAiModels,
  type AiModelSelfTest,
  type AiScannerStatus,
} from '@/native/habit';

const POLL_MS = 1_200;

function seconds(value: number): string {
  if (value < 0) return 'never';
  if (value < 1_000) return 'now';
  return `${Math.round(value / 1_000)}s ago`;
}

function score(value: number): string {
  return value.toFixed(2);
}

function Row({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <View style={styles.row}>
      <Text style={styles.rowLabel}>{label}</Text>
      <Text style={[styles.rowValue, tone ? { color: tone } : null]} numberOfLines={1}>
        {value}
      </Text>
    </View>
  );
}

/**
 * Live diagnostics for the on-device AI screenshot scanner.
 *
 * Everything here is measured from the running native scanner, so a phone that blocks
 * nothing shows exactly which link is broken: accessibility, screenshot access, the
 * overlay permission, a model that failed to load, or the last screenshot error.
 */
export function AiScannerStatusCard() {
  const [status, setStatus] = useState<AiScannerStatus | null>(null);
  const [test, setTest] = useState<AiModelSelfTest | null>(null);
  const [testing, setTesting] = useState(false);
  const [savingDebug, setSavingDebug] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [clearing, setClearing] = useState(false);
  const mounted = useRef(true);

  const refresh = useCallback(async () => {
    const next = await getAiScannerStatus();
    if (mounted.current) setStatus(next);
  }, []);

  useEffect(() => {
    mounted.current = true;
    refresh();
    const timer = setInterval(() => {
      void refresh();
    }, POLL_MS);
    return () => {
      mounted.current = false;
      clearInterval(timer);
    };
  }, [refresh]);

  const runTest = useCallback(async () => {
    setTesting(true);
    try {
      const result = await testAiModels();
      if (mounted.current) setTest(result);
    } finally {
      if (mounted.current) setTesting(false);
    }
  }, []);

  const toggleRecording = useCallback(
    async (enabled: boolean) => {
      setSavingDebug(true);
      // Optimistic: the native setting is written immediately, the poll confirms it.
      setStatus((prev) => (prev ? { ...prev, debugRecordingEnabled: enabled } : prev));
      try {
        const next = await setAiDebugRecording(enabled);
        if (!mounted.current) return;
        setStatus((prev) =>
          prev
            ? {
                ...prev,
                debugRecordingEnabled: next.debugRecordingEnabled,
                debugDryRun: next.debugDryRun,
              }
            : prev
        );
        await refresh();
      } finally {
        if (mounted.current) setSavingDebug(false);
      }
    },
    [refresh]
  );

  const toggleDryRun = useCallback(
    async (enabled: boolean) => {
      setSavingDebug(true);
      setStatus((prev) => (prev ? { ...prev, debugDryRun: enabled } : prev));
      try {
        const next = await setAiDebugDryRun(enabled);
        if (!mounted.current) return;
        setStatus((prev) =>
          prev
            ? {
                ...prev,
                debugRecordingEnabled: next.debugRecordingEnabled,
                debugDryRun: next.debugDryRun,
              }
            : prev
        );
        await refresh();
      } finally {
        if (mounted.current) setSavingDebug(false);
      }
    },
    [refresh]
  );

  const runExport = useCallback(async () => {
    setExporting(true);
    try {
      const result = await exportAiDebugSession();
      if (!mounted.current) return;
      await refresh();
      if (!mounted.current) return;
      if (result.ok) {
        const lines = [result.csvPath, result.zipPath].filter(
          (line): line is string => typeof line === 'string' && line.length > 0
        );
        Alert.alert(
          'Exported to Download',
          `${lines.join('\n')}\n\n${result.frames} frame${
            result.frames === 1 ? '' : 's'
          } included in the ZIP.`
        );
      } else {
        Alert.alert('Export failed', result.error ?? 'Nothing was saved to Download.');
      }
    } finally {
      if (mounted.current) setExporting(false);
    }
  }, [refresh]);

  const runClear = useCallback(async () => {
    setClearing(true);
    try {
      const result = await clearAiDebugSession();
      if (!mounted.current) return;
      await refresh();
      if (!mounted.current) return;
      if (!result.ok) {
        Alert.alert('Could not clear the session', result.error ?? 'Try again.');
      }
    } finally {
      if (mounted.current) setClearing(false);
    }
  }, [refresh]);

  const confirmClear = useCallback(() => {
    Alert.alert(
      'Clear recorded session?',
      'The CSV and every recorded frame are deleted from this phone.',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Clear',
          style: 'destructive',
          onPress: () => {
            void runClear();
          },
        },
      ]
    );
  }, [runClear]);

  const live = status?.running === true;
  const lastError = status?.lastError ?? null;
  const modelError = status?.modelError ?? null;
  const overlayAllowed = status?.overlayAllowed === true;
  const accessibilityEnabled = status?.accessibilityEnabled === true;
  const recording = status?.debugRecordingEnabled === true;
  const dryRun = status?.debugDryRun === true;
  const recordedFrames = status?.recordedFrames ?? 0;
  const debugBusy = savingDebug || exporting || clearing || status == null;

  return (
    <NeuCard style={styles.card}>
      <View style={styles.header}>
        <View style={styles.iconWell}>
          <MaterialCommunityIcons name="radar" size={20} color={Palette.amber} />
        </View>
        <View style={styles.grow}>
          <Text style={styles.title}>Live AI scanner status</Text>
          <Text style={styles.subtitle}>
            {live
              ? `Scanning ${status?.appLabel ?? status?.packageName ?? 'app'}`
              : 'Idle — open a watched app to start'}
          </Text>
        </View>
        <StatusPill
          label={live ? 'LIVE' : 'IDLE'}
          tone={live ? 'success' : 'neutral'}
          dot
        />
      </View>

      {!canReadAiScannerStatus ? (
        <Text style={styles.note}>
          This build has no AI diagnostics bridge. Rebuild the development APK to see the
          scanner state here.
        </Text>
      ) : (
        <>
          <View style={styles.rows}>
            <Row
              label="Accessibility"
              value={accessibilityEnabled ? 'Granted' : 'Off'}
              tone={accessibilityEnabled ? Palette.success : Palette.danger}
            />
            <Row
              label="Screenshot access"
              value={
                status?.screenshotSupported === false
                  ? 'Needs Android 11+'
                  : accessibilityEnabled
                    ? 'Ready (Android 11+)'
                    : 'Granted by accessibility'
              }
              tone={status?.screenshotSupported === false ? Palette.danger : undefined}
            />
            <Row
              label="Display over apps"
              value={overlayAllowed ? 'Allowed' : 'Not allowed (fallback only)'}
              tone={overlayAllowed ? Palette.success : Palette.textSecondary}
            />
            <Row
              label="NSFW MobileNetV2"
              value={
                status?.modelsReady
                  ? (status.stage1Model ?? 'Loaded')
                  : (modelError ?? 'Not loaded yet')
              }
              tone={status?.modelsReady ? Palette.success : Palette.textSecondary}
            />
            <Row
              label="NudeNet 320n"
              value={
                status?.modelsReady
                  ? (status.stage2Model ?? 'Loaded')
                  : (modelError ?? 'Not loaded yet')
              }
              tone={status?.modelsReady ? Palette.success : Palette.textSecondary}
            />
            <Row
              label="Watched apps"
              value={`${status?.monitoredApps ?? 0} selected${
                status?.aiEnabled === false ? ' · monitoring OFF' : ''
              }`}
              tone={status?.aiEnabled === false ? Palette.danger : undefined}
            />
            <Row
              label="Last app / frames"
              value={`${status?.appLabel ?? '—'} · ${status?.frames ?? 0} frames · ${
                status?.blocks ?? 0
              } blocked`}
            />
            <Row
              label="Last scores"
              value={`mob ${score(status?.stage1Score ?? 0)} · nude ${score(
                status?.stage2Score ?? 0
              )} (${status?.verdict ?? 'idle'})`}
            />
            <Row label="Last frame" value={seconds(status?.lastFrameAgeMs ?? -1)} />
          </View>

          {lastError ? (
            <View style={styles.errorBox}>
              <MaterialCommunityIcons
                name="alert-circle-outline"
                size={16}
                color={Palette.danger}
              />
              <Text style={styles.errorText} numberOfLines={4}>
                {lastError} · {seconds(status?.lastErrorAgeMs ?? -1)}
              </Text>
            </View>
          ) : null}

          <View style={styles.debugBox}>
            <Text style={styles.sectionLabel}>DEBUG SESSION</Text>
            <View style={styles.toggleRow}>
              <View style={styles.grow}>
                <Text style={styles.toggleTitle}>Record Debug CSV & Frames</Text>
                <Text style={styles.toggleHint}>
                  Saves every scanned frame and both model scores for calibration.
                </Text>
              </View>
              <NeuSwitch
                value={recording}
                onValueChange={(value) => {
                  void toggleRecording(value);
                }}
                disabled={debugBusy || !canDebugAiSession}
                label="Record Debug CSV and Frames"
              />
            </View>
            <View style={styles.toggleRow}>
              <View style={styles.grow}>
                <Text style={styles.toggleTitle}>Dry-Run Mode (Record scores without blocking)</Text>
                <Text style={styles.toggleHint}>
                  Logs “would block” without the block screen. Needs recording on.
                </Text>
              </View>
              <NeuSwitch
                value={dryRun}
                onValueChange={(value) => {
                  void toggleDryRun(value);
                }}
                disabled={debugBusy || !canDebugAiSession || !recording}
                label="Dry-run mode"
              />
            </View>
            <Row label="Recorded frames" value={`${recordedFrames} in session`} />
            <SecondaryRaisedButton
              label="⬇ Export CSV & Frames to Downloads"
              onPress={() => {
                void runExport();
              }}
              disabled={debugBusy || !canDebugAiSession || recordedFrames === 0}
              icon={
                exporting ? (
                  <ActivityIndicator color={Palette.textPrimary} size="small" />
                ) : (
                  <MaterialCommunityIcons
                    name="download"
                    size={18}
                    color={Palette.textPrimary}
                  />
                )
              }
            />
            <SecondaryRaisedButton
              label="🗑 Clear Recorded Session"
              onPress={confirmClear}
              disabled={debugBusy || !canDebugAiSession || recordedFrames === 0}
              icon={
                clearing ? (
                  <ActivityIndicator color={Palette.textPrimary} size="small" />
                ) : (
                  <MaterialCommunityIcons
                    name="delete-outline"
                    size={18}
                    color={Palette.textPrimary}
                  />
                )
              }
            />
            {!canDebugAiSession ? (
              <Text style={styles.note}>
                This build has no debug session bridge. Rebuild the development APK to record
                and export frames.
              </Text>
            ) : null}
          </View>
        </>
      )}

      {!accessibilityEnabled ? (
        <SecondaryRaisedButton
          label="Turn on accessibility"
          onPress={() => {
            void openAccessibilitySettings();
          }}
        />
      ) : null}

      {!overlayAllowed ? (
        <SecondaryRaisedButton
          label="Allow display over other apps"
          onPress={() => {
            void openOverlaySettings();
          }}
        />
      ) : null}

      <SecondaryRaisedButton
        label={testing ? 'Testing models…' : 'Test AI models now'}
        onPress={() => {
          void runTest();
        }}
        disabled={testing}
        icon={
          testing ? (
            <ActivityIndicator color={Palette.textPrimary} size="small" />
          ) : (
            <MaterialCommunityIcons name="play-circle-outline" size={18} color={Palette.textPrimary} />
          )
        }
      />

      {test ? (
        <View style={styles.testBox}>
          <Row
            label="MobileNetV2 test"
            value={
              test.stage1Ok
                ? `OK · score ${score(test.stage1Score)}`
                : (test.stage1Error ?? 'failed')
            }
            tone={test.stage1Ok ? Palette.success : Palette.danger}
          />
          <Row
            label="NudeNet test"
            value={
              test.stage2Ok
                ? `OK · score ${score(test.stage2Score)}`
                : (test.stage2Error ?? 'failed')
            }
            tone={test.stage2Ok ? Palette.success : Palette.danger}
          />
          <Row label="Elapsed" value={`${test.elapsedMs} ms`} />
        </View>
      ) : null}

      <Text style={styles.benign}>
        The test runs both models once on a generated image, never on your screen.
      </Text>
    </NeuCard>
  );
}

const styles = StyleSheet.create({
  card: { gap: Space.gutter },
  header: { flexDirection: 'row', alignItems: 'center', gap: Space.sm },
  iconWell: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: Palette.amberHighlight,
    alignItems: 'center',
    justifyContent: 'center',
  },
  grow: { flex: 1, minWidth: 0, gap: 2 },
  title: {
    ...Typography.headlineMd,
    fontSize: 15,
    lineHeight: 20,
    color: Palette.textPrimary,
  },
  subtitle: {
    ...Typography.bodyMd,
    fontSize: 12,
    color: Palette.textSecondary,
  },
  note: {
    ...Typography.bodyMd,
    fontSize: 12,
    color: Palette.textMuted,
  },
  rows: { gap: 6 },
  row: { flexDirection: 'row', alignItems: 'center', gap: Space.sm },
  rowLabel: {
    fontFamily: FontNames.monoRegular,
    fontSize: 10,
    lineHeight: 15,
    letterSpacing: 0.4,
    color: Palette.textMuted,
    textTransform: 'uppercase',
    width: 128,
  },
  rowValue: {
    flex: 1,
    fontFamily: FontNames.monoRegular,
    fontSize: 11,
    lineHeight: 15,
    color: Palette.textPrimary,
    textAlign: 'right',
  },
  errorBox: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: 8,
    borderRadius: Radii.control,
    backgroundColor: 'rgba(248, 113, 113, 0.10)',
    paddingHorizontal: 12,
    paddingVertical: 10,
  },
  errorText: {
    flex: 1,
    ...Typography.bodyMd,
    fontSize: 12,
    lineHeight: 17,
    color: Palette.danger,
  },
  testBox: {
    gap: 6,
    borderRadius: Radii.control,
    backgroundColor: Palette.well,
    paddingHorizontal: 12,
    paddingVertical: 10,
  },
  debugBox: {
    gap: 10,
    borderRadius: Radii.control,
    backgroundColor: Palette.well,
    paddingHorizontal: 12,
    paddingVertical: 12,
  },
  sectionLabel: {
    fontFamily: FontNames.monoRegular,
    fontSize: 10,
    lineHeight: 15,
    letterSpacing: 1.2,
    color: Palette.amber,
  },
  toggleRow: { flexDirection: 'row', alignItems: 'center', gap: Space.sm },
  toggleTitle: {
    ...Typography.bodyMd,
    fontSize: 13,
    lineHeight: 18,
    color: Palette.textPrimary,
  },
  toggleHint: {
    ...Typography.bodySm,
    fontSize: 11,
    lineHeight: 15,
    color: Palette.textMuted,
  },
  benign: {
    ...Typography.bodySm,
    fontSize: 11,
    color: Palette.textMuted,
  },
});
