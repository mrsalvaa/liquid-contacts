// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidcontacts.R
import dev.liquidcontacts.data.Repo
import dev.liquidcontacts.data.Trash
import dev.liquidcontacts.data.TrashEntry
import dev.liquidcontacts.ui.components.ContactRow
import dev.liquidcontacts.ui.components.DangerButton
import dev.liquidcontacts.ui.glass.DialogSpec
import dev.liquidcontacts.ui.glass.GlassButton
import dev.liquidcontacts.ui.glass.LocalActionSheet
import dev.liquidcontacts.ui.glass.LocalDialog
import dev.liquidcontacts.ui.glass.LocalGlass
import dev.liquidcontacts.ui.glass.SheetAction
import dev.liquidcontacts.ui.glass.SheetSpec
import dev.liquidcontacts.util.plural
import dev.liquidcontacts.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Contactos borrados desde esta app, los más recientes primero. */
@Composable
fun TrashScreen(padding: PaddingValues, onBack: () -> Unit) {
    val p = LocalGlass.current
    val sheet = LocalActionSheet.current
    val dialog = LocalDialog.current
    val toast = LocalToast.current
    val state by Trash.store.state.collectAsStateWithLifecycle()

    fun restore(e: TrashEntry) = Repo.launch {
        val done = runCatching { Trash.restore(listOf(e.id)) }.isSuccess
        withContext(Dispatchers.Main) { toast(str(if (done) R.string.trash_restored else R.string.trash_restore_failed, e.name.ifBlank { str(R.string.no_name) })) }
    }
    fun confirm(title: String, action: String, run: () -> Unit) = dialog(
        DialogSpec(title) { dismiss ->
            Text(str(R.string.trash_forever_text), fontSize = 15.sp, color = p.contentSecondary)
            Spacer(Modifier.height(16.dp))
            DangerButton(action, {
                dismiss()
                Repo.launch { run() }
            }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
        },
    )

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding(), start = 16.dp, end = 16.dp),
    ) {
        item(key = "title") {
            BackTitle(str(R.string.trash), onBack, subtitle = state.entries.takeIf { it.isNotEmpty() }?.let { plural(R.plurals.contacts_count, it.size) })
        }
        if (state.entries.isNotEmpty()) {
            item(key = "empty-all") {
                DangerButton(str(R.string.trash_empty_action), {
                    confirm(str(R.string.trash_empty_title), str(R.string.trash_empty_action)) { Trash.purgeAll() }
                }, Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp))
            }
        }
        item(key = "hint") {
            Text(str(R.string.trash_hint, Trash.DAYS), Modifier.padding(horizontal = 8.dp, vertical = 6.dp), fontSize = 13.sp, color = p.contentSecondary)
        }
        if (state.entries.isEmpty()) {
            item(key = "empty") { EmptyState(str(R.string.trash_empty), str(R.string.trash_empty_hint)) }
        }
        items(state.entries, key = { it.id }) { e ->
            val name = e.name.ifBlank { str(R.string.no_name) }
            ContactRow(
                name,
                {
                    sheet(
                        SheetSpec(
                            name,
                            listOf(
                                SheetAction(str(R.string.trash_restore), Icons.Rounded.RestoreFromTrash) { restore(e) },
                                SheetAction(str(R.string.trash_forever), Icons.Rounded.DeleteForever) {
                                    confirm(str(R.string.trash_forever_title, name), str(R.string.trash_forever)) { Trash.purge(listOf(e.id)) }
                                },
                            ),
                        ),
                    )
                },
                subtitle = plural(R.plurals.trash_days_left, Trash.daysLeft(e), Trash.daysLeft(e)),
                photoUri = Trash.photoUri(e),
            )
        }
    }
}
