import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useState } from 'react';
import { Animated, Modal, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import {
  DurationWheels,
  formatClockMinutes,
  formatLongMinutes,
  formatShortMinutes,
} from '@/components/duration-wheels';
import { PrimaryGlowButton, SecondaryRaisedButton } from '@/components/neumorphic';
import { FontNames, Palette, Radii, Shadows, Space, Typography } from '@/constants/theme';
import { useSheetDismiss } from '@/hooks/use-sheet-dismiss';

const MIN_OFF_MINUTES = 1;
const MAX_OFF_MINUTES = 1439;

const OFF_PRESETS = [
  { minutes: 1, label: '1m' },
  { minutes: 5, label: '5m' },
  { minutes: 10, label: '10m' },
  { minutes: 30, label: '30m' },
  { minutes: 60, label: '1h' },
  { minutes: 360, label: '6h' },
  { minutes: 720, label: '12h' },
  { minutes: 1439, label: '23h 59m' },
];

function clampOffMinutes(value: number): number {
  return Math.min(MAX_OFF_MINUTES, Math.max(MIN_OFF_MINUTES, Math.round(value)));
}

type Props = {
  visible: boolean;
  /** The saved auto-restart duration, in minutes. */
  initialMinutes: number;
  busy?: boolean;
  onClose: () => void;
  /** `null` means turn protection off without an automatic restart. */
  onConfirm: (minutes: number | null) => void;
};

/**
 * Normal-mode "turn protection off" sheet: the auto-restart duration is chosen with the
 * same 1-minute-resolution wheels as Configure Protection Lock, and turning off without a
 * timer stays available.
 */
export function OffWindowSheet({
  visible,
  initialMinutes,
  busy = false,
  onClose,
  onConfirm,
}: Props) {
  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      statusBarTranslucent
      onRequestClose={onClose}>
      {visible ? (
        <SheetContent
          initialMinutes={initialMinutes}
          busy={busy}
          onClose={onClose}
          onConfirm={onConfirm}
        />
      ) : null}
    </Modal>
  );
}

