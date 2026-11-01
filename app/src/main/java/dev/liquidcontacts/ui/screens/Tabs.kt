// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidcontacts.R
import dev.liquidcontacts.contacts.ContactGroup
import dev.liquidcontacts.core.text.ContactSearch
import dev.liquidcontacts.core.text.SpanishOrder
import dev.liquidcontacts.data.Repo
import dev.liquidcontacts.data.Row
import dev.liquidcontacts.data.Stores
import dev.liquidcontacts.ui.components.DangerButton
import dev.liquidcontacts.ui.components.dangerText
import dev.liquidcontacts.ui.components.AlphabetIndex
import dev.liquidcontacts.ui.components.ContactRow
import dev.liquidcontacts.ui.components.LetterHeader
import dev.liquidcontacts.ui.components.NoticeCard
import dev.liquidcontacts.ui.components.SearchField
import dev.liquidcontacts.ui.glass.GlassButton
import dev.liquidcontacts.ui.glass.GlassCard
import dev.liquidcontacts.ui.glass.GlassIconButton
import dev.liquidcontacts.ui.glass.LocalActionSheet
import dev.liquidcontacts.ui.glass.LocalDialog
import dev.liquidcontacts.ui.glass.LocalGlass
import dev.liquidcontacts.ui.glass.LocalPrompt
import dev.liquidcontacts.ui.glass.PromptSpec
import dev.liquidcontacts.ui.glass.ScreenTitle
import dev.liquidcontacts.ui.glass.SettingRow
import dev.liquidcontacts.ui.glass.SheetAction
import dev.liquidcontacts.ui.glass.SheetSpec
import dev.liquidcontacts.util.plural
import dev.liquidcontacts.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lo que necesitan las pestañas para abrir otras pantallas. */
class TabContext(
    val padding: PaddingValues,
    val onContact: (Long) -> Unit,
    val onSettings: () -> Unit,
    val onAreaCode: () -> Unit,
    val onLabel: (ContactGroup) -> Unit,
)

// ---------------------------------------------------------------- permiso

/** Explica para qué es el permiso antes de que aparezca el diálogo del sistema. */
@Composable
fun PermissionScreen(padding: PaddingValues, onResult: () -> Unit) {
    val p = LocalGlass.current
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        denied = result.values.any { !it }
        onResult()
    }
    Column(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
    ) {
        ScreenTitle(str(R.string.app_name))
        GlassCard {
            Text(str(R.string.permission_title), fontSize = 20.sp, color = p.content)
            Text(str(R.string.permission_text), Modifier.padding(top = 10.dp), fontSize = 15.sp, color = p.contentSecondary)
            Text(str(R.string.permission_privacy), Modifier.padding(top = 10.dp), fontSize = 15.sp, color = p.contentSecondary)
            if (denied) {
                Text(str(R.string.permission_denied), Modifier.padding(top = 10.dp), fontSize = 14.sp, color = p.dangerText)
            }
            Spacer(Modifier.height(18.dp))
            GlassButton(
                str(R.string.permission_continue),
                { launcher.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)) },
                Modifier.fillMaxWidth(),
                prominent = true,
            )
        }
    }
}

// ---------------------------------------------------------------- contactos

