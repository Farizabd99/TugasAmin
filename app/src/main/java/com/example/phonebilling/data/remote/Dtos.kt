package com.example.phonebilling.data.remote

import com.example.phonebilling.data.local.entity.BillingEvent
import com.example.phonebilling.data.local.entity.DeviceMode
import com.example.phonebilling.data.local.entity.DeviceStatus
import com.example.phonebilling.data.local.entity.SessionStatus
import kotlinx.serialization.Serializable

@Serializable
data class ApiEnvelope<T>(
    val data: T? = null,
    val message: String? = null
)

@Serializable
data class RegisterDeviceRequest(
    val deviceId: String,
    val displayName: String,
    val model: String,
    val mode: DeviceMode
)

@Serializable
data class StartSessionRequest(
    val deviceId: String,
    val tariffId: String,
    val operatorId: String,
    val minutes: Int,
    val priceCents: Long
)

@Serializable
data class ExtendSessionRequest(
    val additionalMinutes: Int,
    val additionalPriceCents: Long
)

@Serializable
data class SyncBillingLogsRequest(
    val deviceId: String,
    val sessions: List<SessionDto>,
    val logs: List<BillingLogDto>
)

@Serializable
data class SyncResultDto(
    val acceptedSessionIds: List<String>,
    val acceptedLogIds: List<String>
)

@Serializable
data class DeviceDto(
    val deviceId: String,
    val displayName: String,
    val model: String,
    val mode: DeviceMode,
    val status: DeviceStatus,
    val lastSeenAt: Long
)

@Serializable
data class DeviceStatusDto(
    val deviceId: String,
    val status: DeviceStatus,
    val activeSession: SessionDto? = null,
    val serverTime: Long
)

@Serializable
data class SessionDto(
    val sessionId: String,
    val deviceId: String,
    val tariffId: String,
    val operatorId: String,
    val status: SessionStatus,
    val startedAt: Long,
    val endsAt: Long,
    val stoppedAt: Long? = null,
    val extendedMinutes: Int,
    val priceCents: Long
)

@Serializable
data class BillingLogDto(
    val logId: String,
    val sessionId: String,
    val deviceId: String,
    val event: BillingEvent,
    val amountCents: Long,
    val occurredAt: Long,
    val details: String
)
