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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidcontacts.R
import dev.liquidcontacts.core.dav.DavException
import dev.liquidcontacts.core.dav.RemoteBook
import dev.liquidcontacts.core.vcard.VCardFormat
import dev.liquidcontacts.data.Repo
import dev.liquidcontacts.data.VcfFiles
import dev.liquidcontacts.sync.FirstSyncSummary
import dev.liquidcontacts.sync.SyncEngine
import dev.liquidcontacts.ui.components.dangerText
import dev.liquidcontacts.ui.glass.GlassButton
import dev.liquidcontacts.ui.glass.GlassCard
import dev.liquidcontacts.ui.glass.LocalGlass
import dev.liquidcontacts.util.plural
import dev.liquidcontacts.util.str
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Antes de la primera sincronización de una libreta: cuántos se bajan, cuántos ya están en otras
 * cuentas y un respaldo de lo que ya hay. Solo se baja: no se sube ni se borra nada.
 */
@Composable
fun FirstSyncScreen(book: RemoteBook, padding: PaddingValues, onDone: () -> Unit) {
    val p = LocalGlass.current
    val context = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<FirstSyncSummary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(-1) }
    LaunchedEffect(book) {
        try {
            summary = SyncEngine.prepareFirstSync(book.url)
        } catch (e: DavException) {
            error = errorText(e)
        }
    }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(VcfFiles.MIME)) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    VcfFiles.write(context, uri, VcfFiles.contacts(Repo.book.value.rows.map { it.summary.id }, withPhotos = true), VCardFormat.V3, includePhoto = true)
                }
                toast(str(if (ok) R.string.backup_saved else R.string.export_failed))
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        BackTitle(str(R.string.first_sync_title), onDone, subtitle = book.name)
        error?.let { Text(it, Modifier.padding(8.dp), fontSize = 15.sp, color = p.dangerText) }
        val s = summary
        if (s == null) {
            if (error == null) Text(str(R.string.first_sync_reading), Modifier.padding(8.dp), fontSize = 15.sp)
            return@Column
        }
        GlassCard {
            Text(plural(R.plurals.first_sync_download, s.total), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(plural(R.plurals.first_sync_matches, s.alreadyHere), Modifier.padding(top = 6.dp), fontSize = 15.sp)
            Text(str(R.string.first_sync_rules), Modifier.padding(top = 10.dp), fontSize = 14.sp, color = p.contentSecondary)
        }
        if (s.looksDuplicated || s.davx5) {
            GlassCard(Modifier.padding(top = 10.dp)) {
                if (s.davx5) Text(str(R.string.first_sync_davx5), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = p.dangerText)
                if (s.looksDuplicated) Text(str(R.string.first_sync_duplicated), Modifier.padding(top = 4.dp), fontSize = 14.sp, color = p.dangerText)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (s.existingContacts > 0) {
            GlassButton(plural(R.plurals.first_sync_backup, s.existingContacts), { backup.launch(VcfFiles.backupName()) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        if (progress >= 0) {
            Text(str(R.string.import_progress, progress, s.total), Modifier.padding(8.dp), fontSize = 15.sp)
        } else {
            // Si parece la misma libreta, lo destacado es no sincronizar.
            GlassButton(str(R.string.first_sync_cancel), onDone, Modifier.fillMaxWidth(), prominent = s.looksDuplicated)
            Spacer(Modifier.height(8.dp))
            GlassButton(
                str(if (s.looksDuplicated) R.string.first_sync_start_anyway else R.string.first_sync_start),
                {
                    progress = 0
                    scope.launch {
                        val saved = SyncEngine.runFirstSync(book, s) { n -> progress = n }
                        toast(plural(R.plurals.first_sync_done, saved))
                        onDone()
                    }
                },
                Modifier.fillMaxWidth(),
                prominent = !s.looksDuplicated,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
