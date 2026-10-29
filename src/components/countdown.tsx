import { useEffect, useState } from 'react';

/** "2h 43m" for long waits, "3:07" for the last hour. */
export function formatDuration(ms: number): string {
  const totalSeconds = Math.max(0, Math.ceil(ms / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) {
    return `${hours}h ${minutes.toString().padStart(2, '0')}m`;
  }
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}

/**
 * Ticks locally between native snapshots. The deadline is rebuilt whenever the native
 * remaining value changes, so the display re-syncs at least every few seconds while
 * native stays the only authority on the real deadline.
 */
export function useCountdown(remainingMs: number): number {
  const [display, setDisplay] = useState(remainingMs);

  useEffect(() => {
    const deadline = Date.now() + remainingMs;
    const interval = setInterval(() => {
      setDisplay(Math.max(0, deadline - Date.now()));
    }, 1000);
    return () => clearInterval(interval);
  }, [remainingMs]);

  return display;
}
