import { requireOptionalNativeModule } from 'expo';
import { Platform } from 'react-native';

export type ProtectionMode = 'normal' | 'strict' | 'aggressive';

export type UnlockInfo = {
  cooldownActive: boolean;
  cooldownRemainingMs: number;
  cooldownTotalMs: number;
  offWindowActive: boolean;
  offWindowRemainingMs: number;
  offWindowTotalMs: number;
  failuresToday: number;
  lockRemainingMs: number;
  fastCooldown: boolean;
  restoreMode: ProtectionMode;
};

export type HabitSnapshot = {
  protectionEnabled: boolean;
  mode: ProtectionMode;
  onboardingCompleted: boolean;
  attemptsToday: number;
  accessibilityEnabled: boolean;
  foregroundServiceRunning: boolean;
  debugBuild: boolean;
  deviceAdminActive: boolean;
  unlock: UnlockInfo;
};

export type CharacterChallenge = {
  challengeId: string;
  text: string;
  length: number;
};

export type ChallengeResult = {
  success: boolean;
  challengeId: string;
  text: string;
  length: number;
  lockRemainingMs: number;
  failuresToday: number;
};

export type FaceCheckResult = {
  status: 'first' | 'unlocked' | 'retry' | 'error';
  faces: number;
  stage: number;
  message: string;
};

type AttemptsEvent = { count: number };
type Listener = (payload: AttemptsEvent) => void;
type Subscription = { remove(): void };

type HabitNativeModule = {
  getSnapshot(): Promise<HabitSnapshot>;
  setProtectionEnabled(enabled: boolean): Promise<HabitSnapshot>;
  setMode(mode: ProtectionMode): Promise<HabitSnapshot>;
  requestUnlockTimer(): Promise<HabitSnapshot>;
  cancelUnlockTimer(): Promise<HabitSnapshot>;
  startCharacterChallenge(length: number): Promise<CharacterChallenge | null>;
  submitCharacterChallenge(
    challengeId: string,
    typed: string
  ): Promise<ChallengeResult | null>;
  startFaceUnlock(): Promise<boolean>;
  submitFacePhoto(uri: string): Promise<FaceCheckResult | null>;
  cancelFaceUnlock(): Promise<boolean>;
  setFastCooldown(enabled: boolean): Promise<boolean>;
  debugExitStrict(): Promise<HabitSnapshot>;
  isDeviceAdminActive(): Promise<boolean>;
  activateDeviceAdmin(): Promise<boolean>;
  publishLists(domainsJson: string, keywordsJson: string): Promise<boolean>;
  setOnboardingCompleted(completed: boolean): Promise<HabitSnapshot>;
  isAccessibilityEnabled(): Promise<boolean>;
  areNotificationsEnabled(): Promise<boolean>;
  requestNotificationPermission(): Promise<boolean>;
  openAccessibilitySettings(): Promise<boolean>;
  openNotificationSettings(): Promise<boolean>;
  openAppDetails(): Promise<boolean>;
  openBatterySettings(): Promise<boolean>;
  openAutostartSettings(): Promise<boolean>;
  addListener(eventName: string, listener: Listener): Subscription;
};

const native: HabitNativeModule | null =
  Platform.OS === 'android'
    ? (requireOptionalNativeModule('HabitNative') as HabitNativeModule | null)
    : null;

export const isNativeAvailable = native != null;

/** Fail closed: without native state, protection is treated as ON. */
const FALLBACK_UNLOCK: UnlockInfo = {
  cooldownActive: false,
  cooldownRemainingMs: 0,
  cooldownTotalMs: 0,
  offWindowActive: false,
  offWindowRemainingMs: 0,
  offWindowTotalMs: 0,
  failuresToday: 0,
  lockRemainingMs: 0,
  fastCooldown: false,
  restoreMode: 'strict',
};

const FALLBACK_SNAPSHOT: HabitSnapshot = {
  protectionEnabled: true,
  mode: 'normal',
  onboardingCompleted: false,
  attemptsToday: 0,
  accessibilityEnabled: false,
  foregroundServiceRunning: false,
  debugBuild: false,
  deviceAdminActive: false,
  unlock: FALLBACK_UNLOCK,
};

async function safeCall<T>(call: () => Promise<T>, fallback: T): Promise<T> {
  if (!native) return fallback;
  try {
    return await call();
  } catch {
    return fallback;
  }
}

