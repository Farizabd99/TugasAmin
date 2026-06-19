package com.example.phonebilling

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.phonebilling.admin.KioskController
import com.example.phonebilling.core.design.PhoneBillingTheme
import com.example.phonebilling.core.navigation.Route
import com.example.phonebilling.data.local.entity.DeviceStatus
import com.example.phonebilling.ui.client.ClientActiveSessionScreen
import com.example.phonebilling.ui.client.ClientExpiredScreen
import com.example.phonebilling.ui.client.ClientViewModel
import com.example.phonebilling.ui.client.ClientWaitingScreen
import com.example.phonebilling.ui.operator.ActiveSessionDetailScreen
import com.example.phonebilling.ui.operator.BillingHistoryScreen
import com.example.phonebilling.ui.operator.DeviceListScreen
import com.example.phonebilling.ui.operator.OperatorDashboardScreen
import com.example.phonebilling.ui.operator.OperatorLoginScreen
import com.example.phonebilling.ui.operator.SettingsScreen
import com.example.phonebilling.ui.operator.StartSessionScreen
import com.example.phonebilling.worker.BillingSyncWorker
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var kioskController: KioskController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleBillingSync()
        setContent {
            PhoneBillingTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PhoneBillingNavHost(kioskController)
                }
            }
        }
    }

    private fun scheduleBillingSync() {
        val request = PeriodicWorkRequestBuilder<BillingSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "billing-log-sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}

@Composable
private fun PhoneBillingNavHost(kioskController: KioskController) {
    val navController = rememberNavController()
    val clientViewModel: ClientViewModel = hiltViewModel()
    val clientState by clientViewModel.state.collectAsState()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? android.app.Activity
    LaunchedEffect(currentRoute, clientState.status, clientState.activeSession, clientState.remainingMillis) {
        activity?.let { act ->
            if (currentRoute?.startsWith("client/") == true) {
                val isActive = clientState.status == DeviceStatus.ACTIVE && clientState.activeSession != null && clientState.remainingMillis > 0
                kioskController.allowLockTaskIfOwner(act, sessionActive = isActive)
                kioskController.start(act)
            } else {
                kioskController.stop(act)
            }
        }
    }

    LaunchedEffect(clientState.status, clientState.activeSession, clientState.remainingMillis, currentRoute) {
        if (currentRoute?.startsWith("client/") == true) {
            when {
                clientState.status == DeviceStatus.ACTIVE && clientState.activeSession != null && clientState.remainingMillis > 0 -> {
                    if (currentRoute != Route.ClientActive.path) {
                        navController.navigate(Route.ClientActive.path) { launchSingleTop = true }
                    }
                }
                clientState.status == DeviceStatus.EXPIRED || clientState.activeSession != null && clientState.remainingMillis <= 0 -> {
                    if (currentRoute != Route.ClientExpired.path) {
                        navController.navigate(Route.ClientExpired.path) { launchSingleTop = true }
                    }
                }
                currentRoute != Route.ClientWaiting.path -> {
                    navController.navigate(Route.ClientWaiting.path) { launchSingleTop = true }
                }
            }
        }
    }

    NavHost(navController = navController, startDestination = Route.OperatorLogin.path) {
        composable(Route.OperatorLogin.path) {
            OperatorLoginScreen(
                onLoggedIn = {
                    navController.navigate(Route.OperatorDashboard.path) {
                        popUpTo(Route.OperatorLogin.path) { inclusive = true }
                    }
                },
                openClient = { navController.navigate(Route.ClientWaiting.path) }
            )
        }
        composable(Route.OperatorDashboard.path) {
            OperatorDashboardScreen(
                openDevices = { navController.navigate(Route.DeviceList.path) },
                openStartSession = { navController.navigate(Route.StartSession.path) },
                openHistory = { navController.navigate(Route.BillingHistory.path) },
                openSettings = { navController.navigate(Route.Settings.path) },
                logout = {
                    navController.navigate(Route.OperatorLogin.path) {
                        popUpTo(Route.OperatorDashboard.path) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                openSession = { navController.navigate(Route.ActiveSessionDetail.create(it)) }
            )
        }
        composable(Route.DeviceList.path) {
            DeviceListScreen(onBack = { navController.popBackStack() })
        }
        composable(Route.StartSession.path) {
            StartSessionScreen(
                onBack = { navController.popBackStack() },
                openSession = {
                    navController.navigate(Route.ActiveSessionDetail.create(it))
                }
            )
        }
        composable(
            route = Route.ActiveSessionDetail.path,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) {
            ActiveSessionDetailScreen(
                onBack = { navController.popBackStack() },
                openDashboard = {
                    navController.navigate(Route.OperatorDashboard.path) {
                        popUpTo(Route.OperatorDashboard.path) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable(Route.BillingHistory.path) {
            BillingHistoryScreen(onBack = { navController.popBackStack() })
        }
        composable(Route.Settings.path) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Route.ClientWaiting.path) { ClientWaitingScreen(kioskController, clientViewModel) }
        composable(Route.ClientActive.path) {
            ClientActiveSessionScreen(kioskController = kioskController, viewModel = clientViewModel)
        }
        composable(Route.ClientExpired.path) {
            ClientExpiredScreen(kioskController = kioskController, viewModel = clientViewModel)
        }
    }
}
