import { useColorScheme } from 'react-native';

import { Colors, type Theme } from '@/constants/theme';

export function useTheme(): Theme {
  const scheme = useColorScheme();
  return Colors[scheme === 'dark' ? 'dark' : 'light'];
}
