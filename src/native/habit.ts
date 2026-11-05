import { requireOptionalNativeModule } from 'expo';
import { Platform } from 'react-native';

export type ProtectionMode = 'normal' | 'strict' | 'aggressive';

export type UnlockInfo = {
  cooldownActive: boolean;
  cooldownRemainingMs: number;
  cooldownTotalMs: number;
  /** Wait-it-out duration the user configured (180..1440 minutes), in ms. */
  configuredCooldownMs: number;
  /** Character challenge length locked in while Strict/Aggressive is on. */
  configuredChallengeLength: number;
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
  /** 100, 200 or 300 — chosen in the Configure Protection Lock sheet. */
  configuredChallengeLength: number;
  /** Wait-it-out timer in minutes, always 180..1440. */
  configuredCooldownMinutes: number;
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

/** One analyzed camera frame from the offline ML Kit face detector. */
export type FaceFrameAnalysis = {
  /** A face was found in the frame. */
  faceDetected: boolean;
  /** Both eye-open probabilities are high. */
  eyesOpen: boolean;
  /** At least one eye is below the blink threshold (mid-blink). */
  eyesClosed: boolean;
  /** -1 when ML Kit could not classify the eyes. */
  leftEyeProb: number;
  rightEyeProb: number;
  /** Normalized geometric + colour signature, comparable between frames. */
  faceSignature: number[];
};

export type AiSensitivity = 'low' | 'balanced' | 'high';

export type AiSettings = {
  /** Master switch for the on-device AI screen analyzer. */
  enabled: boolean;
  /** Package names of the apps the user selected. Empty until they choose some. */
  monitoredAppPackages: string[];
  aiSensitivity: AiSensitivity;
  blockAlertsEnabled: boolean;
};

/** An app actually installed on this phone, as reported by the native module. */
export type InstalledApp = {
  packageName: string;
  label: string;
  isSystemApp: boolean;
  isBrowser: boolean;
  /** `file://` path to the cached launcher icon; empty when Android had no icon. */
  iconUri?: string;
};

const DEFAULT_AI_SETTINGS: AiSettings = {
  enabled: true,
  monitoredAppPackages: [],
  aiSensitivity: 'balanced',
  blockAlertsEnabled: true,
};

// Session fallback for Expo Go / web, where the native store does not exist.
let fallbackAiSettings: AiSettings = DEFAULT_AI_SETTINGS;

type AttemptsEvent = { count: number };
type Listener = (payload: AttemptsEvent) => void;
type Subscription = { remove(): void };

type HabitNativeModule = {
  getSnapshot(): Promise<HabitSnapshot>;
  setProtectionEnabled(enabled: boolean): Promise<HabitSnapshot>;
  setMode(mode: ProtectionMode): Promise<HabitSnapshot>;
  configureProtection(challengeLength: number, cooldownMinutes: number): Promise<HabitSnapshot>;
  requestUnlockTimer(): Promise<HabitSnapshot>;
  cancelUnlockTimer(): Promise<HabitSnapshot>;
  startCharacterChallenge(length: number): Promise<CharacterChallenge | null>;
  submitCharacterChallenge(
    challengeId: string,
    typed: string
  ): Promise<ChallengeResult | null>;
  startFaceUnlock(): Promise<boolean>;
  analyzeFaceFrame(imagePath: string): Promise<FaceFrameAnalysis | null>;
  completeFaceUnlock(): Promise<boolean>;
  cancelFaceUnlock(): Promise<boolean>;
  setFastCooldown(enabled: boolean): Promise<boolean>;
  debugExitStrict(): Promise<HabitSnapshot>;
  isDeviceAdminActive(): Promise<boolean>;
  activateDeviceAdmin(): Promise<boolean>;
  publishLists(domainsJson: string, keywordsJson: string): Promise<boolean>;
  setOnboardingCompleted(completed: boolean): Promise<HabitSnapshot>;
  getInstalledPackages(packages: string[]): Promise<string[]>;
  getInstalledApps(): Promise<InstalledApp[]>;
  getAiSettings(): Promise<AiSettings>;
  setAiMonitoringEnabled(enabled: boolean): Promise<AiSettings>;
  setAiSensitivity(sensitivity: string): Promise<AiSettings>;
  setAiWatchedApps(packages: string[]): Promise<AiSettings>;
  setAiBlockAlerts(enabled: boolean): Promise<AiSettings>;
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

/**
 * False on an older development APK whose native module predates
 * `getInstalledPackages`. The Browser Blocking screen then shows "UNAVAILABLE"
 * instead of claiming every browser is missing.
 */
export const canQueryInstalledPackages =
  native != null && typeof native.getInstalledPackages === 'function';

/**
 * False on an older development APK whose native module predates
 * `getInstalledApps`. The Apps screen then explains the build is out of date
 * instead of pretending the phone has no apps.
 */
export const canListInstalledApps =
  native != null && typeof native.getInstalledApps === 'function';

/**
 * False on an older development APK whose native module predates the ML Kit frame
 * analyzer. The face screen then explains the build is out of date instead of opening a
 * camera that can never detect anything.
 */
export const canAnalyzeFaceFrames =
  native != null && typeof native.analyzeFaceFrame === 'function';

/** Fail closed: without native state, protection is treated as ON. */
const FALLBACK_UNLOCK: UnlockInfo = {
  cooldownActive: false,
  cooldownRemainingMs: 0,
  cooldownTotalMs: 0,
  configuredCooldownMs: 3 * 60 * 60 * 1000,
  configuredChallengeLength: 200,
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
  configuredChallengeLength: 200,
  configuredCooldownMinutes: 180,
  attemptsToday: 0,
  accessibilityEnabled: false,
  foregroundServiceRunning: false,
  debugBuild: false,
  deviceAdminActive: false,
  unlock: FALLBACK_UNLOCK,
};

/**
 * The setup last chosen in "Configure Protection Lock". Kept here as a JS-side fallback
 * (web, Expo Go, or a native module that predates `configureProtection`) so refreshing a
 * snapshot always reports the user's own 100/200/300 and 3h..24h, never 200 / 180.
 */
let savedProtectionConfig: { challengeLength: number; cooldownMinutes: number } | null = null;

function fallbackSnapshot(): HabitSnapshot {
  if (!savedProtectionConfig) return FALLBACK_SNAPSHOT;
  const { challengeLength, cooldownMinutes } = savedProtectionConfig;
  return {
    ...FALLBACK_SNAPSHOT,
    configuredChallengeLength: challengeLength,
    configuredCooldownMinutes: cooldownMinutes,
    unlock: {
      ...FALLBACK_UNLOCK,
      configuredCooldownMs: cooldownMinutes * 60_000,
      configuredChallengeLength: challengeLength,
    },
  };
}

async function safeCall<T>(call: () => Promise<T>, fallback: T): Promise<T> {
  if (!native) return fallback;
  try {
    return await call();
  } catch {
    return fallback;
  }
}

export function getSnapshot(): Promise<HabitSnapshot> {
  return safeCall(() => native!.getSnapshot(), fallbackSnapshot());
}

export function setProtectionEnabled(enabled: boolean): Promise<HabitSnapshot> {
  return safeCall(() => native!.setProtectionEnabled(enabled), {
    ...fallbackSnapshot(),
    protectionEnabled: enabled,
  });
}

export function setMode(mode: ProtectionMode): Promise<HabitSnapshot> {
  return safeCall(() => native!.setMode(mode), { ...fallbackSnapshot(), mode });
}

/**
 * Persists the two limits chosen before Strict/Aggressive turns on and returns the fresh
 * snapshot, so the chosen challenge length and wait duration are visible immediately.
 *
 * While protection is OFF or Normal both values are taken exactly as picked (100, 200 or
 * 300 characters and 180..1440 minutes), even when that lowers an earlier setup. The
 * native state machine only refuses a lower while Strict/Aggressive is actually on.
 */
export async function configureProtection(
  challengeLength: number,
  cooldownMinutes: number
): Promise<HabitSnapshot> {
  savedProtectionConfig = { challengeLength, cooldownMinutes };
  return safeCall(() => native!.configureProtection(challengeLength, cooldownMinutes), {
    ...fallbackSnapshot(),
    configuredChallengeLength: challengeLength,
    configuredCooldownMinutes: cooldownMinutes,
  });
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

/**
 * Analyzes one silent camera frame with the on-device ML Kit detector. The native side
 * deletes the temporary capture before resolving, so no image is ever kept.
 */
export function analyzeFaceFrame(imagePath: string): Promise<FaceFrameAnalysis | null> {
  if (!native || !canAnalyzeFaceFrames) return Promise.resolve(null);
  return safeCall(() => native!.analyzeFaceFrame(imagePath), null);
}

/**
 * Ends a verified two-face check and starts the 30-minute OFF window. Refused natively
 * without an active check started by `startFaceUnlock`.
 */
export function completeFaceUnlock(): Promise<boolean> {
  return safeCall(() => native!.completeFaceUnlock(), false);
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

/** Returns the subset of `packages` that Android reports as installed. */
export async function getInstalledPackages(packages: string[]): Promise<string[]> {
  if (!native || !canQueryInstalledPackages) return [];
  try {
    const installed = await native.getInstalledPackages(packages);
    return Array.isArray(installed)
      ? installed.filter((item): item is string => typeof item === 'string')
      : [];
  } catch {
    return [];
  }
}

/**
 * Every app installed on the device (third-party and system, excluding Habit),
 * sorted alphabetically by label. Returns [] when the native module is missing.
 */
export async function getInstalledApps(): Promise<InstalledApp[]> {
  if (!native || !canListInstalledApps) return [];
  try {
    const apps = await native.getInstalledApps();
    if (!Array.isArray(apps)) return [];
    return apps
      .filter(
        (app): app is InstalledApp =>
          app != null &&
          typeof app.packageName === 'string' &&
          typeof app.label === 'string' &&
          app.label.length > 0
      )
      .map((app) => ({
        packageName: app.packageName,
        label: app.label,
        isSystemApp: app.isSystemApp === true,
        isBrowser: app.isBrowser === true,
        iconUri: typeof app.iconUri === 'string' && app.iconUri.length > 0 ? app.iconUri : undefined,
      }))
      .sort((a, b) => a.label.localeCompare(b.label));
  } catch {
    return [];
  }
}

export function setOnboardingCompleted(completed: boolean): Promise<HabitSnapshot> {
  return safeCall(() => native!.setOnboardingCompleted(completed), {
    ...fallbackSnapshot(),
    onboardingCompleted: completed,
  });
}

export function publishLists(domains: string[], keywords: string[]): Promise<boolean> {
  return safeCall(
    () => native!.publishLists(JSON.stringify(domains), JSON.stringify(keywords)),
    false
  );
}

function normalizeAiSettings(value: AiSettings | null | undefined): AiSettings {
  if (!value) return DEFAULT_AI_SETTINGS;
  const sensitivity: AiSensitivity =
    value.aiSensitivity === 'low' || value.aiSensitivity === 'high' ? value.aiSensitivity : 'balanced';
  return {
    enabled: value.enabled === true,
    monitoredAppPackages: Array.isArray(value.monitoredAppPackages)
      ? value.monitoredAppPackages.filter((item): item is string => typeof item === 'string')
      : DEFAULT_AI_SETTINGS.monitoredAppPackages,
    aiSensitivity: sensitivity,
    blockAlertsEnabled: value.blockAlertsEnabled !== undefined ? value.blockAlertsEnabled : DEFAULT_AI_SETTINGS.blockAlertsEnabled,
  };
}

/** Reads the AI monitoring settings persisted by HabitStore. */
export async function getAiSettings(): Promise<AiSettings> {
  if (!native) return fallbackAiSettings;
  try {
    fallbackAiSettings = normalizeAiSettings(await native.getAiSettings());
    return fallbackAiSettings;
  } catch {
    return fallbackAiSettings;
  }
}

export async function setAiMonitoringEnabled(enabled: boolean): Promise<AiSettings> {
  if (!native) {
    fallbackAiSettings = { ...fallbackAiSettings, enabled };
    return fallbackAiSettings;
  }
  try {
    fallbackAiSettings = normalizeAiSettings(await native.setAiMonitoringEnabled(enabled));
  } catch {
    fallbackAiSettings = { ...fallbackAiSettings, enabled };
  }
  return fallbackAiSettings;
}

export async function setAiSensitivity(sensitivity: AiSensitivity): Promise<AiSettings> {
  if (!native) {
    fallbackAiSettings = { ...fallbackAiSettings, aiSensitivity: sensitivity };
    return fallbackAiSettings;
  }
  try {
    fallbackAiSettings = normalizeAiSettings(await native.setAiSensitivity(sensitivity));
  } catch {
    fallbackAiSettings = { ...fallbackAiSettings, aiSensitivity: sensitivity };
  }
  return fallbackAiSettings;
}

export async function setAiBlockAlerts(enabled: boolean): Promise<AiSettings> {
  if (!native) {
    fallbackAiSettings = { ...fallbackAiSettings, blockAlertsEnabled: enabled };
    return fallbackAiSettings;
  }
  try {
    fallbackAiSettings = normalizeAiSettings(await native.setAiBlockAlerts(enabled));
  } catch {
    fallbackAiSettings = { ...fallbackAiSettings, blockAlertsEnabled: enabled };
  }
  return fallbackAiSettings;
}

export async function setAiWatchedApps(watchedApps: string[]): Promise<AiSettings> {
  if (!native) {
    fallbackAiSettings = { ...fallbackAiSettings, monitoredAppPackages: watchedApps };
    return fallbackAiSettings;
  }
  try {
    fallbackAiSettings = normalizeAiSettings(await native.setAiWatchedApps(watchedApps));
  } catch {
    fallbackAiSettings = { ...fallbackAiSettings, monitoredAppPackages: watchedApps };
  }
  return fallbackAiSettings;
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
