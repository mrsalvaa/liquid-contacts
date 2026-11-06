// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidcontacts.contacts.ContactGroup
import dev.liquidcontacts.core.dav.RemoteBook
import dev.liquidcontacts.core.vcard.VCardImport
import dev.liquidcontacts.data.Repo
import dev.liquidcontacts.data.Stores
import dev.liquidcontacts.data.VcfFiles
import dev.liquidcontacts.ui.components.LocalUndo
import dev.liquidcontacts.ui.components.UndoHost
import dev.liquidcontacts.ui.components.UndoSpec
import dev.liquidcontacts.ui.glass.ActionSheetHost
import dev.liquidcontacts.ui.glass.DialogHost
import dev.liquidcontacts.ui.glass.DialogSpec
import dev.liquidcontacts.ui.glass.FlatNavBar
import dev.liquidcontacts.ui.glass.FlatNavBarHeight
import dev.liquidcontacts.ui.glass.GlassIconButton
import dev.liquidcontacts.ui.glass.GlassNavBar
import dev.liquidcontacts.ui.glass.GlassRoot
import dev.liquidcontacts.ui.glass.GlassTheme
import dev.liquidcontacts.ui.glass.LocalActionSheet
import dev.liquidcontacts.ui.glass.LocalDialog
import dev.liquidcontacts.ui.glass.LocalPrompt
import dev.liquidcontacts.ui.glass.LocalReducedMotion
import dev.liquidcontacts.ui.glass.LocalTabActive
import dev.liquidcontacts.ui.glass.NavItem
import dev.liquidcontacts.ui.glass.PromptHost
import dev.liquidcontacts.ui.glass.PromptSpec
import dev.liquidcontacts.ui.glass.SheetSpec
import dev.liquidcontacts.ui.glass.isDarkTheme
import dev.liquidcontacts.ui.screens.ConflictsScreen
import dev.liquidcontacts.ui.screens.ContactDetailScreen
import dev.liquidcontacts.ui.screens.ContactNav
import dev.liquidcontacts.ui.screens.ContactEditScreen
import dev.liquidcontacts.ui.screens.ContactsTab
import dev.liquidcontacts.ui.screens.DuplicatesScreen
import dev.liquidcontacts.ui.screens.EditTarget
import dev.liquidcontacts.ui.screens.ExportScreen
import dev.liquidcontacts.ui.screens.FavoritesTab
import dev.liquidcontacts.ui.screens.FirstSyncScreen
import dev.liquidcontacts.ui.screens.ImportScreen
import dev.liquidcontacts.ui.screens.LabelScreen
import dev.liquidcontacts.ui.screens.LabelsTab
import dev.liquidcontacts.ui.screens.LocalSelectionBottom
import dev.liquidcontacts.ui.screens.LocalToast
import dev.liquidcontacts.ui.screens.MergeScreen
import dev.liquidcontacts.ui.screens.PermissionScreen
import dev.liquidcontacts.ui.screens.PhotoWindow
import dev.liquidcontacts.ui.screens.ServerScreen
import dev.liquidcontacts.ui.screens.SelectionActions
import dev.liquidcontacts.ui.screens.SelectionBottom
import dev.liquidcontacts.ui.screens.SelectionTop
import dev.liquidcontacts.ui.screens.SettingsScreen
import dev.liquidcontacts.ui.screens.TabContext
import dev.liquidcontacts.ui.screens.ToastHost
import dev.liquidcontacts.ui.screens.TrashScreen
import dev.liquidcontacts.util.preferHighestRefreshRate
import dev.liquidcontacts.util.str
import dev.liquidcontacts.sync.SyncScheduler
import dev.liquidcontacts.ui.screens.offlineNotice

/** Pantallas que se abren encima de las pestañas. */
sealed interface Overlay {
    data class Settings(val toAreaCode: Boolean = false) : Overlay
    data class Detail(val contactId: Long) : Overlay
    data class Edit(val target: EditTarget) : Overlay
    data class Label(val group: ContactGroup) : Overlay
    data object Duplicates : Overlay
    data class Merge(val contactIds: List<Long>, val preselected: Boolean) : Overlay
    data class Import(val data: VCardImport.Ok) : Overlay
    data object Export : Overlay
    data object Server : Overlay
    data class FirstSync(val book: RemoteBook) : Overlay
    data class Conflicts(val bookUrl: String) : Overlay
    data object Trash : Overlay
}

