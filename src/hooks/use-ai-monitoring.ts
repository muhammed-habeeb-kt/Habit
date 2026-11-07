import { useCallback, useEffect, useState } from 'react';

import {
  addStateListener,
  getAiSettings,
  normalizeDomain,
  setAiBlockAlerts,
  setAiMonitoredDomains,
  setAiMonitoringEnabled,
  setAiSensitivity,
  setAiWatchedApps,
  type AiSensitivity,
  type AiSettings,
} from '@/native/habit';

/**
 * Reads/writes the AI monitoring settings through the native bridge: the master switch, the
 * sensitivity, the watched apps and the watched browser websites.
 *
 * Every write comes back as the store's own value, so a native refusal (removing a watch
 * while Strict/Aggressive is locked) can never leave the screen showing something that was
 * not persisted. The native store also emits a state event, which keeps Home and the AI tab
 * in sync while both are mounted.
 */
export function useAiMonitoring() {
  const [settings, setSettings] = useState<AiSettings | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let mounted = true;
    getAiSettings().then((next) => {
      if (mounted) setSettings(next);
    });
    const unsubscribe = addStateListener(() => {
      getAiSettings().then((next) => {
        if (mounted) setSettings(next);
      });
    });
    return () => {
      mounted = false;
      unsubscribe();
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

  /**
   * Adds one website. The input is normalized here (`https://www.X.com/a` -> `x.com`) so
   * the stored value is always a bare hostname; a duplicate or a non-domain is ignored.
   */
  const addDomain = useCallback(
    async (input: string) => {
      if (!settings) return;
      const domain = normalizeDomain(input);
      if (!domain || settings.monitoredDomains.includes(domain)) return;
      const next = [...settings.monitoredDomains, domain];
      setBusy(true);
      setSettings({ ...settings, monitoredDomains: next });
      try {
        setSettings(await setAiMonitoredDomains(next));
      } finally {
        setBusy(false);
      }
    },
    [settings]
  );

  const removeDomain = useCallback(
    async (domain: string) => {
      if (!settings) return;
      const next = settings.monitoredDomains.filter((item) => item !== domain);
      setBusy(true);
      setSettings({ ...settings, monitoredDomains: next });
      try {
        setSettings(await setAiMonitoredDomains(next));
      } finally {
        setBusy(false);
      }
    },
    [settings]
  );

  return {
    settings,
    busy,
    toggleMaster,
    toggleBlockAlerts,
    changeSensitivity,
    toggleApp,
    addDomain,
    removeDomain,
  };
}
