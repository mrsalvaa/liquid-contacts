// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidcontacts.R
import dev.liquidcontacts.contacts.ContactGroup
import dev.liquidcontacts.core.text.ContactSearch
import dev.liquidcontacts.core.vcard.VCardFormat
import dev.liquidcontacts.data.Repo
import dev.liquidcontacts.data.VcfFiles
import dev.liquidcontacts.ui.components.ContactRow
import dev.liquidcontacts.ui.components.SearchField
import dev.liquidcontacts.ui.components.SwitchRow
import dev.liquidcontacts.ui.glass.GlassButton
import dev.liquidcontacts.ui.glass.GlassCard
import dev.liquidcontacts.ui.glass.GlassChip
import dev.liquidcontacts.ui.glass.GlassSwitch
import dev.liquidcontacts.ui.glass.LocalGlass
import dev.liquidcontacts.ui.glass.SectionLabel
import dev.liquidcontacts.util.plural
import dev.liquidcontacts.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ExportScope { ALL, LABEL, SELECTED }

/** Exportar a un archivo (selector del sistema) o compartir: todos, por etiqueta o seleccionados. */
@Composable
fun ExportScreen(padding: PaddingValues, onBack: () -> Unit) {
    val p = LocalGlass.current
    val context = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val book by Repo.book.collectAsStateWithLifecycle()
    val numbers by Repo.numbers.collectAsStateWithLifecycle()
    var what by rememberSaveable { mutableStateOf(ExportScope.ALL) }
    var photos by rememberSaveable { mutableStateOf(true) }
    var format by rememberSaveable { mutableStateOf(VCardFormat.V3) }
    var groups by remember { mutableStateOf<List<ContactGroup>>(emptyList()) }
    var group by remember { mutableStateOf<ContactGroup?>(null) }
    var members by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var query by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { groups = withContext(Dispatchers.IO) { Repo.contacts.groups() } }
    LaunchedEffect(group) {
        members = group?.let { g -> withContext(Dispatchers.IO) { Repo.contacts.groupMembers(g.id) } }.orEmpty()
    }
    val ids: List<Long> = when (what) {
        ExportScope.ALL -> book.rows.map { it.summary.id }
        ExportScope.LABEL -> book.rows.map { it.summary.id }.filter { it in members }
        ExportScope.SELECTED -> book.rows.map { it.summary.id }.filter { it in selected }
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(VcfFiles.MIME)) { uri ->
        if (uri != null) {
            val chosen = ids
            working = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) { VcfFiles.write(context, uri, VcfFiles.contacts(chosen, photos), format, photos) }
                working = false
                toast(if (ok) plural(R.plurals.export_done, chosen.size) else str(R.string.export_failed))
            }
        }
    }
    val share = {
        val chosen = ids
        working = true
        scope.launch {
            val intent = withContext(Dispatchers.IO) { VcfFiles.shareIntent(context, chosen, format, photos) }
            working = false
            if (intent == null) toast(str(R.string.export_failed))
            else (context as? Activity)?.startActivity(intent)
        }
        Unit
    }
    val shownRows = remember(what, query, book, numbers) {
        if (what != ExportScope.SELECTED) emptyList() else {
            val search = ContactSearch(query, numbers::searchForms)
            book.rows.filter { search.matches(it.summary.displayName, it.summary.phones, it.summary.emails) }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding(), start = 16.dp, end = 16.dp),
    ) {
        item(key = "title") { BackTitle(str(R.string.export_title), onBack, subtitle = plural(R.plurals.contacts_count, ids.size)) }
        item(key = "options") {
            Column {
                GlassCard {
                    Text(str(R.string.export_which), fontSize = 16.sp)
                    ChipRow(ExportScope.entries, what, {
                        str(
                            when (it) {
                                ExportScope.ALL -> R.string.export_all
                                ExportScope.LABEL -> R.string.export_label
                                ExportScope.SELECTED -> R.string.export_selected
                            },
                        )
                    }) { what = it }
                    if (what == ExportScope.LABEL) {
                        if (groups.isEmpty()) {
                            Text(str(R.string.no_labels), fontSize = 14.sp, color = p.contentSecondary)
                        } else {
                            FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                groups.forEach { g -> GlassChip(g.title, g == group, { group = g }) }
                            }
                        }
                    }
                    SwitchRow(str(R.string.export_photos), photos, { photos = it })
                    Text(str(R.string.export_format), Modifier.padding(top = 6.dp), fontSize = 16.sp)
                    ChipRow(VCardFormat.entries, format, { str(if (it == VCardFormat.V3) R.string.export_v3 else R.string.export_v4) }) { format = it }
                    Text(str(R.string.export_warning), Modifier.padding(top = 6.dp), fontSize = 13.sp, color = p.contentSecondary)
                }
                Spacer(Modifier.height(12.dp))
                GlassButton(str(R.string.export_save), { saveFile.launch(VcfFiles.exportName()) }, Modifier.fillMaxWidth(), prominent = true, enabled = ids.isNotEmpty() && !working)
                Spacer(Modifier.height(8.dp))
                GlassButton(str(R.string.share), share, Modifier.fillMaxWidth(), enabled = ids.isNotEmpty() && !working)
            }
        }
        if (what == ExportScope.SELECTED) {
            item(key = "pick") {
                Column {
                    SectionLabel(str(R.string.export_pick))
                    SearchField(query, { query = it }, str(R.string.search_hint), str(R.string.clear_search), Modifier.padding(vertical = 4.dp))
                }
            }
            items(shownRows, key = { it.summary.id }, contentType = { "row" }) { r ->
                val id = r.summary.id
                ContactRow(
                    r.summary.displayName.ifBlank { str(R.string.no_name) },
                    { selected = if (id in selected) selected - id else selected + id },
                    photoUri = r.summary.photoUri,
                    trailing = { GlassSwitch(id in selected, { on -> selected = if (on) selected + id else selected - id }) },
                )
            }
        }
    }
}
