package com.termux.app.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.termux.app.ui.compose.theme.TermuxTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Compose-based Settings Activity.
 * 
 * Hosts the settings navigation graph with:
 * - Main settings screen
 * - Theme gallery
 * - Profile management
 */
@AndroidEntryPoint
class ComposeSettingsActivity : ComponentActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            TermuxSettingsTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SettingsNavigation(
                        onNavigateBack = { finish() }
                    )
                }
            }
        }
    }
}

/**
 * Settings navigation graph.
 */
@Composable
fun SettingsNavigation(
    onNavigateBack: () -> Unit
) {
    val navController = rememberNavController()
    
    NavHost(
        navController = navController,
        startDestination = SettingsDestination.Main.route
    ) {
        composable(SettingsDestination.Main.route) {
            SettingsScreen(
                onNavigateBack = onNavigateBack,
                onNavigateToThemeGallery = {
                    navController.navigate(SettingsDestination.ThemeGallery.route)
                },
                onNavigateToProfiles = {
                    navController.navigate(SettingsDestination.Profiles.route)
                }
            )
        }
        
        composable(SettingsDestination.ThemeGallery.route) {
            ThemeGalleryScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable(SettingsDestination.Profiles.route) {
            ProfilesScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

/**
 * Settings navigation destinations.
 */
sealed class SettingsDestination(val route: String) {
    data object Main : SettingsDestination("settings_main")
    data object ThemeGallery : SettingsDestination("settings_theme_gallery")
    data object Profiles : SettingsDestination("settings_profiles")
}

/**
 * Termux Settings Material 3 theme.
 *
 * Delegates to the shared [TermuxTheme] so settings screens use exactly the same
 * palette as the styling screen and the XML themed activities.
 */
@Composable
fun TermuxSettingsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    TermuxTheme(
        darkTheme = darkTheme,
        dynamicColor = dynamicColor,
        content = content
    )
}
