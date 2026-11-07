// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidcontacts.R
import dev.liquidcontacts.core.avatar.Avatars
import dev.liquidcontacts.data.AppSettings
import dev.liquidcontacts.data.Stores
import dev.liquidcontacts.ui.glass.ACCENT_HUES
import dev.liquidcontacts.ui.glass.GlassButton
import dev.liquidcontacts.ui.glass.LocalGlass
import dev.liquidcontacts.ui.glass.muted
import dev.liquidcontacts.ui.glass.readableOn
import dev.liquidcontacts.ui.glass.styled
import dev.liquidcontacts.util.str
import java.util.Locale

// Color de las etiquetas. Android no guarda colores para los grupos de contactos: se guardan en los
// ajustes de la app, por nombre. Sin color elegido, cada etiqueta lleva uno fijo según su nombre,
// de los mismos 9 tonos que los avatares (sin el gris).

private fun key(title: String) = title.trim().lowercase(Locale.ROOT)

/** Índice en ACCENT_HUES del color de la etiqueta. */
fun labelHue(title: String, s: AppSettings): Int =
    s.labelColors[key(title)]?.takeIf { it in ACCENT_HUES.indices } ?: Avatars.colorIndex(key(title), ACCENT_HUES.size - 1)

/** El color con que se dibuja la etiqueta, apagado como el resto de la suite. */
@Composable
fun labelColor(title: String, s: AppSettings): Color = hueColor(labelHue(title, s))

@Composable
private fun hueColor(hue: Int): Color = muted(ACCENT_HUES[hue].tones[if (LocalGlass.current.dark) 0 else 1])

/** Al renombrar una etiqueta, el color elegido pasa al nombre nuevo. */
fun moveLabelColor(from: String, to: String) {
    if (key(from) == key(to)) return
    Stores.settings.update { s ->
        val c = s.labelColors[key(from)] ?: return@update s
        s.copy(labelColors = s.labelColors - key(from) + (key(to) to c))
    }
}

/** Al borrar una etiqueta se olvida su color (una nueva con el mismo nombre empieza con el automático). */
fun forgetLabelColor(title: String) {
    Stores.settings.update { s -> if (key(title) in s.labelColors) s.copy(labelColors = s.labelColors - key(title)) else s }
}

/** Ícono de etiqueta en su color, para el principio de una fila. */
@Composable
fun LabelDot(title: String, s: AppSettings) {
    val c = labelColor(title, s)
    Box(Modifier.size(36.dp).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
        Icon(styled(Icons.AutoMirrored.Rounded.Label), null, Modifier.size(20.dp), tint = readableOn(c))
    }
}

/** Las etiquetas de un contacto como píldoras de color. */
@Composable
fun LabelPills(titles: List<String>, modifier: Modifier = Modifier) {
    val s by Stores.settings.state.collectAsStateWithLifecycle()
    val p = LocalGlass.current
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        titles.forEach { t ->
            val c = labelColor(t, s)
            Row(
                Modifier.clip(CircleShape).background(c.copy(alpha = 0.2f)).padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                Spacer(Modifier.size(8.dp))
                Text(t, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = p.content, maxLines = 1)
            }
        }
    }
}

/** Los 10 colores (dos filas de 5) para elegir el de una etiqueta. */
@Composable
fun LabelColorPicker(title: String, s: AppSettings, onDone: () -> Unit) {
    val current = labelHue(title, s)
    val p = LocalGlass.current
    ACCENT_HUES.indices.chunked(5).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            row.forEach { i ->
                val c = hueColor(i)
                val name = str(ACCENT_HUES[i].name)
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(c)
                        .then(if (i == current) Modifier.border(3.dp, p.content, CircleShape) else Modifier)
                        .semantics { contentDescription = name; selected = i == current }
                        .clickable(role = Role.RadioButton) {
                            Stores.settings.update { it.copy(labelColors = it.labelColors + (key(title) to i)) }
                            onDone()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (i == current) Icon(styled(Icons.Rounded.Check), null, Modifier.size(22.dp), tint = readableOn(c))
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    GlassButton(str(R.string.cancel), onDone, Modifier.fillMaxWidth())
}
