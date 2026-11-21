package com.termux.app.ai

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.termux.shared.theme.NightMode

/**
 * Whether the assistant should render dark, following the app's own theme setting.
 *
 * The terminal has three theme modes (always dark, always light, follow system) and the user picks one
 * in Settings. Compose's [isSystemInDarkTheme] only knows about the system, so an app pinned to dark on
 * a light-system phone would render the panel light while the terminal behind it is dark.
 *
 * Kept separate from the panel so the rule is a plain function and can be checked without rendering.
 */
object AssistantTheme {

    /**
     * @param appNightMode the app-wide mode from Termux's settings.
     * @param systemIsDark what the platform reports for the system theme.
     */
    fun isDark(appNightMode: NightMode, systemIsDark: Boolean): Boolean = when (appNightMode) {
        NightMode.TRUE -> true
        NightMode.FALSE -> false
        NightMode.SYSTEM -> systemIsDark
    }
}

/** [AssistantTheme.isDark] bound to the live Compose environment. */
@Composable
internal fun assistantDarkTheme(): Boolean =
    AssistantTheme.isDark(NightMode.getAppNightMode(), isSystemInDarkTheme())
