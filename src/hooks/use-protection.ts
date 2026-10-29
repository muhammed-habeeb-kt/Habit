import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';

import {
  addAttemptsListener,
  addStateListener,
  areNotificationsEnabled,
  getSnapshot,
  setMode,
  setProtectionEnabled,
  type HabitSnapshot,
  type ProtectionMode,
} from '@/native/habit';

export function useProtection() {
  const [snapshot, setSnapshot] = useState<HabitSnapshot | null>(null);
  const [notificationsEnabled, setNotificationsEnabled] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);
  const mounted = useRef(true);

  const refresh = useCallback(async () => {
    const [next, notifications] = await Promise.all([
      getSnapshot(),
      areNotificationsEnabled(),
    ]);
    if (!mounted.current) return;
    setSnapshot(next);
    setNotificationsEnabled(notifications);
  }, []);

  useEffect(() => {
    mounted.current = true;
    refresh();
    return () => {
      mounted.current = false;
    };
  }, [refresh]);

  useEffect(() => {
    const unsubscribeAttempts = addAttemptsListener((count) => {
      setSnapshot((prev) => (prev ? { ...prev, attemptsToday: count } : prev));
    });
    const unsubscribeState = addStateListener(() => {
      refresh();
    });
    const appState = AppState.addEventListener('change', (state) => {
      if (state === 'active') refresh();
    });
    return () => {
      unsubscribeAttempts();
      unsubscribeState();
      appState.remove();
    };
  }, [refresh]);

  // Keep unlock timers in sync while they matter (native owns the real deadlines).
  const timerActive =
    snapshot?.unlock.cooldownActive === true || snapshot?.unlock.offWindowActive === true;
  useEffect(() => {
    if (!timerActive) return;
    const interval = setInterval(() => {
      refresh();
    }, 3000);
    return () => clearInterval(interval);
  }, [timerActive, refresh]);

  const toggle = useCallback(async () => {
    if (!snapshot || busy) return;
    setBusy(true);
    try {
      const next = await setProtectionEnabled(!snapshot.protectionEnabled);
      setSnapshot(next);
    } finally {
      setBusy(false);
    }
  }, [busy, snapshot]);

  const changeMode = useCallback(
    async (mode: ProtectionMode) => {
      setBusy(true);
      try {
        const next = await setMode(mode);
        setSnapshot(next);
        return next;
      } finally {
        setBusy(false);
      }
    },
    []
  );

  return {
    snapshot,
    notificationsEnabled,
    busy,
    toggle,
    changeMode,
    refresh,
    setSnapshot,
  };
}
