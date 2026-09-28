package com.example.cattlemonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import com.example.cattlemonitor.ui.alerts.AlertsScreen
import com.example.cattlemonitor.ui.auth.LoginScreen
import com.example.cattlemonitor.ui.detail.CowDetailScreen
import com.example.cattlemonitor.ui.device.DeviceScreen
import com.example.cattlemonitor.ui.overview.OverviewScreen
import com.example.cattlemonitor.ui.settings.SettingsScreenWithLanguage
import com.example.cattlemonitor.ui.splash.SplashScreen
import com.example.cattlemonitor.ui.theme.Brand
import com.example.cattlemonitor.ui.theme.CattleMonitorTheme
import com.example.cattlemonitor.ui.theme.Surface as SurfaceColor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    private var pendingIntent by mutableStateOf<Intent?>(null)

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* denied → popups stay off; can be re-enabled from system settings */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen() // Android 12+ system splash, brand icon on brand bg
        super.onCreate(savedInstanceState)
        ServiceLocator.init(this)
        pendingIntent = intent
        maybeRequestNotificationPermission()
        setContent {
            CattleMonitorTheme {
                Surface {
                    AppNavHost(startIntent = pendingIntent)
                }
            }
        }
    }

    /** Android 13+: push popups are opt-in — ask on first launch. */
    private fun maybeRequestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntent = intent
    }
}

private data class TopLevelDestination(
    val route: String,
    val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private val topLevelDestinations = listOf(
    TopLevelDestination("overview", R.string.nav_herd, Icons.Filled.Home),
    TopLevelDestination("alerts", R.string.nav_alerts, Icons.Filled.Notifications),
    TopLevelDestination("device", R.string.nav_devices, Icons.Filled.Build),
    TopLevelDestination("settings", R.string.nav_settings, Icons.Filled.Settings),
)

@Composable
private fun AppNavHost(startIntent: Intent?) {
    val navController = rememberNavController()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(startIntent) {
        startIntent?.let { navController.handleDeepLink(it) }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination
    val showBottomNav = topLevelDestinations.any { currentRoute?.hierarchy?.any { d -> d.route == it.route } == true }
    Scaffold(
        bottomBar = {
            if (showBottomNav) {
                NavigationBar(containerColor = SurfaceColor) {
                    topLevelDestinations.forEach { dest ->
                        val selected = currentRoute?.hierarchy?.any { it.route == dest.route } == true
                        val label = stringResource(dest.labelRes)
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = label) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Brand,
                                selectedTextColor = Brand,
                                indicatorColor = Brand.copy(alpha = 0.15f),
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "splash",
            modifier = Modifier.padding(padding),
        ) {
            composable("splash") {
                SplashScreen(
                    onFinished = {
                        scope.launch {
                            // The splash decision now waits for the AUTH STATE to
                            // resolve, not just a timer: supabase-kt restores the
                            // persisted session from DataStore on process start, so
                            // a returning user lands straight in the herd (req:
                            // "save the login"). Falls back to login on timeout
                            // (e.g. no session, or a refresh failure).
                            val loggedIn = withTimeoutOrNull(8_000) {
                                ServiceLocator.auth.sessionState.first { it != null }
                            } == true
                            val dest = if (loggedIn) "overview" else "login"
                            navController.navigate(dest) {
                                popUpTo("splash") { inclusive = true }
                            }
                        }
                    },
                )
            }
            composable("login") {
                LoginScreen(
                    onLoggedIn = {
                        navController.navigate("overview") {
                            popUpTo("login") { inclusive = true }
                        }
                    },
                )
            }
            composable("overview") {
                OverviewScreen(onOpenCow = { navController.navigate("cow/$it") })
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
                DeviceScreen()
            }
            composable("settings") {
                SettingsScreenWithLanguage(
                    onLoggedOut = {
                        navController.navigate("login") {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}
