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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Merge
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidcontacts.R
import dev.liquidcontacts.contacts.ContactAccount
import dev.liquidcontacts.contacts.EditableContact
import dev.liquidcontacts.core.dupes.DuplicateConfidence
import dev.liquidcontacts.core.dupes.Duplicates
import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.model.Email
import dev.liquidcontacts.core.model.Name
import dev.liquidcontacts.core.model.Phone
import dev.liquidcontacts.core.vcard.ImportAction
import dev.liquidcontacts.core.vcard.ImportMatch
import dev.liquidcontacts.core.vcard.ImportMatcher
import dev.liquidcontacts.core.vcard.VCardFormat
import dev.liquidcontacts.core.vcard.VCardImport
import dev.liquidcontacts.data.Repo
import dev.liquidcontacts.data.Stores
import dev.liquidcontacts.data.VcfFiles
import dev.liquidcontacts.ui.components.AccountDropdown
import dev.liquidcontacts.ui.components.ContactRow
import dev.liquidcontacts.ui.components.LocalUndo
import dev.liquidcontacts.ui.components.SwitchRow
import dev.liquidcontacts.ui.components.UndoSpec
import dev.liquidcontacts.ui.glass.DialogSpec
import dev.liquidcontacts.ui.glass.GlassButton
import dev.liquidcontacts.ui.glass.GlassCard
import dev.liquidcontacts.ui.glass.GlassChip
import dev.liquidcontacts.ui.glass.LocalActionSheet
import dev.liquidcontacts.ui.glass.LocalDialog
import dev.liquidcontacts.ui.glass.LocalGlass
import dev.liquidcontacts.ui.glass.SectionLabel
import dev.liquidcontacts.ui.glass.SheetAction
import dev.liquidcontacts.ui.glass.SheetSpec
import dev.liquidcontacts.util.plural
import dev.liquidcontacts.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Vista previa de un .vcf antes de guardar nada: cuántos son y quiénes, en qué cuenta y qué hacer
 * con los que ya están en la agenda (por defecto se omiten; unir hay que pedirlo y confirmarlo).
 */
