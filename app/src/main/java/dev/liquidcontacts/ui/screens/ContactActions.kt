// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import dev.liquidcontacts.ui.glass.FlatNavBarHeight
import dev.liquidcontacts.ui.glass.GlassSurface
import dev.liquidcontacts.ui.glass.pillVeil
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.MergeType
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidcontacts.sync.SyncEngine
import dev.liquidcontacts.ui.components.dangerText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidcontacts.R
import dev.liquidcontacts.core.model.ContactSummary
import dev.liquidcontacts.core.text.SpanishOrder
import dev.liquidcontacts.core.vcard.VCardFormat
import dev.liquidcontacts.data.Repo
import dev.liquidcontacts.data.Trash
import dev.liquidcontacts.data.VcfFiles
import dev.liquidcontacts.ui.components.ContactRow
import dev.liquidcontacts.ui.components.DangerButton
import dev.liquidcontacts.ui.components.LocalUndo
import dev.liquidcontacts.ui.components.SwitchRow
import dev.liquidcontacts.ui.components.UndoSpec
import dev.liquidcontacts.ui.glass.DialogSpec
import dev.liquidcontacts.ui.glass.GlassButton
import dev.liquidcontacts.ui.glass.LocalActionSheet
import dev.liquidcontacts.ui.glass.LocalDialog
import dev.liquidcontacts.ui.glass.LocalGlass
import dev.liquidcontacts.ui.glass.LocalPrompt
import dev.liquidcontacts.ui.glass.PromptSpec
import dev.liquidcontacts.ui.glass.SettingRow
import dev.liquidcontacts.ui.glass.SheetAction
import dev.liquidcontacts.ui.glass.SheetSpec
import dev.liquidcontacts.util.plural
import dev.liquidcontacts.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Adónde llevan los gestos sobre un contacto. */
class ContactNav(
    val onOpen: (Long) -> Unit,
    val onEdit: (Long) -> Unit,
    val onPhoto: (Long, String) -> Unit,
    val onMerge: (List<Long>) -> Unit,
)

/** Selección múltiple de una lista. Se sale con la X, con Atrás o al quedar sin ninguno. */
class ContactSelection {
    var active by mutableStateOf(false)
    var ids by mutableStateOf<Set<Long>>(emptySet())
    fun toggle(id: Long) {
        ids = if (id in ids) ids - id else ids + id
        active = ids.isNotEmpty()
    }
    fun clear() {
        active = false
        ids = emptySet()
    }
}

/** Lo que se puede hacer con uno o varios contactos: lo comparten el menú, la selección y el detalle. */
class ContactActions internal constructor(
    val nav: ContactNav,
    val setStarred: (Collection<Long>, Boolean) -> Unit,
    val addLabel: (Collection<Long>) -> Unit,
    val share: (List<Long>) -> Unit,
    /** Pide confirmación, manda a la papelera y ofrece deshacer. [after] corre al confirmar. */
    val delete: (ids: List<Long>, name: String?, after: () -> Unit) -> Unit,
    internal val dial: (String) -> Unit,
    internal val message: (String) -> Unit,
)

