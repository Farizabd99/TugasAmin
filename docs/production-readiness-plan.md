# Production-Readiness Plan — Restricted Phone Billing

This plan turns the current MVP into a shippable product. It is grounded in the
codebase audit and three product decisions:

- **Backend:** Firebase/Firestore is the *single source of truth*. The local QA
  Node server path is removed from the app.
- **WhatsApp calling:** Keep the Accessibility-driven calling, but harden it.
- **Devices:** Client phones are company-owned and provisioned as **Device Owner**,
  so real Lock Task kiosk enforcement is the design baseline.
- **Firebase account constraint:** The app is wired to project `wartel-195f3`, which
  the team does **not** own. No migration to a new project (decision: keep this one).
  No paid plan / Blaze — **Spark (free) tier only**, so **no Cloud Functions**.

### ⚠️ Critical, time-boxed risk — Firestore rules expire 2026-07-14

`firestore.rules` currently allows all read/write only `if request.time <
timestamp.date(2026, 7, 14)`. After that date Firestore **denies every read and
write** and the app stops working. Fixing it requires deploying new rules, which
requires **member access** to project `wartel-195f3`. Mitigation that needs no
migration and no payment: have the project owner add the team's Google account as
**Editor/Owner** (Firebase Console → Project settings → Users and permissions).
Until that access exists, all "needs project access" items below are blocked and the
app has a hard expiry on 2026-07-14.

Work is grouped into phases ordered by risk. Each item lists the concrete change
and an acceptance criterion. Phases 0–3 are blockers for any pilot; 4–6 are
required before charging real customers; 7–9 are release hardening.

---

## Phase 0 — Repo hygiene & guardrails (0.5 day)

Small, immediate, unblocks everything else.

1. **Remove committed binaries/secrets from git.**
   - Delete `emulator_phone_billing.db`, `emulator_screen.png`, `local.properties`,
     and IDE/build dirs (`.idea/`, `.gradle/`, `.kotlin/`) from tracking.
   - Add them to `.gitignore`. Rotate anything sensitive that was in
     `local.properties`.
   - *Done when:* `git ls-files` shows no binaries/local config; clean checkout builds.

