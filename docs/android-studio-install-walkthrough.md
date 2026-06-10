# Android Studio Install Walkthrough

This guide explains how to open, build, and install the Restricted Phone Billing app from Android Studio.

## 1. Install Prerequisites

Install Android Studio from:

```text
https://developer.android.com/studio
```

During setup, install:

- Android SDK Platform 35
- Android SDK Build-Tools
- Android Emulator
- Android SDK Platform-Tools
- Android Gradle Plugin support

For physical phone testing, also enable:

- Developer Options
- USB Debugging

## 2. Open The Project

Open Android Studio, then choose:

```text
File > Open
```

Select this folder:

```text
/Users/farizabdillah/Documents/Tugas Amin
```

Wait for Gradle Sync to finish.

## 3. Generate Gradle Wrapper If Needed

If Android Studio says `gradle/wrapper/gradle-wrapper.jar` is missing, open Terminal in the project folder and run:

```bash
gradle wrapper --gradle-version 8.10.2
```

If the `gradle` command is not installed on macOS, install it first:

```bash
brew install gradle
```

Then sync the project again in Android Studio:

```text
File > Sync Project with Gradle Files
```

## 4. Select A Test Device

You can test with either an emulator or a physical Android phone.

### Emulator

In Android Studio:

```text
Tools > Device Manager > Create Device
```

Recommended emulator:

- Pixel device profile
- Android 15 / API 35 image
- Google APIs image is fine for normal app testing

Start the emulator before running the app.

### Physical Phone

On the phone:

1. Open Settings.
2. Enable Developer Options.
3. Enable USB Debugging.
4. Connect the phone by USB.
5. Accept the RSA debugging prompt on the phone.

Confirm the device is visible:

```bash
adb devices
```

## 5. Build The App

From Android Studio:

```text
Build > Make Project
```

Or from Terminal:

```bash
./gradlew assembleDebug
```

The debug APK will be created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 6. Run From Android Studio

In the top toolbar:

1. Select the `app` run configuration.
2. Select your emulator or physical phone.
3. Click Run.

The app should launch into the Operator Login screen.

Default login:

```text
Username: admin
Password: admin123
```

## 7. Install APK Manually

If you want to install the APK manually:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch it:

```bash
adb shell monkey -p com.example.phonebilling 1
```

## 8. Test Operator Mode

After login, test these flows:

1. Open Dashboard.
2. Tap `Register This Device`.
3. Open Settings.
4. Set the local server URL.
5. Open Device List.
6. Start a session.
7. Open Active Session Detail.
8. Extend the session.
9. Stop the session.
10. Open Billing History.

Default local server URL:

```text
http://10.0.2.2:8080/
```

Use `10.0.2.2` when the Android emulator needs to reach a server running on your Mac.

For a physical phone, use your Mac LAN IP instead:

```text
http://192.168.1.20:8080/
```

Replace `192.168.1.20` with your real Mac IP address.

## 9. Test Client Mode

From the login screen, tap:

```text
Open Client Mode
```

Expected behavior:

- The phone registers as a client device.
- The waiting screen appears.
- The app polls the configured local server for status.
- If a local active session exists, the client moves to the active countdown screen.
- When time reaches zero, the expired screen appears.

For full multi-device testing, use:

- One device/emulator as Operator Mode
- One physical phone as Client Mode
- A local server on the same network

## 10. Test Kiosk / Lock Task Mode

Basic app testing does not require Device Owner.

Full kiosk testing does require Device Owner. Use a factory-reset physical Android phone.

Install the APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Set Device Owner:

```bash
adb shell dpm set-device-owner com.example.phonebilling/.admin.PhoneBillingDeviceAdminReceiver
```

Launch the app:

```bash
adb shell monkey -p com.example.phonebilling 1
```

Open Client Mode and start a session. During active and expired states:

- Back navigation is blocked by the app.
- Lock Task Mode starts.
- Status bar/keyguard restrictions are applied when Device Owner policy allows them.

More details are in:

```text
docs/device-owner-kiosk-setup.md
```

## 11. Common Issues

### Gradle Sync Fails

Try:

```text
File > Invalidate Caches
```

Then restart Android Studio and sync again.

### Missing Android SDK 35

Open:

```text
Android Studio > Settings > Languages & Frameworks > Android SDK
```

Install Android API 35.

### Emulator Cannot Reach Local Server

Use:

```text
http://10.0.2.2:8080/
```

Do not use `localhost` from the emulator, because `localhost` points to the emulator itself.

### Physical Phone Cannot Reach Local Server

Check that:

- Phone and Mac are on the same Wi-Fi network.
- The server is bound to `0.0.0.0`, not only `localhost`.
- Firewall allows inbound connections.
- The app Settings screen uses your Mac LAN IP.

### Device Owner Command Fails

Usually this means the phone is not freshly reset or already has accounts/users configured.

Factory reset the test phone, skip account login, install the APK, then run the `dpm set-device-owner` command again.

## 12. Production Reminder

Before real deployment:

- Replace the default operator password.
- Use a secure local server.
- Add release signing.
- Enroll client phones through Android Enterprise.
- Validate kiosk behavior on the exact phone model you will deploy.