function SheetContent({ initialMinutes, busy, onClose, onConfirm }: Omit<Props, 'visible'>) {
  const [total, setTotal] = useState(() => clampOffMinutes(initialMinutes || 10));
  const { panHandlers, animatedStyle, dismiss } = useSheetDismiss(onClose);

  return (
    <View style={styles.backdrop}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Close auto-restart sheet"
        onPress={dismiss}
        style={styles.dismissArea}
      />

      <Animated.View style={[styles.sheet, animatedStyle]}>
        <SafeAreaView edges={['bottom']} style={styles.sheetSafe}>
          <View style={styles.sheetHeader} {...panHandlers}>
            <View style={styles.handle} />
          </View>

          <ScrollView
            contentContainerStyle={styles.content}
            showsVerticalScrollIndicator={false}
            nestedScrollEnabled>
            <View style={styles.badge}>
              <View style={styles.badgeDot} />
              <Text style={styles.badgeLabel}>AUTO-RESTART</Text>
            </View>

            <Text style={styles.title}>Turn protection off</Text>
            <Text style={styles.subtitle}>
              Choose how long Protection stays OFF before it turns back on by itself.
            </Text>

            <View style={styles.readout}>
              <MaterialCommunityIcons name="restart" size={22} color={Palette.amber} />
              <Text style={styles.readoutValue}>{formatClockMinutes(total)}</Text>
            </View>

            <View style={styles.presets}>
              {OFF_PRESETS.map((preset) => {
                const active = preset.minutes === total;
                return (
                  <Pressable
                    key={preset.minutes}
                    accessibilityRole="button"
                    accessibilityState={{ selected: active }}
                    onPress={() => setTotal(preset.minutes)}
                    style={[styles.preset, active && styles.presetActive]}>
                    <Text style={[styles.presetLabel, active && styles.presetLabelActive]}>
                      {preset.label}
                    </Text>
                  </Pressable>
                );
              })}
            </View>

            <DurationWheels
              total={total}
              onChange={setTotal}
              minMinutes={MIN_OFF_MINUTES}
              maxMinutes={MAX_OFF_MINUTES}
            />

            <Text style={styles.note}>
              {`Protection turns back on by itself after ${formatLongMinutes(total)}. You can also turn it on immediately whenever you want.`}
            </Text>

            <PrimaryGlowButton
              label={busy ? 'Turning off…' : `Turn off for ${formatShortMinutes(total)}`}
              onPress={() => onConfirm(total)}
              disabled={busy}
              icon={
                <MaterialCommunityIcons
                  name="lock-open-variant-outline"
                  size={20}
                  color={Palette.canvas}
                />
              }
              style={styles.action}
            />

            <SecondaryRaisedButton
              label="Turn off without timer"
              onPress={() => onConfirm(null)}
              disabled={busy}
              style={styles.action}
            />

            <SecondaryRaisedButton label="Stay protected" onPress={dismiss} disabled={busy} />
          </ScrollView>
        </SafeAreaView>
      </Animated.View>
    </View>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: Palette.scrim,
    justifyContent: 'flex-end',
  },
  dismissArea: { flex: 1 },
  sheet: {
    width: '100%',
    maxHeight: '94%',
    backgroundColor: Palette.surface,
    borderTopLeftRadius: 32,
    borderTopRightRadius: 32,
  },
  sheetSafe: { flexShrink: 1 },
  sheetHeader: {
    paddingTop: 12,
    paddingBottom: 8,
    alignItems: 'center',
  },
  handle: {
    width: 40,
    height: 4,
    borderRadius: Radii.pill,
    backgroundColor: Palette.surfaceHigh,
  },
  content: {
    paddingHorizontal: Space.margin,
    paddingBottom: Space.lg,
    gap: Space.md,
  },
  badge: {
    alignSelf: 'center',
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    height: 24,
    paddingHorizontal: 12,
    borderRadius: Radii.pill,
    backgroundColor: Palette.amberHighlight,
    borderWidth: 1,
    borderColor: 'rgba(251, 191, 36, 0.35)',
  },
  badgeDot: {
    width: 5,
    height: 5,
    borderRadius: Radii.pill,
    backgroundColor: Palette.amber,
  },
  badgeLabel: {
    ...Typography.monoLabelSm,
    color: Palette.amber,
  },
  title: {
    fontFamily: FontNames.interBold,
    fontSize: 23,
    lineHeight: 29,
    color: Palette.textPrimary,
    textAlign: 'center',
  },
  subtitle: {
    ...Typography.bodyMd,
    fontSize: 14,
    lineHeight: 20,
    color: Palette.textSecondary,
    textAlign: 'center',
    marginTop: -8,
  },
  readout: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 10,
    paddingVertical: Space.sm,
    borderRadius: 16,
    backgroundColor: Palette.well,
    boxShadow: Shadows.sunkenSmall,
  },
  readoutValue: {
    fontFamily: FontNames.monoBold,
    fontSize: 28,
    lineHeight: 34,
    color: Palette.amber,
    fontVariant: ['tabular-nums'],
  },
  presets: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  preset: {
    flex: 1,
    minWidth: 52,
    height: 36,
    borderRadius: Radii.pill,
    borderWidth: 1,
    borderColor: Palette.surfaceHigh,
    backgroundColor: Palette.well,
    alignItems: 'center',
    justifyContent: 'center',
  },
  presetActive: {
    borderColor: Palette.amber,
    backgroundColor: Palette.amberHighlight,
  },
  presetLabel: {
    fontFamily: FontNames.monoBold,
    fontSize: 12,
    lineHeight: 16,
    color: Palette.textSecondary,
  },
  presetLabelActive: { color: Palette.amber },
  note: {
    ...Typography.bodySm,
    color: Palette.textMuted,
  },
  action: { width: '100%' },
});
