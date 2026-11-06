/**
 * Config plugin that wires Habit's native Android pieces into the generated
 * project. Everything here is declared up front (Parts 1-3) so later parts
 * never need manifest surgery.
 *
 * Declared:
 *  - AccessibilityService (with its XML config)
 *  - Foreground service (specialUse) + persistent notification
 *  - Boot receiver (restart protection after reboot / app update)
 *  - DeviceAdminReceiver (declared, not activated until Aggressive mode)
 *  - Permissions: notifications, exact alarms, camera, boot, foreground, wake lock,
 *    package visibility (QUERY_ALL_PACKAGES + MAIN intent query)
 */
const {
  AndroidConfig,
  withAndroidManifest,
  withDangerousMod,
  withStringsXml,
} = require('expo/config-plugins');
const fs = require('fs');
const path = require('path');

const KOTLIN_PACKAGE = 'com.payjoy.status.habit';
const SERVICE = `${KOTLIN_PACKAGE}.HabitAccessibilityService`;
const FOREGROUND_SERVICE = `${KOTLIN_PACKAGE}.HabitForegroundService`;
const BOOT_RECEIVER = `${KOTLIN_PACKAGE}.BootReceiver`;
const ALARM_RECEIVER = `${KOTLIN_PACKAGE}.HabitAlarmReceiver`;
const DEVICE_ADMIN = `${KOTLIN_PACKAGE}.HabitDeviceAdminReceiver`;

const PERMISSIONS = [
  'android.permission.FOREGROUND_SERVICE',
  'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
  'android.permission.POST_NOTIFICATIONS',
  'android.permission.RECEIVE_BOOT_COMPLETED',
  'android.permission.SCHEDULE_EXACT_ALARM',
  'android.permission.USE_EXACT_ALARM',
  'android.permission.CAMERA',
  'android.permission.WAKE_LOCK',
  'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
  'android.permission.VIBRATE',
  // Android 11+ package visibility: needed so the Apps screen can list every
  // installed package (third-party and system) instead of a filtered subset.
  'android.permission.QUERY_ALL_PACKAGES',
];

const ACCESSIBILITY_PERMISSION = 'android.permission.BIND_ACCESSIBILITY_SERVICE';

// `settingsActivity` must point at the generated launcher activity, whose package
// comes from `expo.android.package` — never at the internal Kotlin package.
const accessibilityXml = (applicationPackage) => `<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeViewTextChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagIncludeNotImportantViews|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:canTakeScreenshot="true"
    android:description="@string/habit_accessibility_description"
    android:notificationTimeout="100"
    android:settingsActivity="${applicationPackage}.MainActivity" />
`;

// No policies at all: the admin exists only so Android confirms before Habit is removed.
const DEVICE_ADMIN_XML = `<?xml version="1.0" encoding="utf-8"?>
<device-admin xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-policies />
</device-admin>
`;

function serviceEntry(manifest) {
  const app = AndroidConfig.Manifest.getMainApplicationOrThrow(manifest);
  app.service = app.service || [];
  return app;
}

function upsertService(app, entry) {
  const name = entry.$['android:name'];
  app.service = (app.service || []).filter((s) => s.$['android:name'] !== name);
  app.service.push(entry);
}

function upsertReceiver(app, entry) {
  const name = entry.$['android:name'];
  app.receiver = (app.receiver || []).filter((r) => r.$['android:name'] !== name);
  app.receiver.push(entry);
}

