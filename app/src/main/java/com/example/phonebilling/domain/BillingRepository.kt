package com.example.phonebilling.domain

import android.os.Build
import com.example.phonebilling.data.local.AppSettings
import com.example.phonebilling.data.local.dao.BillingLogDao
import com.example.phonebilling.data.local.dao.DeviceDao
import com.example.phonebilling.data.local.dao.OperatorDao
import com.example.phonebilling.data.local.dao.SessionDao
import com.example.phonebilling.data.local.dao.TariffDao
import com.example.phonebilling.data.local.entity.BillingEvent
import com.example.phonebilling.data.local.entity.BillingLogEntity
import com.example.phonebilling.data.local.entity.DeviceEntity
import com.example.phonebilling.data.local.entity.DeviceMode
import com.example.phonebilling.data.local.entity.DeviceStatus
import com.example.phonebilling.data.local.entity.OperatorEntity
import com.example.phonebilling.data.local.entity.SessionEntity
import com.example.phonebilling.data.local.entity.SessionStatus
import com.example.phonebilling.data.local.entity.TariffEntity
import com.example.phonebilling.data.remote.BillingLogDto
import com.example.phonebilling.data.remote.ExtendSessionRequest
import com.example.phonebilling.data.remote.LocalServerApiFactory
import com.example.phonebilling.data.remote.RegisterDeviceRequest
import com.example.phonebilling.data.remote.SessionDto
import com.example.phonebilling.data.remote.StartSessionRequest
import com.example.phonebilling.data.remote.SyncBillingLogsRequest
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

