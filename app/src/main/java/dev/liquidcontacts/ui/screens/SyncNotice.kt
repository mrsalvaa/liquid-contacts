// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidcontacts.R
import dev.liquidcontacts.ui.glass.DialogSpec
import dev.liquidcontacts.ui.glass.GlassButton
import dev.liquidcontacts.ui.glass.LocalGlass
import dev.liquidcontacts.util.str

/** La ventana de "guardado en el celular": un cambio para el servidor que espera la conexión. */
fun offlineNotice(): DialogSpec = DialogSpec(str(R.string.sync_offline_title)) { dismiss ->
    Text(str(R.string.sync_offline_text), fontSize = 15.sp, color = LocalGlass.current.contentSecondary)
    Spacer(Modifier.height(16.dp))
    GlassButton(str(R.string.done), dismiss, Modifier.fillMaxWidth(), prominent = true)
}
