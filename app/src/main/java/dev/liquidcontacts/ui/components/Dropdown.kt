// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidcontacts.R
import dev.liquidcontacts.contacts.ContactAccount
import dev.liquidcontacts.core.text.SpanishOrder
import dev.liquidcontacts.sync.SyncEngine
import dev.liquidcontacts.ui.glass.DialogSpec
import dev.liquidcontacts.ui.glass.GlassButton
import dev.liquidcontacts.ui.glass.LocalDialog
import dev.liquidcontacts.ui.glass.LocalGlass
import dev.liquidcontacts.ui.glass.SettingRow
import dev.liquidcontacts.ui.glass.styled
import dev.liquidcontacts.ui.screens.Labels
import dev.liquidcontacts.util.str

/**
 * Fila de un desplegable: lo elegido a la vista y una flecha hacia abajo. Las opciones se abren en
 * una ventana emergente. Sin [title], lo elegido es el título (cuando arriba ya hay un rótulo).
 */
@Composable
fun DropdownRow(title: String?, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalGlass.current
    SettingRow(
        title ?: value,
        modifier.heightIn(min = 48.dp),
        subtitle = value.takeIf { title != null },
        onClick = onClick,
        trailing = { Icon(styled(Icons.Rounded.ExpandMore), null, tint = p.contentSecondary) },
    )
}

/** Una opción dentro de la ventana; la elegida lleva la marca. */
@Composable
fun OptionRow(title: String, chosen: Boolean, onClick: () -> Unit, subtitle: String? = null) {
    val p = LocalGlass.current
    SettingRow(
        title,
        Modifier.heightIn(min = 48.dp).semantics { selected = chosen },
        subtitle = subtitle,
        onClick = onClick,
        trailing = if (chosen) ({ Icon(styled(Icons.Rounded.Check), null, tint = p.accentFg) }) else null,
    )
}

/** Un desplegable para elegir una opción entre varias. [placeholder] es lo que dice sin nada elegido. */
@Composable
fun <T> Dropdown(
    title: String,
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    onPick: (T) -> Unit,
) {
    val dialog = LocalDialog.current
    DropdownRow(
        title,
        selected?.let(label) ?: placeholder,
        {
            dialog(
                DialogSpec(title) { dismiss ->
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(options) { option ->
                            OptionRow(label(option), option == selected, {
                                onPick(option)
                                dismiss()
                            })
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
                },
            )
        },
        modifier,
    )
}

/**
 * El desplegable de dónde guardar, en orden alfabético. Con [onServer], el servidor figura siempre:
 * si todavía no hay una libreta donde guardar, la opción lleva a activarla.
 */
@Composable
fun AccountDropdown(
    title: String?,
    accounts: List<ContactAccount>,
    selected: ContactAccount?,
    local: ContactAccount?,
    modifier: Modifier = Modifier,
    onServer: (() -> Unit)? = null,
    onPick: (ContactAccount) -> Unit,
) {
    val p = LocalGlass.current
    val dialog = LocalDialog.current
    val order = remember { SpanishOrder() }
    val options = accounts.map { it to Labels.account(it, local) }.sortedWith { x, y -> order.compare(x.second, y.second) }
    DropdownRow(
        title,
        options.firstOrNull { it.first == selected }?.second.orEmpty(),
        {
            dialog(
                DialogSpec(str(R.string.save_in)) { dismiss ->
                    val server by SyncEngine.store.state.collectAsStateWithLifecycle()
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(options) { (account, label) ->
                            OptionRow(label, account == selected, {
                                onPick(account)
                                dismiss()
                            })
                        }
                        if (onServer != null && server.books.none { it.firstSyncDone && !it.readOnly }) {
                            item {
                                SettingRow(
                                    server.nickname.ifEmpty { str(R.string.server_title) },
                                    subtitle = str(if (server.configured) R.string.save_in_no_book else R.string.save_in_no_server),
                                    onClick = {
                                        dismiss()
                                        onServer()
                                    },
                                    trailing = { Icon(styled(Icons.AutoMirrored.Rounded.KeyboardArrowRight), null, tint = p.contentSecondary) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
                },
            )
        },
        modifier,
    )
}
