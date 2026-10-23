package com.example.synchealth.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.synchealth.ui.theme.LocalVault
import com.example.synchealth.ui.theme.Zahl

/*
 * Die Bausteine der Oberflaeche, nachgebaut nach der Tagesuebersicht im Vault
 * (`00 System/tagesuebersicht.js`): Karten mit 10 dp Radius und feiner Linie, ein Kopf in
 * gesperrten Grossbuchstaben mit Icon, Zeilen aus Icon, Name und Wert mit Trennlinie.
 */

val Radius = RoundedCornerShape(10.dp)

/** Eine Karte wie in der Tagesuebersicht, oder mit [flaeche] wie ein Callout. */
@Composable
fun VaultKarte(
    modifier: Modifier = Modifier,
    flaeche: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val v = LocalVault.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = Radius,
        color = if (flaeche) v.flaeche else v.bg2,
        border = BorderStroke(1.dp, v.linie),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), content = content)
    }
}

/** `GESUNDHEIT`, `ERNÄHRUNG`: gesperrt, im Akzent, mit Linie darunter. */
@Composable
fun KartenKopf(titel: String, icon: ImageVector) {
    val v = LocalVault.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Icon(icon, contentDescription = null, tint = v.akzent, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(titel.uppercase(), style = MaterialTheme.typography.labelMedium, color = v.akzent)
    }
    HorizontalDivider(color = v.linie)
}

/** Eine Zeile der Karte. Ein leerer Wert steht als Strich da, nicht als 0. */
@Composable
fun MesswertZeile(icon: ImageVector, name: String, wert: String?, einheit: String, trenner: Boolean) {
    val v = LocalVault.current
    if (trenner) HorizontalDivider(color = v.linie)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = v.leise, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, color = v.text, modifier = Modifier.weight(1f))
        if (wert == null) {
            Text("—", style = Zahl.copy(fontSize = 17.sp), color = v.leise)
        } else {
            Text(wert, style = Zahl.copy(fontSize = 17.sp), color = v.text)
            if (einheit.isNotEmpty()) {
                Text(" $einheit", style = Zahl.copy(fontSize = 13.sp), color = v.leise)
            }
        }
    }
}

/** Eine kleine Pille fuer ½×, 1×, 2× und Aehnliches. */
@Composable
fun Pille(text: String, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    val v = LocalVault.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(v.bg)
            .border(1.dp, v.linie, RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = v.akzent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = Zahl.copy(fontSize = 14.sp), color = if (enabled) v.akzent else v.leise)
    }
}

/** Das Mengenfeld einer Zutat: Zahl rechtsbuendig, Einheit leise dahinter. */
@Composable
fun MengenFeld(
    value: String,
    einheit: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    breite: Dp = 92.dp,
) {
    val v = LocalVault.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = Zahl.copy(fontSize = 16.sp, color = v.text, textAlign = TextAlign.End),
        cursorBrush = SolidColor(v.akzent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .width(breite)
                    .height(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(v.bg)
                    .border(1.dp, v.linie, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { inner() }
                Text(" $einheit", style = Zahl.copy(fontSize = 13.sp), color = v.leise)
            }
        },
    )
}
