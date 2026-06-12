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
import com.example.phonebilling.data.local.entity.DeviceMode
import com.example.phonebilling.data.local.entity.DeviceStatus
import com.example.phonebilling.data.local.entity.SessionEntity
import com.example.phonebilling.data.local.entity.SessionStatus
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
    ScreenScaffold(title = "Masuk Operator", subtitle = "Kelola sesi pemakaian ponsel dengan aman") {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = state.username,
                onValueChange = viewModel::updateUsername,
                label = { Text("Nama pengguna") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::updatePassword,
                label = { Text("Kata sandi") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            PrimaryButton("Masuk", viewModel::login, Modifier.fillMaxWidth(), enabled = !state.loading)
            SecondaryButton("Buka Mode Klien", openClient, Modifier.fillMaxWidth())
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
        title = "Dasbor",
        subtitle = "Ringkasan perangkat aktif dan pendapatan",
        actions = { SecondaryButton("Pengaturan", openSettings) }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricCard("Perangkat", state.devices.size.toString(), Modifier.weight(1f))
                MetricCard("Aktif", state.activeSessions.size.toString(), Modifier.weight(1f))
            }
            MetricCard("Pendapatan", state.revenueCents.toRupiah(), Modifier.fillMaxWidth())
            PrimaryButton("Mulai Sesi", openStartSession, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                SecondaryButton("Perangkat", openDevices, Modifier.weight(1f))
                SecondaryButton("Riwayat", openHistory, Modifier.weight(1f))
            }
            SecondaryButton("Daftarkan Perangkat Ini", viewModel::registerOperatorDevice, Modifier.fillMaxWidth())
            SecondaryButton("Sinkronkan", viewModel::sync, Modifier.fillMaxWidth())
            Text("Perangkat aktif", style = MaterialTheme.typography.titleLarge)
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
    ScreenScaffold(title = "Daftar Perangkat", subtitle = "Ponsel yang terdaftar dan baru terlihat") {
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
    ScreenScaffold(title = "Mulai Sesi", subtitle = "Pilih perangkat dan tarif") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Perangkat", style = MaterialTheme.typography.titleLarge)
            state.devices.forEach {
                SelectionRow(
                    title = it.displayName,
                    subtitle = "${it.status.toIndonesian()} - ${it.model}",
                    selected = it.deviceId == state.selectedDeviceId,
                    onClick = { viewModel.selectDevice(it.deviceId) }
                )
            }
            Text("Tarif", style = MaterialTheme.typography.titleLarge)
            state.tariffs.forEach {
                SelectionRow(
                    title = it.name,
                    subtitle = it.priceCents.toRupiah(),
                    selected = it.tariffId == state.selectedTariffId,
                    onClick = { viewModel.selectTariff(it.tariffId) }
                )
            }
            PrimaryButton("Mulai", viewModel::start, Modifier.fillMaxWidth(), enabled = state.selectedDeviceId != null && state.selectedTariffId != null)
        }
    }
}

@Composable
fun ActiveSessionDetailScreen(viewModel: SessionDetailViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsState()
    ScreenScaffold(title = "Sesi Aktif", subtitle = session?.deviceId ?: "Sesi tidak ditemukan") {
        session?.let {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                MetricCard("Status", it.status.toIndonesian(), Modifier.fillMaxWidth())
                MetricCard("Mulai", it.startedAt.toClock(), Modifier.fillMaxWidth())
                MetricCard("Selesai", it.endsAt.toClock(), Modifier.fillMaxWidth())
                MetricCard("Sisa Waktu", (it.endsAt - System.currentTimeMillis()).toCountdown(), Modifier.fillMaxWidth())
                MetricCard("Total", it.priceCents.toRupiah(), Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    SecondaryButton("Tambah 30 mnt", { viewModel.extend(it) }, Modifier.weight(1f))
                    PrimaryButton("Hentikan", { viewModel.stop(it) }, Modifier.weight(1f))
                }
            }
        } ?: Text("Tidak ada sesi lokal untuk halaman ini.")
    }
}

@Composable
fun BillingHistoryScreen(viewModel: OperatorDashboardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    ScreenScaffold(title = "Riwayat Tagihan", subtitle = "Catatan sesi aktif dan selesai") {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.history) { SessionRow(it, onClick = {}) }
        }
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    ScreenScaffold(title = "Pengaturan", subtitle = "Koneksi server lokal") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = viewModel::updateUrl,
                label = { Text("URL dasar server") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            PrimaryButton("Simpan", viewModel::save, Modifier.fillMaxWidth())
            if (state.saved) Text("Tersimpan", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceEntity) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(device.displayName, style = MaterialTheme.typography.titleLarge)
            Text(
                "${device.status.toIndonesian()} - ${device.mode.toIndonesian()} - ${device.model}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SessionRow(session: SessionEntity, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(session.deviceId, style = MaterialTheme.typography.titleLarge)
            Text(
                "${session.status.toIndonesian()} - selesai ${session.endsAt.toClock()} - ${session.priceCents.toRupiah()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SelectionRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun DeviceStatus.toIndonesian(): String = when (this) {
    DeviceStatus.WAITING -> "Menunggu"
    DeviceStatus.ACTIVE -> "Aktif"
    DeviceStatus.EXPIRED -> "Waktu habis"
    DeviceStatus.OFFLINE -> "Tidak terhubung"
}

private fun DeviceMode.toIndonesian(): String = when (this) {
    DeviceMode.OPERATOR -> "Operator"
    DeviceMode.CLIENT -> "Klien"
}

private fun SessionStatus.toIndonesian(): String = when (this) {
    SessionStatus.PENDING -> "Menunggu"
    SessionStatus.ACTIVE -> "Aktif"
    SessionStatus.EXPIRED -> "Waktu habis"
    SessionStatus.STOPPED -> "Dihentikan"
}
