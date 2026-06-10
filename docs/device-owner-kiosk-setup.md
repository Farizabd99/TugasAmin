# Device Owner / Kiosk Setup Guide

Android only allows strong kiosk restrictions when the app is Device Owner or is allowlisted by a Device Policy Controller. Without Device Owner, the app can request Lock Task Mode, but users may still escape depending on device policy and Android version.

## What This App Implements

- `android:lockTaskMode="if_whitelisted"` on `MainActivity`
- `DeviceAdminReceiver` metadata
- Device Owner check through `DevicePolicyManager`
- Lock Task package allowlisting when the app is Device Owner
- Status bar and keyguard disabling when Device Owner
- `startLockTask()` during active and expired client screens
- Compose `BackHandler` that consumes back navigation during active and expired sessions

## ADB Provisioning For Test Devices

Use a freshly reset device with no Google account added.

1. Build and install the debug APK:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

2. Set Device Owner:

```bash
adb shell dpm set-device-owner com.example.phonebilling/.admin.PhoneBillingDeviceAdminReceiver
```

3. Launch the app:

```bash
adb shell monkey -p com.example.phonebilling 1
```

4. Open Client Mode from the login screen on client phones.

## Removing Device Owner During Testing

Device Owner removal usually requires factory reset. On some debug builds, this may work:

```bash
adb shell dpm remove-active-admin com.example.phonebilling/.admin.PhoneBillingDeviceAdminReceiver
```

If it fails, factory reset the device.

## Android Enterprise Deployment

For production, provision through Android Enterprise:

- Use zero-touch enrollment, QR provisioning, NFC bump, or an EMM/MDM.
- Configure this app as Device Owner or install it under a Device Owner DPC.
- Allowlist package `com.example.phonebilling` for Lock Task Mode.
- Disable status bar, keyguard, settings access, safe boot, app uninstall, and unknown sources through policy.
- Use managed configuration for the local server URL if your EMM supports it.

## Technical Limits

- Apps cannot fully restrict Android without Device Owner / enterprise policy.
- Back navigation can be intercepted inside Compose, but system gestures, status shade, recents, and settings require Device Owner policy.
- Lock Task Mode must be allowlisted for reliable kiosk behavior.
- Some OEM Android builds customize kiosk behavior; validate on the exact target device model.

## Recommended Production Policy

- Factory reset each phone before enrollment.
- Enroll as Device Owner before handing the device to users.
- Disable notification shade and keyguard.
- Disable app uninstall.
- Disable USB debugging for deployed client devices.
- Use a dedicated local network or VPN for server sync.
- Monitor unsynced billing logs from the server dashboard.
