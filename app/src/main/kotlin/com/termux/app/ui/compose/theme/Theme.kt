package com.termux.app.ui.compose.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Modern neutral palette shared with the XML themes so that Compose screens
// (settings, styling, dialogs) match the rest of the app.
private val AccentTeal = Color(0xFF2DD4BF)
private val AccentTealDark = Color(0xFF0F766E)
private val SurfaceDark = Color(0xFF0E1013)
private val SurfaceContainerDark = Color(0xFF191C20)
private val SurfaceLight = Color(0xFFF8FAFA)
private val SurfaceContainerLight = Color(0xFFFFFFFF)
private val OnSurfaceDark = Color(0xFFE4E7EA)
private val OnSurfaceLight = Color(0xFF1A1C1E)
private val OnSurfaceMutedDark = Color(0xFF9AA4AE)
private val OnSurfaceMutedLight = Color(0xFF606A72)
private val DividerDark = Color(0xFF262B31)
private val DividerLight = Color(0xFFE4E8EB)

private val DarkColorScheme = darkColorScheme(
    primary = AccentTeal,
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF134E4A),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF8E9BA8),
    onSecondary = Color(0xFF11161A),
    tertiary = AccentTeal,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceContainerDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = Color(0xFF1F242A),
    onSurfaceVariant = OnSurfaceMutedDark,
    outline = DividerDark,
    outlineVariant = DividerDark,
    error = Color(0xFFF87171),
    onError = Color(0xFF2A0A0A),
    // Material's stock error container is a saturated red that clashes with the teal and neutral
    // palette everywhere else. These are muted to sit in the same family as the surfaces.
    errorContainer = Color(0xFF3B1618),
    onErrorContainer = Color(0xFFFBD5D5),
)

private val LightColorScheme = lightColorScheme(
    primary = AccentTealDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = Color(0xFF4B5563),
    onSecondary = Color.White,
    tertiary = AccentTealDark,
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceContainerLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = Color(0xFFEFF2F4),
    onSurfaceVariant = OnSurfaceMutedLight,
    outline = DividerLight,
    outlineVariant = DividerLight,
    error = Color(0xFFB91C1C),
    onError = Color.White,
    // Muted to match the light surfaces instead of Material's default warm pink.
    errorContainer = Color(0xFFFBE3E3),
    onErrorContainer = Color(0xFF5A1010),
)

@Composable
fun TermuxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Match the status bar to the neutral surface so Compose screens
            // share the same chrome as the XML themed screens.
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
