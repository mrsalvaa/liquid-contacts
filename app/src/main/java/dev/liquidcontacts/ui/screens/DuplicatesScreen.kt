// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidcontacts.R
import dev.liquidcontacts.core.dupes.DuplicateConfidence
import dev.liquidcontacts.core.dupes.DuplicateGroup
import dev.liquidcontacts.core.dupes.DuplicateReason
import dev.liquidcontacts.core.dupes.Duplicates
import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.model.Email
import dev.liquidcontacts.core.model.Name
import dev.liquidcontacts.core.model.Phone
import dev.liquidcontacts.core.vcard.VCardFormat
import dev.liquidcontacts.data.Repo
import dev.liquidcontacts.data.VcfFiles
import dev.liquidcontacts.ui.components.LocalUndo
import dev.liquidcontacts.ui.components.UndoSpec
import dev.liquidcontacts.ui.components.ContactRow
import dev.liquidcontacts.ui.glass.DialogSpec
import dev.liquidcontacts.ui.glass.GlassButton
import dev.liquidcontacts.ui.glass.GlassCard
import dev.liquidcontacts.ui.glass.GlassSwitch
import dev.liquidcontacts.ui.glass.LocalDialog
import dev.liquidcontacts.ui.glass.LocalGlass
import dev.liquidcontacts.ui.glass.SectionLabel
import dev.liquidcontacts.ui.glass.SettingRow
import dev.liquidcontacts.util.plural
import dev.liquidcontacts.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Grupos de posibles duplicados, separados por confianza. Tocar un grupo abre la vista previa:
 * nada se une sin confirmar ahí.
 */
@Composable
fun DuplicatesScreen(padding: PaddingValues, onBack: () -> Unit, onReview: (List<Long>, Boolean) -> Unit) {
    val p = LocalGlass.current
    val book by Repo.book.collectAsStateWithLifecycle()
    val numbers by Repo.numbers.collectAsStateWithLifecycle()
    var groups by remember { mutableStateOf<List<DuplicateGroup>?>(null) }
    LaunchedEffect(book, numbers) {
        groups = withContext(Dispatchers.Default) {
            // Para detectar alcanza con nombre, números y emails de la lista (sin leer cada contacto).
            val contacts = book.rows.map { r ->
                Contact(id = r.summary.id, name = Name(display = r.summary.displayName), phones = r.summary.phones.map { Phone(it) }, emails = r.summary.emails.map { Email(it) })
            }
            Duplicates(numbers).find(contacts)
        }
    }
    val high = groups.orEmpty().filter { it.confidence == DuplicateConfidence.HIGH }
    val low = groups.orEmpty().filter { it.confidence == DuplicateConfidence.LOW }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding(), start = 16.dp, end = 16.dp),
    ) {
        item(key = "title") { BackTitle(str(R.string.duplicates), onBack) }
        if (groups != null && groups!!.isEmpty()) item(key = "empty") { EmptyState(str(R.string.no_duplicates), str(R.string.no_duplicates_hint)) }
        if (high.isNotEmpty()) item(key = "high") { SectionLabel(str(R.string.duplicates_high)) }
        items(high, key = { "h" + it.contacts.joinToString { c -> c.id.toString() } }) { g -> GroupCard(g) { onReview(g.contacts.mapNotNull { it.id }, true) } }
        if (low.isNotEmpty()) {
            item(key = "low") {
                Column {
                    SectionLabel(str(R.string.duplicates_low))
                    Text(str(R.string.duplicates_low_hint), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 13.sp, color = p.contentSecondary)
                }
            }
        }
        items(low, key = { "l" + it.contacts.joinToString { c -> c.id.toString() } }) { g -> GroupCard(g) { onReview(g.contacts.mapNotNull { it.id }, false) } }
    }
}

