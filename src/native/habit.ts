import { requireOptionalNativeModule } from 'expo';
import { Platform } from 'react-native';

export type ProtectionMode = 'normal' | 'strict' | 'aggressive';

export type UnlockInfo = {
  cooldownActive: boolean;
  cooldownRemainingMs: number;
  cooldownTotalMs: number;
  /** Wait-it-out duration the user configured (180..1440 minutes), in ms. */
  configuredCooldownMs: number;
  /** How long protection stays OFF before auto-restarting (1..1439 minutes), in ms. */
  configuredOffWindowMs: number;
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
  /** Auto-restart OFF window in minutes, always 1..1439 (1m .. 23h 59m). */
  configuredOffWindowMinutes: number;
  attemptsToday: number;
  /**
   * Clean streak: monotonic time with protection actively ON. Counted natively, never from
   * the phone's wall clock, and reset by any block or by protection turning OFF.
   */
  currentStreakMs: number;
  /** All-time highest `currentStreakMs`, kept across resets and reboots. */
  bestStreakMs: number;
  /** Live Android check: the accessibility service is enabled right now. */
  accessibilityEnabled: boolean;
  /** Live Android check: notifications are allowed, including POST_NOTIFICATIONS. */
  notificationsGranted: boolean;
  /** Live Android check: Habit is exempt from battery optimisation. */
  batteryOptimizationIgnored: boolean;
  /**
   * Android 13+ gates accessibility behind "Allow restricted settings". True on older
   * Android, true once accessibility is on, and otherwise only after the user returned
   * from App Info.
   */
  restrictedSettingsAllowed: boolean;
  foregroundServiceRunning: boolean;
  debugBuild: boolean;
  deviceAdminActive: boolean;
  unlock: UnlockInfo;
  /**
   * The AI settings map from the same snapshot. Every native snapshot carries it, and the
   * incognito switch uses it to read back the value that was just committed.
   */
  ai?: AiSettings;
  /**
   * Watched browser websites straight from the native store, so the Browser list can always
   * be rebuilt from disk instead of from JS memory.
   */
  aiMonitoredDomains: string[];
  /** Blocks Incognito / Private / Secret / InPrivate tabs in every browser. */
  incognitoBlockingEnabled: boolean;
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
  /** Head rotation in degrees; all three stay near 0 only for a frontal face. */
  eulerX: number;
  eulerY: number;
  eulerZ: number;
  /** ML Kit face-tracking id, or -1 when the detector could not track the face. */
  trackingId: number;
  /**
   * Illumination-normalized inner-feature signature. The first 7 entries are scale-free
   * geometry ratios; the rest is a skin grid over forehead, cheeks and chin. The eye slits
   * are excluded, so blinking does not change it.
   */
  faceSignature: number[];
};

export type AiSensitivity = 'low' | 'balanced' | 'high';

export type AiSettings = {
  /** Master switch for the on-device AI screen analyzer. */
  enabled: boolean;
  /** Package names of the apps the user selected. Empty until they choose some. */
  monitoredAppPackages: string[];
  /**
   * Browser websites (e.g. `web.telegram.org`) that arm the screenshot scanner while the
   * user is on that site inside a watched browser. Stored as lowercase hostnames.
   */
  monitoredDomains: string[];
  aiSensitivity: AiSensitivity;
  blockAlertsEnabled: boolean;
  /** Blocks Incognito / Private / Secret / InPrivate tabs across the watched browsers. */
  incognitoBlockingEnabled: boolean;
};

/** An app actually installed on this phone, as reported by the native module. */
/**
 * Live state of the on-device AI screenshot scanner. Every field is a real observation
 * from the running native scanner, so the AI tab can show why nothing is being blocked
 * instead of guessing.
 */
export type AiScannerStatus = {
  running: boolean;
  packageName: string | null;
  appLabel: string | null;
  /** Frames classified in the current session (one per ~1 s). */
  frames: number;
  /** Screens the cascade has closed in this session. */
  blocks: number;
  /** Last stage-1 NSFW MobileNetV2 score (0..1). */
  stage1Score: number;
  /** Last stage-2 NudeNet score (0..1). */
  stage2Score: number;
  verdict: string;
  modelsReady: boolean;
  /** Exact load error when a model could not be opened. */
  modelError: string | null;
  /** e.g. `nsfw_mobilenet_v2.tflite FLOAT32 [1x224x224x3] -> [1x5]` once loaded. */
  stage1Model: string | null;
  stage2Model: string | null;
  lastError: string | null;
  /** -1 when no error has happened yet. */
  lastErrorAgeMs: number;
  lastFrameAgeMs: number;
  /** `takeScreenshot()` needs Android 11+. */
  screenshotSupported: boolean;
  accessibilityEnabled: boolean;
  /** "Display over other apps" — needed for the overlay fallback on HyperOS. */
  overlayAllowed: boolean;
  monitoredApps: number;
  aiEnabled: boolean;
  protectionEnabled: boolean;
};

