package com.example.phonebilling.ui.client

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.phonebilling.admin.KioskController
import com.example.phonebilling.ui.common.MetricCard
import com.example.phonebilling.ui.common.PrimaryButton
import com.example.phonebilling.ui.common.ScreenScaffold
import com.example.phonebilling.ui.common.toCountdown

@Composable
fun ClientWaitingScreen(
    viewModel: ClientViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    ScreenScaffold(title = "Waiting", subtitle = "Device is ready for activation") {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MetricCard("Device ID", state.deviceId, Modifier.fillMaxWidth())
            MetricCard("Server", if (state.serverOnline) "Online" else "Offline, using local state", Modifier.fillMaxWidth())
            PrimaryButton("Sync Now", viewModel::syncNow, Modifier.fillMaxWidth())
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
    DisposableEffect(activity) {
        activity?.let {
            kioskController.allowLockTaskIfOwner(it)
            kioskController.start(it)
        }
        onDispose { }
    }
    ScreenScaffold(title = "Session Active", subtitle = "Phone usage is restricted") {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(
                state.remainingMillis.toCountdown(),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary
            )
            MetricCard("Device ID", state.deviceId, Modifier.fillMaxWidth())
            MetricCard("Ends In", state.remainingMillis.toCountdown(), Modifier.fillMaxWidth())
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
    DisposableEffect(activity) {
        activity?.let { kioskController.start(it) }
        onDispose { }
    }
    ScreenScaffold(title = "Time Expired", subtitle = "Please contact the operator") {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MetricCard("Device ID", state.deviceId, Modifier.fillMaxWidth())
            MetricCard("Status", if (state.serverOnline) "Synced" else "Pending sync", Modifier.fillMaxWidth())
            PrimaryButton("Retry Sync", viewModel::syncNow, Modifier.fillMaxWidth())
        }
    }
}
