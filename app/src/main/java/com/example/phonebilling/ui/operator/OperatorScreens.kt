package com.example.phonebilling.ui.operator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.phonebilling.data.local.entity.DeviceEntity
import com.example.phonebilling.data.local.entity.SessionEntity
import com.example.phonebilling.ui.common.MetricCard
import com.example.phonebilling.ui.common.PrimaryButton
import com.example.phonebilling.ui.common.ScreenScaffold
import com.example.phonebilling.ui.common.SecondaryButton
import com.example.phonebilling.ui.common.toClock
import com.example.phonebilling.ui.common.toCountdown
import com.example.phonebilling.ui.common.toRupiah

@Composable
fun OperatorLoginScreen(
    onLoggedIn: () -> Unit,
    openClient: () -> Unit,
    viewModel: OperatorLoginViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.operator) {
        if (state.operator != null) onLoggedIn()
    }
    ScreenScaffold(title = "Operator Login", subtitle = "Restricted phone billing console") {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::updateUsername,
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::updatePassword,
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            PrimaryButton("Login", viewModel::login, Modifier.fillMaxWidth(), enabled = !state.loading)
            SecondaryButton("Open Client Mode", openClient, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun OperatorDashboardScreen(
    openDevices: () -> Unit,
    openStartSession: () -> Unit,
    openHistory: () -> Unit,
    openSettings: () -> Unit,
    openSession: (String) -> Unit,
    viewModel: OperatorDashboardViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    ScreenScaffold(
        title = "Dashboard",
        subtitle = "Active phones and billing overview",
        actions = { SecondaryButton("Settings", openSettings) }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("Devices", state.devices.size.toString(), Modifier.weight(1f))
                MetricCard("Active", state.activeSessions.size.toString(), Modifier.weight(1f))
            }
            MetricCard("Revenue", state.revenueCents.toRupiah(), Modifier.fillMaxWidth())
            PrimaryButton("Start Session", openStartSession, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton("Devices", openDevices, Modifier.weight(1f))
                SecondaryButton("History", openHistory, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton("Register This Device", viewModel::registerOperatorDevice, Modifier.weight(1f))
                SecondaryButton("Sync", viewModel::sync, Modifier.weight(1f))
            }
            Text("Active devices", style = MaterialTheme.typography.titleLarge)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.activeSessions) { session ->
                    SessionRow(session, onClick = { openSession(session.sessionId) })
                }
            }
        }
    }
}

@Composable
fun DeviceListScreen(viewModel: OperatorDashboardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    ScreenScaffold(title = "Device List", subtitle = "Registered and recently seen phones") {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.devices) { DeviceRow(it) }
        }
    }
}

@Composable
fun StartSessionScreen(
    openSession: (String) -> Unit,
    viewModel: StartSessionViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.lastStartedSessionId) {
        state.lastStartedSessionId?.let(openSession)
    }
    ScreenScaffold(title = "Start Session", subtitle = "Choose a device and tariff") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Device", style = MaterialTheme.typography.titleLarge)
            state.devices.forEach {
                SelectionRow(
                    title = it.displayName,
                    subtitle = "${it.status} • ${it.model}",
                    selected = it.deviceId == state.selectedDeviceId,
                    onClick = { viewModel.selectDevice(it.deviceId) }
                )
            }
            Text("Tariff", style = MaterialTheme.typography.titleLarge)
            state.tariffs.forEach {
                SelectionRow(
                    title = it.name,
                    subtitle = it.priceCents.toRupiah(),
                    selected = it.tariffId == state.selectedTariffId,
                    onClick = { viewModel.selectTariff(it.tariffId) }
                )
            }
            PrimaryButton("Start", viewModel::start, Modifier.fillMaxWidth(), enabled = state.selectedDeviceId != null && state.selectedTariffId != null)
        }
    }
}

@Composable
fun ActiveSessionDetailScreen(viewModel: SessionDetailViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsState()
    ScreenScaffold(title = "Active Session", subtitle = session?.deviceId ?: "Session not found") {
        session?.let {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                MetricCard("Status", it.status.name, Modifier.fillMaxWidth())
                MetricCard("Started", it.startedAt.toClock(), Modifier.fillMaxWidth())
                MetricCard("Ends", it.endsAt.toClock(), Modifier.fillMaxWidth())
                MetricCard("Remaining", (it.endsAt - System.currentTimeMillis()).toCountdown(), Modifier.fillMaxWidth())
                MetricCard("Total", it.priceCents.toRupiah(), Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    SecondaryButton("Extend 30m", { viewModel.extend(it) }, Modifier.weight(1f))
                    PrimaryButton("Stop", { viewModel.stop(it) }, Modifier.weight(1f))
                }
            }
        } ?: Text("No local session exists for this route.")
    }
}

@Composable
fun BillingHistoryScreen(viewModel: OperatorDashboardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    ScreenScaffold(title = "Billing History", subtitle = "Completed and active session ledger") {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.history) { SessionRow(it, onClick = {}) }
        }
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    ScreenScaffold(title = "Settings", subtitle = "Local server connection") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = viewModel::updateUrl,
                label = { Text("Server base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            PrimaryButton("Save", viewModel::save, Modifier.fillMaxWidth())
            if (state.saved) Text("Saved", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceEntity) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(device.displayName, style = MaterialTheme.typography.titleLarge)
            Text("${device.status} • ${device.mode} • ${device.model}", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun SessionRow(session: SessionEntity, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(session.deviceId, style = MaterialTheme.typography.titleLarge)
            Text("${session.status} • ends ${session.endsAt.toClock()} • ${session.priceCents.toRupiah()}")
        }
    }
}

@Composable
private fun SelectionRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
