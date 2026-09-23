package com.example.cattlemonitor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import com.example.cattlemonitor.ui.alerts.AlertsScreen
import com.example.cattlemonitor.ui.detail.CowDetailScreen
import com.example.cattlemonitor.ui.device.DeviceScreen
import com.example.cattlemonitor.ui.overview.OverviewScreen
import com.example.cattlemonitor.ui.settings.SettingsScreen

class MainActivity : ComponentActivity() {

    private var pendingIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingIntent = intent
        setContent {
            MaterialTheme {
                Surface {
                    AppNavHost(startIntent = pendingIntent)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntent = intent
    }
}

@Composable
private fun AppNavHost(startIntent: Intent?) {
    val navController = rememberNavController()

    // Handle notification deep links (cattleapp://cow/{id}) on cold start
    // and while the activity is already running.
    androidx.compose.runtime.LaunchedEffect(startIntent) {
        startIntent?.let { navController.handleDeepLink(it) }
    }

    NavHost(navController = navController, startDestination = "overview") {
        composable("overview") {
            OverviewScreen(
                onOpenCow = { navController.navigate("cow/$it") },
                onOpenAlerts = { navController.navigate("alerts") },
                onOpenDevice = { navController.navigate("device") },
                onOpenSettings = { navController.navigate("settings") },
            )
        }
        composable(
            route = "cow/{cowId}",
            deepLinks = listOf(navDeepLink { uriPattern = "cattleapp://cow/{cowId}" }),
        ) { entry ->
            val cowId = entry.arguments?.getString("cowId") ?: return@composable
            CowDetailScreen(
                cowId = cowId,
                onBack = { navController.popBackStack() },
            )
        }
        composable("alerts") {
            AlertsScreen(onOpenCow = { navController.navigate("cow/$it") })
        }
        composable("device") {
            DeviceScreen(onDone = { navController.popBackStack() })
        }
        composable("settings") {
            SettingsScreen()
        }
    }
}
