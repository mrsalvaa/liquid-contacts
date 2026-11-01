// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable

/** Se completa en el punto de exportación. */
@Composable
fun ExportScreen(padding: PaddingValues, onBack: () -> Unit) {
    BackTitle("", onBack)
    padding.hashCode()
}
