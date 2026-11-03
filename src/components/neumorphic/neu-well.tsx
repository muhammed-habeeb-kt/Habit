import { type ReactNode } from 'react';
import { StyleSheet, View, type ViewProps } from 'react-native';

import { Palette, Radii, Shadows } from '@/constants/theme';

type Props = ViewProps & {
  children?: ReactNode;
  /** Defaults to the 20 dp card padding; pass a custom style to override. */
  padded?: boolean;
};

/** Concave sunken well: inset tracks, counters, inputs and nested groups. */
export function NeuWell({ children, padded = true, style, ...rest }: Props) {
  return (
    <View {...rest} style={[styles.base, padded && styles.padded, style]}>
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  base: {
    backgroundColor: Palette.well,
    borderRadius: Radii.well,
    boxShadow: Shadows.sunken,
  },
  padded: {
    padding: 16,
  },
});