/** Result of running both bundled models once on a generated bitmap. */
export type AiModelSelfTest = {
  stage1Ok: boolean;
  stage1Score: number;
  stage1Error: string | null;
  stage2Ok: boolean;
  stage2Score: number;
  stage2Error: string | null;
  elapsedMs: number;
};

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
  monitoredDomains: [],
  aiSensitivity: 'balanced',
  blockAlertsEnabled: true,
  incognitoBlockingEnabled: true,
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
  configureProtection(
    challengeLength: number,
    cooldownMinutes: number,
    offWindowMinutes: number
  ): Promise<HabitSnapshot>;
  startTimedOffWindow(minutes: number): Promise<HabitSnapshot>;
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
  setAiMonitoredDomains(domains: string[]): Promise<AiSettings>;
  setAiBlockAlerts(enabled: boolean): Promise<AiSettings>;
  setIncognitoBlockingEnabled(enabled: boolean): Promise<HabitSnapshot | AiSettings>;
  getAiScannerStatus(): Promise<AiScannerStatus>;
  testAiModels(): Promise<AiModelSelfTest>;
  canDrawOverlays(): Promise<boolean>;
  openOverlaySettings(): Promise<boolean>;
  isAccessibilityEnabled(): Promise<boolean>;
  areNotificationsEnabled(): Promise<boolean>;
  requestNotificationPermission(): Promise<boolean>;
  setRestrictedSettingsVisited(visited: boolean): Promise<boolean>;
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
 * `getInstalledApps`. The AI screen then explains the build is out of date
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

/**
 * Removing a monitored app or turning AI monitoring off is a protection removal, so it is
 * locked while Strict/Aggressive is actually protecting the phone. Adding is always allowed.
 * The temporary unlocked OFF window has protection off, so this returns false there.
 */
export function isMonitoringLocked(snapshot: HabitSnapshot | null | undefined): boolean {
  return snapshot?.protectionEnabled === true && snapshot.mode !== 'normal';
}

/** Fail closed: without native state, protection is treated as ON. */
const FALLBACK_UNLOCK: UnlockInfo = {
  cooldownActive: false,
  cooldownRemainingMs: 0,
  cooldownTotalMs: 0,
  configuredCooldownMs: 3 * 60 * 60 * 1000,
  configuredOffWindowMs: 10 * 60 * 1000,
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
  configuredOffWindowMinutes: 10,
  attemptsToday: 0,
  currentStreakMs: 0,
  bestStreakMs: 0,
  accessibilityEnabled: false,
  notificationsGranted: false,
  batteryOptimizationIgnored: false,
  restrictedSettingsAllowed: false,
  foregroundServiceRunning: false,
  debugBuild: false,
  deviceAdminActive: false,
  unlock: FALLBACK_UNLOCK,
  aiMonitoredDomains: [],
  incognitoBlockingEnabled: true,
};

/**
 * The setup last chosen in "Configure Protection Lock". Kept here as a JS-side fallback
 * (web, Expo Go, or a native module that predates `configureProtection`) so refreshing a
 * snapshot always reports the user's own 100/200/300 and 3h..24h, never 200 / 180.
 */
let savedProtectionConfig: {
  challengeLength: number;
  cooldownMinutes: number;
  offWindowMinutes: number;
} | null = null;

