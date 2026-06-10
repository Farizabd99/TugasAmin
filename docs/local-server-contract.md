# Local Server Contract

The app is offline-first. Server calls should be idempotent because local Room state is the source of continuity when Wi-Fi or the server drops.

## Register Device

`POST /api/devices/register`

```json
{
  "deviceId": "android-id",
  "displayName": "Client phone",
  "model": "Pixel",
  "mode": "CLIENT"
}
```

## Get Device Status

`GET /api/devices/{deviceId}/status`

```json
{
  "data": {
    "deviceId": "android-id",
    "status": "ACTIVE",
    "serverTime": 1760000000000,
    "activeSession": {
      "sessionId": "uuid",
      "deviceId": "android-id",
      "tariffId": "tariff-60",
      "operatorId": "operator-default",
      "status": "ACTIVE",
      "startedAt": 1760000000000,
      "endsAt": 1760003600000,
      "stoppedAt": null,
      "extendedMinutes": 0,
      "priceCents": 9000
    }
  }
}
```

## Start Session

`POST /api/sessions/start`

Fields: `deviceId`, `tariffId`, `operatorId`, `minutes`, `priceCents`.

## Stop Session

`POST /api/sessions/{sessionId}/stop`

## Extend Session

`POST /api/sessions/{sessionId}/extend`

Fields: `additionalMinutes`, `additionalPriceCents`.

## Sync Billing Logs

`POST /api/billing/logs/sync`

The server should return accepted IDs so the client can mark local rows synced.

```json
{
  "data": {
    "acceptedSessionIds": ["uuid"],
    "acceptedLogIds": ["uuid"]
  }
}
```
