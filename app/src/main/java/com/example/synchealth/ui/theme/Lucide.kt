package com.example.synchealth.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Die Lucide-Icons, die auch die Tagesuebersicht im Vault zeichnet, damit App und Notiz
 * dieselbe Bildsprache sprechen. Erzeugt aus lucide-static 0.460.0 (ISC-Lizenz), Kreise,
 * Linien und Rechtecke dabei in Pfade umgeschrieben. Die Strichfarbe ist egal: `Icon`
 * faerbt ueber `tint`.
 */
object Lucide {
    val Beef: ImageVector by lazy {
        icon(
            "beef",
        "M10 8.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0",
        "M12.5 2a6.5 6.5 0 0 0-6.22 4.6c-1.1 3.13-.78 3.9-3.18 6.08A3 3 0 0 0 5 18c4 0 8.4-1.8 11.4-4.3A6.5 6.5 0 0 0 12.5 2Z",
        "m18.5 6 2.19 4.5a6.48 6.48 0 0 1 .31 2 6.49 6.49 0 0 1-2.6 5.2C15.4 20.2 11 22 7 22a3 3 0 0 1-2.68-1.66L2.4 16.5",
        )
    }

    val Calendar: ImageVector by lazy {
        icon(
            "calendar",
        "M8 2v4",
        "M16 2v4",
        "M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2Z",
        "M3 10h18",
        )
    }

    val Candy: ImageVector by lazy {
        icon(
            "candy",
        "m9.5 7.5-2 2a4.95 4.95 0 1 0 7 7l2-2a4.95 4.95 0 1 0-7-7Z",
        "M14 6.5v10",
        "M10 7.5v10",
        "m16 7 1-5 1.37.68A3 3 0 0 0 19.7 3H21v1.3c0 .46.1.92.32 1.33L22 7l-5 1",
        "m8 17-1 5-1.37-.68A3 3 0 0 0 4.3 21H3v-1.3a3 3 0 0 0-.32-1.33L2 17l5-1",
        )
    }

    val ChevronDown: ImageVector by lazy {
        icon(
            "chevron-down",
        "m6 9 6 6 6-6",
        )
    }

    val ChevronLeft: ImageVector by lazy {
        icon(
            "chevron-left",
        "m15 18-6-6 6-6",
        )
    }

    val ChevronRight: ImageVector by lazy {
        icon(
            "chevron-right",
        "m9 18 6-6-6-6",
        )
    }

    val Cookie: ImageVector by lazy {
        icon(
            "cookie",
        "M12 2a10 10 0 1 0 10 10 4 4 0 0 1-5-5 4 4 0 0 1-5-5",
        "M8.5 8.5v.01",
        "M16 15.5v.01",
        "M12 12v.01",
        "M11 17v.01",
        "M7 14v.01",
        )
    }

    val Droplet: ImageVector by lazy {
        icon(
            "droplet",
        "M12 22a7 7 0 0 0 7-7c0-2-1-3.9-3-5.5s-3.5-4-4-6.5c-.5 2.5-2 4.9-4 6.5C6 11.1 5 13 5 15a7 7 0 0 0 7 7z",
        )
    }

    val Flame: ImageVector by lazy {
        icon(
            "flame",
        "M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.072-2.143-.224-4.054 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.153.433-2.294 1-3a2.5 2.5 0 0 0 2.5 2.5z",
        )
    }

    val Footprints: ImageVector by lazy {
        icon(
            "footprints",
        "M4 16v-2.38C4 11.5 2.97 10.5 3 8c.03-2.72 1.49-6 4.5-6C9.37 2 10 3.8 10 5.5c0 3.11-2 5.66-2 8.68V16a2 2 0 1 1-4 0Z",
        "M20 20v-2.38c0-2.12 1.03-3.12 1-5.62-.03-2.72-1.49-6-4.5-6C14.63 6 14 7.8 14 9.5c0 3.11 2 5.66 2 8.68V20a2 2 0 1 0 4 0Z",
        "M16 17h4",
        "M4 13h4",
        )
    }

    val GlassWater: ImageVector by lazy {
        icon(
            "glass-water",
        "M5.116 4.104A1 1 0 0 1 6.11 3h11.78a1 1 0 0 1 .994 1.105L17.19 20.21A2 2 0 0 1 15.2 22H8.8a2 2 0 0 1-2-1.79z",
        "M6 12a5 5 0 0 1 6 0 5 5 0 0 0 6 0",
        )
    }

