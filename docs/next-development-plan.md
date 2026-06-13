# Next Development Plan

## Phase 1: Backend Core

Build a real backend as the source of truth for multi-device operation.

- Store devices, sessions, tariffs, operators, and billing logs in a backend database.
- Implement device registration and device listing.
- Implement session start, stop, extend, and status lookup.
- Implement billing log synchronization.
- Validate the API with a REST client before wiring every app flow to it.

Required endpoints:

- `POST /api/devices/register`
- `GET /api/devices`
- `GET /api/devices/{deviceId}/status`
- `POST /api/sessions/start`
- `POST /api/sessions/{sessionId}/stop`
- `POST /api/sessions/{sessionId}/extend`
- `POST /api/billing/logs/sync`

## Phase 2: Correct App Synchronization

Make the backend the source of truth for both operator and client devices.

- Operator start session must succeed on the backend before showing an active session.
- Operator stop and extend actions must update the backend first.
- Client mode must poll the backend every few seconds for current status.
- Client "Sync Now" must refresh device status, not only upload pending logs.
- Clear local active sessions when the backend says the device is waiting, stopped, or expired.
- Prevent duplicate active sessions for the same device.

## Phase 3: UX and QA Fixes

Make every critical workflow visible and testable.

- Add loading states for login, sync, start session, stop session, and extend session.
- Show success and failure messages for network actions.
- Display clear device labels, including model and device ID.
- Add visible refresh actions to dashboard and device list.
- Keep navigation explicit with back/logout buttons on all operator pages.
- Add empty states for missing devices, tariffs, and history.

## Phase 4: Kiosk Mode and Device Owner

Make customer devices reliably restricted during active sessions.

- Provision customer phones as Android Device Owner.
- Document factory reset and ADB provisioning flow.
- Allowlist the app for Lock Task Mode.
- Start Lock Task Mode when a session becomes active or expired.
- Stop Lock Task Mode when a session is stopped or reset.
- Validate behavior on the target Xiaomi/MIUI version.

Device Owner testing command:

```bash
adb shell dpm set-device-owner com.amin.wartel/com.example.phonebilling.admin.PhoneBillingDeviceAdminReceiver
```

## Phase 5: Production Operator Flow

Move operator management and reporting out of local-only demo mode.

- Replace seeded `admin/admin123` with backend authentication.
- Add operator roles and permissions.
- Manage tariffs from the backend.
- Load dashboard revenue from backend data.
- Add session history filtering.
- Add billing report export.

## Phase 6: Reliability and Observability

Harden the app for real multi-device usage.

- Detect offline devices.
- Retry failed syncs with clear UI feedback.
- Handle server downtime gracefully.
- Add backend idempotency for start, stop, extend, and sync requests.
- Add app-side debug/status screen for server URL, device ID, and last sync result.
- Add automated tests for repository sync logic and session state transitions.

## Recommended Build Order

1. Build the backend and database.
2. Wire operator start, stop, extend, and device list to the backend.
3. Fix client polling and manual sync behavior.
4. Add clear UI feedback for every network action.
5. Provision and test Device Owner kiosk mode on real customer devices.
6. Add reporting, authentication, and production hardening.

The next target should be a reliable multi-device MVP: emulator/operator and Xiaomi/customer must agree on device status, session state, countdown, stop, and extend behavior through the backend.