const withHabitManifest = (config) =>
  withAndroidManifest(config, (config) => {
    const manifest = config.modResults;

    // --- permissions -------------------------------------------------------
    manifest.manifest['uses-permission'] = manifest.manifest['uses-permission'] || [];
    const existing = new Set(
      manifest.manifest['uses-permission'].map((p) => p.$['android:name'])
    );
    for (const permission of PERMISSIONS) {
      if (!existing.has(permission)) {
        manifest.manifest['uses-permission'].push({ $: { 'android:name': permission } });
      }
    }

    // Browsers we watch. Declared so the app can resolve them when needed.
    const queries = manifest.manifest.queries || [];
    const browserPackages = [
      'com.android.chrome',
      'com.chrome.beta',
      'com.chrome.dev',
      'com.brave.browser',
      'com.microsoft.emmx',
      'com.sec.android.app.sbrowser',
      'org.mozilla.firefox',
      'org.mozilla.firefox_beta',
      'com.opera.browser',
      'com.opera.mini.native',
      'com.duckduckgo.mobile.android',
      'com.mi.globalbrowser',
      'com.android.browser',
    ];
    if (!queries.some((q) => q.package)) {
      queries.push({ package: browserPackages.map((name) => ({ $: { 'android:name': name } })) });
    }
    // Declare the MAIN/LAUNCHER intent query so Android 11-15 (incl. Android 14 /
    // HyperOS) exposes every app with a launcher entry, even if a store review
    // strips QUERY_ALL_PACKAGES.
    const hasLauncherIntent = queries.some((q) =>
      (q.intent || []).some((intent) => {
        const hasMain = (intent.action || []).some(
          (action) => action.$['android:name'] === 'android.intent.action.MAIN'
        );
        const hasLauncher = (intent.category || []).some(
          (category) => category.$['android:name'] === 'android.intent.category.LAUNCHER'
        );
        return hasMain && hasLauncher;
      })
    );
    if (!hasLauncherIntent) {
      queries.push({
        intent: [
          {
            action: [{ $: { 'android:name': 'android.intent.action.MAIN' } }],
            category: [{ $: { 'android:name': 'android.intent.category.LAUNCHER' } }],
          },
        ],
      });
    }
    manifest.manifest.queries = queries;

    // --- application entries ----------------------------------------------
    const app = serviceEntry(manifest);

    upsertService(app, {
      $: {
        'android:name': SERVICE,
        'android:exported': 'true',
        'android:label': 'Habit',
        'android:permission': ACCESSIBILITY_PERMISSION,
      },
      'intent-filter': [
        {
          action: [{ $: { 'android:name': 'android.accessibilityservice.AccessibilityService' } }],
        },
      ],
      'meta-data': [
        {
          $: {
            'android:name': 'android.accessibilityservice',
            'android:resource': '@xml/habit_accessibility_service',
          },
        },
      ],
    });

    upsertService(app, {
      $: {
        'android:name': FOREGROUND_SERVICE,
        'android:exported': 'false',
        'android:stopWithTask': 'false',
        'android:foregroundServiceType': 'specialUse',
      },
      property: [
        {
          $: {
            'android:name': 'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE',
            'android:value': 'Keeps the adult-content blocker active while protection is on',
          },
        },
      ],
    });

    upsertReceiver(app, {
      $: {
        'android:name': BOOT_RECEIVER,
        'android:enabled': 'true',
        'android:exported': 'true',
      },
      'intent-filter': [
        {
          action: [
            { $: { 'android:name': 'android.intent.action.BOOT_COMPLETED' } },
            { $: { 'android:name': 'android.intent.action.LOCKED_BOOT_COMPLETED' } },
            { $: { 'android:name': 'android.intent.action.MY_PACKAGE_REPLACED' } },
          ],
        },
      ],
    });

    // Fires unlock deadlines (cooldown end / auto re-enable) even if the app was killed.
    upsertReceiver(app, {
      $: {
        'android:name': ALARM_RECEIVER,
        'android:enabled': 'true',
        'android:exported': 'false',
      },
    });

    upsertReceiver(app, {
      $: {
        'android:name': DEVICE_ADMIN,
        'android:enabled': 'true',
        'android:exported': 'true',
        'android:permission': 'android.permission.BIND_DEVICE_ADMIN',
      },
      'meta-data': [
        {
          $: {
            'android:name': 'android.app.device_admin',
            'android:resource': '@xml/habit_device_admin',
          },
        },
      ],
      'intent-filter': [
        {
          action: [
            { $: { 'android:name': 'android.app.action.DEVICE_ADMIN_ENABLED' } },
            { $: { 'android:name': 'android.app.action.DEVICE_ADMIN_DISABLED' } },
          ],
        },
      ],
    });

    return config;
  });

const withHabitResources = (config) =>
  withDangerousMod(config, [
    'android',
    async (config) => {
      const resXml = path.join(
        config.modRequest.platformProjectRoot,
        'app',
        'src',
        'main',
        'res',
        'xml'
      );
      const applicationPackage = config.android?.package || 'com.habit.guardian';
      fs.mkdirSync(resXml, { recursive: true });
      fs.writeFileSync(
        path.join(resXml, 'habit_accessibility_service.xml'),
        accessibilityXml(applicationPackage)
      );
      fs.writeFileSync(path.join(resXml, 'habit_device_admin.xml'), DEVICE_ADMIN_XML);
      return config;
    },
  ]);

const withHabitStrings = (config) =>
  withStringsXml(config, (config) => {
    config.modResults = AndroidConfig.Strings.setStringItem(
      [
        {
          $: { name: 'habit_accessibility_description' },
          _: 'Habit checks the address and visible text of browser pages to block adult websites. Pages are never recorded or stored.',
        },
      ],
      config.modResults
    );
    return config;
  });

module.exports = function withHabitAndroid(config) {
  config = withHabitManifest(config);
  config = withHabitResources(config);
  config = withHabitStrings(config);
  return config;
};
