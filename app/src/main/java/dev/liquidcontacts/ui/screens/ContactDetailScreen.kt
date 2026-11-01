// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidcontacts.R
import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.model.ContactEvent
import dev.liquidcontacts.core.text.Clean
import dev.liquidcontacts.core.vcard.VCardFormat
import dev.liquidcontacts.data.Repo
import dev.liquidcontacts.data.VcfFiles
import dev.liquidcontacts.ui.components.ContactAvatar
import dev.liquidcontacts.ui.glass.DialogSpec
import dev.liquidcontacts.ui.glass.GlassButton
import dev.liquidcontacts.ui.glass.GlassCard
import dev.liquidcontacts.ui.glass.GlassIconButton
import dev.liquidcontacts.ui.glass.GlassSwitch
import dev.liquidcontacts.ui.glass.LocalDialog
import dev.liquidcontacts.ui.glass.LocalGlass
import dev.liquidcontacts.ui.glass.SectionLabel
import dev.liquidcontacts.ui.glass.SettingRow
import dev.liquidcontacts.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Llamar, escribir, mandar un email o abrir un mapa con la app que el usuario tenga elegida. */
object Actions {
    fun dial(context: Context, number: String) = start(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
    fun message(context: Context, number: String) = start(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))
    fun email(context: Context, address: String) = start(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null)))
    fun map(context: Context, address: String) = start(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(address))))
    /** Solo http y https: una agenda puede traer cualquier cosa como "sitio web" (javascript:, intent:). */
    fun web(context: Context, url: String): Boolean {
        val safe = Clean.url(url) ?: return false
        return start(context, Intent(Intent.ACTION_VIEW, Uri.parse(if (safe.startsWith("http", ignoreCase = true) && "://" in safe) safe else "https://$safe")))
    }

    /** false si no hay ninguna app para eso. */
    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