@Composable
private fun GroupCard(g: DuplicateGroup, onClick: () -> Unit) {
    val p = LocalGlass.current
    GlassCard(Modifier.padding(vertical = 6.dp), onClick = onClick) {
        if (g.confidence == DuplicateConfidence.LOW) {
            Text(str(R.string.possible_duplicate), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = p.accentFg)
        }
        Text(g.contacts.joinToString(" · ") { it.displayName.ifBlank { str(R.string.no_name) } }, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(g.reasons.sorted().joinToString(", ") { reason(it) }, fontSize = 13.sp, color = p.contentSecondary)
    }
}

private fun reason(r: DuplicateReason): String = str(
    when (r) {
        DuplicateReason.SAME_NAME -> R.string.reason_name
        DuplicateReason.SAME_PHONE -> R.string.reason_phone
        DuplicateReason.SAME_EMAIL -> R.string.reason_email
    },
)

/**
 * Vista previa de la unión: cada contacto del grupo con un interruptor (los de confianza baja
 * empiezan sin marcar), el resultado de unir los marcados y la confirmación explícita.
 */
@Composable
fun MergeScreen(contactIds: List<Long>, preselected: Boolean, padding: PaddingValues, onBack: () -> Unit) {
    val p = LocalGlass.current
    val context = LocalContext.current
    val dialog = LocalDialog.current
    val toast = LocalToast.current
    val undo = LocalUndo.current
    val scope = rememberCoroutineScope()
    val numbers by Repo.numbers.collectAsStateWithLifecycle()
    var contacts by remember { mutableStateOf<List<Contact>?>(null) }
    var chosen by remember { mutableStateOf(if (preselected) contactIds.toSet() else emptySet()) }
    var merging by remember { mutableStateOf(false) }
    var backup by remember { mutableStateOf(false) }
    var deletes by remember { mutableStateOf(true) }
    LaunchedEffect(contactIds) { contacts = withContext(Dispatchers.IO) { contactIds.mapNotNull { Repo.contacts.loadFull(it) } } }
    LaunchedEffect(chosen) {
        if (chosen.size >= 2) deletes = withContext(Dispatchers.IO) { Repo.contacts.mergeDeletes(chosen.toList()) }
    }
    val list = contacts ?: return
    val selected = list.filter { it.id in chosen }
    val preview = remember(selected, numbers) { if (selected.size >= 2) Duplicates(numbers).merge(selected) else null }

    fun merge(ids: List<Long>, merged: Contact) {
        merging = true
        Repo.launch {
            val result = runCatching { Repo.contacts.merge(ids, merged) }.getOrNull()
            withContext(Dispatchers.Main) {
                merging = false
                when {
                    result == null -> toast(str(R.string.merge_failed))
                    result.undo != null -> {
                        val copy = result.undo!!
                        undo(
                            UndoSpec(str(R.string.merged), str(R.string.undo)) {
                                Repo.launch {
                                    val ok = runCatching { Repo.contacts.undoMerge(copy) }.isSuccess
                                    withContext(Dispatchers.Main) { toast(str(if (ok) R.string.merge_undone else R.string.merge_undo_failed)) }
                                }
                            },
                        )
                        onBack()
                    }
                    else -> {
                        toast(str(R.string.merged))
                        onBack()
                    }
                }
            }
        }
    }
    fun confirm() {
        val ids = selected.mapNotNull { it.id }
        val merged = preview ?: return
        dialog(
            DialogSpec(plural(R.plurals.merge_confirm_title, ids.size)) { dismiss ->
                Text(str(if (deletes) R.string.merge_confirm_deletes else R.string.merge_confirm_links), fontSize = 15.sp, color = p.contentSecondary)
                Spacer(Modifier.height(16.dp))
                GlassButton(str(R.string.merge), {
                    dismiss()
                    merge(ids, merged)
                }, Modifier.fillMaxWidth(), prominent = true)
                Spacer(Modifier.height(8.dp))
                GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
            },
        )
    }
    // Con respaldo: primero el archivo; si no se pudo guardar, no se une nada.
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(VcfFiles.MIME)) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { VcfFiles.write(context, uri, selected.asSequence(), VCardFormat.V3, includePhoto = true) }
                toast(str(if (ok) R.string.backup_saved else R.string.backup_failed))
                if (ok) confirm()
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        BackTitle(str(R.string.merge_title), onBack)
        Text(str(if (preselected) R.string.merge_hint else R.string.merge_hint_low), Modifier.padding(horizontal = 8.dp), fontSize = 14.sp, color = p.contentSecondary)
        SectionLabel(str(R.string.merge_contacts))
        GlassCard {
            list.forEach { c ->
                ContactRow(
                    c.displayName.ifBlank { str(R.string.no_name) },
                    { chosen = if (c.id in chosen) chosen - c.id!! else chosen + c.id!! },
                    subtitle = listOfNotNull(c.phones.firstOrNull()?.number?.let(numbers::format), c.emails.firstOrNull()?.address).joinToString(" · "),
                    trailing = { GlassSwitch(c.id in chosen, { on -> chosen = if (on) chosen + c.id!! else chosen - c.id!! }) },
                )
            }
        }
        SectionLabel(str(R.string.merge_result))
        if (preview == null) {
            GlassCard { Text(str(R.string.merge_pick_two), Modifier.padding(vertical = 8.dp), fontSize = 15.sp, color = p.contentSecondary) }
        } else {
            GlassCard {
                Text(preview.displayName.ifBlank { str(R.string.no_name) }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                listOfNotNull(preview.organization, preview.jobTitle).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, fontSize = 14.sp, color = p.contentSecondary)
                }
                PreviewLines(str(R.string.phones), preview.phones.map { numbers.format(it.number) + " · " + Labels.of(it) })
                PreviewLines(str(R.string.emails), preview.emails.map { it.address + " · " + Labels.of(it) })
                PreviewLines(str(R.string.addresses), preview.addresses.map { it.formatted })
                PreviewLines(str(R.string.dates), preview.events.map { Labels.of(it) + ": " + formatDate(it) })
                PreviewLines(str(R.string.websites), preview.websites)
                PreviewLines(str(R.string.tab_labels), listOfNotNull(preview.groups.sorted().joinToString(", ").ifEmpty { null }))
                PreviewLines(str(R.string.note), listOfNotNull(preview.note))
            }
        }
        Spacer(Modifier.height(12.dp))
        GlassCard {
            SettingRow(
                str(R.string.merge_backup),
                subtitle = str(R.string.merge_backup_hint),
                trailing = { GlassSwitch(backup, { backup = it }) },
            )
        }
        Spacer(Modifier.height(16.dp))
        GlassButton(
            if (selected.size >= 2) plural(R.plurals.merge_n, selected.size) else str(R.string.merge),
            { if (backup) saveBackup.launch(VcfFiles.backupName()) else confirm() },
            Modifier.fillMaxWidth(),
            prominent = true,
            enabled = selected.size >= 2 && !merging,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PreviewLines(title: String, lines: List<String>) {
    if (lines.isEmpty()) return
    val p = LocalGlass.current
    Text(title, Modifier.padding(top = 12.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = p.contentSecondary)
    lines.forEach { Text(it, Modifier.fillMaxWidth().padding(vertical = 2.dp), fontSize = 15.sp) }
}
