package com.payjoy.status.habit

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Minimal device-admin plumbing for Aggressive mode.
 *
 * Habit asks for no admin policies at all: the admin exists only so Android shows the
 * "deactivate device admin" warning before Habit can be uninstalled. Nothing here can
 * lock or wipe the phone.
 */
object HabitDeviceAdmin {
  private const val TAG = "HabitDeviceAdmin"

  fun component(context: Context): ComponentName {
    return ComponentName(context.applicationContext, HabitDeviceAdminReceiver::class.java)
  }

  fun isActive(context: Context): Boolean {
    return try {
      val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        ?: return false
      manager.isAdminActive(component(context))
    } catch (error: Throwable) {
      Log.w(TAG, "isActive failed: ${error.message}")
      false
    }
  }

  fun requestIntent(context: Context): Intent {
    return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
      .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
      .putExtra(
        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
        "Aggressive mode uses device admin only to make removing Habit require an extra confirmation. It cannot lock or wipe your phone."
      )
  }
}
