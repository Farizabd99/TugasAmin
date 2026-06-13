package com.example.phonebilling.ui.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.phonebilling.data.local.entity.DeviceMode
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
    val activeSession: SessionEntity? = null,
    val remainingMillis: Long = 0,
    val serverOnline: Boolean = true
)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ClientViewModel @Inject constructor(
    private val repository: BillingRepository
) : ViewModel() {
    private val ticker = MutableStateFlow(System.currentTimeMillis())
    private val serverOnline = MutableStateFlow(true)

    val state: StateFlow<ClientUiState> = repository.observeDeviceId()
        .flatMapLatest { deviceId ->
            combine(
                repository.observeActiveSessionForDevice(deviceId),
                ticker,
                serverOnline
            ) { session, now, online ->
                ClientUiState(
                    deviceId = deviceId,
                    activeSession = session,
                    remainingMillis = (session?.endsAt ?: now) - now,
                    serverOnline = online
                )
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
            while (true) {
                ticker.value = System.currentTimeMillis()
                state.value.activeSession?.let {
                    if (state.value.remainingMillis <= 0) repository.expireSession(it)
                }
                delay(1_000)
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            serverOnline.value = repository.syncPendingLogs().isSuccess
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.stopRealtimeSync()
    }
}
