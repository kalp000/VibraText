package com.example.vibratext.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.vibratext.app.ui.apps.AppsScreen
import com.example.vibratext.app.ui.decoder.DecoderScreen
import com.example.vibratext.app.ui.help.HelpScreen
import com.example.vibratext.app.ui.history.HistoryScreen
import com.example.vibratext.app.ui.home.HomeScreen
import com.example.vibratext.app.ui.profiles.ProfileEditorScreen
import com.example.vibratext.app.ui.profiles.ProfilesScreen
import com.example.vibratext.app.ui.rules.RulesScreen
import com.example.vibratext.app.ui.settings.SettingsScreen
import com.example.vibratext.app.ui.testing.TestScreen

object Routes {
    const val HOME = "home"
    const val APPS = "apps"
    const val PROFILES = "profiles"
    const val PROFILE_EDIT = "profile/{id}"
    const val RULES = "rules"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val TEST = "test"
    const val DECODER = "decoder"
    const val HELP = "help"

    fun profile(id: String) = "profile/${Uri.encode(id)}"
}

private class TopLevel(val route: String, val label: String, val icon: ImageVector)

private val topLevel = listOf(
    TopLevel(Routes.HOME, "Home", Icons.Filled.Home),
    TopLevel(Routes.APPS, "Apps", Icons.Filled.Notifications),
    TopLevel(Routes.PROFILES, "Profiles", Icons.Filled.Person),
    TopLevel(Routes.HISTORY, "History", Icons.Filled.List),
    TopLevel(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun VibraTextRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = topLevel.any { it.route == route }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    topLevel.forEach { item ->
                        NavigationBarItem(
                            selected = route == item.route,
                            onClick = { nav.navigateTopLevel(item.route) },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController = nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) { HomeScreen(onNavigate = { nav.navigateTo(it) }) }
            composable(Routes.APPS) { AppsScreen() }
            composable(Routes.PROFILES) { ProfilesScreen(onEdit = { nav.navigate(Routes.profile(it)) }) }
            composable(Routes.PROFILE_EDIT, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                ProfileEditorScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.RULES) { RulesScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.HISTORY) { HistoryScreen() }
            composable(Routes.SETTINGS) {
                SettingsScreen(onOpenApps = { nav.navigateTopLevel(Routes.APPS) }, onOpenProfiles = { nav.navigateTopLevel(Routes.PROFILES) })
            }
            composable(Routes.TEST) { TestScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.DECODER) { DecoderScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.HELP) { HelpScreen(onBack = { nav.popBackStack() }) }
        }
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Top-level tabs use tab navigation; every other destination is pushed on top. */
private fun NavHostController.navigateTo(route: String) {
    if (topLevel.any { it.route == route }) navigateTopLevel(route) else navigate(route) { launchSingleTop = true }
}
