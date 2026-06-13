package com.example.phonebilling.domain

import com.example.phonebilling.data.local.BillingSettings
import com.example.phonebilling.data.local.dao.BillingLogDao
import com.example.phonebilling.data.local.dao.DeviceDao
import com.example.phonebilling.data.local.dao.OperatorDao
import com.example.phonebilling.data.local.dao.SessionDao
import com.example.phonebilling.data.local.dao.TariffDao
import com.example.phonebilling.data.local.entity.BillingLogEntity
import com.example.phonebilling.data.local.entity.DeviceEntity
import com.example.phonebilling.data.local.entity.DeviceMode
import com.example.phonebilling.data.local.entity.DeviceStatus
import com.example.phonebilling.data.local.entity.OperatorEntity
import com.example.phonebilling.data.local.entity.SessionEntity
import com.example.phonebilling.data.local.entity.SessionStatus
import com.example.phonebilling.data.local.entity.TariffEntity
import com.example.phonebilling.data.remote.ApiEnvelope
import com.example.phonebilling.data.remote.BillingApi
import com.example.phonebilling.data.remote.BillingApiProvider
import com.example.phonebilling.data.remote.DeviceDto
import com.example.phonebilling.data.remote.DeviceStatusDto
import com.example.phonebilling.data.remote.ExtendSessionRequest
import com.example.phonebilling.data.remote.RegisterDeviceRequest
import com.example.phonebilling.data.remote.SessionDto
import com.example.phonebilling.data.remote.StartSessionRequest
import com.example.phonebilling.data.remote.SyncBillingLogsRequest
import com.example.phonebilling.data.remote.SyncResultDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingRepositoryTest {
    @Test
    fun startSessionPersistsOnlyAfterBackendSuccess() = runTest {
        val api = FakeBillingApi()
        api.startResponse = SessionDto(
            sessionId = "server-session",
            deviceId = "client-1",
            tariffId = "tariff-30",
            operatorId = "operator-default",
            status = SessionStatus.ACTIVE,
            startedAt = 1_000,
            endsAt = 1_801_000,
            stoppedAt = null,
            extendedMinutes = 0,
            priceCents = 5_000
        )
        val sessionDao = FakeSessionDao()
        val repository = repository(api = api, sessionDao = sessionDao)

        val result = repository.startSession(
            deviceId = "client-1",
            tariff = TariffEntity("tariff-30", "30 menit", 30, 5_000, true),
            operatorId = "operator-default"
        )

        assertTrue(result.isSuccess)
        assertEquals("server-session", result.getOrThrow().sessionId)
        assertEquals(listOf("server-session"), sessionDao.sessions.value.map { it.sessionId })
        assertEquals(1, api.startCalls)
    }

    @Test
    fun startSessionDoesNotCreateLocalSessionWhenBackendFails() = runTest {
        val api = FakeBillingApi().apply { failStart = true }
        val sessionDao = FakeSessionDao()
        val repository = repository(api = api, sessionDao = sessionDao)

        val result = repository.startSession(
            deviceId = "client-1",
            tariff = TariffEntity("tariff-30", "30 menit", 30, 5_000, true),
            operatorId = "operator-default"
        )

        assertTrue(result.isFailure)
        assertEquals(emptyList<SessionEntity>(), sessionDao.sessions.value)
    }

    @Test
    fun refreshClientStatusClearsActiveSessionWhenBackendReportsWaiting() = runTest {
        val api = FakeBillingApi()
        api.statusResponse = DeviceStatusDto(
            deviceId = "client-1",
            status = DeviceStatus.WAITING,
            activeSession = null,
            serverTime = 2_000
        )
        val sessionDao = FakeSessionDao(
            listOf(
                SessionEntity(
                    sessionId = "local-active",
                    deviceId = "client-1",
                    tariffId = "tariff-30",
                    operatorId = "operator-default",
                    status = SessionStatus.ACTIVE,
                    startedAt = 1_000,
                    endsAt = 1_801_000,
                    stoppedAt = null,
                    extendedMinutes = 0,
                    priceCents = 5_000,
                    synced = false
                )
            )
        )
        val repository = repository(api = api, sessionDao = sessionDao)

        val result = repository.refreshClientStatus("client-1")

        assertTrue(result.isSuccess)
        assertEquals(SessionStatus.STOPPED, sessionDao.sessions.value.single().status)
        assertEquals(DeviceStatus.WAITING, repositoryDeviceStatus(repository = repository))
    }

    private fun repository(
        api: FakeBillingApi,
        sessionDao: FakeSessionDao,
        deviceDao: FakeDeviceDao = FakeDeviceDao(
            listOf(
                DeviceEntity(
                    deviceId = "client-1",
                    displayName = "Client 1",
                    model = "Pixel",
                    mode = DeviceMode.CLIENT,
                    status = DeviceStatus.WAITING,
                    serverBaseUrl = "http://localhost:8080/",
                    lastSeenAt = 0,
                    createdAt = 0
                )
            )
        )
    ): BillingRepository = BillingRepository(
        settings = FakeSettings(),
        apiFactory = FakeApiProvider(api),
        deviceDao = deviceDao,
        sessionDao = sessionDao,
        tariffDao = FakeTariffDao(),
        operatorDao = FakeOperatorDao(),
        billingLogDao = FakeBillingLogDao()
    )

    private fun repositoryDeviceStatus(repository: BillingRepository): DeviceStatus {
        val deviceDao = repository.javaClass.getDeclaredField("deviceDao").apply { isAccessible = true }
            .get(repository) as FakeDeviceDao
        return deviceDao.devices.value.single { it.deviceId == "client-1" }.status
    }
}

