# Restricted Calling MVP Architecture

## Goal

Build a restricted Android phone rental system for prison use cases. A detainee should only be able to use a rented phone for an approved call session.

The target detainee flow is:

1. Wait for an operator to activate a paid session.
2. Enter a destination phone number.
3. Choose one allowed communication mode.
4. Use the call until the timer ends or the operator stops the session.

The strict target modes are:

- Voice call
- Video call

No chat, browser, gallery, contacts, settings, notification access, app switching, or general Android navigation should be exposed during an active or expired session.

## Current App Baseline

The existing Android app already provides a useful base:

- Operator login with seeded local credentials.
- Operator dashboard.
- Device registration.
- Session start, stop, and extend.
- Local tariffs.
- Local Room persistence for devices, sessions, operators, tariffs, and billing logs.
- Retrofit API client for a local backend contract.
- Periodic WorkManager sync.
- Client mode with waiting, active, and expired states.
- Countdown timer.
- Initial Android Lock Task Mode support through Device Owner APIs.
- In-memory `qa-server.js` for local testing.

The missing MVP capability is the actual restricted calling surface.

## Architecture Options

There are two realistic implementation paths.

## Option A: WhatsApp Quick Launcher MVP

This is the fastest prototype path. The app remains the controlled launcher and opens WhatsApp for the selected number.

### Flow

1. Operator starts a session from the operator device.
2. Client device enters active kiosk mode.
3. Detainee enters a phone number.
4. App validates and normalizes the number.
5. App opens WhatsApp using a supported deep link.
6. Detainee manually taps call or video call inside WhatsApp.
7. App/backend session timer remains the billing source of truth.
8. Operator can stop or extend the session.

### Supported WhatsApp Actions

Reliable:

- Open chat for a phone number with `https://wa.me/<phone>`.
- Open chat with a prefilled message using `https://wa.me/<phone>?text=<encodedMessage>`.

Not reliable:

- Directly starting WhatsApp voice calls.
- Directly starting WhatsApp video calls.
- Sending messages automatically without user action.

WhatsApp does not provide a stable public API for directly triggering consumer voice or video calls to arbitrary numbers.

### Android Implementation Notes

Example deep link:

```kotlin
val phone = "6281234567890"
val url = "https://wa.me/$phone"
val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
    setPackage("com.whatsapp")
}

try {
    startActivity(intent)
} catch (error: Exception) {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
```

For kiosk mode, WhatsApp must be allowlisted if the app launches it during Lock Task Mode:

```kotlin
dpm.setLockTaskPackages(
    admin,
    arrayOf(
        context.packageName,
        "com.whatsapp",
        "com.whatsapp.w4b"
    )
)
```

### Major Limitation

This path cannot fully guarantee "no chat". Once WhatsApp is opened, the detainee may still access parts of the WhatsApp UI, including text entry, existing chats, attachments, profile screens, and other app-controlled surfaces.

Use this path only as a fast validation prototype, not as the final prison-grade restriction model.

## Option B: Controlled In-App Calling MVP

This is the recommended architecture for a strict prison-use MVP.

The app owns the calling screen and never exposes WhatsApp, the dialer, contacts, chat, browser, or general Android UI.

### Flow

1. Operator authenticates to the backend.
2. Operator registers or selects a client device.
3. Operator starts a paid session.
4. Backend creates an active session and returns session state to both operator and client.
5. Client device enters Lock Task Mode.
6. Client app shows only:
   - countdown timer
   - destination number input
   - Voice Call button
   - Video Call button
   - call status
7. Detainee enters a number and chooses call type.
8. Client app requests call authorization from backend.
9. Backend validates session, number policy, and remaining time.
10. Backend creates a call record and returns call configuration.
11. Client app starts the in-app call.
12. Client app and backend end the call when:
   - timer expires
   - operator stops the session
   - call fails
   - network disconnect policy is triggered

### Calling Provider Choices

Voice to phone numbers:

- Use SIP trunk or programmable voice provider.
- Backend brokers call setup.
- App can use SIP/WebRTC SDK depending on provider.

Video calls:

- Phone-number-based video calling is not generally available like normal PSTN voice.
- Use WebRTC or a provider video SDK.
- Recipient must join through a compatible app, web link, or controlled endpoint.

Recommended MVP split:

- Voice: support calls to normal phone numbers through a telephony provider.
- Video: support WebRTC room/link calls only, with clear recipient flow.

## Recommended MVP Path

Build in two stages:

