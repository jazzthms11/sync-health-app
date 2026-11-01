package com.example.synchealth.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * Der Vault-Look statt Androids dynamischer Farben: Hell und dunkel folgen dem System, so
 * wie der Vault auf "system" steht. Die eigenen Farben kommen ueber [LocalVault], die
 * Material-Bausteine (Dialoge, Textfelder, Snackbar) bekommen dieselben ueber das Schema.
 */
@Composable
fun SyncHealthTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val v = if (darkTheme) VaultDark else VaultLight
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = v.akzent,
        onPrimary = if (darkTheme) Color(0xFF1E1B18) else Color.White,
        secondary = v.titel,
        onSecondary = v.bg,
        background = v.bg,
        onBackground = v.text,
        surface = v.bg,
        onSurface = v.text,
        surfaceVariant = v.flaeche,
        onSurfaceVariant = v.leise,
        surfaceContainer = v.bg2,
        surfaceContainerHigh = v.flaeche,
        surfaceContainerHighest = v.flaeche,
        surfaceContainerLow = v.bg2,
        outline = v.leise,
        outlineVariant = v.linie,
        error = v.warnung,
        inverseSurface = v.text,
        inverseOnSurface = v.bg,
        inversePrimary = v.titel,
    )
    CompositionLocalProvider(LocalVault provides v) {
        MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
    }
}