@Composable
fun rememberContactActions(nav: ContactNav): ContactActions {
    val p = LocalGlass.current
    val context = LocalContext.current
    val dialog = LocalDialog.current
    val prompt = LocalPrompt.current
    val toast = LocalToast.current
    val undo = LocalUndo.current
    val scope = rememberCoroutineScope()
    return remember(nav) {
        fun label(ids: Collection<Long>, title: String) {
            if (title.isBlank()) return
            Repo.launch {
                val done = runCatching { Repo.contacts.addToLabel(ids, title); Repo.reload() }.isSuccess
                withContext(Dispatchers.Main) { toast(str(if (done) R.string.label_added else R.string.label_add_failed, title)) }
            }
        }
        ContactActions(
            nav = nav,
            setStarred = { ids, on -> Repo.launch { ids.forEach { Repo.contacts.setStarred(it, on) } } },
            addLabel = { ids ->
                dialog(
                    DialogSpec(str(R.string.add_label)) { dismiss ->
                        var titles by remember { mutableStateOf<List<String>>(emptyList()) }
                        LaunchedEffect(Unit) {
                            titles = withContext(Dispatchers.IO) {
                                val order = SpanishOrder()
                                Repo.contacts.groups().map { it.title }.distinct().sortedWith { a, b -> order.compare(a, b) }
                            }
                        }
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                            items(titles) { t -> SettingRow(t, onClick = { dismiss(); label(ids, t) }) }
                        }
                        Spacer(Modifier.height(8.dp))
                        GlassButton(str(R.string.new_label), {
                            dismiss()
                            prompt(PromptSpec(str(R.string.new_label), str(R.string.create), str(R.string.label_name)) { label(ids, it) })
                        }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add)
                        Spacer(Modifier.height(8.dp))
                        GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
                    },
                )
            },
            share = { ids ->
                dialog(
                    DialogSpec(str(R.string.share_contact)) { dismiss ->
                        var photos by remember { mutableStateOf(true) }
                        SwitchRow(str(R.string.export_photos), photos, { photos = it })
                        Text(str(R.string.export_warning), fontSize = 13.sp, color = p.contentSecondary)
                        Spacer(Modifier.height(16.dp))
                        GlassButton(str(R.string.share), {
                            dismiss()
                            scope.launch {
                                val intent = withContext(Dispatchers.IO) { VcfFiles.shareIntent(context, ids, VCardFormat.V3, photos) }
                                if (intent == null) toast(str(R.string.export_failed)) else context.startActivity(intent)
                            }
                        }, Modifier.fillMaxWidth(), prominent = true)
                        Spacer(Modifier.height(8.dp))
                        GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
                    },
                )
            },
            delete = { ids, name, after ->
                val title = if (name != null) str(R.string.delete_contact_title, name.ifBlank { str(R.string.no_name) }) else plural(R.plurals.delete_many_title, ids.size, ids.size)
                dialog(
                    DialogSpec(title) { dismiss ->
                        Text(str(R.string.delete_contact_text, Trash.DAYS), fontSize = 15.sp, color = p.contentSecondary)
                        val onServer by produceState(false, ids) { value = withContext(Dispatchers.IO) { runCatching { Trash.onServer(ids) }.getOrDefault(false) } }
                        if (onServer) {
                            val server by SyncEngine.store.state.collectAsStateWithLifecycle()
                            Text(
                                str(R.string.delete_contact_server, server.nickname.ifEmpty { str(R.string.server_title) }),
                                Modifier.padding(top = 10.dp),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = p.dangerText,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        DangerButton(str(R.string.delete), {
                            dismiss()
                            after()
                            Repo.launch {
                                val entries = runCatching { Trash.moveToTrash(ids) }.getOrNull()
                                withContext(Dispatchers.Main) {
                                    if (entries == null) {
                                        toast(str(R.string.delete_failed))
                                    } else {
                                        undo(
                                            UndoSpec(plural(R.plurals.moved_to_trash, entries.size, entries.size), str(R.string.undo)) {
                                                Repo.launch { runCatching { Trash.restore(entries) } }
                                            },
                                        )
                                    }
                                }
                            }
                        }, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
                    },
                )
            },
            dial = { if (!Actions.dial(context, it)) toast(str(R.string.no_app)) },
            message = { if (!Actions.message(context, it)) toast(str(R.string.no_app)) },
        )
    }
}

/** Fila de contacto con todos sus gestos: tocar abre, mantener da opciones, la foto se ve o selecciona. */
@Composable
fun SelectableContactRow(
    s: ContactSummary,
    selection: ContactSelection,
    actions: ContactActions,
    subtitle: String? = null,
    showStar: Boolean = true,
) {
    val sheet = LocalActionSheet.current
    val name = s.displayName.ifBlank { str(R.string.no_name) }
    val selecting = selection.active
    ContactRow(
        name,
        onClick = { if (selecting) selection.toggle(s.id) else actions.nav.onOpen(s.id) },
        subtitle = subtitle,
        photoUri = s.photoUri,
        starred = s.starred && showStar,
        starredLabel = str(R.string.favorite),
        onLongClick = {
            if (selecting) {
                selection.toggle(s.id)
            } else {
                val phone = s.phones.firstOrNull()
                sheet(
                    SheetSpec(
                        name,
                        buildList {
                            if (phone != null) {
                                add(SheetAction(str(R.string.call), Icons.Rounded.Call) { actions.dial(phone) })
                                add(SheetAction(str(R.string.message), Icons.AutoMirrored.Rounded.Message) { actions.message(phone) })
                            }
                            add(
                                SheetAction(
                                    str(if (s.starred) R.string.remove_favorite else R.string.add_favorite),
                                    if (s.starred) Icons.Rounded.StarOutline else Icons.Rounded.Star,
                                ) { actions.setStarred(listOf(s.id), !s.starred) },
                            )
                            add(SheetAction(str(R.string.edit), Icons.Rounded.Edit) { actions.nav.onEdit(s.id) })
                            add(SheetAction(str(R.string.add_label), Icons.AutoMirrored.Rounded.Label) { actions.addLabel(listOf(s.id)) })
                            add(SheetAction(str(R.string.share), Icons.Rounded.Share) { actions.share(listOf(s.id)) })
                            add(SheetAction(str(R.string.select), Icons.Rounded.CheckCircleOutline) { selection.toggle(s.id) })
                            // Sin danger: el rojo de ui/glass no llega a 4,5:1 en claro; el diálogo que sigue sí es rojo.
                            add(SheetAction(str(R.string.delete), Icons.Rounded.DeleteOutline) { actions.delete(listOf(s.id), s.displayName) {} })
                        },
                    ),
                )
            }
        },
        onPhotoClick = {
            when {
                selecting -> selection.toggle(s.id)
                s.photoUri != null -> actions.nav.onPhoto(s.id, name)
                else -> actions.nav.onOpen(s.id)
            }
        },
        onPhotoLongClick = { selection.toggle(s.id) },
        photoLabel = if (s.photoUri != null) str(R.string.view_photo) else str(R.string.open_contact),
        selectLabel = str(R.string.select),
        selected = if (selecting) s.id in selection.ids else null,
    )
}

/** El mismo cristal que la píldora de pestañas (GlassNavBar), para que las tres se vean iguales. */
private val PILL_FROST = 4.dp

/** Una opción de la barra de abajo en la selección múltiple. */
class BarItem(val icon: ImageVector, val label: String, val description: String, val enabled: Boolean, val onClick: () -> Unit)

/** Lo de arriba de la selección múltiple: cuántos hay, seleccionar todos y salir. */
class SelectionTopState(val count: Int, val canSelectAll: Boolean, val onSelectAll: () -> Unit, val onExit: () -> Unit)

/**
 * Lo que muestra la lista que está seleccionando. Lo pinta la capa flotante de la app
 * ([SelectionTop] y [SelectionActions]): solo ahí el cristal ve la lista que pasa por debajo; dentro
 * de la página refractaría únicamente el fondo y, con fondo liso, se vería como un color sólido.
 */
class SelectionBottom(val top: State<SelectionTopState>, val items: State<List<BarItem>>)

val LocalSelectionBottom = staticCompositionLocalOf<(SelectionBottom?) -> Unit> { {} }

/**
 * Selección múltiple de una lista: publica sus barras para la capa flotante, donde la píldora de
 * abajo cambia a las opciones y el botón de contacto nuevo se va. [all] son los contactos que se
 * ven. El botón Atrás sale de la selección.
 */
@Composable
fun SelectionBar(selection: ContactSelection, all: List<ContactSummary>, actions: ContactActions, backEnabled: Boolean = true) {
    if (!selection.active) return
    BackHandler(enabled = backEnabled) { selection.clear() }
    val ids = selection.ids.toList()
    val some = ids.isNotEmpty()
    val removeStar = some && all.filter { it.id in selection.ids }.all { it.starred }

    val items = rememberUpdatedState(
        listOf(
            BarItem(
                if (removeStar) Icons.Rounded.StarOutline else Icons.Rounded.Star,
                str(if (removeStar) R.string.bar_unfavorite else R.string.favorite),
                str(if (removeStar) R.string.remove_favorite else R.string.add_favorite),
                some,
            ) {
                actions.setStarred(ids, !removeStar)
                selection.clear()
            },
            BarItem(Icons.AutoMirrored.Rounded.Label, str(R.string.bar_label), str(R.string.add_label), some) {
                actions.addLabel(ids)
                selection.clear()
            },
            BarItem(Icons.Rounded.Share, str(R.string.share), str(R.string.share), some) {
                actions.share(ids)
                selection.clear()
            },
            BarItem(Icons.AutoMirrored.Rounded.MergeType, str(R.string.merge), str(R.string.merge), ids.size >= 2) {
                actions.nav.onMerge(ids)
                selection.clear()
            },
            BarItem(Icons.Rounded.DeleteOutline, str(R.string.delete), str(R.string.delete), some) {
                actions.delete(ids, null) { selection.clear() }
            },
        ),
    )
    val top = rememberUpdatedState(
        SelectionTopState(ids.size, ids.size < all.size, { selection.ids = all.mapTo(HashSet()) { it.id } }, selection::clear),
    )
    val setBottom = LocalSelectionBottom.current
    DisposableEffect(Unit) {
        setBottom(SelectionBottom(top, items))
        onDispose { setBottom(null) }
    }
}

/** La píldora de arriba durante la selección, con el mismo cristal que la de pestañas. */
@Composable
fun SelectionTop(bottom: SelectionBottom, flat: Boolean, topInset: Dp, modifier: Modifier = Modifier) {
    val p = LocalGlass.current
    val state = bottom.top.value
    val row: @Composable (Modifier) -> Unit = { rowModifier ->
        Row(
            rowModifier
                // Los toques no pasan a la lista de abajo.
                .clickable(remember { MutableInteractionSource() }, null) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarAction(Icons.Rounded.Close, null, str(R.string.selection_exit), state.onExit)
            Text(
                plural(R.plurals.selected_count, state.count, state.count),
                Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = p.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BarAction(Icons.Rounded.SelectAll, str(R.string.select_all), str(R.string.select_all_full), state.onSelectAll, enabled = state.canSelectAll)
        }
    }
    if (flat) {
        // Sólido: barra plana de borde a borde, como la de abajo.
        row(
            modifier
                .fillMaxWidth()
                .background(p.levels.firstOrNull() ?: p.base)
                .drawBehind { drawLine(p.outline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx()) }
                .padding(top = topInset + 4.dp, bottom = 4.dp, start = 8.dp, end = 8.dp),
        )
    } else {
        GlassSurface(
            modifier.padding(top = topInset + 8.dp, start = 16.dp, end = 16.dp).fillMaxWidth(),
            shape = CircleShape,
            strong = true,
            tint = p.pillVeil(),
            frost = PILL_FROST,
        ) {
            row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

/** La píldora de abajo durante la selección: las mismas medidas que la de pestañas, con las opciones. */
@Composable
fun SelectionActions(bottom: SelectionBottom, flat: Boolean, modifier: Modifier = Modifier) {
    val p = LocalGlass.current
    val row: @Composable (Modifier) -> Unit = { rowModifier ->
        Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
            bottom.items.value.forEach { BarAction(it.icon, it.label, it.description, it.onClick, Modifier.weight(1f), it.enabled) }
        }
    }
    if (flat) {
        Column(
            modifier
                .fillMaxWidth()
                .background(p.levels.firstOrNull() ?: p.base)
                .drawBehind { drawLine(p.outline, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) }
                .navigationBarsPadding(),
        ) {
            row(Modifier.fillMaxWidth().height(FlatNavBarHeight))
        }
    } else {
        GlassSurface(
            modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().height(70.dp),
            shape = CircleShape,
            strong = true,
            tint = p.pillVeil(),
            frost = PILL_FROST,
        ) {
            row(Modifier.fillMaxSize().padding(horizontal = 6.dp))
        }
    }
}

/** Botón plano con ícono y texto. Apagado se ve tenue y no responde. */
@Composable
private fun BarAction(icon: ImageVector, label: String?, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = LocalGlass.current
    val color = if (enabled) p.content else p.contentSecondary.copy(alpha = 0.45f)
    Column(
        modifier
            .widthIn(min = 52.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = color)
        if (label != null) Text(label, fontSize = 11.sp, color = color, maxLines = 1, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}
