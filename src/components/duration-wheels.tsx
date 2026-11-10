import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ScrollView,
  StyleSheet,
  Text,
  View,
  type StyleProp,
  type ViewStyle,
} from 'react-native';

import { FontNames, Palette, Typography } from '@/constants/theme';

export const ROW_HEIGHT = 36;
export const VISIBLE_ROWS = 5;
const WHEEL_PADDING = (ROW_HEIGHT * (VISIBLE_ROWS - 1)) / 2;

export type WheelScrollRef = React.RefObject<ScrollView | null>;

export function pad2(value: number): string {
  return value.toString().padStart(2, '0');
}

/** "00:10", "01:30", "23:59" — the HH:MM readout used by both timers. */
export function formatClockMinutes(total: number): string {
  const safe = Math.max(0, Math.trunc(total));
  return `${pad2(Math.floor(safe / 60))}:${pad2(safe % 60)}`;
}

/** "45m" / "1h" / "1h 30m" for compact labels such as pipeline captions. */
export function formatShortMinutes(total: number): string {
  const safe = Math.max(0, Math.trunc(total));
  const hours = Math.floor(safe / 60);
  const minutes = safe % 60;
  if (hours === 0) return `${minutes}m`;
  return minutes === 0 ? `${hours}h` : `${hours}h ${minutes}m`;
}

/** "45 minutes" / "1 hour 30 min" for body copy. */
export function formatLongMinutes(total: number): string {
  const safe = Math.max(0, Math.trunc(total));
  const hours = Math.floor(safe / 60);
  const minutes = safe % 60;
  if (hours === 0) return `${minutes} ${minutes === 1 ? 'minute' : 'minutes'}`;
  const hourLabel = `${hours} ${hours === 1 ? 'hour' : 'hours'}`;
  return minutes === 0 ? hourLabel : `${hourLabel} ${minutes} min`;
}

function range(start: number, end: number): number[] {
  return Array.from({ length: end - start + 1 }, (_, index) => start + index);
}

type WheelProps = {
  values: readonly number[];
  selectedIndex: number;
  onSelect: (index: number) => void;
  scrollRef: WheelScrollRef;
  /** Bumped by the parent so a clamped pick still snaps the wheel back. */
  snapToken?: number;
  style?: StyleProp<ViewStyle>;
};

/**
 * One snapping vertical wheel column. Scrolling reports the centred row to the parent;
 * `nestedScrollEnabled` keeps the gesture inside the wheel instead of the sheet.
 */
export function Wheel({ values, selectedIndex, onSelect, scrollRef, snapToken, style }: WheelProps) {
  const settleTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    scrollRef.current?.scrollTo({ y: Math.max(0, selectedIndex) * ROW_HEIGHT, animated: false });
  }, [scrollRef, selectedIndex, snapToken]);

  useEffect(
    () => () => {
      if (settleTimer.current) clearTimeout(settleTimer.current);
    },
    []
  );

  // Debounced so a flick isn't read halfway through its momentum.
  const handleScroll = (offsetY: number) => {
    if (settleTimer.current) clearTimeout(settleTimer.current);
    settleTimer.current = setTimeout(() => {
      const index = Math.min(
        values.length - 1,
        Math.max(0, Math.round(offsetY / ROW_HEIGHT))
      );
      if (index !== selectedIndex) onSelect(index);
    }, 140);
  };

  return (
    <View style={[styles.wheel, style]}>
      <View style={styles.wheelHighlight} pointerEvents="none" />
      <ScrollView
        ref={scrollRef}
        style={styles.wheelScroll}
        contentContainerStyle={styles.wheelContent}
        showsVerticalScrollIndicator={false}
        snapToInterval={ROW_HEIGHT}
        decelerationRate="fast"
        nestedScrollEnabled
        scrollEventThrottle={16}
        onScroll={(event) => handleScroll(event.nativeEvent.contentOffset.y)}
        onMomentumScrollEnd={(event) => handleScroll(event.nativeEvent.contentOffset.y)}>
        {values.map((value, index) => (
          <View key={value} style={styles.wheelRow}>
            <Text
              style={[styles.wheelValue, index === selectedIndex && styles.wheelValueActive]}>
              {pad2(value)}
            </Text>
          </View>
        ))}
      </ScrollView>
    </View>
  );
}

export type DurationWheelsProps = {
  /** Selected duration in whole minutes. */
  total: number;
  onChange: (minutes: number) => void;
  /** Smallest selectable duration in minutes (e.g. 1 or 180). */
  minMinutes: number;
  /** Largest selectable duration in minutes (e.g. 1439 or 1440). */
  maxMinutes: number;
};