@Composable
fun ContactDetailScreen(contactId: Long, padding: PaddingValues, onBack: () -> Unit, onEdit: () -> Unit) {
    val p = LocalGlass.current
    val context = LocalContext.current
    val dialog = LocalDialog.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val book by Repo.book.collectAsStateWithLifecycle()
    val numbers by Repo.numbers.collectAsStateWithLifecycle()
    var contact by remember { mutableStateOf<Contact?>(null) }
    var gone by remember { mutableStateOf(false) }
    // Se relee cuando cambia la agenda (al editar, o si otra app lo cambia o lo borra).
    LaunchedEffect(book) {
        val c = withContext(Dispatchers.IO) { Repo.contacts.load(contactId) }
        if (c == null) gone = true else contact = c
    }
    LaunchedEffect(gone) { if (gone) onBack() }
    val noApp = { toast(str(R.string.no_app)) }
    val c = contact

    Column(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
    ) {
        BackTitle("", onBack) {
            if (c != null) {
                GlassIconButton(
                    if (c.starred) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                    str(if (c.starred) R.string.remove_favorite else R.string.add_favorite),
                    {
                        val next = !c.starred
                        contact = c.copy(starred = next)
                        Repo.launch { Repo.contacts.setStarred(contactId, next) }
                    },
                )
                GlassIconButton(Icons.Rounded.Share, str(R.string.share), {
                    dialog(
                        DialogSpec(str(R.string.share_contact)) { dismiss ->
                            var photos by remember { mutableStateOf(true) }
                            SettingRow(str(R.string.export_photos), trailing = { GlassSwitch(photos, { photos = it }) })
                            Text(str(R.string.export_warning), fontSize = 13.sp, color = p.contentSecondary)
                            Spacer(Modifier.height(16.dp))
                            GlassButton(str(R.string.share), {
                                dismiss()
                                scope.launch {
                                    val intent = withContext(Dispatchers.IO) { VcfFiles.shareIntent(context, listOf(contactId), VCardFormat.V3, photos) }
                                    if (intent == null) toast(str(R.string.export_failed)) else context.startActivity(intent)
                                }
                            }, Modifier.fillMaxWidth(), prominent = true)
                            Spacer(Modifier.height(8.dp))
                            GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
                        },
                    )
                })
                GlassIconButton(Icons.Rounded.Edit, str(R.string.edit), onEdit)
            }
        }
        if (c == null) return@Column

        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ContactAvatar(c.displayName, size = 112.dp, photoUri = book.rows.firstOrNull { it.summary.id == contactId }?.summary?.photoUri)
            Text(
                c.displayName.ifBlank { str(R.string.no_name) },
                Modifier.padding(top = 14.dp).semantics { heading() },
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            val work = listOfNotNull(c.jobTitle, c.organization).filter { it.isNotBlank() }.joinToString(" · ")
            if (work.isNotEmpty()) Text(work, fontSize = 15.sp, color = p.contentSecondary, textAlign = TextAlign.Center)
            if (!c.nickname.isNullOrBlank()) Text("“${c.nickname}”", fontSize = 15.sp, color = p.contentSecondary)
        }

        // Acciones rápidas con el número o email principal.
        val mainPhone = c.phones.firstOrNull { it.primary } ?: c.phones.firstOrNull()
        val mainEmail = c.emails.firstOrNull { it.primary } ?: c.emails.firstOrNull()
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (mainPhone != null) {
                GlassButton(str(R.string.call), { if (!Actions.dial(context, mainPhone.number)) noApp() }, Modifier.weight(1f), icon = Icons.Rounded.Call, prominent = true)
                GlassButton(str(R.string.message), { if (!Actions.message(context, mainPhone.number)) noApp() }, Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.Message)
            }
            if (mainEmail != null && mainPhone == null) {
                GlassButton(str(R.string.email), { if (!Actions.email(context, mainEmail.address)) noApp() }, Modifier.weight(1f), icon = Icons.Rounded.Email, prominent = true)
            }
        }

        if (c.phones.isNotEmpty()) {
            SectionLabel(str(R.string.phones))
            GlassCard {
                c.phones.forEach { ph ->
                    DetailRow(numbers.format(ph.number), Labels.of(ph), { if (!Actions.dial(context, ph.number)) noApp() }) {
                        GlassIconButton(Icons.AutoMirrored.Rounded.Message, str(R.string.message_number, ph.number), { if (!Actions.message(context, ph.number)) noApp() }, size = 44.dp)
                    }
                }
            }
        }
        if (c.emails.isNotEmpty()) {
            SectionLabel(str(R.string.emails))
            GlassCard { c.emails.forEach { e -> DetailRow(e.address, Labels.of(e), { if (!Actions.email(context, e.address)) noApp() }) } }
        }
        if (c.addresses.isNotEmpty()) {
            SectionLabel(str(R.string.addresses))
            GlassCard { c.addresses.forEach { a -> DetailRow(a.formatted, Labels.of(a), { if (!Actions.map(context, a.formatted)) noApp() }) } }
        }
        if (c.events.isNotEmpty()) {
            SectionLabel(str(R.string.dates))
            GlassCard { c.events.forEach { e -> DetailRow(formatDate(e), Labels.of(e), null) } }
        }
        if (c.websites.isNotEmpty()) {
            SectionLabel(str(R.string.websites))
            GlassCard { c.websites.forEach { w -> DetailRow(w, null, { if (!Actions.web(context, w)) noApp() }) } }
        }
        if (c.groups.isNotEmpty()) {
            SectionLabel(str(R.string.tab_labels))
            GlassCard { Text(c.groups.sorted().joinToString(" · "), Modifier.padding(vertical = 8.dp), fontSize = 16.sp) }
        }
        if (!c.note.isNullOrBlank()) {
            SectionLabel(str(R.string.note))
            GlassCard { Text(c.note!!, Modifier.padding(vertical = 8.dp), fontSize = 16.sp) }
        }

        Spacer(Modifier.height(24.dp))
        GlassButton(
            str(R.string.delete_contact),
            {
                dialog(
                    DialogSpec(str(R.string.delete_contact_title, c.displayName.ifBlank { str(R.string.no_name) })) { dismiss ->
                        Text(str(R.string.delete_contact_text), fontSize = 15.sp, color = p.contentSecondary)
                        Spacer(Modifier.height(16.dp))
                        GlassButton(str(R.string.delete), {
                            dismiss()
                            Repo.launch { Repo.contacts.delete(contactId) }
                            onBack()
                        }, Modifier.fillMaxWidth(), danger = true)
                        Spacer(Modifier.height(8.dp))
                        GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
                    },
                )
            },
            Modifier.fillMaxWidth(),
            icon = Icons.Rounded.DeleteOutline,
            danger = true,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** Fila de un dato: valor grande, tipo abajo; tocarla hace la acción (llamar, escribir, abrir). */
@Composable
private fun DetailRow(value: String, label: String?, onClick: (() -> Unit)?, trailing: @Composable (() -> Unit)? = null) {
    val p = LocalGlass.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(value, label).joinToString(", ") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(value, fontSize = 17.sp)
            if (!label.isNullOrBlank()) Text(label, fontSize = 13.sp, color = p.contentSecondary)
        }
        trailing?.invoke()
    }
}

/** Fecha en el formato del idioma del teléfono ("14 de marzo de 1990"); sin año, solo día y mes. */
fun formatDate(e: ContactEvent): String {
    val locale = java.util.Locale.getDefault()
    return if (e.year != null) {
        java.time.LocalDate.of(e.year!!, e.month, e.day).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG).withLocale(locale))
    } else {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMM")
        java.time.MonthDay.of(e.month, e.day).format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
    }
}
