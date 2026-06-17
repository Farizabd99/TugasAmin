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

    fun allowLockTaskIfOwner(context: Context, sessionActive: Boolean) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = ComponentName(context, PhoneBillingDeviceAdminReceiver::class.java)
        
        if (sessionActive) {
            // Sesi aktif: Hanya izinkan aplikasi kita + WhatsApp
            val allowedPackagesList = arrayOf(context.packageName, "com.whatsapp")
            dpm.setLockTaskPackages(admin, allowedPackagesList)
            
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            dpm.setStatusBarDisabled(admin, true)
            dpm.setKeyguardDisabled(admin, true)
        } else {
            // Sesi tidak aktif: Kunci total ke aplikasi kita saja
            dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            dpm.setStatusBarDisabled(admin, true)
            dpm.setKeyguardDisabled(admin, true)
        }
    }

    private fun getLauncherPackages(context: Context): List<String> {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
        }
        val resolveInfo = context.packageManager.resolveActivity(
            intent,
            0
        )
        val defaultLauncher = resolveInfo?.activityInfo?.packageName
        return if (defaultLauncher != null) listOf(defaultLauncher) else emptyList()
    }

    fun start(activity: Activity) {
        runCatching { activity.startLockTask() }
    }

    fun stop(activity: Activity) {
        runCatching { activity.stopLockTask() }
        disableKioskMode(activity)
    }

    fun disableKioskMode(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val admin = ComponentName(context, PhoneBillingDeviceAdminReceiver::class.java)
        runCatching {
            dpm.setLockTaskPackages(admin, arrayOf())
            dpm.setStatusBarDisabled(admin, false)
            dpm.setKeyguardDisabled(admin, false)
        }
    }
}
