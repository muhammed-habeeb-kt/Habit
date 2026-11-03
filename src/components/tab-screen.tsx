import { type ReactNode } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { MaxContentWidth, Palette, Space } from '@/constants/theme';

/** Dark canvas + safe area + scrolling content with room for the floating tabs. */
export function TabScreen({ children }: { children: ReactNode }) {
  return (
    <View style={styles.screen}>
      <SafeAreaView style={styles.safe} edges={['top', 'left', 'right']}>
        <ScrollView
          contentContainerStyle={styles.content}
          showsVerticalScrollIndicator={false}
          keyboardShouldPersistTaps="handled">
          {children}
        </ScrollView>
      </SafeAreaView>
    </View>
  );
}

export function SectionLabel({ children }: { children: ReactNode }) {
  return <View style={styles.sectionLabel}>{children}</View>;
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: Palette.canvas },
  safe: { flex: 1 },
  content: {
    width: '100%',
    maxWidth: MaxContentWidth,
    alignSelf: 'center',
    paddingHorizontal: Space.margin,
    paddingTop: Space.sm,
    paddingBottom: Space.tabBar,
    gap: Space.md,
  },
  sectionLabel: { marginTop: Space.sm },
});
