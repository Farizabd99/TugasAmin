package com.example.phonebilling.ui.client

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.phonebilling.admin.KioskController
import com.example.phonebilling.ui.common.MetricCard
import com.example.phonebilling.ui.common.PrimaryButton
import com.example.phonebilling.ui.common.SecondaryButton
import com.example.phonebilling.ui.common.ScreenScaffold
import com.example.phonebilling.ui.common.toCountdown

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

enum class ActiveSessionStep {
    SELECT_CALL_TYPE,
    INPUT_NUMBER
}

fun formatToWhatsAppNumber(number: String): String {
    val clean = number.filter { it.isDigit() }
    return when {
        clean.startsWith("0") -> "62" + clean.substring(1)
        clean.startsWith("62") -> clean
        else -> "62" + clean
    }
}

fun launchWhatsAppCall(context: android.content.Context, number: String, isVideo: Boolean) {
    val guideMessage = if (isVideo) {
        "Membuka WhatsApp... Silakan ketuk ikon Video Call (kamera) di pojok kanan atas."
    } else {
        "Membuka WhatsApp... Silakan ketuk ikon Telepon di pojok kanan atas."
    }
    Toast.makeText(context, guideMessage, Toast.LENGTH_LONG).show()
    
    runCatching {
        val url = "https://wa.me/$number"
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse(url)
            `package` = "com.whatsapp"
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "Gagal membuka WhatsApp. Pastikan WhatsApp terinstall.", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun ClientActiveSessionScreen(
    kioskController: KioskController,
    viewModel: ClientViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val state by viewModel.state.collectAsState()
    
    var currentStep by remember { mutableStateOf(ActiveSessionStep.SELECT_CALL_TYPE) }
    var selectedCallType by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    
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
    
    ScreenScaffold(
        title = "Sesi Aktif", 
        subtitle = "Sisa Waktu: ${state.remainingMillis.toCountdown()}"
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (currentStep == ActiveSessionStep.SELECT_CALL_TYPE) {
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
                            val formattedNumber = formatToWhatsAppNumber(phoneNumber)
                            launchWhatsAppCall(context, formattedNumber, selectedCallType == "VIDEO")
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
