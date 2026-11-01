// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidcontacts.R
import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.sync.ConflictKind
import dev.liquidcontacts.core.vcard.VCards
import dev.liquidcontacts.data.Repo
import dev.liquidcontacts.data.SyncConflict
import dev.liquidcontacts.sync.SyncEngine
import dev.liquidcontacts.ui.glass.GlassButton
import dev.liquidcontacts.ui.glass.GlassCard
import dev.liquidcontacts.ui.glass.LocalGlass
import dev.liquidcontacts.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Contactos que cambiaron en los dos lados: se ven las dos versiones y elige el usuario. */
@Composable
fun ConflictsScreen(bookUrl: String, padding: PaddingValues, onBack: () -> Unit) {
    val s by SyncEngine.store.state.collectAsStateWithLifecycle()
    val conflicts = s.books.firstOrNull { it.url == bookUrl }?.conflicts.orEmpty()
    LaunchedEffect(conflicts.isEmpty()) { if (conflicts.isEmpty()) onBack() }
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        BackTitle(str(R.string.conflicts_title), onBack)
        conflicts.forEach { c -> ConflictCard(bookUrl, c) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ConflictCard(bookUrl: String, c: SyncConflict) {
    val p = LocalGlass.current
    val scope = rememberCoroutineScope()
    var mine by remember(c.rawId) { mutableStateOf<Contact?>(null) }
    LaunchedEffect(c.rawId) {
        mine = withContext(Dispatchers.IO) { if (c.kind == ConflictKind.DELETED_HERE_EDITED_THERE) null else runCatching { Repo.contacts.readRawContact(c.rawId) }.getOrNull() }
    }
    val theirs = remember(c.serverVcard) { c.serverVcard?.let(VCards::contactOf) }
    GlassCard(Modifier.padding(vertical = 6.dp)) {
        Text(c.title.ifBlank { str(R.string.no_name) }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Version(str(R.string.conflicts_mine), mine, str(R.string.conflicts_deleted_here))
        Version(str(R.string.conflicts_theirs), theirs, str(R.string.conflicts_deleted_there))
        Spacer(Modifier.height(10.dp))
        GlassButton(str(R.string.conflicts_keep_mine), { scope.launch { SyncEngine.keepMine(bookUrl, c) } }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        GlassButton(str(R.string.conflicts_keep_theirs), { scope.launch { SyncEngine.keepServer(bookUrl, c) } }, Modifier.fillMaxWidth())
        Text(str(R.string.conflicts_later), Modifier.padding(top = 8.dp), fontSize = 12.sp, color = p.contentSecondary)
    }
}

@Composable
private fun Version(title: String, c: Contact?, gone: String) {
    val p = LocalGlass.current
    Text(title, Modifier.padding(top = 10.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = p.contentSecondary)
    if (c == null) {
        Text(gone, fontSize = 15.sp)
        return
    }
    val lines = listOfNotNull(c.displayName.ifBlank { null }, c.organization) + c.phones.map { it.number } + c.emails.map { it.address } + listOfNotNull(c.note)
    lines.forEach { Text(it, Modifier.fillMaxWidth(), fontSize = 15.sp) }
}
