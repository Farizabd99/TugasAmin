package com.example.phonebilling.domain

import android.os.Build
import com.example.phonebilling.data.local.BillingSettings
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
import com.example.phonebilling.data.remote.DeviceDto
import com.example.phonebilling.data.remote.ExtendSessionRequest
import com.example.phonebilling.data.remote.BillingApiProvider
import com.example.phonebilling.data.remote.RegisterDeviceRequest
import com.example.phonebilling.data.remote.SessionDto
import com.example.phonebilling.data.remote.StartSessionRequest
import com.example.phonebilling.data.remote.SyncBillingLogsRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BillingRepository @Inject constructor(
    private val settings: BillingSettings,
    private val firestore: FirebaseFirestore? = null,
    private val apiFactory: BillingApiProvider? = null,
    private val deviceDao: DeviceDao,
    private val sessionDao: SessionDao,
    private val tariffDao: TariffDao,
    private val operatorDao: OperatorDao,
    private val billingLogDao: BillingLogDao
) {
    private var deviceListener: ListenerRegistration? = null
    private var sessionListener: ListenerRegistration? = null
    private var operatorDevicesListener: ListenerRegistration? = null
    private var operatorSessionsListener: ListenerRegistration? = null
    private var serverTimeOffset: Long = 0L

    fun getServerTimeOffset(): Long = serverTimeOffset

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
    fun isLocalServerMode(): Boolean = firestore == null

    suspend fun ensureDefaults() {
        if (operatorDao.count() == 0) {
            val now = now()
            operatorDao.upsert(
                OperatorEntity(
                    operatorId = "operator-default",
                    username = "admin",
                    passwordHash = sha256("admin123"),
                    displayName = "Operator Utama",
                    active = true,
                    createdAt = now
                )
            )
            listOf(
                TariffEntity("tariff-30", "30 menit", 30, 5000, true),
                TariffEntity("tariff-60", "1 jam", 60, 9000, true),
                TariffEntity("tariff-120", "2 jam", 120, 16000, true)
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
        val local = DeviceEntity(
            deviceId = deviceId,
            displayName = displayName.ifBlank { Build.MODEL ?: "Perangkat Android" },
            model = Build.MODEL ?: "Android",
            mode = mode,
            status = DeviceStatus.WAITING,
            serverBaseUrl = if (firestore != null) "firebase" else settings.serverBaseUrl.first(),
            lastSeenAt = now,
            createdAt = now
        )
        deviceDao.upsert(local)
        
        val firestoreInst = firestore
        if (firestoreInst != null) {
            return runCatching {
                val deviceData = hashMapOf(
                    "deviceId" to local.deviceId,
                    "displayName" to local.displayName,
                    "model" to local.model,
                    "mode" to local.mode.name,
                    "status" to local.status.name,
                    "lastSeenAt" to now
                )
                firestoreInst.collection("devices").document(local.deviceId).set(deviceData).awaitTask()
            }
        } else {
            val baseUrl = settings.serverBaseUrl.first()
            return runCatching {
                api(baseUrl).registerDevice(
                    RegisterDeviceRequest(local.deviceId, local.displayName, local.model, local.mode)
                )
            }.map { }
        }
    }

    suspend fun refreshClientStatus(deviceId: String): Result<Unit> {
        val firestoreInst = firestore
        if (firestoreInst != null) {
            return runCatching {
                val snapshot = firestoreInst.collection("devices").document(deviceId).get().awaitTask()
                if (snapshot.exists()) {
                    val statusStr = snapshot.getString("status") ?: "WAITING"
                    val status = try { DeviceStatus.valueOf(statusStr) } catch(e: Exception) { DeviceStatus.WAITING }
                    deviceDao.getDevice(deviceId)?.let {
                        deviceDao.upsert(it.copy(status = status, lastSeenAt = now()))
                    }
                }
            }
        } else {
            val baseUrl = settings.serverBaseUrl.first()
            return runCatching {
                val status = api(baseUrl).getDeviceStatus(deviceId).data
                if (status != null) {
                    val clientTime = System.currentTimeMillis()
                    serverTimeOffset = status.serverTime - clientTime
                    val timestamp = clientTime + serverTimeOffset
                    val current = deviceDao.getDevice(deviceId)
                    deviceDao.upsert(
                        current?.copy(status = status.status, lastSeenAt = timestamp)
                            ?: DeviceEntity(
                                deviceId = status.deviceId,
                                displayName = status.deviceId,
                                model = Build.MODEL ?: "Android",
                                mode = DeviceMode.CLIENT,
                                status = status.status,
                                serverBaseUrl = baseUrl,
                                lastSeenAt = timestamp,
                                createdAt = timestamp
                            )
                    )
                    val activeSession = status.activeSession
                    if (status.status == DeviceStatus.ACTIVE && activeSession != null) {
                        stopOtherActiveSessionsForDevice(deviceId, activeSession.sessionId, timestamp)
                        sessionDao.upsert(activeSession.toEntity(synced = true))
                    } else {
                        clearActiveSessionsForDevice(deviceId, status.status, timestamp)
                    }
                }
            }
        }
    }

    suspend fun startSession(
        deviceId: String,
        tariff: TariffEntity,
        operatorId: String
    ): Result<SessionEntity> = runCatching {
        val firestoreInst = firestore
        if (firestoreInst != null) {
            val now = now()
            stopActiveSessionsForDevice(deviceId, now)
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
            addLog(session, BillingEvent.SESSION_STARTED, tariff.priceCents, "Memulai ${tariff.name}")

            runCatching {
                val sessionData = hashMapOf(
                    "sessionId" to session.sessionId,
                    "deviceId" to session.deviceId,
                    "tariffId" to session.tariffId,
                    "operatorId" to session.operatorId,
                    "status" to session.status.name,
                    "startedAt" to session.startedAt,
                    "endsAt" to session.endsAt,
                    "stoppedAt" to null,
                    "extendedMinutes" to session.extendedMinutes,
                    "priceCents" to session.priceCents
                )
                firestoreInst.collection("sessions").document(session.sessionId).set(sessionData).awaitTask()
                firestoreInst.collection("devices").document(deviceId).update("status", DeviceStatus.ACTIVE.name).awaitTask()
                sessionDao.upsert(session.copy(synced = true))
            }
            session
        } else {
            val remote = api(settings.serverBaseUrl.first()).startSession(
                StartSessionRequest(deviceId, tariff.tariffId, operatorId, tariff.minutes, tariff.priceCents)
            ).data ?: error("Server tidak mengembalikan sesi")
            val timestamp = now()
            stopOtherActiveSessionsForDevice(deviceId, remote.sessionId, timestamp)
            val session = remote.toEntity(synced = true)
            sessionDao.upsert(session)
            updateDeviceStatus(deviceId, DeviceStatus.ACTIVE)
            addLog(session, BillingEvent.SESSION_STARTED, tariff.priceCents, "Memulai ${tariff.name}")
            session
        }
    }

    suspend fun stopSession(session: SessionEntity): Result<SessionEntity> = runCatching {
        val firestoreInst = firestore
        if (firestoreInst != null) {
            val stoppedAt = now()
            stopActiveSessionsForDevice(session.deviceId, stoppedAt)
            val updated = session.copy(
                status = SessionStatus.STOPPED,
                stoppedAt = stoppedAt,
                synced = false
            )
            sessionDao.upsert(updated)
            updateDeviceStatus(session.deviceId, DeviceStatus.WAITING)
            addLog(updated, BillingEvent.SESSION_STOPPED, 0, "Dihentikan manual")
            runCatching {
                firestoreInst.collection("sessions").document(session.sessionId)
                    .update(
                        "status", SessionStatus.STOPPED.name,
                        "stoppedAt", stoppedAt
                    ).awaitTask()
                firestoreInst.collection("devices").document(session.deviceId)
                    .update("status", DeviceStatus.WAITING.name).awaitTask()
                sessionDao.upsert(updated.copy(synced = true))
            }
            updated
        } else {
            val remote = api(settings.serverBaseUrl.first()).stopSession(session.sessionId).data
            val stoppedAt = remote?.stoppedAt ?: now()
            val updated = remote?.toEntity(synced = true)
                ?: session.copy(status = SessionStatus.STOPPED, stoppedAt = stoppedAt, synced = true)
            sessionDao.upsert(updated)
            updateDeviceStatus(session.deviceId, DeviceStatus.WAITING)
            addLog(updated, BillingEvent.SESSION_STOPPED, 0, "Dihentikan manual")
            updated
        }
    }

    suspend fun extendSession(session: SessionEntity, minutes: Int, priceCents: Long): Result<SessionEntity> = runCatching {
        val firestoreInst = firestore
        if (firestoreInst != null) {
            val updated = session.copy(
                endsAt = session.endsAt + minutes.minutesToMillis(),
                extendedMinutes = session.extendedMinutes + minutes,
                priceCents = session.priceCents + priceCents,
                synced = false
            )
            sessionDao.upsert(updated)
            addLog(updated, BillingEvent.SESSION_EXTENDED, priceCents, "Ditambah $minutes menit")
            runCatching {
                firestoreInst.collection("sessions").document(session.sessionId)
                    .update(
                        "endsAt", updated.endsAt,
                        "extendedMinutes", updated.extendedMinutes,
                        "priceCents", updated.priceCents
                    ).awaitTask()
                sessionDao.upsert(updated.copy(synced = true))
            }
            updated
        } else {
            val remote = api(settings.serverBaseUrl.first()).extendSession(
                session.sessionId,
                ExtendSessionRequest(minutes, priceCents)
            ).data ?: error("Server tidak mengembalikan sesi")
            val updated = remote.toEntity(synced = true)
            sessionDao.upsert(updated)
            addLog(updated, BillingEvent.SESSION_EXTENDED, priceCents, "Ditambah $minutes menit")
            updated
        }
    }

    suspend fun expireSession(session: SessionEntity): SessionEntity {
        val updated = session.copy(status = SessionStatus.EXPIRED, stoppedAt = now(), synced = false)
        sessionDao.upsert(updated)
        updateDeviceStatus(session.deviceId, DeviceStatus.EXPIRED)
        addLog(updated, BillingEvent.SESSION_EXPIRED, 0, "Waktu sesi habis")
        
        val firestoreInst = firestore
        if (firestoreInst != null) {
            runCatching {
                firestoreInst.collection("sessions").document(session.sessionId)
                    .update(
                        "status", SessionStatus.EXPIRED.name,
                        "stoppedAt", updated.stoppedAt
                    ).awaitTask()
                firestoreInst.collection("devices").document(session.deviceId)
                    .update("status", DeviceStatus.EXPIRED.name).awaitTask()
                sessionDao.upsert(updated.copy(synced = true))
            }
        }
        return updated
    }

    fun startRealtimeSync(deviceId: String) {
        val firestoreInst = firestore ?: return
        stopRealtimeSync()
        
        deviceListener = firestoreInst.collection("devices").document(deviceId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                if (snapshot != null && snapshot.exists()) {
                    val statusStr = snapshot.getString("status") ?: "WAITING"
                    val status = try { DeviceStatus.valueOf(statusStr) } catch(e: Exception) { DeviceStatus.WAITING }
                    val displayName = snapshot.getString("displayName") ?: ""
                    val model = snapshot.getString("model") ?: ""
                    
                    CoroutineScope(Dispatchers.IO).launch {
                        val current = deviceDao.getDevice(deviceId)
                        if (current == null) {
                            deviceDao.upsert(
                                DeviceEntity(
                                    deviceId = deviceId,
                                    displayName = displayName,
                                    model = model,
                                    mode = DeviceMode.CLIENT,
                                    status = status,
                                    serverBaseUrl = "firebase",
                                    lastSeenAt = now(),
                                    createdAt = now()
                                )
                            )
                        } else {
                            deviceDao.upsert(current.copy(status = status, displayName = displayName, model = model, lastSeenAt = now()))
                        }
                        
                        if (status != DeviceStatus.ACTIVE) {
                            stopActiveSessionsForDevice(deviceId, now())
                        }
                    }
                }
            }

        sessionListener = firestoreInst.collection("sessions")
            .whereEqualTo("deviceId", deviceId)
            .whereEqualTo("status", SessionStatus.ACTIVE.name)
            .addSnapshotListener { snapshots, error ->
                if (error != null) return@addSnapshotListener
                if (snapshots != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        val activeDocs = snapshots.documents
                        if (activeDocs.isEmpty()) {
                            val activeSessions = sessionDao.getActiveSessionsForDevice(deviceId)
                            for (session in activeSessions) {
                                sessionDao.upsert(session.copy(status = SessionStatus.STOPPED, stoppedAt = now(), synced = true))
                            }
                        } else {
                            val doc = activeDocs.first()
                            val sessionId = doc.id
                            val tariffId = doc.getString("tariffId") ?: ""
                            val operatorId = doc.getString("operatorId") ?: ""
                            val startedAt = doc.getLong("startedAt") ?: now()
                            val endsAt = doc.getLong("endsAt") ?: now()
                            val extendedMinutes = doc.getLong("extendedMinutes")?.toInt() ?: 0
                            val priceCents = doc.getLong("priceCents") ?: 0L

                            stopOtherActiveSessionsForDevice(deviceId, sessionId, now())
                            
                            val session = SessionEntity(
                                sessionId = sessionId,
                                deviceId = deviceId,
                                tariffId = tariffId,
                                operatorId = operatorId,
                                status = SessionStatus.ACTIVE,
                                startedAt = startedAt,
                                endsAt = endsAt,
                                stoppedAt = null,
                                extendedMinutes = extendedMinutes,
                                priceCents = priceCents,
                                synced = true
                            )
                            sessionDao.upsert(session)
                        }
                    }
                }
            }
    }

    fun stopRealtimeSync() {
        deviceListener?.remove()
        deviceListener = null
        sessionListener?.remove()
        sessionListener = null
    }

    fun startOperatorRealtimeSync() {
        val firestoreInst = firestore ?: return
        stopOperatorRealtimeSync()

        operatorDevicesListener = firestoreInst.collection("devices")
            .addSnapshotListener { snapshots, error ->
                if (error != null) return@addSnapshotListener
                if (snapshots != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        for (doc in snapshots.documents) {
                            val deviceId = doc.id
                            val displayName = doc.getString("displayName") ?: ""
                            val model = doc.getString("model") ?: ""
                            val modeStr = doc.getString("mode") ?: "CLIENT"
                            val mode = try { DeviceMode.valueOf(modeStr) } catch(e: Exception) { DeviceMode.CLIENT }
                            val statusStr = doc.getString("status") ?: "WAITING"
                            val status = try { DeviceStatus.valueOf(statusStr) } catch(e: Exception) { DeviceStatus.WAITING }
                            val lastSeenAt = doc.getLong("lastSeenAt") ?: System.currentTimeMillis()

                            val entity = DeviceEntity(
                                deviceId = deviceId,
                                displayName = displayName,
                                model = model,
                                mode = mode,
                                status = status,
                                serverBaseUrl = "firebase",
                                lastSeenAt = lastSeenAt,
                                createdAt = lastSeenAt
                            )
                            deviceDao.upsert(entity)

                            if (status != DeviceStatus.ACTIVE) {
                                stopActiveSessionsForDevice(deviceId, now())
                            }
                        }
                    }
                }
            }

        operatorSessionsListener = firestoreInst.collection("sessions")
            .addSnapshotListener { snapshots, error ->
                if (error != null) return@addSnapshotListener
                if (snapshots != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        for (doc in snapshots.documents) {
                            val sessionId = doc.id
                            val deviceId = doc.getString("deviceId") ?: ""
                            val tariffId = doc.getString("tariffId") ?: ""
                            val operatorId = doc.getString("operatorId") ?: ""
                            val statusStr = doc.getString("status") ?: "ACTIVE"
                            val status = try { SessionStatus.valueOf(statusStr) } catch(e: Exception) { SessionStatus.ACTIVE }
                            val startedAt = doc.getLong("startedAt") ?: System.currentTimeMillis()
                            val endsAt = doc.getLong("endsAt") ?: System.currentTimeMillis()
                            val stoppedAt = doc.getLong("stoppedAt")
                            val extendedMinutes = doc.getLong("extendedMinutes")?.toInt() ?: 0
                            val priceCents = doc.getLong("priceCents") ?: 0L

                            val session = SessionEntity(
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
                                synced = true
                            )
                            sessionDao.upsert(session)
                        }
                    }
                }
            }
    }

    fun stopOperatorRealtimeSync() {
        operatorDevicesListener?.remove()
        operatorDevicesListener = null
        operatorSessionsListener?.remove()
        operatorSessionsListener = null
    }

    suspend fun syncPendingLogs(): Result<Unit> = runCatching {
        val sessions = sessionDao.unsyncedSessions()
        val logs = billingLogDao.unsyncedLogs()
        if (sessions.isEmpty() && logs.isEmpty()) return@runCatching

        val firestoreInst = firestore
        if (firestoreInst != null) {
            for (session in sessions) {
                val sessionData = hashMapOf(
                    "sessionId" to session.sessionId,
                    "deviceId" to session.deviceId,
                    "tariffId" to session.tariffId,
                    "operatorId" to session.operatorId,
                    "status" to session.status.name,
                    "startedAt" to session.startedAt,
                    "endsAt" to session.endsAt,
                    "stoppedAt" to session.stoppedAt,
                    "extendedMinutes" to session.extendedMinutes,
                    "priceCents" to session.priceCents
                )
                firestoreInst.collection("sessions").document(session.sessionId).set(sessionData).awaitTask()
                sessionDao.markSynced(listOf(session.sessionId))
            }

            for (log in logs) {
                val logData = hashMapOf(
                    "logId" to log.logId,
                    "sessionId" to log.sessionId,
                    "deviceId" to log.deviceId,
                    "event" to log.event.name,
                    "amountCents" to log.amountCents,
                    "occurredAt" to log.occurredAt,
                    "details" to log.details
                )
                firestoreInst.collection("billing_logs").document(log.logId).set(logData).awaitTask()
                billingLogDao.markSynced(listOf(log.logId))
            }
        } else {
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
    }

    suspend fun syncDevicesAndLogs(): Result<Unit> = runCatching {
        val firestoreInst = firestore
        if (firestoreInst != null) {
            val snapshot = firestoreInst.collection("devices").get().awaitTask()
            for (doc in snapshot.documents) {
                val deviceId = doc.id
                val displayName = doc.getString("displayName") ?: ""
                val model = doc.getString("model") ?: ""
                val modeStr = doc.getString("mode") ?: "CLIENT"
                val mode = try { DeviceMode.valueOf(modeStr) } catch(e: Exception) { DeviceMode.CLIENT }
                val statusStr = doc.getString("status") ?: "WAITING"
                val status = try { DeviceStatus.valueOf(statusStr) } catch(e: Exception) { DeviceStatus.WAITING }
                val lastSeenAt = doc.getLong("lastSeenAt") ?: System.currentTimeMillis()

                val entity = DeviceEntity(
                    deviceId = deviceId,
                    displayName = displayName,
                    model = model,
                    mode = mode,
                    status = status,
                    serverBaseUrl = "firebase",
                    lastSeenAt = lastSeenAt,
                    createdAt = lastSeenAt
                )
                deviceDao.upsert(entity)

                if (status != DeviceStatus.ACTIVE) {
                    stopActiveSessionsForDevice(deviceId, now())
                }
            }
        } else {
            val baseUrl = settings.serverBaseUrl.first()
            api(baseUrl).getDevices().data.orEmpty().forEach {
                deviceDao.upsert(it.toEntity(baseUrl))
                clearActiveSessionsForDevice(it.deviceId, it.status, now())
            }
        }
        syncPendingLogs().getOrThrow()
    }

    private suspend fun stopActiveSessionsForDevice(deviceId: String, stoppedAt: Long) {
        sessionDao.getActiveSessionsForDevice(deviceId).forEach {
            sessionDao.upsert(it.copy(status = SessionStatus.STOPPED, stoppedAt = stoppedAt, synced = false))
        }
    }

    private suspend fun clearActiveSessionsForDevice(deviceId: String, status: DeviceStatus, stoppedAt: Long) {
        if (status == DeviceStatus.ACTIVE) return
        val sessionStatus = when (status) {
            DeviceStatus.EXPIRED -> SessionStatus.EXPIRED
            DeviceStatus.WAITING, DeviceStatus.OFFLINE -> SessionStatus.STOPPED
            DeviceStatus.ACTIVE -> SessionStatus.ACTIVE
        }
        sessionDao.getActiveSessionsForDevice(deviceId).forEach {
            sessionDao.upsert(it.copy(status = sessionStatus, stoppedAt = stoppedAt, synced = true))
        }
    }

    private suspend fun stopOtherActiveSessionsForDevice(deviceId: String, keepSessionId: String, stoppedAt: Long) {
        sessionDao.getActiveSessionsForDevice(deviceId).filterNot { it.sessionId == keepSessionId }.forEach {
            sessionDao.upsert(it.copy(status = SessionStatus.STOPPED, stoppedAt = stoppedAt, synced = true))
        }
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

    private fun api(baseUrl: String) = apiFactory?.create(baseUrl) ?: error("API Provider not configured")
    fun now() = System.currentTimeMillis() + serverTimeOffset
}

private fun Int.minutesToMillis(): Long = this * 60_000L

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            cont.resume(task.result)
        } else {
            cont.resumeWithException(task.exception ?: Exception("Task failed"))
        }
    }
}

private fun DeviceDto.toEntity(serverBaseUrl: String) = DeviceEntity(
    deviceId = deviceId,
    displayName = displayName,
    model = model,
    mode = mode,
    status = status,
    serverBaseUrl = serverBaseUrl,
    lastSeenAt = lastSeenAt,
    createdAt = lastSeenAt
)

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