private class FakeSettings : BillingSettings {
    override val serverBaseUrl = MutableStateFlow("http://localhost:8080/")
    override val deviceId = MutableStateFlow("client-1")
    override suspend fun setServerBaseUrl(value: String) {
        serverBaseUrl.value = value
    }
}

private class FakeApiProvider(private val api: BillingApi) : BillingApiProvider {
    override fun create(baseUrl: String): BillingApi = api
}

private class FakeBillingApi : BillingApi {
    var startCalls = 0
    var failStart = false
    var startResponse: SessionDto? = null
    var statusResponse = DeviceStatusDto("client-1", DeviceStatus.WAITING, null, 0)

    override suspend fun registerDevice(request: RegisterDeviceRequest): ApiEnvelope<DeviceDto> =
        ApiEnvelope(DeviceDto(request.deviceId, request.displayName, request.model, request.mode, DeviceStatus.WAITING, 0))

    override suspend fun getDevices(): ApiEnvelope<List<DeviceDto>> = ApiEnvelope(emptyList())

    override suspend fun getDeviceStatus(deviceId: String): ApiEnvelope<DeviceStatusDto> = ApiEnvelope(statusResponse)

    override suspend fun startSession(request: StartSessionRequest): ApiEnvelope<SessionDto> {
        startCalls += 1
        if (failStart) error("backend down")
        return ApiEnvelope(checkNotNull(startResponse))
    }

    override suspend fun stopSession(sessionId: String): ApiEnvelope<SessionDto> = error("not used")

    override suspend fun extendSession(sessionId: String, request: ExtendSessionRequest): ApiEnvelope<SessionDto> =
        error("not used")

    override suspend fun syncBillingLogs(request: SyncBillingLogsRequest): ApiEnvelope<SyncResultDto> =
        ApiEnvelope(SyncResultDto(emptyList(), emptyList()))
}

private class FakeDeviceDao(initial: List<DeviceEntity> = emptyList()) : DeviceDao {
    val devices = MutableStateFlow(initial)
    override fun observeDevices(): Flow<List<DeviceEntity>> = devices
    override fun observeDevicesByStatus(status: DeviceStatus): Flow<List<DeviceEntity>> =
        devices.map { list -> list.filter { it.status == status } }

    override fun observeDevice(deviceId: String): Flow<DeviceEntity?> =
        devices.map { list -> list.firstOrNull { it.deviceId == deviceId } }

    override suspend fun getDevice(deviceId: String): DeviceEntity? =
        devices.value.firstOrNull { it.deviceId == deviceId }

    override suspend fun upsert(device: DeviceEntity) {
        devices.value = devices.value.filterNot { it.deviceId == device.deviceId } + device
    }
}

private class FakeSessionDao(initial: List<SessionEntity> = emptyList()) : SessionDao {
    val sessions = MutableStateFlow(initial)
    override fun observeSessions(): Flow<List<SessionEntity>> = sessions
    override fun observeSessionsByStatus(status: SessionStatus): Flow<List<SessionEntity>> =
        sessions.map { list -> list.filter { it.status == status } }

    override fun observeSession(sessionId: String): Flow<SessionEntity?> =
        sessions.map { list -> list.firstOrNull { it.sessionId == sessionId } }

    override fun observeActiveSessionForDevice(deviceId: String): Flow<SessionEntity?> =
        sessions.map { list -> list.firstOrNull { it.deviceId == deviceId && it.status == SessionStatus.ACTIVE } }

    override suspend fun getActiveSessionForDevice(deviceId: String): SessionEntity? =
        sessions.value.firstOrNull { it.deviceId == deviceId && it.status == SessionStatus.ACTIVE }

    override suspend fun getActiveSessionsForDevice(deviceId: String): List<SessionEntity> =
        sessions.value.filter { it.deviceId == deviceId && it.status == SessionStatus.ACTIVE }

    override suspend fun unsyncedSessions(): List<SessionEntity> = sessions.value.filterNot { it.synced }

    override suspend fun upsert(session: SessionEntity) {
        sessions.value = sessions.value.filterNot { it.sessionId == session.sessionId } + session
    }

    override suspend fun markSynced(sessionIds: List<String>) {
        sessions.value = sessions.value.map {
            if (it.sessionId in sessionIds) it.copy(synced = true) else it
        }
    }
}

private class FakeTariffDao : TariffDao {
    override fun observeActiveTariffs(): Flow<List<TariffEntity>> = MutableStateFlow(emptyList())
    override suspend fun getTariff(tariffId: String): TariffEntity? = null
    override suspend fun upsert(tariff: TariffEntity) = Unit
}

private class FakeOperatorDao : OperatorDao {
    override suspend fun findActiveByUsername(username: String): OperatorEntity? = null
    override suspend fun count(): Int = 1
    override suspend fun upsert(operator: OperatorEntity) = Unit
}

private class FakeBillingLogDao : BillingLogDao {
    val logs = MutableStateFlow(emptyList<BillingLogEntity>())
    override fun observeLogs(): Flow<List<BillingLogEntity>> = logs
    override suspend fun unsyncedLogs(): List<BillingLogEntity> = logs.value.filterNot { it.synced }
    override fun observeTotalRevenueCents(): Flow<Long> = logs.map { list -> list.sumOf { it.amountCents } }
    override suspend fun upsert(log: BillingLogEntity) {
        logs.value = logs.value.filterNot { it.logId == log.logId } + log
    }

    override suspend fun markSynced(logIds: List<String>) {
        logs.value = logs.value.map {
            if (it.logId in logIds) it.copy(synced = true) else it
        }
    }
}
