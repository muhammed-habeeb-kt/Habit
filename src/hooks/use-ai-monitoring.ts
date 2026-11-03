import { useCallback, useEffect, useState } from 'react';

import {
  getAiSettings,
  setAiBlockAlerts,
  setAiMonitoringEnabled,
  setAiSensitivity,
  setAiWatchedApps,
  type AiSensitivity,
  type AiSettings,
} from '@/native/habit';

/**
 * Reads/writes the AI monitoring settings through the native bridge.
 * No AI engine is running yet: these settings are stored so the future scanner
 * only has to read them.
 */
export function useAiMonitoring() {
  const [settings, setSettings] = useState<AiSettings | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let mounted = true;
    getAiSettings().then((next) => {
      if (mounted) setSettings(next);
    });
    return () => {
      mounted = false;
    };
  }, []);

  const toggleMaster = useCallback(async (enabled: boolean) => {
    setBusy(true);
    setSettings((prev) => (prev ? { ...prev, enabled } : prev));
    try {
      setSettings(await setAiMonitoringEnabled(enabled));
    } finally {
      setBusy(false);
    }
  }, []);

  const toggleBlockAlerts = useCallback(async (enabled: boolean) => {
    setBusy(true);
    setSettings((prev) => (prev ? { ...prev, blockAlertsEnabled: enabled } : prev));
    try {
      setSettings(await setAiBlockAlerts(enabled));
    } finally {
      setBusy(false);
    }
  }, []);

  const changeSensitivity = useCallback(async (sensitivity: AiSensitivity) => {
    setBusy(true);
    setSettings((prev) => (prev ? { ...prev, sensitivity } : prev));
    try {
      setSettings(await setAiSensitivity(sensitivity));
    } finally {
      setBusy(false);
    }
  }, []);

  const toggleApp = useCallback(
    async (packageName: string) => {
      if (!settings) return;
      const watchedApps = settings.monitoredAppPackages.includes(packageName)
        ? settings.monitoredAppPackages.filter((pkg) => pkg !== packageName)
        : [...settings.monitoredAppPackages, packageName];
      setBusy(true);
      setSettings({ ...settings, monitoredAppPackages: watchedApps });
      try {
        setSettings(await setAiWatchedApps(watchedApps));
      } finally {
        setBusy(false);
      }
    },
    [settings]
  );

  return { settings, busy, toggleMaster, toggleBlockAlerts, changeSensitivity, toggleApp };
}
