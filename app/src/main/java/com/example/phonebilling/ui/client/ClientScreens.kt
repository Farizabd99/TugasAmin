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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.phonebilling.admin.KioskController
import com.example.phonebilling.ui.common.MetricCard
import com.example.phonebilling.ui.common.PrimaryButton
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
        }
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
    BackHandler(enabled = true) { }
    LaunchedEffect(activity) {
        activity?.let {
            kioskController.allowLockTaskIfOwner(it, sessionActive = true)
        }
    }
    ScreenScaffold(title = "Sesi Aktif", subtitle = "Pemakaian ponsel sedang dibatasi") {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(
                state.remainingMillis.toCountdown(),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary
            )
            MetricCard("ID Perangkat", state.deviceId, Modifier.fillMaxWidth())
            MetricCard("Sisa Waktu", state.remainingMillis.toCountdown(), Modifier.fillMaxWidth())
            
            PrimaryButton(
                text = "Buka WhatsApp",
                onClick = {
                    val launchIntent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                    if (launchIntent != null) {
                        context.startActivity(launchIntent)
                    } else {
                        Toast.makeText(context, "WhatsApp tidak terinstall di perangkat ini", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
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