function fallbackSnapshot(): HabitSnapshot {
  if (!savedProtectionConfig) return FALLBACK_SNAPSHOT;
  const { challengeLength, cooldownMinutes, offWindowMinutes } = savedProtectionConfig;
  return {
    ...FALLBACK_SNAPSHOT,
    configuredChallengeLength: challengeLength,
    configuredCooldownMinutes: cooldownMinutes,
    configuredOffWindowMinutes: offWindowMinutes,
    unlock: {
      ...FALLBACK_UNLOCK,
      configuredCooldownMs: cooldownMinutes * 60_000,
      configuredOffWindowMs: offWindowMinutes * 60_000,
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
 * While protection is OFF or Normal all three values are taken exactly as picked (100, 200
 * or 300 characters, 180..1440 minutes and a 1..1439 minute OFF window), even when that
 * lowers an earlier setup. Native only refuses a weaker setup while Strict/Aggressive runs.
 */
export async function configureProtection(
  challengeLength: number,
  cooldownMinutes: number,
  offWindowMinutes: number
): Promise<HabitSnapshot> {
  savedProtectionConfig = { challengeLength, cooldownMinutes, offWindowMinutes };
  return safeCall(
    () => native!.configureProtection(challengeLength, cooldownMinutes, offWindowMinutes),
    {
      ...fallbackSnapshot(),
      configuredChallengeLength: challengeLength,
      configuredCooldownMinutes: cooldownMinutes,
      configuredOffWindowMinutes: offWindowMinutes,
    }
  );
}

/**
 * Normal-mode "turn off with auto-restart": protection stops now and comes back by itself
 * after `minutes` (1..1439). Native refuses it outside Normal mode.
 */
export async function startTimedOffWindow(minutes: number): Promise<HabitSnapshot> {
  const safe = Math.min(1439, Math.max(1, Math.round(minutes)));
  const result = await safeCall(() => native!.startTimedOffWindow(safe), {
    ...fallbackSnapshot(),
    protectionEnabled: false,
    configuredOffWindowMinutes: safe,
  });
  savedProtectionConfig = savedProtectionConfig
    ? { ...savedProtectionConfig, offWindowMinutes: result.configuredOffWindowMinutes ?? safe }
    : { challengeLength: 200, cooldownMinutes: 180, offWindowMinutes: safe };
  return result;
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
 * Ends a verified two-face check and starts the configured auto-restart OFF window.
 * Refused natively without an active check started by `startFaceUnlock`.
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

/** True for the plain settings map an older APK returns from the incognito switch. */
function isAiSettings(value: unknown): value is AiSettings {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as AiSettings).aiSensitivity === 'string'
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
    monitoredDomains: Array.isArray(value.monitoredDomains)
      ? value.monitoredDomains.filter((item): item is string => typeof item === 'string')
      : DEFAULT_AI_SETTINGS.monitoredDomains,
    aiSensitivity: sensitivity,
    blockAlertsEnabled: value.blockAlertsEnabled !== undefined ? value.blockAlertsEnabled : DEFAULT_AI_SETTINGS.blockAlertsEnabled,
    incognitoBlockingEnabled:
      value.incognitoBlockingEnabled !== undefined
        ? value.incognitoBlockingEnabled === true
        : DEFAULT_AI_SETTINGS.incognitoBlockingEnabled,
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

export async function setIncognitoBlockingEnabled(enabled: boolean): Promise<AiSettings> {
  if (!native || typeof native.setIncognitoBlockingEnabled !== 'function') {
    fallbackAiSettings = { ...fallbackAiSettings, incognitoBlockingEnabled: enabled };
    return fallbackAiSettings;
  }
  try {
    const result = await native.setIncognitoBlockingEnabled(enabled);
    // Current APKs answer with the updated snapshot (`ai` holds the value that was just
    // committed); older ones answer with the settings map. Both keep the switch honest.
    let settings: AiSettings = fallbackAiSettings;
    if ('ai' in result && result.ai) settings = result.ai;
    else if (isAiSettings(result)) settings = result;
    fallbackAiSettings = normalizeAiSettings(settings);
  } catch {
    fallbackAiSettings = { ...fallbackAiSettings, incognitoBlockingEnabled: enabled };
  }
  return fallbackAiSettings;
}

/** False on an APK whose native module predates the incognito-tab bridge. */
export const canSetIncognitoBlocking =
  native != null && typeof native.setIncognitoBlockingEnabled === 'function';

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

export async function setAiMonitoredDomains(domains: string[]): Promise<AiSettings> {
  if (!native || typeof native.setAiMonitoredDomains !== 'function') {
    fallbackAiSettings = { ...fallbackAiSettings, monitoredDomains: domains };
    return fallbackAiSettings;
  }
  try {
    fallbackAiSettings = normalizeAiSettings(await native.setAiMonitoredDomains(domains));
  } catch {
    fallbackAiSettings = { ...fallbackAiSettings, monitoredDomains: domains };
  }
  return fallbackAiSettings;
}

/**
 * Re-syncs the Browser list from the native store when JS holds no domains but the store
 * does (a fresh JS runtime after a Metro restart or a full app reload). An empty store is
 * left alone so a real "removed every site" never gets overwritten.
 */
export async function restoreMonitoredDomains(): Promise<AiSettings> {
  const current = await getAiSettings();
  if (current.monitoredDomains.length > 0) return current;
  const snapshot = await getSnapshot();
  const saved = snapshot.aiMonitoredDomains;
  if (!Array.isArray(saved) || saved.length === 0) return current;
  return setAiMonitoredDomains(saved);
}

/** False on an APK whose native module predates the watched-website bridge. */
export const canSetAiDomains =
  native != null && typeof native.setAiMonitoredDomains === 'function';

/**
 * Cleans typed or pasted website text into a bare lowercase hostname: the scheme, `www.`,
 * credentials, port, path, query and trailing dots are stripped, so `HTTPS://WWW.X.com/a`
 * becomes `x.com`. Returns an empty string when the text holds no hostname at all, so the
 * caller can refuse it instead of storing junk.
 */
export function normalizeDomain(input: string): string {
  // A pasted URL: drop the scheme, credentials and everything after the host.
  let value = input.trim().toLowerCase().split(' ')[0] ?? '';
  const scheme = value.indexOf('://');
  if (scheme >= 0) value = value.slice(scheme + 3);
  const at = value.indexOf('@');
  if (at >= 0) value = value.slice(at + 1);
  const hostEnd = value.search(/[/?#:@]/);
  if (hostEnd >= 0) value = value.slice(0, hostEnd);
  if (value.startsWith('www.')) value = value.slice(4);
  while (value.endsWith('.')) value = value.slice(0, -1);
  if (!/^[a-z0-9-]+([.][a-z0-9-]+)+$/.test(value)) return '';
  return value;
}

export function isAccessibilityEnabled(): Promise<boolean> {
  return safeCall(() => native!.isAccessibilityEnabled(), false);
}

/** False on an APK whose native module predates the AI diagnostics bridge. */
export const canReadAiScannerStatus =
  native != null && typeof native.getAiScannerStatus === 'function';

const EMPTY_AI_STATUS: AiScannerStatus = {
  running: false,
  packageName: null,
  appLabel: null,
  frames: 0,
  blocks: 0,
  stage1Score: 0,
  stage2Score: 0,
  verdict: 'idle',
  modelsReady: false,
  modelError: null,
  stage1Model: null,
  stage2Model: null,
  lastError: null,
  lastErrorAgeMs: -1,
  lastFrameAgeMs: -1,
  screenshotSupported: false,
  accessibilityEnabled: false,
  overlayAllowed: false,
  monitoredApps: 0,
  aiEnabled: false,
  protectionEnabled: true,
};

/** Reads the live AI scanner diagnostics (frames, scores, models, last error). */
export async function getAiScannerStatus(): Promise<AiScannerStatus> {
  if (!native || !canReadAiScannerStatus) return EMPTY_AI_STATUS;
  try {
    const status = await native.getAiScannerStatus();
    if (!status) return EMPTY_AI_STATUS;
    return {
      ...EMPTY_AI_STATUS,
      ...status,
      frames: Number(status.frames) || 0,
      blocks: Number(status.blocks) || 0,
      stage1Score: Number(status.stage1Score) || 0,
      stage2Score: Number(status.stage2Score) || 0,
      monitoredApps: Number(status.monitoredApps) || 0,
    };
  } catch {
    return EMPTY_AI_STATUS;
  }
}

/**
 * Runs both bundled models once on a generated bitmap (never on the screen) so the phone
 * can prove the models load and execute inside this exact APK.
 */
export async function testAiModels(): Promise<AiModelSelfTest> {
  const fallback: AiModelSelfTest = {
    stage1Ok: false,
    stage1Score: 0,
    stage1Error: 'this build has no AI diagnostics bridge',
    stage2Ok: false,
    stage2Score: 0,
    stage2Error: 'this build has no AI diagnostics bridge',
    elapsedMs: 0,
  };
  if (!native || typeof native.testAiModels !== 'function') return fallback;
  return safeCall(() => native!.testAiModels(), fallback);
}

/** "Display over other apps" — the fallback path for the block screen on HyperOS. */
export function canDrawOverlays(): Promise<boolean> {
  if (!native || typeof native.canDrawOverlays !== 'function') return Promise.resolve(false);
  return safeCall(() => native!.canDrawOverlays(), false);
}

export function openOverlaySettings(): Promise<boolean> {
  if (!native || typeof native.openOverlaySettings !== 'function') return Promise.resolve(false);
  return safeCall(() => native!.openOverlaySettings(), false);
}

export function areNotificationsEnabled(): Promise<boolean> {
  return safeCall(() => native!.areNotificationsEnabled(), false);
}

export function requestNotificationPermission(): Promise<boolean> {
  return safeCall(() => native!.requestNotificationPermission(), false);
}

/**
 * Marks "Allow restricted settings" as visited. JS must only call this after the user has
 * actually returned from Android's App Info screen, never on the tap that opened it.
 */
export function setRestrictedSettingsVisited(visited: boolean): Promise<boolean> {
  if (!native || typeof native.setRestrictedSettingsVisited !== 'function') {
    return Promise.resolve(false);
  }
  return safeCall(() => native!.setRestrictedSettingsVisited(visited), false);
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
