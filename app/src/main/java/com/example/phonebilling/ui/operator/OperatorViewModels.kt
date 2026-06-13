package com.example.phonebilling.ui.operator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.phonebilling.data.local.entity.DeviceEntity
import com.example.phonebilling.data.local.entity.DeviceMode
import com.example.phonebilling.data.local.entity.OperatorEntity
import com.example.phonebilling.data.local.entity.SessionEntity
import com.example.phonebilling.data.local.entity.TariffEntity
import com.example.phonebilling.domain.BillingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LoginUiState(
    val username: String = "admin",
    val password: String = "admin123",
    val loading: Boolean = false,
    val error: String? = null,
    val operator: OperatorEntity? = null
)

@HiltViewModel
class OperatorLoginViewModel @Inject constructor(
    private val repository: BillingRepository
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state

    init {
        viewModelScope.launch { repository.ensureDefaults() }
    }

    fun updateUsername(value: String) = update { copy(username = value, error = null) }
    fun updatePassword(value: String) = update { copy(password = value, error = null) }

    fun login() {
        viewModelScope.launch {
            update { copy(loading = true, error = null) }
            val operator = repository.login(state.value.username, state.value.password)
            update {
                copy(
                    loading = false,
                    operator = operator,
                    error = if (operator == null) "Nama pengguna atau kata sandi tidak sesuai" else null
                )
            }
        }
    }

    private fun update(block: LoginUiState.() -> LoginUiState) {
        _state.value = _state.value.block()
    }
}

data class DashboardUiState(
    val devices: List<DeviceEntity> = emptyList(),
    val activeSessions: List<SessionEntity> = emptyList(),
    val history: List<SessionEntity> = emptyList(),
    val revenueCents: Long = 0,
    val syncing: Boolean = false,
    val syncMessage: String? = null
)

@HiltViewModel
class OperatorDashboardViewModel @Inject constructor(
    private val repository: BillingRepository
) : ViewModel() {
    private val syncing = MutableStateFlow(false)
    private val syncMessage = MutableStateFlow<String?>(null)

    private val dashboardData = combine(
        repository.observeDevices(),
        repository.observeActiveSessions(),
        repository.observeSessions(),
        repository.observeRevenue()
    ) { devices, active, history, revenue ->
        DashboardUiState(devices, active, history, revenue)
    }

    val state: StateFlow<DashboardUiState> = combine(
        dashboardData,
        syncing,
        syncMessage
    ) { dashboard, isSyncing, message ->
        dashboard.copy(syncing = isSyncing, syncMessage = message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    init {
        repository.startOperatorRealtimeSync()
    }

    fun registerOperatorDevice() {
        viewModelScope.launch {
            repository.registerCurrentDevice("Stasiun operator", DeviceMode.OPERATOR)
        }
    }

    fun sync() {
        viewModelScope.launch {
            syncing.value = true
            syncMessage.value = null
            val result = repository.syncDevicesAndLogs()
            syncing.value = false
            syncMessage.value = if (result.isSuccess) "Sinkron selesai" else "Sinkron gagal"
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.stopOperatorRealtimeSync()
    }
}

data class StartSessionUiState(
    val devices: List<DeviceEntity> = emptyList(),
    val tariffs: List<TariffEntity> = emptyList(),
    val selectedDeviceId: String? = null,
    val selectedTariffId: String? = null,
    val lastStartedSessionId: String? = null
)

@HiltViewModel
class StartSessionViewModel @Inject constructor(
    private val repository: BillingRepository
) : ViewModel() {
    private val selectedDeviceId = MutableStateFlow<String?>(null)
    private val selectedTariffId = MutableStateFlow<String?>(null)
    private val lastStarted = MutableStateFlow<String?>(null)

    val state: StateFlow<StartSessionUiState> = combine(
        repository.observeDevices(),
        repository.observeTariffs(),
        selectedDeviceId,
        selectedTariffId,
        lastStarted
    ) { devices, tariffs, deviceId, tariffId, last ->
        StartSessionUiState(devices, tariffs, deviceId ?: devices.firstOrNull()?.deviceId, tariffId ?: tariffs.firstOrNull()?.tariffId, last)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StartSessionUiState())

    fun selectDevice(deviceId: String) { selectedDeviceId.value = deviceId }
    fun selectTariff(tariffId: String) { selectedTariffId.value = tariffId }

    fun start(operatorId: String = "operator-default") {
        viewModelScope.launch {
            val current = state.value
            val deviceId = current.selectedDeviceId ?: return@launch
            val tariff = current.tariffs.firstOrNull { it.tariffId == current.selectedTariffId } ?: return@launch
            lastStarted.value = repository.startSession(deviceId, tariff, operatorId).sessionId
        }
    }
}

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: BillingRepository
) : ViewModel() {
    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])

    val session: StateFlow<SessionEntity?> = repository.observeSessions()
        .map { sessions -> sessions.firstOrNull { it.sessionId == sessionId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun stop(session: SessionEntity) {
        viewModelScope.launch { repository.stopSession(session) }
    }

    fun extend(session: SessionEntity, minutes: Int = 30, priceCents: Long = 5000) {
        viewModelScope.launch { repository.extendSession(session, minutes, priceCents) }
    }
}

data class SettingsUiState(
    val serverUrl: String = "",
    val saved: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: BillingRepository
) : ViewModel() {
    private val editedUrl = MutableStateFlow<String?>(null)
    private val saved = MutableStateFlow(false)

    val state: StateFlow<SettingsUiState> = combine(
        repository.observeServerUrl(),
        editedUrl,
        saved
    ) { persisted, edited, wasSaved ->
        SettingsUiState(edited ?: persisted, wasSaved)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun updateUrl(value: String) {
        editedUrl.value = value
        saved.value = false
    }

    fun save() {
        viewModelScope.launch {
            repository.saveServerUrl(state.value.serverUrl)
            saved.value = true
        }
    }
}