@Composable
fun ContactsTab(ctx: TabContext) {
    val book by Repo.book.collectAsStateWithLifecycle()
    val numbers by Repo.numbers.collectAsStateWithLifecycle()
    val settings by Stores.settings.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    // Al irse de la lista se cierra el teclado de la búsqueda.
    val open: (Long) -> Unit = { id ->
        focus.clearFocus()
        ctx.onContact(id)
    }

    // Resultados de la búsqueda (en segundo plano: con miles de contactos se nota).
    var results by remember { mutableStateOf<List<Row>?>(null) }
    LaunchedEffect(query, book, numbers) {
        results = if (query.isBlank()) {
            null
        } else {
            withContext(Dispatchers.Default) {
                val search = ContactSearch(query, numbers::searchForms)
                book.rows
                    .map { it to search.score(it.summary.displayName, it.summary.phones, it.summary.emails) }
                    .filter { it.second > 0 }
                    .sortedByDescending { it.second }
                    .map { it.first }
            }
        }
    }
    // Aviso de números sin código de área (solo si el ajuste está vacío y no se descartó).
    var missingArea by remember { mutableIntStateOf(0) }
    LaunchedEffect(book, numbers, settings.defaultAreaCode) {
        missingArea = if (settings.defaultAreaCode.isNotEmpty()) 0 else withContext(Dispatchers.Default) {
            book.rows.count { r -> r.summary.phones.any(numbers::missingAreaCode) }
        }
    }
    val showNotice = missingArea > 0 && !settings.areaCodeNoticeDismissed && query.isBlank()

    Box(Modifier.fillMaxSize()) {
        // Índice de la lista: posición de la primera fila de cada letra (con sus encabezados y el aviso).
        val shown = results ?: book.rows
        val firstOf = remember(shown, results, showNotice) {
            val map = HashMap<String, Int>()
            var i = 2 + (if (showNotice) 1 else 0)
            var last: String? = null
            for (r in shown) {
                if (results == null && r.section != last) {
                    map.putIfAbsent(r.section, i)
                    last = r.section
                    i++
                }
                i++
            }
            map
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(
                top = ctx.padding.calculateTopPadding(),
                bottom = ctx.padding.calculateBottomPadding() + 70.dp,
                start = 16.dp,
                end = if (results == null) 36.dp else 16.dp,
            ),
        ) {
            item(key = "title") {
                ScreenTitle(
                    str(R.string.tab_contacts),
                    subtitle = if (book.loaded) plural(R.plurals.contacts_count, book.rows.size) else null,
                ) {
                    GlassIconButton(Icons.Rounded.Settings, str(R.string.settings), ctx.onSettings)
                }
            }
            item(key = "search") {
                SearchField(query, { query = it }, str(R.string.search_hint), str(R.string.clear_search), Modifier.padding(vertical = 8.dp))
            }
            if (showNotice) {
                item(key = "notice") {
                    NoticeCard(
                        plural(R.plurals.area_code_notice, missingArea),
                        str(R.string.area_code_notice_action),
                        ctx.onAreaCode,
                        str(R.string.dismiss),
                        { Stores.settings.update { it.copy(areaCodeNoticeDismissed = true) } },
                        Modifier.padding(vertical = 6.dp),
                    )
                }
            }
            if (book.loaded && shown.isEmpty()) {
                item(key = "empty") {
                    if (results != null) EmptyState(str(R.string.no_results), str(R.string.no_results_hint))
                    else EmptyState(str(R.string.empty_title), str(R.string.empty_hint))
                }
            }
            var last: String? = null
            for (r in shown) {
                if (results == null && r.section != last) {
                    val letter = r.section
                    item(key = "h:$letter", contentType = "header") { LetterHeader(letter) }
                    last = letter
                }
                item(key = r.summary.id, contentType = "row") {
                    ContactRow(
                        r.summary.displayName.ifBlank { str(R.string.no_name) },
                        { open(r.summary.id) },
                        subtitle = if (results != null) r.summary.phones.firstOrNull()?.let(numbers::format) else null,
                        photoUri = r.summary.photoUri,
                        starred = r.summary.starred,
                        starredLabel = str(R.string.favorite),
                    )
                }
            }
        }
        if (results == null && book.rows.size > 20) {
            AlphabetIndex(
                SpanishOrder.SECTIONS,
                book.sections,
                { letter -> firstOf[letter]?.let { scope.launch { list.scrollToItem(it) } } },
                // Debajo del título, la búsqueda y el aviso; arriba del botón de contacto nuevo.
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(
                        top = ctx.padding.calculateTopPadding() + (if (showNotice) 330.dp else 190.dp),
                        bottom = ctx.padding.calculateBottomPadding() + 76.dp,
                        end = 2.dp,
                    ),
                jumpLabel = { str(R.string.jump_to_letter, it) },
            )
        }
    }
}

// ---------------------------------------------------------------- favoritos

@Composable
fun FavoritesTab(ctx: TabContext) {
    val book by Repo.book.collectAsStateWithLifecycle()
    val favorites = remember(book) { book.rows.filter { it.summary.starred } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = ctx.padding.calculateTopPadding(),
            bottom = ctx.padding.calculateBottomPadding() + 70.dp,
            start = 16.dp,
            end = 16.dp,
        ),
    ) {
        item(key = "title") { ScreenTitle(str(R.string.tab_favorites)) }
        if (book.loaded && favorites.isEmpty()) {
            item(key = "empty") { EmptyState(str(R.string.no_favorites), str(R.string.no_favorites_hint)) }
        }
        items(favorites, key = { it.summary.id }, contentType = { "row" }) { r ->
            ContactRow(r.summary.displayName.ifBlank { str(R.string.no_name) }, { ctx.onContact(r.summary.id) }, photoUri = r.summary.photoUri)
        }
    }
}

// ---------------------------------------------------------------- etiquetas

