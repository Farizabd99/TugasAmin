package com.example.phonebilling.ui.client

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

import android.content.Context
import android.provider.Settings
import android.net.Uri
import android.content.Intent
import com.example.phonebilling.calling.WartelAccessibilityService
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

// ─────────────────────────────────────────────────────────────
// Pengecekan Izin Panggilan Otomatis
// ─────────────────────────────────────────────────────────────

fun hasOverlayPermission(context: Context): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else {
        true
    }
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedId = "${context.packageName}/${WartelAccessibilityService::class.java.name}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.split(":").any { it.equals(expectedId, ignoreCase = true) }
}

fun hasNotificationPermission(context: Context): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

fun hasCallPhonePermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.CALL_PHONE
    ) == PackageManager.PERMISSION_GRANTED
}

fun hasContactsPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED &&
    ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.WRITE_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED
}

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
    
    var overlayOk by remember { mutableStateOf(hasOverlayPermission(context)) }
    var accessibilityOk by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var notificationOk by remember { mutableStateOf(hasNotificationPermission(context)) }
    var callPhoneOk by remember { mutableStateOf(hasCallPhonePermission(context)) }
    var contactsOk by remember { mutableStateOf(hasContactsPermission(context)) }

    var showOverlayGuide by remember { mutableStateOf(false) }
    var showAccessibilityGuide by remember { mutableStateOf(false) }

    // Launchers untuk izin runtime
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notificationOk = granted }

    val callPhoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> callPhoneOk = granted }

    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> contactsOk = results.values.all { it } }

    // Dapatkan lifecycle owner untuk memperbarui status ketika kembali dari halaman pengaturan
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayOk = hasOverlayPermission(context)
                accessibilityOk = isAccessibilityServiceEnabled(context)
                notificationOk = hasNotificationPermission(context)
                callPhoneOk = hasCallPhonePermission(context)
                contactsOk = hasContactsPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val allPermissionsGranted = overlayOk && accessibilityOk && notificationOk && callPhoneOk && contactsOk

    if (showOverlayGuide) {
        Dialog(onDismissRequest = { showOverlayGuide = false }) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                modifier = Modifier.padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Panduan Menggambar di Atas Aplikasi",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "1. Anda akan diarahkan ke pengaturan sistem.\n" +
                               "2. Cari dan pilih aplikasi \"Wartel\".\n" +
                               "3. Aktifkan/Centang pilihan \"Izinkan menarik di atas aplikasi lain\" (Allow display over other apps).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SecondaryButton(
                            text = "Batal",
                            onClick = { showOverlayGuide = false },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        PrimaryButton(
                            text = "Buka Pengaturan",
                            onClick = {
                                showOverlayGuide = false
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    runCatching {
                                        context.startActivity(intent)
                                    }.onFailure {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAccessibilityGuide) {
        Dialog(onDismissRequest = { showAccessibilityGuide = false }) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                modifier = Modifier.padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Panduan Aksesibilitas",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "1. Anda akan diarahkan ke pengaturan Aksesibilitas.\n" +
                               "2. Cari dan ketuk menu \"Aplikasi Terunduh\" (Downloaded apps) atau \"Layanan Terunduh Lainnya\".\n" +
                               "3. Pilih aplikasi \"Wartel\".\n" +
                               "4. Aktifkan \"Gunakan Wartel\" (Use Wartel).\n\n" +
                               "⚠️ JIKA TOMBOL DI-GRAYOUT (Restricted Settings):\n" +
                               "- Buka Settings HP -> Apps -> Manage Apps -> Wartel.\n" +
                               "- Ketuk titik 3 di kanan atas -> pilih \"Allow restricted settings\" (Izinkan setelan dibatasi).\n" +
                               "- Kembali ke halaman ini dan aktifkan Aksesibilitas.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SecondaryButton(
                            text = "Batal",
                            onClick = { showAccessibilityGuide = false },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        PrimaryButton(
                            text = "Buka Pengaturan",
                            onClick = {
                                showAccessibilityGuide = false
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    BackHandler(enabled = true) { }
    LaunchedEffect(activity) {
        activity?.let {
            kioskController.allowLockTaskIfOwner(it, sessionActive = false)
        }
    }
    ScreenScaffold(title = "Menunggu", subtitle = "Perangkat siap diaktifkan oleh operator") {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            
            // Tampilkan Setup Wizard jika izin belum lengkap
            if (!allPermissionsGranted) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "PANDUAN SETUP IZIN",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )

                        PermissionRow(
                            title = "Menggambar di Atas Aplikasi",
                            description = "Wajib untuk otomatisasi WhatsApp.",
                            granted = overlayOk,
                            onRequest = { showOverlayGuide = true }
                        )
                        PermissionRow(
                            title = "Aksesibilitas",
                            description = "Wajib untuk mengklik tombol WhatsApp.",
                            granted = accessibilityOk,
                            onRequest = { showAccessibilityGuide = true },
                            extraNote = "Xiaomi/Vivo: Cek 'Setelan Dibatasi' jika izin dinonaktifkan."
                        )
                        PermissionRow(
                            title = "Notifikasi",
                            description = "Wajib agar panggilan terpantau.",
                            granted = notificationOk,
                            onRequest = { if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
                        )
                        PermissionRow(
                            title = "Telepon",
                            description = "Wajib untuk memproses status panggilan.",
                            granted = callPhoneOk,
                            onRequest = { callPhoneLauncher.launch(android.Manifest.permission.CALL_PHONE) }
                        )
                        PermissionRow(
                            title = "Kontak",
                            description = "Wajib untuk sinkronisasi data.",
                            granted = contactsOk,
                            onRequest = { contactsLauncher.launch(arrayOf(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)) }
                        )
                    }
                }
            }

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

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    onRequest: () -> Unit,
    extraNote: String? = null
) {
    if (granted) {
        Text("\u2713 $title", color = Color(0xFF2E7D32), style = MaterialTheme.typography.bodyMedium)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SecondaryButton(
                text = "\u2192 $title",
                onClick = onRequest,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            extraNote?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
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

    LaunchedEffect(state.activeSession?.sessionId) {
        currentStep = ActiveSessionStep.SELECT_CALL_TYPE
        selectedCallType = ""
        phoneNumber = ""
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
            SecondaryButton(
                "Keluar Kiosk",
                { activity?.let { kioskController.stop(it) } },
                Modifier.fillMaxWidth()
            )
        }
    }
}