1. Short prototype: WhatsApp quick launcher to validate operator flow, session timing, kiosk provisioning, and user journey.
2. Production MVP: replace WhatsApp dependency with controlled in-app voice/video calling.

Do not design the production architecture around WhatsApp if the hard requirement is "voice/video only, no chat".

## Backend Responsibilities

The backend should become the source of truth for:

- Operators.
- Devices.
- Tariffs.
- Sessions.
- Calls.
- Billing logs.
- Device lock state.
- Audit logs.

Required backend modules:

- Auth service.
- Device service.
- Session service.
- Tariff service.
- Call service.
- Billing service.
- Audit service.
- Admin/reporting API.

## Suggested Data Model

### operators

- `id`
- `username`
- `password_hash`
- `display_name`
- `role`
- `active`
- `created_at`
- `updated_at`

### devices

- `id`
- `android_id`
- `display_name`
- `model`
- `mode`
- `status`
- `last_seen_at`
- `lock_task_ready`
- `created_at`
- `updated_at`

### tariffs

- `id`
- `name`
- `minutes`
- `price_cents`
- `active`
- `created_at`
- `updated_at`

### sessions

- `id`
- `device_id`
- `operator_id`
- `tariff_id`
- `status`
- `started_at`
- `ends_at`
- `stopped_at`
- `extended_minutes`
- `price_cents`
- `created_at`
- `updated_at`

### calls

- `id`
- `session_id`
- `device_id`
- `destination_number`
- `call_type`
- `status`
- `provider`
- `provider_call_id`
- `started_at`
- `connected_at`
- `ended_at`
- `failure_reason`
- `created_at`
- `updated_at`

### billing_logs

- `id`
- `session_id`
- `call_id`
- `device_id`
- `event`
- `amount_cents`
- `details`
- `occurred_at`
- `created_at`

### audit_logs

- `id`
- `actor_type`
- `actor_id`
- `action`
- `target_type`
- `target_id`
- `metadata_json`
- `occurred_at`

## API Surface

Keep the existing endpoints and add call-specific endpoints.

Existing required endpoints:

- `POST /api/devices/register`
- `GET /api/devices`
- `GET /api/devices/{deviceId}/status`
- `POST /api/sessions/start`
- `POST /api/sessions/{sessionId}/stop`
- `POST /api/sessions/{sessionId}/extend`
- `POST /api/billing/logs/sync`

New recommended endpoints:

- `POST /api/auth/login`
- `GET /api/tariffs`
- `POST /api/calls/authorize`
- `POST /api/calls/{callId}/start`
- `POST /api/calls/{callId}/end`
- `POST /api/calls/{callId}/heartbeat`
- `GET /api/sessions/{sessionId}/calls`
- `GET /api/reports/sessions`
- `GET /api/reports/billing`

Example call authorization request:

```json
{
  "sessionId": "session-id",
  "deviceId": "device-id",
  "destinationNumber": "6281234567890",
  "callType": "VOICE"
}
```

Example call authorization response:

```json
{
  "data": {
    "callId": "call-id",
    "allowed": true,
    "remainingMillis": 1200000,
    "provider": "sip-or-webrtc-provider",
    "callConfig": {}
  }
}
```

## Android Client Responsibilities

Client mode should become a locked call terminal.

Required screens:

- Waiting screen.
- Active session call screen.
- In-call screen.
- Expired screen.
- Offline/reconnect screen.

Active session screen controls:

- Destination number input.
- Voice Call button.
- Video Call button.
- Countdown timer.
- Current session status.

Validation rules:

- Normalize Indonesian numbers to `62...`.
- Reject empty or malformed numbers.
- Optionally enforce allowlist/blocklist from backend.
- Optionally limit one active call at a time.

Restrictions:

- No contacts picker.
- No chat input.
- No browser fallback.
- No gallery/file picker.
- No notification-driven navigation.
- No general settings access.

## Android Kiosk Requirements

Production devices must be provisioned as Android Device Owner.

Required controls:

- Lock Task Mode enabled.
- App allowlisted for Lock Task.
- Status bar disabled.
- Keyguard disabled.
- Back navigation blocked in restricted screens.
- App set as default launcher if required by device policy.
- Boot receiver to relaunch app after restart.
- Operator-only unlock/reset path.

Do not rely on normal app permissions alone. A regular installed Android app cannot reliably prevent app switching or system access.

## Infrastructure Recommendation

### Local MVP

- Android client app.
- Backend API service.
- PostgreSQL database.
- Redis for short-lived session/call state if needed.
- Provider sandbox for SIP/WebRTC/calling.
- Admin/operator web dashboard or Android operator mode.

### Deployment MVP

