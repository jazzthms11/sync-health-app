package com.example.synchealth.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.synchealth.R

/**
 * Dieselben drei Schriften wie im Vault: Fraunces fuer Ueberschriften, Outfit fuer Zahlen,
 * Inter fuer den Rest. Alle drei sind variable Schriften und liegen unter `res/font`, die
 * Lizenzen (OFL) unter `docs/fonts`.
 */
@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int, vararg extra: FontVariation.Setting) = Font(
    resId = res,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), *extra),
)

/**
 * `SOFT` und `WONK` stehen auf 0, wie in `life-dashboard.css`: Ohne feste Werte liefert die
 * WONK-Achse absichtlich unregelmaessige Glyphen, und klein sieht das kaputt aus.
 */
@OptIn(ExperimentalTextApi::class)
val Fraunces = FontFamily(
    listOf(400, 500, 600, 700).map {
        variable(R.font.fraunces, it, FontVariation.Setting("SOFT", 0f), FontVariation.Setting("WONK", 0f))
    },
)

val Outfit = FontFamily(listOf(400, 500, 600, 700).map { variable(R.font.outfit, it) })

val Inter = FontFamily(listOf(400, 500, 600, 700).map { variable(R.font.inter, it) })

val Typography = Typography(
    headlineMedium = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.1).sp),
    titleMedium = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.2.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

/** Zahlen in gleich breiten Ziffern, wie die Tabellen im Vault. */
val Zahl = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