export function getSnapshot(): Promise<HabitSnapshot> {
  return safeCall(() => native!.getSnapshot(), FALLBACK_SNAPSHOT);
}

export function setProtectionEnabled(enabled: boolean): Promise<HabitSnapshot> {
  return safeCall(() => native!.setProtectionEnabled(enabled), {
    ...FALLBACK_SNAPSHOT,
    protectionEnabled: enabled,
  });
}

export function setMode(mode: ProtectionMode): Promise<HabitSnapshot> {
  return safeCall(() => native!.setMode(mode), { ...FALLBACK_SNAPSHOT, mode });
}

export function requestUnlockTimer(): Promise<HabitSnapshot> {
  return safeCall(() => native!.requestUnlockTimer(), FALLBACK_SNAPSHOT);
}

export function cancelUnlockTimer(): Promise<HabitSnapshot> {
  return safeCall(() => native!.cancelUnlockTimer(), FALLBACK_SNAPSHOT);
}

export function startCharacterChallenge(length: number): Promise<CharacterChallenge | null> {
  return safeCall(() => native!.startCharacterChallenge(length), null);
}

export function submitCharacterChallenge(
  challengeId: string,
  typed: string
): Promise<ChallengeResult | null> {
  return safeCall(() => native!.submitCharacterChallenge(challengeId, typed), null);
}

export function startFaceUnlock(): Promise<boolean> {
  return safeCall(() => native!.startFaceUnlock(), false);
}

export function submitFacePhoto(uri: string): Promise<FaceCheckResult | null> {
  return safeCall(() => native!.submitFacePhoto(uri), null);
}

export function cancelFaceUnlock(): Promise<boolean> {
  return safeCall(() => native!.cancelFaceUnlock(), false);
}

export function setFastCooldown(enabled: boolean): Promise<boolean> {
  return safeCall(() => native!.setFastCooldown(enabled), false);
}

export function debugExitStrict(): Promise<HabitSnapshot> {
  return safeCall(() => native!.debugExitStrict(), FALLBACK_SNAPSHOT);
}

export function isDeviceAdminActive(): Promise<boolean> {
  return safeCall(() => native!.isDeviceAdminActive(), false);
}

/** Opens Android's "Activate device admin" dialog. Resolves when the dialog is launched. */
export function activateDeviceAdmin(): Promise<boolean> {
  return safeCall(() => native!.activateDeviceAdmin(), false);
}

export function setOnboardingCompleted(completed: boolean): Promise<HabitSnapshot> {
  return safeCall(() => native!.setOnboardingCompleted(completed), {
    ...FALLBACK_SNAPSHOT,
    onboardingCompleted: completed,
  });
}

export function publishLists(domains: string[], keywords: string[]): Promise<boolean> {
  return safeCall(
    () => native!.publishLists(JSON.stringify(domains), JSON.stringify(keywords)),
    false
  );
}

export function isAccessibilityEnabled(): Promise<boolean> {
  return safeCall(() => native!.isAccessibilityEnabled(), false);
}

export function areNotificationsEnabled(): Promise<boolean> {
  return safeCall(() => native!.areNotificationsEnabled(), false);
}

export function requestNotificationPermission(): Promise<boolean> {
  return safeCall(() => native!.requestNotificationPermission(), false);
}

export function openAccessibilitySettings(): Promise<boolean> {
  return safeCall(() => native!.openAccessibilitySettings(), false);
}

export function openNotificationSettings(): Promise<boolean> {
  return safeCall(() => native!.openNotificationSettings(), false);
}

export function openAppDetails(): Promise<boolean> {
  return safeCall(() => native!.openAppDetails(), false);
}

export function openBatterySettings(): Promise<boolean> {
  return safeCall(() => native!.openBatterySettings(), false);
}

export function openAutostartSettings(): Promise<boolean> {
  return safeCall(() => native!.openAutostartSettings(), false);
}

export function addAttemptsListener(listener: (count: number) => void): () => void {
  if (!native) return () => {};
  try {
    const subscription = native.addListener('onAttemptsChanged', (event) => {
      if (typeof event?.count === 'number') listener(event.count);
    });
    return () => subscription.remove();
  } catch {
    return () => {};
  }
}

export function addStateListener(listener: () => void): () => void {
  if (!native) return () => {};
  try {
    const subscription = native.addListener('onStateChanged', () => listener());
    return () => subscription.remove();
  } catch {
    return () => {};
  }
}