@Singleton
class BillingRepository @Inject constructor(
    private val settings: AppSettings,
    private val apiFactory: LocalServerApiFactory,
    private val deviceDao: DeviceDao,
    private val sessionDao: SessionDao,
    private val tariffDao: TariffDao,
    private val operatorDao: OperatorDao,
    private val billingLogDao: BillingLogDao
) {
    fun observeDevices(): Flow<List<DeviceEntity>> = deviceDao.observeDevices()
    fun observeActiveSessions(): Flow<List<SessionEntity>> = sessionDao.observeSessionsByStatus(SessionStatus.ACTIVE)
    fun observeSessions(): Flow<List<SessionEntity>> = sessionDao.observeSessions()
    fun observeBillingLogs(): Flow<List<BillingLogEntity>> = billingLogDao.observeLogs()
    fun observeRevenue(): Flow<Long> = billingLogDao.observeTotalRevenueCents()
    fun observeActiveSessionForDevice(deviceId: String): Flow<SessionEntity?> =
        sessionDao.observeActiveSessionForDevice(deviceId)
    fun observeTariffs(): Flow<List<TariffEntity>> = tariffDao.observeActiveTariffs()
    fun observeServerUrl(): Flow<String> = settings.serverBaseUrl
    fun observeDeviceId(): Flow<String> = settings.deviceId

    suspend fun ensureDefaults() {
        if (operatorDao.count() == 0) {
            val now = now()
            operatorDao.upsert(
                OperatorEntity(
                    operatorId = "operator-default",
                    username = "admin",
                    passwordHash = sha256("admin123"),
                    displayName = "Main Operator",
                    active = true,
                    createdAt = now
                )
            )
            listOf(
                TariffEntity("tariff-30", "30 minutes", 30, 5000, true),
                TariffEntity("tariff-60", "1 hour", 60, 9000, true),
                TariffEntity("tariff-120", "2 hours", 120, 16000, true)
            ).forEach { tariffDao.upsert(it) }
        }
    }

    suspend fun login(username: String, password: String): OperatorEntity? {
        ensureDefaults()
        val operator = operatorDao.findActiveByUsername(username.trim())
        return operator?.takeIf { it.passwordHash == sha256(password) }
    }

    suspend fun saveServerUrl(url: String) = settings.setServerBaseUrl(url)

    suspend fun registerCurrentDevice(displayName: String, mode: DeviceMode): Result<Unit> {
        val now = now()
        val deviceId = settings.deviceId.first()
        val baseUrl = settings.serverBaseUrl.first()
        val local = DeviceEntity(
            deviceId = deviceId,
            displayName = displayName.ifBlank { Build.MODEL ?: "Android device" },
            model = Build.MODEL ?: "Android",
            mode = mode,
            status = DeviceStatus.WAITING,
            serverBaseUrl = baseUrl,
            lastSeenAt = now,
            createdAt = now
        )
        deviceDao.upsert(local)
        return runCatching {
            api(baseUrl).registerDevice(
                RegisterDeviceRequest(local.deviceId, local.displayName, local.model, local.mode)
            )
        }.map { }
    }

    suspend fun refreshClientStatus(deviceId: String): Result<Unit> {
        val baseUrl = settings.serverBaseUrl.first()
        return runCatching {
            val status = api(baseUrl).getDeviceStatus(deviceId).data
            if (status != null) {
                deviceDao.getDevice(deviceId)?.let {
                    deviceDao.upsert(it.copy(status = status.status, lastSeenAt = now()))
                }
                status.activeSession?.let { sessionDao.upsert(it.toEntity(synced = true)) }
            }
        }
    }

    suspend fun startSession(
        deviceId: String,
        tariff: TariffEntity,
        operatorId: String
    ): SessionEntity {
        val now = now()
        val session = SessionEntity(
            sessionId = UUID.randomUUID().toString(),
            deviceId = deviceId,
            tariffId = tariff.tariffId,
            operatorId = operatorId,
            status = SessionStatus.ACTIVE,
            startedAt = now,
            endsAt = now + tariff.minutes.minutesToMillis(),
            stoppedAt = null,
            extendedMinutes = 0,
            priceCents = tariff.priceCents,
            synced = false
        )
        sessionDao.upsert(session)
        updateDeviceStatus(deviceId, DeviceStatus.ACTIVE)
        addLog(session, BillingEvent.SESSION_STARTED, tariff.priceCents, "Started ${tariff.name}")

        runCatching {
            api(settings.serverBaseUrl.first()).startSession(
                StartSessionRequest(deviceId, tariff.tariffId, operatorId, tariff.minutes, tariff.priceCents)
            ).data
        }.getOrNull()?.let {
            sessionDao.upsert(it.toEntity(synced = true))
        }
        return session
    }

    suspend fun stopSession(session: SessionEntity): SessionEntity {
        val updated = session.copy(
            status = SessionStatus.STOPPED,
            stoppedAt = now(),
            synced = false
        )
        sessionDao.upsert(updated)
        updateDeviceStatus(session.deviceId, DeviceStatus.WAITING)
        addLog(updated, BillingEvent.SESSION_STOPPED, 0, "Stopped manually")
        runCatching { api(settings.serverBaseUrl.first()).stopSession(session.sessionId) }
        return updated
    }

    suspend fun extendSession(session: SessionEntity, minutes: Int, priceCents: Long): SessionEntity {
        val updated = session.copy(
            endsAt = session.endsAt + minutes.minutesToMillis(),
            extendedMinutes = session.extendedMinutes + minutes,
            priceCents = session.priceCents + priceCents,
            synced = false
        )
        sessionDao.upsert(updated)
        addLog(updated, BillingEvent.SESSION_EXTENDED, priceCents, "Extended $minutes minutes")
        runCatching {
            api(settings.serverBaseUrl.first()).extendSession(
                session.sessionId,
                ExtendSessionRequest(minutes, priceCents)
            )
        }
        return updated
    }

    suspend fun expireSession(session: SessionEntity): SessionEntity {
        val updated = session.copy(status = SessionStatus.EXPIRED, stoppedAt = now(), synced = false)
        sessionDao.upsert(updated)
        updateDeviceStatus(session.deviceId, DeviceStatus.EXPIRED)
        addLog(updated, BillingEvent.SESSION_EXPIRED, 0, "Session time expired")
        return updated
    }

    suspend fun syncPendingLogs(): Result<Unit> = runCatching {
        val sessions = sessionDao.unsyncedSessions()
        val logs = billingLogDao.unsyncedLogs()
        if (sessions.isEmpty() && logs.isEmpty()) return@runCatching
        val deviceId = settings.deviceId.first()
        val result = api(settings.serverBaseUrl.first()).syncBillingLogs(
            SyncBillingLogsRequest(
                deviceId = deviceId,
                sessions = sessions.map { it.toDto() },
                logs = logs.map { it.toDto() }
            )
        ).data
        sessionDao.markSynced(result?.acceptedSessionIds ?: sessions.map { it.sessionId })
        billingLogDao.markSynced(result?.acceptedLogIds ?: logs.map { it.logId })
    }

    private suspend fun updateDeviceStatus(deviceId: String, status: DeviceStatus) {
        deviceDao.getDevice(deviceId)?.let {
            deviceDao.upsert(it.copy(status = status, lastSeenAt = now()))
        }
    }

    private suspend fun addLog(
        session: SessionEntity,
        event: BillingEvent,
        amountCents: Long,
        details: String
    ) {
        billingLogDao.upsert(
            BillingLogEntity(
                logId = UUID.randomUUID().toString(),
                sessionId = session.sessionId,
                deviceId = session.deviceId,
                event = event,
                amountCents = amountCents,
                occurredAt = now(),
                details = details,
                synced = false
            )
        )
    }

    private fun api(baseUrl: String) = apiFactory.create(baseUrl)
    private fun now() = System.currentTimeMillis()
}

private fun Int.minutesToMillis(): Long = this * 60_000L

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

private fun SessionEntity.toDto() = SessionDto(
    sessionId = sessionId,
    deviceId = deviceId,
    tariffId = tariffId,
    operatorId = operatorId,
    status = status,
    startedAt = startedAt,
    endsAt = endsAt,
    stoppedAt = stoppedAt,
    extendedMinutes = extendedMinutes,
    priceCents = priceCents
)

private fun SessionDto.toEntity(synced: Boolean) = SessionEntity(
    sessionId = sessionId,
    deviceId = deviceId,
    tariffId = tariffId,
    operatorId = operatorId,
    status = status,
    startedAt = startedAt,
    endsAt = endsAt,
    stoppedAt = stoppedAt,
    extendedMinutes = extendedMinutes,
    priceCents = priceCents,
    synced = synced
)

private fun BillingLogEntity.toDto() = BillingLogDto(
    logId = logId,
    sessionId = sessionId,
    deviceId = deviceId,
    event = event,
    amountCents = amountCents,
    occurredAt = occurredAt,
    details = details
)