2. **Pin and verify the Gradle wrapper jar** (README admits it's missing) so CI and
   new clones build deterministically.
   - *Done when:* `./gradlew assembleDebug` works on a fresh clone with no manual steps.

---

## Phase 1 — Make Firebase the real backend (3–4 days)

Today `AppModule.provideFirestore()` returns `null`, so **all** Firestore code in
`BillingRepository` is dead and the app silently uses the local server. Fix the lie.

1. **Provide a real Firestore + remove the local-server path.**
   - `AppModule.kt`: return `FirebaseFirestore.getInstance()` (configured with
     persistence + `FirebaseFirestoreSettings`). Ensure `google-services.json` is
     present and the `com.google.gms.google-services` plugin is applied.
   - Delete `LocalServerApiFactory`, `BillingApi`, `BillingApiProvider`, the
     `apiFactory` constructor param, and every `else`/local-server branch in
     `BillingRepository`. Delete or repurpose `qa-server.js` (keep only as a local
     emulator/dev fixture, clearly labeled).
   - Remove the now-unused `serverBaseUrl` Setting + Settings screen URL field, or
     repurpose Settings for real config.
   - *Done when:* `isLocalServerMode()` and all Retrofit code are gone; the app
     compiles with one code path.

2. **Replace the dead `FirebaseAuth` injection with real operator auth** (see Phase 5).
   `provideFirebaseAuth()` currently calls `getInstance()` but is never used and
   will crash injection if Firebase isn't configured.

3. **Fix structured concurrency in Firestore listeners.**
   - In `startRealtimeSync` / `startOperatorRealtimeSync`, replace the per-callback
     `CoroutineScope(Dispatchers.IO).launch { }` with a single repository-scoped
     `CoroutineScope(SupervisorJob() + Dispatchers.IO)` that is cancelled in
     `stopRealtimeSync()`. No leaked scopes.
   - *Done when:* listeners are cancelled deterministically; no scope is created
     inside a snapshot callback.

4. **Centralize Firestore (de)serialization.** The listeners hand-parse every field
   with `getString`/`getLong` defaults. Define `@Serializable`/POJO models or
   mapping extensions in one place to avoid drift between `startSession`,
   `syncPendingLogs`, and the listeners.

---

## Phase 2 — Server-authoritative billing & session integrity (4–5 days)

Billing must not depend on the client's clock or client-side writes. This is the
core money-correctness work.

> **Spark-only constraint:** No Cloud Functions (no Blaze). Integrity is enforced
> with `serverTimestamp()` + Firestore **security rules** instead. The rules part
> **requires member access to `wartel-195f3`** — blocked until that access exists.

1. **Server-stamped session lifecycle via security rules (no Functions).**
   - Write `startedAt` with `FieldValue.serverTimestamp()`; derive/validate `endsAt`
     and `priceCents` from the authoritative tariff doc. The client no longer
     constructs `endsAt` locally (today `startSession` does `now() + tariff.minutes`).
   - Security rules: only an authenticated operator may create/extend a session;
     clients are read-only on their own device/session docs; billing logs are
     append-only. This is the free-tier substitute for Function-enforced integrity.
   - *Note:* without Functions, expiry can't be enforced purely server-side; the
     operator dashboard is the reconciliation point (see item 3).
   - *Done when:* a non-operator client cannot create or extend a session, and
     `startedAt` originates from the server, not the device clock.

2. **Server-authoritative time on the client display.**
   - Drive the countdown from the session's server `endsAt` and a server-time
     reference (Firestore `serverTimestamp` round-trip or Functions time), not
     `System.currentTimeMillis()`. Remove the `serverTimeOffset` guesswork in
     `BillingRepository.now()` / `ClientViewModel`.
   - *Done when:* changing the device clock does not change remaining time or billing.

3. **Expiry is enforced server-side; client expiry is display-only.**
   - A scheduled Function (or Firestore TTL + a sweeper) flips `ACTIVE → EXPIRED`
     at `endsAt`. The client may *show* expired but the truth is the server doc.
   - Fix the client bug where auto-expiry stops when nothing is subscribed:
     `ClientViewModel` reads `state.value` from a `WhileSubscribed(5_000)` flow, so
     expiry stalls when the overlay covers the UI. Move the ticking/expiry decision
     off the UI-subscription lifecycle (e.g. a foreground service or always-on
     `viewModelScope` flow independent of `stateIn`).
   - *Done when:* a session expires on time even if the client UI is backgrounded by
     the WhatsApp overlay.

4. **Idempotency + consistent `synced` semantics.**
   - Give every session/log a client-generated UUID (already present) and make
     Functions upserts idempotent (README already flags this).
   - Fix the divergent `synced` flags: `stopActiveSessionsForDevice` sets
     `synced=false` while `clearActiveSessionsForDevice` / `stopOtherActiveSessionsForDevice`
     set `synced=true` on locally-stopped sessions that were never pushed. Pick one
     rule (locally-mutated ⇒ `synced=false` until confirmed).
   - *Done when:* replaying a sync never double-bills; no locally-stopped session is
     marked synced before the backend confirms.

5. **Thread the real operator through.**
   - `StartSessionViewModel.start()` hardcodes `operatorId = "operator-default"` and
     `SessionDetailViewModel.extend()` hardcodes 30 min / 5000. Pass the authenticated
     operator's id and the selected tariff/extension everywhere.
   - *Done when:* every session/log is attributed to the operator who created it.

---

## Phase 3 — Kiosk hardening as a Device Owner (3 days)

You can provision Device Owner, so make kiosk a real boundary, not best-effort.

1. **Fail loudly when not Device Owner.**
   - `KioskController` wraps everything in `runCatching {}` and silently no-ops if not
     owner. Surface a blocking operator-facing error/health state instead, and a
     startup self-check ("Kiosk enforcement: ACTIVE / NOT ENFORCED").
   - *Done when:* an un-provisioned device cannot be put into a paid session without a
     visible warning.

2. **Re-scope the WhatsApp allowance.**
   - Allowing `com.whatsapp` in `setLockTaskPackages` opens browser/share/settings
     escape routes. Tighten with `setLockTaskFeatures` appropriately and apply Device
     Owner restrictions to neuter escapes: `DISALLOW_SAFE_BOOT`,
     `DISALLOW_ADD_USER`, `DISALLOW_FACTORY_RESET`, `DISALLOW_CONFIG_*`,
     `DISALLOW_INSTALL_APPS`/`DISALLOW_APPS_CONTROL`, disable the WhatsApp in-app
     browser exit where possible, and block status bar/keyguard (already done).
   - Evaluate whether WhatsApp must be a *foreground* lock-task package at all, or
     whether the call can be launched and returned without leaving the kiosk app
     persistently exposed.
   - *Done when:* during an active session a user cannot reach a browser, Settings,
     another app, or factory reset from WhatsApp.

3. **Provisioning + lifecycle.**
   - Document and script Device Owner enrollment (QR/`afw#` / ADB
     `dpm set-device-owner`) in `docs/device-owner-kiosk-setup.md`.
   - Auto-grant runtime permissions via Device Owner (`setPermissionGrantState`) for
     `SYSTEM_ALERT_WINDOW`, contacts, etc., and enable the Accessibility service via
     policy so kiosk devices don't need manual toggles.
   - *Done when:* a wiped phone reaches "ready client" via a documented, repeatable
     enrollment with no manual permission tapping.

---

## Phase 4 — WhatsApp calling: harden (kept feature) (3 days)

1. **Stop leaking data.** Remove the plaintext `accessibility_debug.txt` logging in
   `WartelAccessibilityService` (and the file-pull comment). Gate any logging behind
   `BuildConfig.DEBUG` and never write contact/call data to disk in release.

2. **Guard every precondition.**
   - Before launching: verify Accessibility service enabled, `SYSTEM_ALERT_WINDOW`
     granted (`Settings.canDrawOverlays()` — currently unchecked before
     `windowManager.addView`), WhatsApp installed at an expected version, and contacts
     permission present. Show a clear failure if not.
   - *Done when:* a missing prerequisite produces a friendly message, never a crash or
     a stuck black overlay.

3. **Narrow the Accessibility scope & matching.**
   - `accessibilityEventTypes="typeAllMask"` + `flagIncludeNotImportantViews` receives
     every event on the device. Restrict `packageNames="com.whatsapp"` and the event
     types to what's needed.
   - Fix the loose phone-number match in `findVoipDataId`
     (`contains` either-direction can dial the wrong contact) — match on a normalized
     full number / E.164.
   - *Done when:* the service only wakes for WhatsApp and never dials a wrong number.

4. **Robust timeout/cleanup.** The 8s timeout + temp-contact deletion path should be
   guaranteed even on exceptions (the current `launchCall` never visibly deletes the
   temp contact on the success path). Ensure temp contacts are always cleaned up.

5. **Version-lock & resilience.** Document the supported WhatsApp version range, pin
   auto-update off via Device Owner, and add a monitored fallback (open chat, let user
   tap) when the automation can't find the button.

---

## Phase 5 — Authentication & data security (2–3 days)

1. **Real operator auth.** Replace unsalted SHA-256 (`BillingRepository.sha256`) +
   seeded `admin/admin123`. Use Firebase Auth (email/password or custom claims for
   operator role). Remove the password pre-fill in `LoginUiState`
   (`username="admin", password="admin123"`).

2. **Lock down Firestore rules.** Current rules allow **all** read/write to anyone
   until 2026-07-14. Replace with role-based rules: only authenticated operators write
   sessions/tariffs/devices; clients read only their own device/session docs; billing
   logs are append-only/Function-only.
   - *Done when:* an anonymous client cannot read other devices or write billing.

3. **Transport & config.** Remove the global `cleartextTrafficPermitted="true"` from
   `network_security_config` (Firebase is TLS). Keep cleartext only if a dev emulator
   host is still needed, scoped to that host.

---

## Phase 6 — Runtime stability / crash fixes (2 days)

1. **OverlayService foreground-service type.** Declare
   `android:foregroundServiceType` (e.g. `specialUse`) + the matching
   `FOREGROUND_SERVICE_*` permission, or `startForeground` throws on Android 14+.

2. **Overlay add guarded.** Wrap `windowManager.addView` and check
   `canDrawOverlays()` first; handle `BadTokenException`.

3. **ClientViewModel concurrency.** Replace the nested
   `observeDeviceId().collect { while(true){…} }` / nested `observeDevices().collect`
   blocks with `flatMapLatest` + `distinctUntilChanged`. Today a device-id change
   would never be picked up.

4. **POST_NOTIFICATIONS runtime request** for Android 13+ (foreground-service
   notification).

---

## Phase 7 — Data layer robustness (1–2 days)

1. **Remove `fallbackToDestructiveMigration()`** from the Room builder — it wipes all
   local sessions/logs (including unsynced revenue) on any schema change. Write real
   migrations and add migration tests.

2. **Outbox/sync worker hardening.** `BillingSyncWorker` runs every 15 min; ensure it
   uses exponential backoff, surfaces permanent failures, and that
   `syncPendingLogs`/`syncDevicesAndLogs` are safe to run concurrently with the
   realtime listeners (no write races into the same docs).

---

## Phase 8 — Observability, testing & QA (3–4 days)

1. **Crash/metrics:** add Crashlytics + minimal analytics (session started/stopped,
   call success/failure, kiosk-enforcement state).

2. **Tests:**
   - Keep/extend repository unit tests; the current ones validate the *local* path
     that is being deleted — rewrite them against the Firebase/Functions path
     (use the Firestore emulator).
   - Add Cloud Functions tests for billing math, idempotency, expiry.
   - Instrumented tests for kiosk enforcement and the calling flow on a provisioned
     device (or a documented manual QA checklist where automation isn't feasible).

3. **CI:** lint + unit + build on every PR; block merge on failures.

---

## Phase 9 — Release engineering (2 days)

1. **Signed release config** in `app/build.gradle.kts` (keystore in CI secrets),
   `minifyEnabled`/R8 + ProGuard rules for Firebase/Compose/Accessibility.
2. **Versioning** + an internal distribution channel (Play internal track or EMM
   push) — note the Accessibility/contacts usage means a Play submission needs the
   prominent-disclosure + permissions justification; distributing via EMM to
   owned devices sidesteps Play review.
3. **Runbook:** enrollment, recovery (un-stuck kiosk), WhatsApp-version updates,
   incident response for billing disputes.

---

## Suggested milestones

- **M1 – Pilot-ready (Phases 0–3):** one honest backend, server-authoritative
  billing, real kiosk. The app can be trialed on a few owned devices.
- **M2 – Charge-real-money-ready (Phases 4–6):** calling hardened, auth + rules
  locked, no crash classes. Safe to bill customers.
- **M3 – Scale-ready (Phases 7–9):** migrations, observability, tests, signed
  release, runbook. Safe to roll out fleet-wide.

## Top risks to retire first

1. Billing correctness depending on client clock / client writes (Phase 2).
2. Wide-open Firestore rules (Phase 5.2) — exploitable today.
3. Kiosk escape via WhatsApp + silent non-owner no-op (Phase 3).
4. Android 14 foreground-service & overlay crashes (Phase 6).