    val HeartPulse: ImageVector by lazy {
        icon(
            "heart-pulse",
        "M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z",
        "M3.22 12H9.5l.5-1 2 4.5 2-7 1.5 3.5h5.27",
        )
    }

    val MoonStar: ImageVector by lazy {
        icon(
            "moon-star",
        "M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9",
        "M20 3v4",
        "M22 5h-4",
        )
    }

    val Moon: ImageVector by lazy {
        icon(
            "moon",
        "M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z",
        )
    }

    val RefreshCw: ImageVector by lazy {
        icon(
            "refresh-cw",
        "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8",
        "M21 3v5h-5",
        "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16",
        "M8 16H3v5",
        )
    }

    val RotateCcw: ImageVector by lazy {
        icon(
            "rotate-ccw",
        "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8",
        "M3 3v5h5",
        )
    }

    val Settings: ImageVector by lazy {
        icon(
            "settings",
        "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
        "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
        )
    }

    val Smartphone: ImageVector by lazy {
        icon(
            "smartphone",
        "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-16a2 2 0 0 1 2 -2Z",
        "M12 18h.01",
        )
    }

    val Sun: ImageVector by lazy {
        icon(
            "sun",
        "M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0",
        "M12 2v2",
        "M12 20v2",
        "m4.93 4.93 1.41 1.41",
        "m17.66 17.66 1.41 1.41",
        "M2 12h2",
        "M20 12h2",
        "m6.34 17.66-1.41 1.41",
        "m19.07 4.93-1.41 1.41",
        )
    }

    val Sunrise: ImageVector by lazy {
        icon(
            "sunrise",
        "M12 2v8",
        "m4.93 10.93 1.41 1.41",
        "M2 18h2",
        "M20 18h2",
        "m19.07 10.93-1.41 1.41",
        "M22 22H2",
        "m8 6 4-4 4 4",
        "M16 18a4 4 0 0 0-8 0",
        )
    }

    val TriangleAlert: ImageVector by lazy {
        icon(
            "triangle-alert",
        "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3",
        "M12 9v4",
        "M12 17h.01",
        )
    }

    val Utensils: ImageVector by lazy {
        icon(
            "utensils",
        "M3 2v7c0 1.1.9 2 2 2h4a2 2 0 0 0 2-2V2",
        "M7 2v20",
        "M21 15V2a5 5 0 0 0-5 5v6c0 1.1.9 2 2 2h3Zm0 0v7",
        )
    }

    val Wheat: ImageVector by lazy {
        icon(
            "wheat",
        "M2 22 16 8",
        "M3.47 12.53 5 11l1.53 1.53a3.5 3.5 0 0 1 0 4.94L5 19l-1.53-1.53a3.5 3.5 0 0 1 0-4.94Z",
        "M7.47 8.53 9 7l1.53 1.53a3.5 3.5 0 0 1 0 4.94L9 15l-1.53-1.53a3.5 3.5 0 0 1 0-4.94Z",
        "M11.47 4.53 13 3l1.53 1.53a3.5 3.5 0 0 1 0 4.94L13 11l-1.53-1.53a3.5 3.5 0 0 1 0-4.94Z",
        "M20 2h2v2a4 4 0 0 1-4 4h-2V6a4 4 0 0 1 4-4Z",
        "M11.47 17.47 13 19l-1.53 1.53a3.5 3.5 0 0 1-4.94 0L5 19l1.53-1.53a3.5 3.5 0 0 1 4.94 0Z",
        "M15.47 13.47 17 15l-1.53 1.53a3.5 3.5 0 0 1-4.94 0L9 15l1.53-1.53a3.5 3.5 0 0 1 4.94 0Z",
        "M19.47 9.47 21 11l-1.53 1.53a3.5 3.5 0 0 1-4.94 0L13 11l1.53-1.53a3.5 3.5 0 0 1 4.94 0Z",
        )
    }

    val X: ImageVector by lazy {
        icon(
            "x",
        "M18 6 6 18",
        "m6 6 12 12",
        )
    }
}

private fun icon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        for (d in paths) {
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