private const val TAB_CONTACTS = 0
private const val TAB_FAVORITES = 1
private const val TAB_LABELS = 2
private const val TAB_COUNT = 3

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        VcfFiles.cleanShared(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        preferHighestRefreshRate()
        setContent {
            val settings by Stores.settings.state.collectAsStateWithLifecycle()
            SystemBarsFor(isDarkTheme(settings.themeMode))
            GlassTheme(settings.glassStyle) {
                ContactsUi(settings.animatedBackground, flatNav = settings.uiStyle == 1)
            }
        }
    }
}

/**
 * Toda la app. Desde otra app ([ExternalActivity]), [external] dice qué pantalla abrir (se resuelve
 * después de tener el permiso; null = el pedido no vale) y [onExit] termina cuando se cierra: sin
 * pestañas ni nada más de la agenda a la vista.
 */
@Composable
fun ContactsUi(
    animated: Boolean,
    flatNav: Boolean,
    external: (suspend () -> Overlay?)? = null,
    onExit: (savedContact: Long?) -> Unit = {},
) {
    var granted by remember { mutableStateOf(Repo.hasPermission()) }
    LaunchedEffect(granted) { if (granted) Repo.start() }
    val isExternal = external != null

    var tab by rememberSaveable { mutableIntStateOf(TAB_CONTACTS) }
    var tabDrag by remember { mutableStateOf<Float?>(null) }
    val stack = remember { mutableStateListOf<Overlay>() }
    val holder = rememberSaveableStateHolder()
    var sheet by remember { mutableStateOf<SheetSpec?>(null) }
    var prompt by remember { mutableStateOf<PromptSpec?>(null) }
    var dialog by remember { mutableStateOf<DialogSpec?>(null) }
    // Un cambio para el servidor quedó en el celular por falta de conexión: se avisa con una ventana,
    // sin tapar otra que esté abierta.
    var offlinePending by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { SyncScheduler.offline.collect { offlinePending = true } }
    LaunchedEffect(offlinePending, dialog) {
        if (offlinePending && dialog == null) {
            dialog = offlineNotice()
            offlinePending = false
        }
    }
    var toast by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var undo by remember { mutableStateOf<UndoSpec?>(null) }
    var selectionBottom by remember { mutableStateOf<SelectionBottom?>(null) }
    val reduced = LocalReducedMotion.current

    fun push(o: Overlay) {
        stack += o
    }
    fun pop() {
        if (stack.isEmpty()) return
        val key = "${stack.lastIndex}:${stack.last()}"
        stack.removeAt(stack.lastIndex)
        holder.removeState(key)
    }
    BackHandler(enabled = stack.isNotEmpty() && sheet == null && prompt == null && dialog == null) { pop() }
    BackHandler(enabled = !isExternal && stack.isEmpty() && tab != TAB_CONTACTS) { tab = TAB_CONTACTS }
    if (external != null) {
        var opened by remember { mutableStateOf(false) }
        LaunchedEffect(granted) {
            if (granted && !opened) {
                val first = external()
                if (first == null) onExit(null) else {
                    push(first)
                    opened = true
                }
            }
        }
        // Se cerró lo último que había abierto: se vuelve a la otra app.
        LaunchedEffect(opened, stack.size) { if (opened && stack.isEmpty()) onExit(null) }
    }

    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val dir = LocalLayoutDirection.current
    val navBar = if (flatNav) FlatNavBarHeight else 70.dp + 12.dp
    val navHeight = navBar + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tabPadding = PaddingValues(
        top = insets.calculateTopPadding(),
        bottom = navHeight + 16.dp,
        start = insets.calculateStartPadding(dir),
        end = insets.calculateEndPadding(dir),
    )
    val overlayPadding = PaddingValues(
        top = insets.calculateTopPadding(),
        bottom = insets.calculateBottomPadding() + 16.dp,
        start = insets.calculateStartPadding(dir),
        end = insets.calculateEndPadding(dir),
    )
    val contactNav = remember {
        ContactNav(
            onOpen = { push(Overlay.Detail(it)) },
            onEdit = { push(Overlay.Edit(EditTarget.Existing(it))) },
            onPhoto = { id, name -> dialog = DialogSpec(name) { dismiss -> PhotoWindow(id, name, dismiss) } },
            onMerge = { push(Overlay.Merge(it, preselected = true)) },
        )
    }
    val tabContext = TabContext(
        padding = tabPadding,
        onContact = { push(Overlay.Detail(it)) },
        onSettings = { push(Overlay.Settings()) },
        onAreaCode = { push(Overlay.Settings(toAreaCode = true)) },
        onLabel = { push(Overlay.Label(it)) },
        onServer = { push(Overlay.Server) },
        nav = contactNav,
    )

    CompositionLocalProvider(
        LocalActionSheet provides { sheet = it },
        LocalPrompt provides { prompt = it },
        LocalDialog provides { dialog = it },
        LocalToast provides { toast = it to System.nanoTime() },
        LocalUndo provides { undo = it },
        LocalSelectionBottom provides { selectionBottom = it },
    ) {
        GlassRoot(
            animated = animated,
            floating = {
                if (granted && !isExternal) {
                    AnimatedVisibility(
                        stack.isEmpty() && selectionBottom == null,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        enter = fadeIn() + if (reduced) fadeIn() else slideInVertically { it / 2 },
                        exit = fadeOut() + if (reduced) fadeOut() else slideOutVertically { it / 2 },
                    ) {
                        val navItems = listOf(
                            NavItem(str(R.string.tab_contacts), Icons.Rounded.Contacts),
                            NavItem(str(R.string.tab_favorites), Icons.Rounded.Star),
                            NavItem(str(R.string.tab_labels), Icons.AutoMirrored.Rounded.Label),
                        )
                        if (flatNav) {
                            FlatNavBar(navItems, selected = tab, onSelect = { tab = it })
                        } else {
                            GlassNavBar(
                                items = navItems,
                                selected = tab,
                                dragPosition = { tabDrag },
                                onSelect = { tab = it },
                                modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                    AnimatedVisibility(
                        stack.isEmpty() && selectionBottom == null,
                        modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 24.dp, bottom = navBar + 18.dp),
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        GlassIconButton(Icons.Rounded.PersonAdd, str(R.string.new_contact), { push(Overlay.Edit(EditTarget.New)) }, size = 58.dp, prominent = true)
                    }
                }
                // Durante la selección múltiple, la píldora cambia a las opciones (también en una etiqueta).
                var lastBottom by remember { mutableStateOf(selectionBottom) }
                if (selectionBottom != null) lastBottom = selectionBottom
                AnimatedVisibility(
                    selectionBottom != null,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn() + if (reduced) fadeIn() else slideInVertically { it / 2 },
                    exit = fadeOut() + if (reduced) fadeOut() else slideOutVertically { it / 2 },
                ) {
                    lastBottom?.let { SelectionActions(it, flatNav) }
                }
                AnimatedVisibility(
                    selectionBottom != null,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = fadeIn() + if (reduced) fadeIn() else slideInVertically { -it / 2 },
                    exit = fadeOut() + if (reduced) fadeOut() else slideOutVertically { -it / 2 },
                ) {
                    lastBottom?.let { SelectionTop(it, flatNav, insets.calculateTopPadding()) }
                }
                ToastHost(toast) { toast = null }
                UndoHost(undo) { undo = null }
                ActionSheetHost(sheet) { sheet = null }
                PromptHost(prompt) { prompt = null }
                DialogHost(dialog) { dialog = null }
            },
        ) {
            if (!granted) {
                PermissionScreen(overlayPadding) { granted = Repo.hasPermission() }
                return@GlassRoot
            }
            val top = stack.lastOrNull()
            // Desde otra app no se muestra la agenda: solo lo que pidió.
            val tabsAlpha by animateFloatAsState(
                if (top == null) 1f else 0f,
                tween(if (reduced) 0 else if (top == null) 220 else 120),
                label = "tabs",
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = tabsAlpha }
                    .drawWithContent { if (tabsAlpha > 0.01f) drawContent() }
                    .then(if (top != null) Modifier.clearAndSetSemantics { }.pointerInput(Unit) { swallowTouches() } else Modifier),
            ) {
                if (!isExternal) {
                    SwipeTabs(tab, onScreen = top == null, swipeEnabled = selectionBottom == null, onTab = { tab = it }, onDrag = { tabDrag = it }) { t ->
                        when (t) {
                            TAB_CONTACTS -> ContactsTab(tabContext)
                            TAB_FAVORITES -> FavoritesTab(tabContext)
                            else -> LabelsTab(tabContext)
                        }
                    }
                }
            }

            val topKey = if (top == null) "none" else "${stack.lastIndex}:$top"
            AnimatedContent(
                targetState = top to topKey,
                transitionSpec = {
                    if (reduced) {
                        fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                    } else {
                        (fadeIn(tween(200)) + scaleIn(tween(220), initialScale = 0.97f)) togetherWith fadeOut(tween(120))
                    }
                },
                contentKey = { it.second },
                label = "overlay",
            ) { (current, key) ->
                if (current == null) {
                    Box(Modifier.fillMaxSize())
                    return@AnimatedContent
                }
                holder.SaveableStateProvider(key) {
                    when (current) {
                        is Overlay.Settings -> SettingsScreen(overlayPadding, ::pop, current.toAreaCode, onDuplicates = { push(Overlay.Duplicates) }, onImport = { push(Overlay.Import(it)) }, onExport = { push(Overlay.Export) }, onServer = { push(Overlay.Server) }, onTrash = { push(Overlay.Trash) })
                        is Overlay.Import -> ImportScreen(current.data, overlayPadding, ::pop)
                        Overlay.Export -> ExportScreen(overlayPadding, ::pop)
                        Overlay.Server -> ServerScreen(overlayPadding, ::pop, onFirstSync = { push(Overlay.FirstSync(it)) }, onConflicts = { push(Overlay.Conflicts(it)) })
                        is Overlay.FirstSync -> FirstSyncScreen(current.book, overlayPadding, ::pop)
                        is Overlay.Conflicts -> ConflictsScreen(current.bookUrl, overlayPadding, ::pop)
                        is Overlay.Detail -> ContactDetailScreen(current.contactId, overlayPadding, ::pop, contactNav)
                        Overlay.Trash -> TrashScreen(overlayPadding, ::pop)
                        is Overlay.Edit -> ContactEditScreen(current.target, overlayPadding, ::pop, onServer = { push(Overlay.Server) }, onSaved = { id ->
                            // Si lo pidió otra app, se le devuelve el contacto guardado y se cierra.
                            if (isExternal && stack.size == 1) {
                                onExit(id)
                                return@ContactEditScreen
                            }
                            pop()
                            // Un contacto nuevo se abre al guardarlo; uno editado vuelve a su detalle.
                            if (current.target is EditTarget.New && id != null) push(Overlay.Detail(id))
                        })
                        is Overlay.Label -> LabelScreen(current.group, overlayPadding, ::pop, contactNav)
                        Overlay.Duplicates -> DuplicatesScreen(overlayPadding, ::pop) { ids, preselected -> push(Overlay.Merge(ids, preselected)) }
                        is Overlay.Merge -> MergeScreen(current.contactIds, current.preselected, overlayPadding, ::pop)
                    }
                }
            }
        }
    }
}

/** Se traga todos los toques (para las pestañas que quedan debajo de otra pantalla). */
private suspend fun PointerInputScope.swallowTouches() {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Pestañas que se cambian deslizando, como en Liquid Calendar. Todas siguen creadas aunque no se
 * vean y la burbuja de la barra sigue al dedo.
 */
@Composable
private fun SwipeTabs(
    tab: Int,
    onScreen: Boolean,
    swipeEnabled: Boolean,
    onTab: (Int) -> Unit,
    onDrag: (Float?) -> Unit,
    content: @Composable (Int) -> Unit,
) {
    val pager = rememberPagerState(initialPage = tab) { TAB_COUNT }
    val select by rememberUpdatedState(onTab)
    val drag by rememberUpdatedState(onDrag)
    val reduced = LocalReducedMotion.current
    // La pestaña pedida con un toque, mientras el pager va hacia ella. La burbuja no lo espera: sale
    // enseguida por su cuenta, y un toque nuevo no vuelve atrás por una pestaña que quedó en el camino.
    var requested by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { if (requested == null || requested == it) select(it) }
    }
    LaunchedEffect(pager) {
        snapshotFlow { if (pager.isScrollInProgress && requested == null) pager.currentPage + pager.currentPageOffsetFraction else null }
            .collect { drag(it) }
    }
    LaunchedEffect(tab) {
        if (pager.targetPage != tab) {
            requested = tab
            try {
                if (reduced) pager.scrollToPage(tab) else pager.animateScrollToPage(tab, animationSpec = tween(220, easing = FastOutSlowInEasing))
            } finally {
                if (requested == tab) requested = null
            }
        }
    }
    HorizontalPager(
        state = pager,
        flingBehavior = PagerDefaults.flingBehavior(pager, snapPositionalThreshold = 0.15f),
        beyondViewportPageCount = TAB_COUNT - 1,
        userScrollEnabled = onScreen && swipeEnabled,
        key = { it },
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        CompositionLocalProvider(LocalTabActive provides (page == pager.currentPage && onScreen)) { content(page) }
    }
}

/** Iconos de las barras del sistema según el tema (claros sobre fondo oscuro). */
@Composable
fun SystemBarsFor(dark: Boolean) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    LaunchedEffect(dark) {
        val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
