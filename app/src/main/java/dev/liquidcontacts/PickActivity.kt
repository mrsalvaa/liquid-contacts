// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidcontacts.core.text.ContactSearch
import dev.liquidcontacts.data.Repo
import dev.liquidcontacts.data.Stores
import dev.liquidcontacts.external.ExternalIntents
import dev.liquidcontacts.external.PickKind
import dev.liquidcontacts.ui.components.ContactRow
import dev.liquidcontacts.ui.components.SearchField
import dev.liquidcontacts.ui.glass.ActionSheetHost
import dev.liquidcontacts.ui.glass.GlassRoot
import dev.liquidcontacts.ui.glass.GlassTheme
import dev.liquidcontacts.ui.glass.LocalActionSheet
import dev.liquidcontacts.ui.glass.LocalGlass
import dev.liquidcontacts.ui.glass.ScreenTitle
import dev.liquidcontacts.ui.glass.SheetAction
import dev.liquidcontacts.ui.glass.SheetSpec
import dev.liquidcontacts.ui.glass.isDarkTheme
import dev.liquidcontacts.ui.screens.PermissionScreen
import dev.liquidcontacts.util.preferHighestRefreshRate
import dev.liquidcontacts.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Elegir un contacto, número, email o dirección para otra app. Devuelve únicamente la URI de lo
 * elegido, con un permiso temporal de lectura sobre esa URI (sin persistentes): la otra app no
 * necesita el permiso de contactos y no ve nada más de la agenda.
 */
class PickActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        preferHighestRefreshRate()
        window.decorView.filterTouchesWhenObscured = true
        setResult(RESULT_CANCELED)
        val type = runCatching { intent.resolveType(contentResolver) }.getOrNull()
        val kind = ExternalIntents.pickKind(intent, type)
        if (kind == null) {
            finish()
            return
        }
        setContent {
            val settings by Stores.settings.state.collectAsStateWithLifecycle()
            SystemBarsFor(isDarkTheme(settings.themeMode))
            GlassTheme(settings.glassStyle) { PickUi(kind, settings.animatedBackground, ::choose) }
        }
    }

    private fun choose(uri: Uri) {
        setResult(RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        finish()
    }
}

@Composable
private fun PickUi(kind: PickKind, animated: Boolean, onChosen: (Uri) -> Unit) {
    var granted by remember { mutableStateOf(Repo.hasPermission()) }
    LaunchedEffect(granted) { if (granted) Repo.start() }
    var sheet by remember { mutableStateOf<SheetSpec?>(null) }
    val padding = WindowInsets.safeDrawing.asPaddingValues()
    CompositionLocalProvider(LocalActionSheet provides { sheet = it }) {
        GlassRoot(animated = animated, floating = { ActionSheetHost(sheet) { sheet = null } }) {
            if (granted) PickList(kind, padding, onChosen) else PermissionScreen(padding) { granted = Repo.hasPermission() }
        }
    }
}

@Composable
private fun PickList(kind: PickKind, padding: PaddingValues, onChosen: (Uri) -> Unit) {
    val p = LocalGlass.current
    val sheet = LocalActionSheet.current
    val scope = rememberCoroutineScope()
    val book by Repo.book.collectAsStateWithLifecycle()
    val numbers by Repo.numbers.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val title = str(
        when (kind) {
            PickKind.CONTACT -> R.string.pick_contact
            PickKind.PHONE -> R.string.pick_phone
            PickKind.EMAIL -> R.string.pick_email
            PickKind.POSTAL -> R.string.pick_postal
        },
    )
    val rows = remember(book, query, numbers, kind) {
        val search = ContactSearch(query, numbers::searchForms)
        book.rows.filter { r ->
            val has = when (kind) {
                PickKind.PHONE -> r.summary.phones.isNotEmpty()
                PickKind.EMAIL -> r.summary.emails.isNotEmpty()
                else -> true
            }
            has && search.matches(r.summary.displayName, r.summary.phones, r.summary.emails)
        }
    }
    fun pick(id: Long, name: String) {
        scope.launch {
            if (kind == PickKind.CONTACT) {
                withContext(Dispatchers.IO) { Repo.contacts.lookupUri(id) }?.let(onChosen)
                return@launch
            }
            val (content, column) = when (kind) {
                PickKind.PHONE -> CommonDataKinds.Phone.CONTENT_URI to CommonDataKinds.Phone.NUMBER
                PickKind.EMAIL -> CommonDataKinds.Email.CONTENT_URI to CommonDataKinds.Email.ADDRESS
                else -> CommonDataKinds.StructuredPostal.CONTENT_URI to CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS
            }
            val values = withContext(Dispatchers.IO) { Repo.contacts.dataRows(id, content, column) }
            when (values.size) {
                0 -> Unit
                1 -> onChosen(ContentUris.withAppendedId(content, values[0].first))
                else -> sheet(
                    SheetSpec(
                        name,
                        values.map { (row, value) ->
                            SheetAction(if (kind == PickKind.PHONE) numbers.format(value) else value, Icons.Rounded.Check) {
                                onChosen(ContentUris.withAppendedId(content, row))
                            }
                        },
                    ),
                )
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp, start = 16.dp, end = 16.dp),
        ) {
            item(key = "title") { ScreenTitle(title) }
            item(key = "note") { Text(str(R.string.pick_note), Modifier.padding(horizontal = 8.dp), fontSize = 13.sp, color = p.contentSecondary) }
            item(key = "search") { SearchField(query, { query = it }, str(R.string.search_hint), str(R.string.clear_search), Modifier.padding(vertical = 8.dp)) }
            items(rows, key = { it.summary.id }, contentType = { "row" }) { r ->
                val name = r.summary.displayName.ifBlank { str(R.string.no_name) }
                ContactRow(
                    name,
                    { pick(r.summary.id, name) },
                    subtitle = when (kind) {
                        PickKind.PHONE -> r.summary.phones.firstOrNull()?.let(numbers::format)
                        PickKind.EMAIL -> r.summary.emails.firstOrNull()
                        else -> null
                    },
                    photoUri = r.summary.photoUri,
                )
            }
        }
    }
}