/**
 * Hours (00..23) + Minutes (00..59) wheels with every single minute selectable.
 *
 * Boundary hours clamp their minutes, so `minMinutes = 1` can never show `00:00` and
 * `maxMinutes = 1440` shows only `24:00`. Both wheels snap back when a pick is clamped.
 */
export function DurationWheels({
  total,
  onChange,
  minMinutes,
  maxMinutes,
}: DurationWheelsProps) {
  const minHour = Math.floor(minMinutes / 60);
  const maxHour = Math.floor(maxMinutes / 60);
  const hours = useMemo(() => range(minHour, maxHour), [minHour, maxHour]);

  const safeTotal = Math.min(maxMinutes, Math.max(minMinutes, Math.trunc(total)));
  const hour = Math.floor(safeTotal / 60);
  const minute = safeTotal % 60;

  const minuteValues = useMemo(() => {
    const low = hour === minHour ? minMinutes - hour * 60 : 0;
    const high = hour === maxHour ? maxMinutes - hour * 60 : 59;
    return range(Math.max(0, low), Math.min(59, high));
  }, [hour, minHour, maxHour, minMinutes, maxMinutes]);

  const hourIndex = Math.min(hours.length - 1, Math.max(0, hour - minHour));
  const minuteIndex = Math.max(0, minuteValues.indexOf(minute));

  const hoursRef = useRef<ScrollView | null>(null);
  const minutesRef = useRef<ScrollView | null>(null);
  const [snapToken, setSnapToken] = useState(0);

  const apply = useCallback(
    (next: number) => {
      onChange(Math.min(maxMinutes, Math.max(minMinutes, next)));
      setSnapToken((current) => current + 1);
    },
    [maxMinutes, minMinutes, onChange]
  );

  const selectHour = useCallback(
    (index: number) => {
      const nextHour = hours[index] ?? minHour;
      const low = nextHour === minHour ? minMinutes - nextHour * 60 : 0;
      const high = nextHour === maxHour ? maxMinutes - nextHour * 60 : 59;
      const nextMinute = Math.min(high, Math.max(low, minute));
      apply(nextHour * 60 + nextMinute);
    },
    [apply, hours, maxHour, maxMinutes, minHour, minMinutes, minute]
  );

  const selectMinute = useCallback(
    (index: number) => {
      const nextMinute = minuteValues[index];
      if (nextMinute === undefined) return;
      apply(hour * 60 + nextMinute);
    },
    [apply, hour, minuteValues]
  );

  return (
    <View style={styles.wheels}>
      <View style={styles.wheelColumn}>
        <Text style={styles.wheelLabel}>HOURS</Text>
        <Wheel
          values={hours}
          selectedIndex={hourIndex}
          onSelect={selectHour}
          scrollRef={hoursRef}
          snapToken={snapToken}
        />
      </View>
      <Text style={styles.wheelColon}>:</Text>
      <View style={styles.wheelColumn}>
        <Text style={styles.wheelLabel}>MINUTES</Text>
        <Wheel
          values={minuteValues}
          selectedIndex={minuteIndex}
          onSelect={selectMinute}
          scrollRef={minutesRef}
          snapToken={snapToken}
        />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wheels: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    justifyContent: 'center',
    gap: 8,
  },
  wheelColumn: { flex: 1, alignItems: 'center', gap: 4 },
  wheelLabel: {
    ...Typography.monoLabelSm,
    fontSize: 9,
    lineHeight: 12,
    color: Palette.textMuted,
  },
  wheel: {
    width: '100%',
    height: ROW_HEIGHT * VISIBLE_ROWS,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: Palette.surfaceHigh,
    backgroundColor: Palette.well,
    overflow: 'hidden',
  },
  wheelHighlight: {
    position: 'absolute',
    top: WHEEL_PADDING,
    left: 6,
    right: 6,
    height: ROW_HEIGHT,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: 'rgba(251, 191, 36, 0.45)',
    backgroundColor: Palette.amberHighlight,
  },
  wheelScroll: { flex: 1 },
  wheelContent: { paddingVertical: WHEEL_PADDING },
  wheelRow: {
    height: ROW_HEIGHT,
    alignItems: 'center',
    justifyContent: 'center',
  },
  wheelValue: {
    fontFamily: FontNames.monoBold,
    fontSize: 17,
    lineHeight: 22,
    color: Palette.textMuted,
    fontVariant: ['tabular-nums'],
  },
  wheelValueActive: { color: Palette.amber },
  wheelColon: {
    fontFamily: FontNames.monoBold,
    fontSize: 20,
    lineHeight: 26,
    color: Palette.textSecondary,
    marginTop: WHEEL_PADDING + 16,
  },
});
