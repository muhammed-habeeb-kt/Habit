package com.payjoy.status.habit

import android.app.admin.DeviceAdminReceiver

/**
 * Declared in the manifest for Part 3 (Aggressive mode). It is not activated in
 * Part 1 — nothing here can lock the device yet.
 */
class HabitDeviceAdminReceiver : DeviceAdminReceiver()