- Backend API behind HTTPS.
- PostgreSQL managed database.
- Object/log storage for audit exports if needed.
- Centralized logs.
- Monitoring and alerting.
- Device network pinned to prison Wi-Fi or controlled mobile data.
- MDM or Android Enterprise provisioning process.

### Suggested Backend Stack

Any stable backend stack is acceptable. Choose based on the team.

Pragmatic options:

- Node.js/NestJS + PostgreSQL.
- Kotlin/Ktor + PostgreSQL.
- Java/Spring Boot + PostgreSQL.

Important backend properties:

- Idempotent session and call endpoints.
- Server-side session clock.
- Device heartbeats.
- Clear state machine for sessions and calls.
- Auditable operator actions.
- Strict auth and role checks.

## State Machines

### Device Status

- `WAITING`
- `ACTIVE`
- `IN_CALL`
- `EXPIRED`
- `OFFLINE`
- `LOCKED`

### Session Status

- `ACTIVE`
- `STOPPED`
- `EXPIRED`
- `CANCELLED`

### Call Status

- `AUTHORIZED`
- `STARTING`
- `RINGING`
- `CONNECTED`
- `ENDED`
- `FAILED`
- `FORCE_ENDED`

## Security Considerations

- Replace local seeded `admin/admin123` with backend authentication.
- Use HTTPS for all backend traffic.
- Use short-lived access tokens for operators.
- Use device enrollment tokens for client device registration.
- Store only required call metadata.
- Log all operator actions.
- Log device state transitions.
- Protect against duplicate start/stop/extend requests.
- Enforce server-side authorization before every call.
- Do not trust client-side timers for billing.
- Add tamper detection where possible.

## Reliability Considerations

- Client must poll session status every few seconds.
- Client should heartbeat during calls.
- Backend should mark devices offline after missed heartbeats.
- Timer expiry must be enforced by backend and client.
- Calls should be force-ended when session expires.
- Pending billing logs should retry with WorkManager.
- Operator UI should show stale/offline devices clearly.
- Backend endpoints should be idempotent.

## Compliance and Policy Considerations

Confirm these before production:

- Whether calls must be recorded.
- Whether recipients must consent to monitoring or recording.
- Whether destination numbers need allowlisting.
- Whether video calls are allowed by facility policy.
- Required audit retention period.
- Data retention and deletion rules.
- Operator access controls.
- Incident review process.

## Implementation Milestones

### Milestone 1: Backend Source of Truth

- Build real backend database.
- Implement device, tariff, session, and billing endpoints.
- Replace `qa-server.js` for multi-device testing.
- Keep Android local Room as cache/offline support.

### Milestone 2: Harden Kiosk Mode

- Provision client devices as Device Owner.
- Validate Lock Task Mode on target Xiaomi/MIUI devices.
- Add boot relaunch.
- Add operator-only reset/unlock flow.
- Confirm no access to settings, notifications, recents, or other apps.

### Milestone 3: Restricted Calling UI

- Replace active client screen with number input and Voice/Video choices.
- Add number normalization and validation.
- Add backend call authorization endpoint.
- Add call state UI and audit logging.

### Milestone 4: Prototype Calling Integration

- If using WhatsApp prototype, implement deep link launcher and document limitations.
- If using controlled calling, integrate selected SIP/WebRTC/provider SDK.
- Track call start, connect, end, failure, and force-end events.

### Milestone 5: Billing and Reporting

- Store call records.
- Tie call records to sessions and billing logs.
- Add operator reports.
- Add exportable session and billing history.

### Milestone 6: Production Hardening

- Backend auth and roles.
- HTTPS and deployment pipeline.
- Monitoring and alerting.
- Device health dashboard.
- Audit log review.
- Failure-mode QA on real devices.

## Team Decision Points

Before implementation, decide:

- Is WhatsApp acceptable for prototype only, or must MVP avoid WhatsApp entirely?
- Must voice calls reach normal phone numbers?
- How will video call recipients join?
- Are calls recorded or only logged?
- Are destination numbers unrestricted, allowlisted, or blocklisted?
- What device models and Android versions are in scope?
- Who can unlock a client device?
- What happens when network drops during an active call?

## Recommendation

Use the current app as the session, billing, and kiosk foundation.

For a fast demo, add the WhatsApp quick launcher but document that it cannot guarantee "no chat".

For the real MVP, build controlled in-app calling with backend-authorized sessions and Device Owner kiosk enforcement. This is the only path that matches the strict requirement: detainees can only enter a number and choose voice or video call, with no chat or access to other apps.
