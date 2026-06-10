package com.example.phonebilling.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import javax.inject.Inject

class KioskController @Inject constructor() {
    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    fun allowLockTaskIfOwner(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = ComponentName(context, PhoneBillingDeviceAdminReceiver::class.java)
        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
        dpm.setStatusBarDisabled(admin, true)
        dpm.setKeyguardDisabled(admin, true)
    }

    fun start(activity: Activity) {
        runCatching { activity.startLockTask() }
    }

    fun stop(activity: Activity) {
        runCatching { activity.stopLockTask() }
    }
}