@Composable
fun LabelsTab(ctx: TabContext) {
    val book by Repo.book.collectAsStateWithLifecycle()
    val sheet = LocalActionSheet.current
    val prompt = LocalPrompt.current
    val dialog = LocalDialog.current
    val p = LocalGlass.current
    var groups by remember { mutableStateOf<List<ContactGroup>?>(null) }
    // Las etiquetas cambian con la agenda (sincronizaciones, otras apps): se releen con ella.
    LaunchedEffect(book) { groups = withContext(Dispatchers.IO) { Repo.contacts.groups() } }
    val local = remember { mutableStateOf<dev.liquidcontacts.contacts.ContactAccount?>(null) }
    LaunchedEffect(Unit) { local.value = withContext(Dispatchers.IO) { Repo.contacts.localAccount() } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = ctx.padding.calculateTopPadding(),
            bottom = ctx.padding.calculateBottomPadding() + 70.dp,
            start = 16.dp,
            end = 16.dp,
        ),
    ) {
        item(key = "title") {
            ScreenTitle(str(R.string.tab_labels)) {
                GlassIconButton(Icons.Rounded.Add, str(R.string.new_label), {
                    prompt(
                        PromptSpec(str(R.string.new_label), str(R.string.create), str(R.string.label_name)) { title ->
                            if (title.isNotBlank()) {
                                val account = dev.liquidcontacts.contacts.ContactAccount.fromId(Stores.settings.value.newContactAccount)
                                    .takeIf { Stores.settings.value.newContactAccount.isNotEmpty() } ?: local.value
                                Repo.launch { Repo.contacts.createGroup(title, account ?: Repo.contacts.localAccount()); Repo.reload() }
                            }
                        },
                    )
                })
            }
        }
        val list = groups
        if (list != null && list.isEmpty()) {
            item(key = "empty") { EmptyState(str(R.string.no_labels), str(R.string.no_labels_hint)) }
        }
        if (!list.isNullOrEmpty()) {
            item(key = "card") {
                GlassCard {
                    // Si la misma etiqueta existe en varias cuentas, se aclara de cuál es.
                    val repeated = list.groupBy { it.title }.filterValues { it.size > 1 }.keys
                    list.forEach { g ->
                        SettingRow(
                            g.title,
                            Modifier.combinedClickable(onClick = { ctx.onLabel(g) }, onLongClick = {
                                sheet(
                                    SheetSpec(
                                        g.title,
                                        listOf(
                                            SheetAction(str(R.string.rename), Icons.Rounded.Edit) {
                                                prompt(PromptSpec(str(R.string.rename_label), str(R.string.save), g.title) { t ->
                                                    if (t.isNotBlank()) Repo.launch { Repo.contacts.renameGroup(g.id, t); Repo.reload() }
                                                })
                                            },
                                            // Sin danger: el rojo de ui/glass no llega a 4,5:1 en claro; el diálogo que sigue sí es rojo.
                                            SheetAction(str(R.string.delete_label), Icons.Rounded.DeleteOutline) {
                                                dialog(
                                                    dev.liquidcontacts.ui.glass.DialogSpec(str(R.string.delete_label_title, g.title)) { dismiss ->
                                                        Text(str(R.string.delete_label_text), fontSize = 15.sp, color = p.contentSecondary)
                                                        Spacer(Modifier.height(16.dp))
                                                        DangerButton(str(R.string.delete_label), {
                                                            dismiss()
                                                            Repo.launch { Repo.contacts.deleteGroup(g.id); Repo.reload() }
                                                        }, Modifier.fillMaxWidth())
                                                        Spacer(Modifier.height(8.dp))
                                                        GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
                                                    },
                                                )
                                            },
                                        ),
                                    ),
                                )
                            }),
                            subtitle = listOfNotNull(
                                plural(R.plurals.contacts_count, g.count),
                                g.account.name?.takeIf { g.title in repeated },
                            ).joinToString(" · "),
                        )
                    }
                }
            }
        }
        item(key = "hint") {
            Text(
                str(R.string.labels_hint),
                Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                fontSize = 12.sp,
                color = p.contentSecondary,
            )
        }
    }
}

@Composable
fun LabelScreen(group: ContactGroup, padding: PaddingValues, onBack: () -> Unit, onContact: (Long) -> Unit) {
    val book by Repo.book.collectAsStateWithLifecycle()
    var members by remember { mutableStateOf<Set<Long>?>(null) }
    LaunchedEffect(book) { members = withContext(Dispatchers.IO) { Repo.contacts.groupMembers(group.id) } }
    val rows = remember(book, members) { members?.let { m -> book.rows.filter { it.summary.id in m } } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding(), start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        item(key = "title") { BackTitle(group.title, onBack, subtitle = rows?.let { plural(R.plurals.contacts_count, it.size) }) }
        if (rows != null && rows.isEmpty()) item(key = "empty") { EmptyState(str(R.string.label_empty), str(R.string.label_empty_hint)) }
        items(rows.orEmpty(), key = { it.summary.id }) { r ->
            ContactRow(r.summary.displayName.ifBlank { str(R.string.no_name) }, { onContact(r.summary.id) }, photoUri = r.summary.photoUri, starred = r.summary.starred, starredLabel = str(R.string.favorite))
        }
    }
}
