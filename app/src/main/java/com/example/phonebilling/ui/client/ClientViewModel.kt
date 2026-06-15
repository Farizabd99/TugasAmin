package com.example.phonebilling.ui.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.phonebilling.data.local.entity.DeviceMode
import com.example.phonebilling.data.local.entity.DeviceStatus
import com.example.phonebilling.data.local.entity.SessionEntity
import com.example.phonebilling.domain.BillingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ClientUiState(
    val deviceId: String = "",
    val status: DeviceStatus = DeviceStatus.WAITING,
    val activeSession: SessionEntity? = null,
    val remainingMillis: Long = 0,
    val serverOnline: Boolean = true,
    val syncing: Boolean = false,
    val message: String? = null
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ClientViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val repository: BillingRepository
) : ViewModel() {
    private val ticker = MutableStateFlow(System.currentTimeMillis())
    private val serverOnline = MutableStateFlow(true)
    private val syncing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<ClientUiState> = repository.observeDeviceId()
        .flatMapLatest { deviceId ->
            val clientData = combine(
                repository.observeActiveSessionForDevice(deviceId),
                repository.observeDevices(),
                ticker
            ) { session, devices, now ->
                val status = devices.firstOrNull { it.deviceId == deviceId }?.status ?: DeviceStatus.WAITING
                ClientUiState(
                    deviceId = deviceId,
                    status = status,
                    activeSession = session,
                    remainingMillis = (session?.endsAt ?: now) - now
                )
            }
            combine(
                clientData,
                serverOnline,
                syncing,
                message
            ) { data, online, isSyncing, currentMessage ->
                data.copy(serverOnline = online, syncing = isSyncing, message = currentMessage)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClientUiState())

    init {
        viewModelScope.launch {
            repository.registerCurrentDevice("Ponsel klien", DeviceMode.CLIENT)
        }
        viewModelScope.launch {
            repository.observeDeviceId().collect { deviceId ->
                if (deviceId.isNotBlank()) {
                    repository.startRealtimeSync(deviceId)
                }
            }
        }
        viewModelScope.launch {
            var lastStatus: DeviceStatus? = null
            repository.observeDeviceId().collect { deviceId ->
                if (deviceId.isNotBlank()) {
                    repository.observeDevices().collect { devices ->
                        val status = devices.firstOrNull { it.deviceId == deviceId }?.status
                        android.util.Log.d("ClientViewModel", "Status change collection: deviceId=$deviceId, lastStatus=$lastStatus, currentStatus=$status")
                        if (lastStatus == DeviceStatus.ACTIVE && (status == DeviceStatus.WAITING || status == DeviceStatus.EXPIRED)) {
                            android.util.Log.d("ClientViewModel", "Transition from ACTIVE detected. Bringing app to foreground.")
                            bringAppToForeground()
                        }
                        lastStatus = status
                    }
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                ticker.value = System.currentTimeMillis()
                state.value.activeSession?.let {
                    if (state.value.remainingMillis <= 0) repository.expireSession(it)
                }
                delay(1_000)
            }
        }
    }

    private fun bringAppToForeground() {
        runCatching {
            val intent = android.content.Intent(context, com.example.phonebilling.MainActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            val deviceId = state.value.deviceId
            if (deviceId.isBlank()) return@launch
            syncing.value = true
            message.value = null
            val result = repository.refreshClientStatus(deviceId)
            if (result.isSuccess) repository.syncPendingLogs()
            serverOnline.value = result.isSuccess
            syncing.value = false
            message.value = if (result.isSuccess) "Status berhasil diperbarui" else "Gagal memperbarui status"
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.stopRealtimeSync()
    }
}