@Composable
fun ImportScreen(data: VCardImport.Ok, padding: PaddingValues, onBack: () -> Unit) {
    val p = LocalGlass.current
    val dialog = LocalDialog.current
    val sheet = LocalActionSheet.current
    val toast = LocalToast.current
    val undo = LocalUndo.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val book by Repo.book.collectAsStateWithLifecycle()
    val numbers by Repo.numbers.collectAsStateWithLifecycle()

    var accounts by remember { mutableStateOf<List<ContactAccount>>(emptyList()) }
    var local by remember { mutableStateOf<ContactAccount?>(null) }
    var account by remember { mutableStateOf<ContactAccount?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val loc = Repo.contacts.localAccount()
            local = loc
            accounts = (listOf(loc) + Repo.allAccounts()).distinct()
            val preferred = Stores.settings.value.newContactAccount.takeIf { it.isNotEmpty() }?.let(ContactAccount::fromId)
            account = preferred?.takeIf { it in accounts } ?: loc
        }
    }
    // Se compara una sola vez, contra la agenda al abrir: lo que se decide no cambia solo.
    var matches by remember { mutableStateOf<List<ImportMatch?>?>(null) }
    val actions = remember { mutableStateMapOf<Int, ImportAction>() }
    // Con qué contacto se une cada uno: si hay varios candidatos, lo elige el usuario.
    val targets = remember { mutableStateMapOf<Int, Long>() }
    LaunchedEffect(book.loaded) {
        if (book.loaded && matches == null) {
            matches = withContext(Dispatchers.Default) {
                val existing = book.rows.map { r ->
                    Contact(id = r.summary.id, name = Name(display = r.summary.displayName), phones = r.summary.phones.map { Phone(it) }, emails = r.summary.emails.map { Email(it) })
                }
                val matcher = ImportMatcher(numbers, existing)
                data.contacts.map(matcher::match)
            }
        }
    }
    val found = matches
    fun actionOf(i: Int): ImportAction = actions[i] ?: if (found?.getOrNull(i) == null) ImportAction.NEW else ImportAction.SKIP
    fun actionLabel(a: ImportAction): String = str(
        when (a) {
            ImportAction.NEW -> R.string.import_action_new
            ImportAction.SKIP -> R.string.import_action_skip
            ImportAction.MERGE -> R.string.import_action_merge
        },
    )
    var progress by remember { mutableIntStateOf(-1) }
    val working = progress >= 0
    val count = { a: ImportAction -> data.contacts.indices.count { actionOf(it) == a } }

    fun run() {
        val target = account ?: return
        val plan = data.contacts.indices.map { i -> Triple(data.contacts[i], actionOf(i), targets[i]) }
        progress = 0
        Repo.launch {
            var created = 0
            var merged = 0
            var failed = 0
            // Copia en memoria de cada ficha antes de sumarle datos, para el Deshacer.
            val before = LinkedHashMap<Long, EditableContact>()
            plan.forEachIndexed { i, (imported, action, targetId) ->
                // Una falla (por ejemplo, un dato que el proveedor rechaza) no corta el resto.
                val ok = runCatching {
                    val c = imported.copy(photo = imported.photo?.let(VcfFiles::normalizePhoto))
                    when (action) {
                        ImportAction.NEW -> Repo.contacts.save(EditableContact(null, target, c), photoChanged = c.photo != null).also { created++ }
                        ImportAction.MERGE -> {
                            val id = targetId ?: error("sin contacto para unir")
                            val current = Repo.contacts.loadForEdit(id) ?: error("el contacto ya no existe")
                            before.putIfAbsent(current.rawContactId!!, current)
                            val result = Duplicates(numbers).merge(listOf(current.contact, c))
                            val photoChanged = current.contact.photo == null && result.photo != null
                            Repo.contacts.save(EditableContact(current.rawContactId, current.account, result), photoChanged).also { merged++ }
                        }
                        ImportAction.SKIP -> Unit
                    }
                }.isSuccess
                if (!ok) failed++
                withContext(Dispatchers.Main) { progress = i + 1 }
            }
            withContext(Dispatchers.Main) {
                val summary = str(R.string.import_done, created, merged, failed)
                if (before.isEmpty()) {
                    toast(summary)
                } else {
                    val copy = before.values.toList()
                    undo(
                        UndoSpec(summary, str(R.string.undo)) {
                            Repo.launch {
                                val ok = runCatching { Repo.contacts.restore(copy) }.isSuccess
                                withContext(Dispatchers.Main) { toast(str(if (ok) R.string.merge_undone else R.string.merge_undo_failed)) }
                            }
                        },
                    )
                }
                progress = -1
                onBack()
            }
        }
    }

    var backup by remember { mutableStateOf(false) }
    fun confirm() {
        val a = account ?: return
        dialog(
            DialogSpec(str(R.string.import_confirm_title)) { dismiss ->
                Text(
                    str(R.string.import_confirm_text, count(ImportAction.NEW), Labels.account(a, local), count(ImportAction.MERGE), count(ImportAction.SKIP)),
                    fontSize = 15.sp,
                    color = p.contentSecondary,
                )
                Spacer(Modifier.height(16.dp))
                GlassButton(str(R.string.import_button), { dismiss(); run() }, Modifier.fillMaxWidth(), prominent = true)
                Spacer(Modifier.height(8.dp))
                GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
            },
        )
    }
    // Con respaldo: primero el archivo con los contactos que se van a tocar; si falla, no se importa.
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(VcfFiles.MIME)) { uri ->
        if (uri != null) {
            scope.launch {
                val ids = data.contacts.indices.filter { actionOf(it) == ImportAction.MERGE }.mapNotNull { targets[it] }.distinct()
                val ok = withContext(Dispatchers.IO) { VcfFiles.write(context, uri, VcfFiles.contacts(ids, withPhotos = true), VCardFormat.V3, includePhoto = true) }
                toast(str(if (ok) R.string.backup_saved else R.string.backup_failed_import))
                if (ok) confirm()
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding(), start = 16.dp, end = 16.dp),
    ) {
        item(key = "title") { BackTitle(str(R.string.import_title), onBack, subtitle = plural(R.plurals.contacts_count, data.contacts.size)) }
        val notes = listOfNotNull(
            str(R.string.import_truncated).takeIf { data.truncated },
            plural(R.plurals.import_skipped, data.skipped).takeIf { data.skipped > 0 },
            plural(R.plurals.import_photos_dropped, data.photosDropped).takeIf { data.photosDropped > 0 },
        )
        if (notes.isNotEmpty()) {
            item(key = "notes") {
                GlassCard(Modifier.padding(vertical = 6.dp)) {
                    notes.forEach { Text(it, Modifier.padding(vertical = 3.dp), fontSize = 14.sp, color = p.contentSecondary) }
                }
            }
        }
        if (account != null) {
            item(key = "account") {
                Column {
                    SectionLabel(str(R.string.save_in))
                    GlassCard {
                        AccountDropdown(null, accounts, account, local) { account = it }
                    }
                }
            }
        }
        item(key = "action") {
            Column(Modifier.padding(vertical = 12.dp)) {
                if (working) {
                    Text(
                        str(R.string.import_progress, progress, data.contacts.size),
                        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        fontSize = 15.sp,
                    )
                } else {
                    Text(
                        str(R.string.import_summary, count(ImportAction.NEW), count(ImportAction.MERGE), count(ImportAction.SKIP)),
                        fontSize = 14.sp,
                        color = p.contentSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (count(ImportAction.MERGE) > 0) {
                        SwitchRow(str(R.string.import_backup), backup, { backup = it }, subtitle = str(R.string.merge_backup_hint))
                    }
                    GlassButton(
                        str(R.string.import_button),
                        { if (backup && count(ImportAction.MERGE) > 0) saveBackup.launch(VcfFiles.backupName()) else confirm() },
                        Modifier.fillMaxWidth(),
                        prominent = true,
                        enabled = found != null && count(ImportAction.NEW) + count(ImportAction.MERGE) > 0,
                    )
                }
            }
        }
        item(key = "list") { SectionLabel(str(R.string.import_list)) }
        itemsIndexed(data.contacts, contentType = { _, _ -> "row" }) { i, c ->
            val match = found?.getOrNull(i)
            val detail = when {
                match == null -> listOfNotNull(c.phones.firstOrNull()?.number?.let(numbers::format), c.emails.firstOrNull()?.address).joinToString(" · ")
                match.confidence == DuplicateConfidence.HIGH -> str(R.string.import_exists, match.existing.first().displayName)
                else -> str(R.string.import_maybe_exists, match.existing.first().displayName)
            }
            val current = actionOf(i)
            val choose = {
                if (!working) {
                    sheet(
                        SheetSpec(
                            c.displayName,
                            buildList {
                                add(SheetAction(actionLabel(ImportAction.NEW), Icons.Rounded.Add) { actions[i] = ImportAction.NEW })
                                match?.existing?.take(8)?.forEach { e ->
                                    val who = listOfNotNull(e.displayName, e.phones.firstOrNull()?.number?.let(numbers::format) ?: e.emails.firstOrNull()?.address).joinToString(" · ")
                                    add(
                                        SheetAction(str(R.string.import_merge_with, who), Icons.Rounded.Merge) {
                                            targets[i] = e.id!!
                                            actions[i] = ImportAction.MERGE
                                        },
                                    )
                                }
                                add(SheetAction(actionLabel(ImportAction.SKIP), Icons.Rounded.Close) { actions[i] = ImportAction.SKIP })
                            },
                        ),
                    )
                }
            }
            ContactRow(
                c.displayName.ifBlank { str(R.string.no_name) },
                choose,
                subtitle = detail,
                photo = c.photo,
                trailing = { GlassChip(actionLabel(current), false, choose, Modifier.padding(start = 8.dp)) },
            )
        }
    }
}
