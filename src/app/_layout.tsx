import {
  Inter_400Regular,
  Inter_500Medium,
  Inter_600SemiBold,
  Inter_700Bold,
} from '@expo-google-fonts/inter';
import {
  SpaceMono_400Regular,
  SpaceMono_700Bold,
} from '@expo-google-fonts/space-mono';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useFonts } from 'expo-font';
import {
  DarkTheme,
  Stack,
  ThemeProvider,
  type ErrorBoundaryProps,
} from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { Palette } from '@/constants/theme';
import blocklist from '@/data/blocklist.json';
import { publishDomains } from '@/native/habit';

SplashScreen.preventAutoHideAsync().catch(() => {});

const NavigationTheme = {
  ...DarkTheme,
  colors: {
    ...DarkTheme.colors,
    background: Palette.canvas,
    card: Palette.surface,
    text: Palette.textPrimary,
    border: '#26272B',
    primary: Palette.amber,
  },
};

/**
 * Shown instead of a blank screen if a route throws while rendering.
 * Keeps the message on screen so it can be reported without a computer.
 */
export function ErrorBoundary({ error, retry }: ErrorBoundaryProps) {
  return (
    <View style={errorStyles.screen}>
      <Text style={errorStyles.title}>Habit could not draw this screen</Text>
      <Text style={errorStyles.message}>{error.message}</Text>
      <Pressable
        accessibilityRole="button"
        onPress={retry}
        style={({ pressed }) => [errorStyles.button, pressed && errorStyles.pressed]}>
        <Text style={errorStyles.buttonLabel}>Try again</Text>
      </Pressable>
    </View>
  );
}

const errorStyles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: '#101114',
    justifyContent: 'center',
    padding: 24,
    gap: 12,
  },
  title: { color: '#F2F2F4', fontSize: 20, fontWeight: '700' },
  message: { color: '#F87171', fontSize: 13, lineHeight: 20 },
  button: {
    alignSelf: 'flex-start',
    marginTop: 8,
    borderRadius: 999,
    backgroundColor: '#FBBF24',
    paddingHorizontal: 20,
    paddingVertical: 10,
  },
  pressed: { opacity: 0.8 },
  buttonLabel: { color: '#101114', fontSize: 14, fontWeight: '700' },
});

export default function RootLayout() {
  const [fontsLoaded, fontError] = useFonts({
    Inter_400Regular,
    Inter_500Medium,
    Inter_600SemiBold,
    Inter_700Bold,
    SpaceMono_400Regular,
    SpaceMono_700Bold,
    ...MaterialCommunityIcons.font,
  });

  useEffect(() => {
    if (fontsLoaded || fontError) {
      SplashScreen.hideAsync().catch(() => {});
      return;
    }
    // Never leave the app stuck on the splash screen if font assets cannot load:
    // text falls back to the system face and the UI still renders.
    const fallback = setTimeout(() => {
      SplashScreen.hideAsync().catch(() => {});
    }, 2500);
    return () => clearTimeout(fallback);
  }, [fontsLoaded, fontError]);

  useEffect(() => {
    publishDomains(blocklist as string[]).catch(() => {});
  }, []);

  return (
    <ThemeProvider value={NavigationTheme}>
      <StatusBar style="light" />
      <Stack
        screenOptions={{
          headerShown: false,
          contentStyle: { backgroundColor: Palette.canvas },
        }}>
        <Stack.Screen name="(tabs)" />
        <Stack.Screen name="browsers" options={{ animation: 'slide_from_right' }} />
        <Stack.Screen name="known-limits" options={{ animation: 'slide_from_right' }} />
        <Stack.Screen
          name="unlock"
          options={{ presentation: 'modal', animation: 'slide_from_bottom' }}
        />
        <Stack.Screen
          name="aggressive"
          options={{
            presentation: 'transparentModal',
            animation: 'slide_from_bottom',
            contentStyle: { backgroundColor: 'transparent' },
          }}
        />
        <Stack.Screen
          name="configure-protection"
          options={{
            presentation: 'transparentModal',
            animation: 'slide_from_bottom',
            contentStyle: { backgroundColor: 'transparent' },
          }}
        />
        <Stack.Screen
          name="face-unlock"
          options={{ presentation: 'modal', animation: 'slide_from_bottom' }}
        />
        <Stack.Screen
          name="onboarding"
          options={{ presentation: 'modal', animation: 'slide_from_bottom' }}
        />
      </Stack>
    </ThemeProvider>
  );
}
