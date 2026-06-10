# Restricted Phone Billing

Production-oriented Android app for managing restricted phone usage sessions. It works like internet cafe billing, but the client endpoint is an Android phone placed into kiosk-style Lock Task Mode during an active paid session.

## Stack

- Kotlin
- Jetpack Compose
- Material 3
- MVVM
- StateFlow
- Coroutines
- Hilt
- Room
- Retrofit
- Kotlin Serialization
- Navigation Compose
- WorkManager for deferred billing sync

## Included Modes

### Operator Mode

- Operator login
- Register/manage current operator device
- View devices
- Start session
- Stop session
- Extend session
- View active devices
- View billing summary
- View session history
- Configure local server URL

Default local login:

- Username: `admin`
- Password: `admin123`

Change this before production deployment.

### Client Mode

- Registers the phone as a client device
- Waits for session activation
- Polls local server for status
- Shows countdown timer during active session
- Starts Android Lock Task Mode during active/expired states
- Prevents back navigation during active/expired states
- Automatically expires local session when the timer reaches zero
- Keeps local state in Room if server is unavailable
- Syncs pending sessions/logs when connectivity returns

## Local Server API

Retrofit expects these endpoints under the configured base URL:

- `POST /api/devices/register`
- `GET /api/devices/{deviceId}/status`
- `POST /api/sessions/start`
- `POST /api/sessions/{sessionId}/stop`
- `POST /api/sessions/{sessionId}/extend`
- `POST /api/billing/logs/sync`

Default URL: `http://10.0.2.2:8080/`

For physical devices, set the server URL in Settings, for example `http://192.168.1.20:8080/`.

## Build

Open this folder in Android Studio, let Gradle sync, then build:

```bash
./gradlew assembleDebug
```

Install debug APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release APK:

```bash
./gradlew assembleRelease
```

This workspace does not include the binary `gradle-wrapper.jar`. If Android Studio does not generate it automatically, install Gradle once and run:

```bash
gradle wrapper --gradle-version 8.10.2
```

Then use `./gradlew` normally.

## Project Layout

- `app/src/main/java/com/example/phonebilling/data/local`: Room entities, DAOs, DataStore settings
- `app/src/main/java/com/example/phonebilling/data/remote`: Retrofit API and Kotlin Serialization DTOs
- `app/src/main/java/com/example/phonebilling/domain`: billing repository and offline-first sync logic
- `app/src/main/java/com/example/phonebilling/ui/operator`: operator MVVM and screens
- `app/src/main/java/com/example/phonebilling/ui/client`: client MVVM and kiosk screens
- `app/src/main/java/com/example/phonebilling/admin`: Device Owner / Lock Task helpers
- `docs/device-owner-kiosk-setup.md`: provisioning guide
- `Apple - DESIGN.md`: visual design reference used for the Compose theme

## Production Notes

- Replace the seeded admin account with a secure enrollment flow.
- Store operator credentials using a server-backed auth scheme or Android Keystore-backed credential storage.
- Use HTTPS or a private trusted network for real deployments.
- Add server-side idempotency for session and billing sync endpoints.
- Add signed release config in `app/build.gradle.kts`.
- Configure Android Enterprise Device Owner for reliable kiosk enforcement.
