import { useCallback, useRef, useState } from 'react';
import { Animated, PanResponder } from 'react-native';

/** Distance (dp) or velocity that counts as "the user meant to close this sheet". */
const DISMISS_DISTANCE = 90;
const DISMISS_VELOCITY = 0.6;
const OFFSCREEN_DISTANCE = 900;

/**
 * Tap-backdrop and swipe-down dismissal for bottom sheets.
 *
 * `panHandlers` goes on the sheet header (or the whole sheet when it has no scrollable
 * content); `animatedStyle` goes on the sheet itself. Both `dismiss()` and a completed
 * drag slide the sheet off-screen before the screen is popped, so closing feels smooth.
 */
export function useSheetDismiss(onDismiss: () => void) {
  const [translateY] = useState(() => new Animated.Value(0));
  const closing = useRef(false);

  const dismiss = useCallback(() => {
    if (closing.current) return;
    closing.current = true;
    Animated.timing(translateY, {
      toValue: OFFSCREEN_DISTANCE,
      duration: 180,
      useNativeDriver: true,
    }).start(() => onDismiss());
  }, [onDismiss, translateY]);

  // Built once: the gesture reads only the stable Animated value and the close callback.
  const [panResponder] = useState(() =>
    PanResponder.create({
      onMoveShouldSetPanResponder: (_, gesture) =>
        gesture.dy > 6 && Math.abs(gesture.dy) > Math.abs(gesture.dx),
      onPanResponderMove: (_, gesture) => {
        translateY.setValue(Math.max(0, gesture.dy));
      },
      onPanResponderRelease: (_, gesture) => {
        if (gesture.dy > DISMISS_DISTANCE || gesture.vy > DISMISS_VELOCITY) {
          Animated.timing(translateY, {
            toValue: OFFSCREEN_DISTANCE,
            duration: 180,
            useNativeDriver: true,
          }).start(() => onDismiss());
          return;
        }
        Animated.spring(translateY, {
          toValue: 0,
          bounciness: 0,
          useNativeDriver: true,
        }).start();
      },
      onPanResponderTerminate: () => {
        Animated.spring(translateY, {
          toValue: 0,
          bounciness: 0,
          useNativeDriver: true,
        }).start();
      },
    })
  );

  return {
    panHandlers: panResponder.panHandlers,
    animatedStyle: { transform: [{ translateY }] },
    dismiss,
  };
}
