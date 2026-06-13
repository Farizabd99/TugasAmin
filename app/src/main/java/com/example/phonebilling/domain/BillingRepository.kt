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
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

@Singleton
class BillingRepository @Inject constructor(
    private val settings: AppSettings,
    private val firestore: FirebaseFirestore,
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
            serverBaseUrl = "firebase",
            lastSeenAt = now,
            createdAt = now
        )
        deviceDao.upsert(local)
        return runCatching {
            val deviceData = hashMapOf(
                "deviceId" to local.deviceId,
                "displayName" to local.displayName,
                "model" to local.model,
                "mode" to local.mode.name,
                "status" to local.status.name,
                "lastSeenAt" to now
            )
            firestore.collection("devices").document(local.deviceId).set(deviceData).awaitTask()
        }
    }

    suspend fun refreshClientStatus(deviceId: String): Result<Unit> {
        return runCatching {
            val snapshot = firestore.collection("devices").document(deviceId).get().awaitTask()
            if (snapshot.exists()) {
                val statusStr = snapshot.getString("status") ?: "WAITING"
                val status = try { DeviceStatus.valueOf(statusStr) } catch(e: Exception) { DeviceStatus.WAITING }
                deviceDao.getDevice(deviceId)?.let {
                    deviceDao.upsert(it.copy(status = status, lastSeenAt = now()))
                }
            }
        }
    }

    suspend fun startSession(
        deviceId: String,
        tariff: TariffEntity,
        operatorId: String
    ): SessionEntity {
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
            firestore.collection("sessions").document(session.sessionId).set(sessionData).awaitTask()
            firestore.collection("devices").document(deviceId).update("status", DeviceStatus.ACTIVE.name).awaitTask()
            sessionDao.upsert(session.copy(synced = true))
        }
        return session
    }

    suspend fun stopSession(session: SessionEntity): SessionEntity {
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
            firestore.collection("sessions").document(session.sessionId)
                .update(
                    "status", SessionStatus.STOPPED.name,
                    "stoppedAt", stoppedAt
                ).awaitTask()
            firestore.collection("devices").document(session.deviceId)
                .update("status", DeviceStatus.WAITING.name).awaitTask()
            sessionDao.upsert(updated.copy(synced = true))
        }
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
        addLog(updated, BillingEvent.SESSION_EXTENDED, priceCents, "Ditambah $minutes menit")
        runCatching {
            firestore.collection("sessions").document(session.sessionId)
                .update(
                    "endsAt", updated.endsAt,
                    "extendedMinutes", updated.extendedMinutes,
                    "priceCents", updated.priceCents
                ).awaitTask()
            sessionDao.upsert(updated.copy(synced = true))
        }
        return updated
    }

    suspend fun expireSession(session: SessionEntity): SessionEntity {
        val updated = session.copy(status = SessionStatus.EXPIRED, stoppedAt = now(), synced = false)
        sessionDao.upsert(updated)
        updateDeviceStatus(session.deviceId, DeviceStatus.EXPIRED)
        addLog(updated, BillingEvent.SESSION_EXPIRED, 0, "Waktu sesi habis")
        runCatching {
            firestore.collection("sessions").document(session.sessionId)
                .update(
                    "status", SessionStatus.EXPIRED.name,
                    "stoppedAt", updated.stoppedAt
                ).awaitTask()
            firestore.collection("devices").document(session.deviceId)
                .update("status", DeviceStatus.EXPIRED.name).awaitTask()
            sessionDao.upsert(updated.copy(synced = true))
        }
        return updated
    }

    fun startRealtimeSync(deviceId: String) {
        stopRealtimeSync()
        
        deviceListener = firestore.collection("devices").document(deviceId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                if (snapshot != null && snapshot.exists()) {
                    val statusStr = snapshot.getString("status") ?: "WAITING"
                    val status = try { DeviceStatus.valueOf(statusStr) } catch(e: Exception) { DeviceStatus.WAITING }
                    val displayName = snapshot.getString("displayName") ?: ""
                    val model = snapshot.getString("model") ?: ""
                    val modeStr = snapshot.getString("mode") ?: "CLIENT"
                    val mode = try { DeviceMode.valueOf(modeStr) } catch(e: Exception) { DeviceMode.CLIENT }
                    val lastSeenAt = snapshot.getLong("lastSeenAt") ?: System.currentTimeMillis()

                    CoroutineScope(Dispatchers.IO).launch {
                        deviceDao.getDevice(deviceId)?.let { localDevice ->
                            deviceDao.upsert(localDevice.copy(
                                status = status,
                                displayName = displayName,
                                model = model,
                                mode = mode,
                                lastSeenAt = lastSeenAt
                            ))
                        }
                    }
                }
            }

        sessionListener = firestore.collection("sessions")
            .whereEqualTo("deviceId", deviceId)
            .whereEqualTo("status", "ACTIVE")
            .addSnapshotListener { snapshots, error ->
                if (error != null) return@addSnapshotListener
                if (snapshots != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        if (snapshots.isEmpty) {
                            stopActiveSessionsForDevice(deviceId, System.currentTimeMillis())
                        } else {
                            for (doc in snapshots.documents) {
                                val sessionId = doc.id
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
    }

    fun stopRealtimeSync() {
        deviceListener?.remove()
        deviceListener = null
        sessionListener?.remove()
        sessionListener = null
    }

    fun startOperatorRealtimeSync() {
        stopOperatorRealtimeSync()

        operatorDevicesListener = firestore.collection("devices")
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

        operatorSessionsListener = firestore.collection("sessions")
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
            firestore.collection("sessions").document(session.sessionId).set(sessionData).awaitTask()
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
            firestore.collection("billing_logs").document(log.logId).set(logData).awaitTask()
            billingLogDao.markSynced(listOf(log.logId))
        }
    }

    suspend fun syncDevicesAndLogs(): Result<Unit> = runCatching {
        val snapshot = firestore.collection("devices").get().awaitTask()
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
        syncPendingLogs().getOrThrow()
    }

    private suspend fun stopActiveSessionsForDevice(deviceId: String, stoppedAt: Long) {
        sessionDao.getActiveSessionsForDevice(deviceId).forEach {
            sessionDao.upsert(it.copy(status = SessionStatus.STOPPED, stoppedAt = stoppedAt, synced = false))
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

    private fun now() = System.currentTimeMillis()
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
