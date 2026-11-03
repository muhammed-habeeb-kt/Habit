import { type ReactNode } from 'react';
import { StyleSheet, View, type ViewProps } from 'react-native';

import { Palette, Radii, Shadows } from '@/constants/theme';

type Props = ViewProps & {
  children?: ReactNode;
  /** Applies the standard 20 dp inner padding. */
  padded?: boolean;
};

/** Convex raised module: the standard 24 dp card surface. */
export function NeuCard({ children, padded = true, style, ...rest }: Props) {
  return (
    <View {...rest} style={[styles.base, padded && styles.padded, style]}>
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  base: {
    backgroundColor: Palette.raised,
    borderRadius: Radii.card,
    boxShadow: Shadows.raised,
  },
  padded: {
    padding: 20,
  },
});
