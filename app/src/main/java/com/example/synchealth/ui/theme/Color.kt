package com.example.synchealth.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Die Palette aus `vault-look.css` im Vault: warmes Papier, Serif-Ueberschriften, ein
 * Akzent in Terracotta. Die Namen sind dieselben wie dort (`--lk-…`), damit eine
 * Aenderung am Vault-Look hier ohne Suchen nachzuziehen ist.
 */
@Immutable
data class VaultColors(
    /** `--lk-bg`: der Seitengrund. */
    val bg: Color,
    /** `--lk-bg2`: der Grund der Karten, wie die Tagesuebersicht ihn zeichnet. */
    val bg2: Color,
    /** `--lk-flaeche`: Callouts, Felder, Pillen. */
    val flaeche: Color,
    val text: Color,
    val leise: Color,
    val titel: Color,
    val akzent: Color,
    val linie: Color,
    /** Fuer Warnungen: der Akzent ins Rote gezogen, statt Materials grellem Fehlerrot. */
    val warnung: Color,
)

val VaultLight = VaultColors(
    bg = Color(0xFFFAF7F2),
    bg2 = Color(0xFFF4EEE5),
    flaeche = Color(0xFFF2EADF),
    text = Color(0xFF3D3630),
    leise = Color(0xFF8C7F70),
    titel = Color(0xFF7B5E43),
    akzent = Color(0xFFB08157),
    linie = Color(0xFFE7DCCB),
    warnung = Color(0xFFB4593C),
)

val VaultDark = VaultColors(
    bg = Color(0xFF1E1B18),
    bg2 = Color(0xFF191614),
    flaeche = Color(0xFF2A2521),
    text = Color(0xFFE9E1D6),
    leise = Color(0xFF9A8E7F),
    titel = Color(0xFFD9B78E),
    akzent = Color(0xFFC79A6B),
    linie = Color(0xFF37302A),
    warnung = Color(0xFFD9886A),
)

val LocalVault = staticCompositionLocalOf { VaultDark }
