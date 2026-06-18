package com.example.phonebilling.ui.client

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.phonebilling.admin.KioskController
import com.example.phonebilling.calling.WhatsAppCallHelper
import com.example.phonebilling.ui.common.MetricCard
import com.example.phonebilling.ui.common.PrimaryButton
import com.example.phonebilling.ui.common.SecondaryButton
import com.example.phonebilling.ui.common.ScreenScaffold
import com.example.phonebilling.ui.common.toCountdown

// ─────────────────────────────────────────────────────────────
// Langkah pemilihan pada sesi aktif klien
// ─────────────────────────────────────────────────────────────

enum class ActiveSessionStep {
    SELECT_CALL_TYPE,
    INPUT_NUMBER
}

// ─────────────────────────────────────────────────────────────
// Layar Menunggu (Waiting Screen)
// ─────────────────────────────────────────────────────────────

@Composable
fun ClientWaitingScreen(
    kioskController: KioskController,
    viewModel: ClientViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val state by viewModel.state.collectAsState()
    BackHandler(enabled = true) { }
    LaunchedEffect(activity) {
        activity?.let {
            kioskController.allowLockTaskIfOwner(it, sessionActive = false)
        }
    }
    ScreenScaffold(title = "Menunggu", subtitle = "Perangkat siap diaktifkan oleh operator") {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MetricCard("ID Perangkat", state.deviceId, Modifier.fillMaxWidth())
            MetricCard("Server", if (state.serverOnline) "Terhubung" else "Tidak terhubung, memakai data lokal", Modifier.fillMaxWidth())
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            PrimaryButton(
                if (state.syncing) "Menyinkronkan..." else "Sinkronkan Sekarang",
                viewModel::syncNow,
                Modifier.fillMaxWidth(),
                enabled = !state.syncing
            )
            SecondaryButton(
                "Keluar Kiosk",
                { activity?.let { kioskController.stop(it) } },
                Modifier.fillMaxWidth()
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Layar Sesi Aktif (Active Session Screen)
// ─────────────────────────────────────────────────────────────

@Composable
fun ClientActiveSessionScreen(
    kioskController: KioskController,
    viewModel: ClientViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val state by viewModel.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Instance WhatsAppCallHelper untuk menangani panggilan
    val callHelper = remember { WhatsAppCallHelper() }

    var currentStep by remember { mutableStateOf(ActiveSessionStep.SELECT_CALL_TYPE) }
    var selectedCallType by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var isInitiatingCall by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        if (currentStep == ActiveSessionStep.INPUT_NUMBER) {
            currentStep = ActiveSessionStep.SELECT_CALL_TYPE
        }
    }

    LaunchedEffect(activity) {
        activity?.let {
            kioskController.allowLockTaskIfOwner(it, sessionActive = true)
        }
    }

    // Dialog loading saat panggilan sedang dihubungkan
    if (isInitiatingCall) {
        Dialog(onDismissRequest = {}) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                modifier = Modifier.padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "Menghubungkan panggilan...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    ScreenScaffold(
        title = "Sesi Aktif",
        subtitle = "Sisa Waktu: ${state.remainingMillis.toCountdown()}"
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (currentStep == ActiveSessionStep.SELECT_CALL_TYPE) {
                // ─── Langkah 1: Pilih Jenis Panggilan ───
                Text(
                    text = "Pilih Jenis Panggilan",
                    style = MaterialTheme.typography.titleLarge
                )

                PrimaryButton(
                    text = "Panggilan Suara (Voice Call)",
                    onClick = {
                        selectedCallType = "VOICE"
                        currentStep = ActiveSessionStep.INPUT_NUMBER
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                PrimaryButton(
                    text = "Panggilan Video (Video Call)",
                    onClick = {
                        selectedCallType = "VIDEO"
                        currentStep = ActiveSessionStep.INPUT_NUMBER
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                // ─── Langkah 2: Masukkan Nomor & Mulai Panggilan ───
                Text(
                    text = if (selectedCallType == "VOICE") "Telepon Suara" else "Panggilan Video",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = { input ->
                        phoneNumber = input.filter { it.isDigit() }
                    },
                    label = { Text("Nomor WhatsApp (Contoh: 0858...)") },
                    placeholder = { Text("08xxxxxxxxxx") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                PrimaryButton(
                    text = if (selectedCallType == "VOICE") "Mulai Telepon" else "Mulai Video Call",
                    onClick = {
                        if (phoneNumber.isNotBlank()) {
                            val formattedNumber = callHelper.formatToInternational(phoneNumber)
                            callHelper.launchCall(
                                context = context,
                                phoneNumber = formattedNumber,
                                isVideo = selectedCallType == "VIDEO",
                                onLoadingStateChanged = { isInitiatingCall = it },
                                coroutineScope = coroutineScope
                            )
                        } else {
                            Toast.makeText(context, "Nomor tidak boleh kosong", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = phoneNumber.isNotBlank()
                )

                SecondaryButton(
                    text = "Kembali",
                    onClick = { currentStep = ActiveSessionStep.SELECT_CALL_TYPE },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Layar Waktu Habis (Expired Screen)
// ─────────────────────────────────────────────────────────────

@Composable
fun ClientExpiredScreen(
    kioskController: KioskController,
    viewModel: ClientViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val state by viewModel.state.collectAsState()
    BackHandler(enabled = true) { }
    LaunchedEffect(activity) {
        activity?.let {
            kioskController.allowLockTaskIfOwner(it, sessionActive = false)
        }
    }
    ScreenScaffold(title = "Waktu Habis", subtitle = "Silakan hubungi operator untuk bantuan") {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MetricCard("ID Perangkat", state.deviceId, Modifier.fillMaxWidth())
            MetricCard("Status", if (state.serverOnline) "Tersinkron" else "Menunggu sinkronisasi", Modifier.fillMaxWidth())
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            PrimaryButton(
                if (state.syncing) "Menyinkronkan..." else "Coba Sinkronkan Lagi",
                viewModel::syncNow,
                Modifier.fillMaxWidth(),
                enabled = !state.syncing
            )
        }
    }
}
