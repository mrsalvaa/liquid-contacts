// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.ui.screens

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.liquidcontacts.R
import dev.liquidcontacts.contacts.ContactAccount
import dev.liquidcontacts.contacts.EditableContact
import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.model.ContactEvent
import dev.liquidcontacts.core.model.Email
import dev.liquidcontacts.core.model.EventKind
import dev.liquidcontacts.core.model.LabelKind
import dev.liquidcontacts.core.model.Phone
import dev.liquidcontacts.core.model.PostalAddress
import dev.liquidcontacts.data.Repo
import dev.liquidcontacts.data.Stores
import dev.liquidcontacts.ui.components.DangerButton
import dev.liquidcontacts.ui.components.ContactAvatar
import dev.liquidcontacts.ui.components.SwitchRow
import dev.liquidcontacts.ui.glass.DialogSpec
import dev.liquidcontacts.ui.glass.GlassButton
import dev.liquidcontacts.ui.glass.GlassCard
import dev.liquidcontacts.ui.glass.GlassChip
import dev.liquidcontacts.ui.glass.GlassIconButton
import dev.liquidcontacts.ui.glass.GlassTextField
import dev.liquidcontacts.ui.glass.LocalActionSheet
import dev.liquidcontacts.ui.glass.LocalDialog
import dev.liquidcontacts.ui.glass.LocalGlass
import dev.liquidcontacts.ui.glass.LocalPrompt
import dev.liquidcontacts.ui.glass.PromptSpec
import dev.liquidcontacts.ui.glass.SectionLabel
import dev.liquidcontacts.ui.glass.SettingRow
import dev.liquidcontacts.ui.glass.SheetAction
import dev.liquidcontacts.ui.glass.SheetSpec
import dev.liquidcontacts.ui.glass.WheelPicker
import dev.liquidcontacts.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.YearMonth

sealed interface EditTarget {
    data object New : EditTarget
    /** [extra]: datos que otra app pidió sumar al formulario; nada se guarda sin tocar guardar. */
    data class Existing(val contactId: Long, val extra: Contact? = null) : EditTarget

    /** Contacto nuevo con datos ya cargados (p. ej. desde otra app). */
    data class Prefilled(val contact: Contact) : EditTarget
}

@Composable
fun ContactEditScreen(target: EditTarget, padding: PaddingValues, onBack: () -> Unit, onSaved: (Long?) -> Unit) {
    val p = LocalGlass.current
    val context = LocalContext.current
    val dialog = LocalDialog.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val pickKind = rememberKindPicker()
    val pickEventKind = rememberEventKindPicker()
    val pickDate = rememberDatePicker()

    var original by remember { mutableStateOf<EditableContact?>(null) }
    var draft by remember { mutableStateOf<Contact?>(null) }
    var account by remember { mutableStateOf<ContactAccount?>(null) }
    var accounts by remember { mutableStateOf<List<ContactAccount>>(emptyList()) }
    var local by remember { mutableStateOf<ContactAccount?>(null) }
    var photoChanged by remember { mutableStateOf(false) }
    var moreName by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    // Fichas del contacto (una por cuenta, en general) y cuál se está editando.
    var fichas by remember { mutableStateOf<List<Pair<Long, ContactAccount>>>(emptyList()) }
    var rawChoice by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(target, rawChoice) {
        withContext(Dispatchers.IO) {
            val loc = Repo.contacts.localAccount()
            local = loc
            accounts = (listOf(loc) + Repo.contacts.accounts()).distinct()
            val preferred = Stores.settings.value.newContactAccount.takeIf { it.isNotEmpty() }?.let(ContactAccount::fromId)
            when (target) {
                is EditTarget.Existing -> Repo.contacts.loadForEdit(target.contactId, preferred, rawChoice)?.let {
                    fichas = Repo.contacts.rawContactIds(target.contactId)
                    original = it
                    draft = target.extra?.let { extra -> it.contact.plusExtra(extra) } ?: it.contact
                    photoChanged = false
                    account = it.account
                    moreName = listOf(it.contact.name.prefix, it.contact.name.middle, it.contact.name.suffix, it.contact.name.phoneticGiven, it.contact.name.phoneticFamily)
                        .any { s -> !s.isNullOrBlank() }
                }
                EditTarget.New, is EditTarget.Prefilled -> {
                    val c = (target as? EditTarget.Prefilled)?.contact ?: Contact(phones = listOf(Phone("")))
                    draft = c
                    photoChanged = c.photo != null
                    account = preferred?.takeIf { it in accounts } ?: loc
                }
            }
        }
    }
    val d = draft ?: return
    val start = original?.contact ?: Contact(phones = listOf(Phone("")))
    val dirty = d != start || photoChanged
    // Siempre sobre el borrador actual (los menús y diálogos responden más tarde).
    fun edit(f: (Contact) -> Contact) {
        draft = draft?.let(f)
    }

    // Volver con cambios sin guardar: se pregunta antes de descartarlos.
    val confirmDiscard = {
        dialog(
            DialogSpec(str(R.string.discard_title)) { dismiss ->
                Text(str(R.string.discard_text), fontSize = 15.sp, color = p.contentSecondary)
                Spacer(Modifier.height(16.dp))
                DangerButton(str(R.string.discard), { dismiss(); onBack() }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                GlassButton(str(R.string.keep_editing), dismiss, Modifier.fillMaxWidth())
            },
        )
    }
    BackHandler(enabled = dirty && !saving) { confirmDiscard() }

    val save = save@{
        if (saving) return@save
        val clean = d.copy(
            phones = d.phones.filter { it.number.isNotBlank() },
            emails = d.emails.filter { it.address.isNotBlank() },
            addresses = d.addresses.filter { it.formatted.isNotBlank() },
            websites = d.websites.filter { it.isNotBlank() },
        )
        if (clean.displayName.isBlank() && clean.photo == null) {
            toast(str(R.string.empty_contact))
            return@save
        }
        saving = true
        val edit = EditableContact(original?.rawContactId, account ?: ContactAccount(null, null), clean)
        Repo.launch {
            val id = runCatching { Repo.contacts.save(edit, photoChanged) }.getOrNull()
            withContext(Dispatchers.Main) {
                saving = false
                if (id == null) toast(str(R.string.save_failed)) else onSaved(id)
            }
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { runCatching { downscaledJpeg(context.contentResolver, uri) }.getOrNull() }
                if (bytes == null) toast(str(R.string.photo_failed)) else {
                    draft = draft?.copy(photo = bytes)
                    photoChanged = true
                }
            }
        }
    }

    Column(
        // [padding] ya incluye el teclado (safeDrawing): imePadding acá lo descontaría dos veces.
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
    ) {
        BackTitle(
            str(if (original == null) R.string.new_contact else R.string.edit_contact),
            { if (dirty) confirmDiscard() else onBack() },
        ) {
            GlassIconButton(Icons.Rounded.Check, str(R.string.save), save, prominent = true, enabled = !saving)
        }

        // Foto
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ContactAvatar(
                d.displayName,
                Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    .semantics { contentDescription = str(R.string.choose_photo) },
                size = 104.dp,
                photo = d.photo,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                GlassChip(str(if (d.photo == null) R.string.add_photo else R.string.change_photo), false, {
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
                if (d.photo != null) {
                    GlassChip(str(R.string.remove_photo), false, {
                        edit { it.copy(photo = null) }
                        photoChanged = true
                    })
                }
            }
        }

        // Un contacto armado con varias fichas: cada una dice de qué cuenta es y se edita por separado.
        if (fichas.size > 1) {
            SectionLabel(str(R.string.fichas_title))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fichas.forEachIndexed { i, (raw, a) ->
                    val repeated = fichas.count { it.second == a } > 1
                    val label = Labels.account(a, local) + if (repeated) " (${i + 1})" else ""
                    GlassChip(label, raw == original?.rawContactId, {
                        if (raw != original?.rawContactId) {
                            if (dirty) {
                                dialog(
                                    DialogSpec(str(R.string.discard_title)) { dismiss ->
                                        Text(str(R.string.discard_text), fontSize = 15.sp, color = p.contentSecondary)
                                        Spacer(Modifier.height(16.dp))
                                        DangerButton(str(R.string.discard), { dismiss(); rawChoice = raw }, Modifier.fillMaxWidth())
                                        Spacer(Modifier.height(8.dp))
                                        GlassButton(str(R.string.keep_editing), dismiss, Modifier.fillMaxWidth())
                                    },
                                )
                            } else {
                                rawChoice = raw
                            }
                        }
                    })
                }
            }
            Text(str(R.string.fichas_hint), Modifier.padding(horizontal = 8.dp, vertical = 6.dp), fontSize = 13.sp, color = p.contentSecondary)
        } else if (original == null && accounts.size > 1) {
            SectionLabel(str(R.string.save_in))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                accounts.forEach { a -> GlassChip(Labels.account(a, local), a == account, { account = a }) }
            }
        } else if (account != null) {
            Text(
                str(R.string.saved_in, Labels.account(account!!, local)),
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                fontSize = 13.sp,
                color = p.contentSecondary,
            )
        }

        // Nombre
        SectionLabel(str(R.string.name))
        GlassCard {
            val n = d.name
            val words = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
            // Un nombre que vino armado (sin partes) se edita como nombre de pila.
            val given = n.given ?: n.display.takeIf { n.family.isNullOrBlank() && n.given.isNullOrBlank() }
            Field(given.orEmpty(), { v -> edit { it.copy(name = it.name.copy(given = v, display = "")) } }, str(R.string.given_name), words)
            Field(n.family.orEmpty(), { v -> edit { it.copy(name = it.name.copy(family = v, given = it.name.given ?: given, display = "")) } }, str(R.string.family_name), words)
            if (moreName) {
                Field(n.prefix.orEmpty(), { v -> edit { it.copy(name = it.name.copy(prefix = v)) } }, str(R.string.name_prefix), words)
                Field(n.middle.orEmpty(), { v -> edit { it.copy(name = it.name.copy(middle = v)) } }, str(R.string.middle_name), words)
                Field(n.suffix.orEmpty(), { v -> edit { it.copy(name = it.name.copy(suffix = v)) } }, str(R.string.name_suffix), words)
                Field(n.phoneticGiven.orEmpty(), { v -> edit { it.copy(name = it.name.copy(phoneticGiven = v)) } }, str(R.string.phonetic_given), words)
                Field(n.phoneticFamily.orEmpty(), { v -> edit { it.copy(name = it.name.copy(phoneticFamily = v)) } }, str(R.string.phonetic_family), words)
            }
            Field(d.nickname.orEmpty(), { v -> edit { it.copy(nickname = v) } }, str(R.string.nickname), words)
            if (!moreName) {
                Text(
                    str(R.string.more_name_fields),
                    Modifier.clickable(role = Role.Button) { moreName = true }.padding(vertical = 12.dp),
                    color = p.accentFg,
                    fontSize = 15.sp,
                )
            }
        }

        SectionLabel(str(R.string.work))
        GlassCard {
            Field(d.organization.orEmpty(), { v -> edit { it.copy(organization = v) } }, str(R.string.company), KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            Field(d.jobTitle.orEmpty(), { v -> edit { it.copy(jobTitle = v) } }, str(R.string.job_title), KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        }

        // Teléfonos
        SectionLabel(str(R.string.phones))
        GlassCard {
            d.phones.forEachIndexed { i, ph ->
                ItemRow(
                    kind = Labels.of(ph),
                    onKind = { pickKind(Labels.PHONE_KINDS, false) { k, label -> edit { it.copy(phones = it.phones.replaceAt(i, ph.copy(kind = k, label = label))) } } },
                    onRemove = { edit { it.copy(phones = it.phones.removeAt(i)) } },
                ) {
                    Field(ph.number, { v -> edit { it.copy(phones = it.phones.replaceAt(i, ph.copy(number = v))) } }, str(R.string.phone), KeyboardOptions(keyboardType = KeyboardType.Phone))
                }
            }
            AddRow(str(R.string.add_phone)) { edit { it.copy(phones = it.phones + Phone("")) } }
        }

        SectionLabel(str(R.string.emails))
        GlassCard {
            d.emails.forEachIndexed { i, e ->
                ItemRow(
                    kind = Labels.of(e),
                    onKind = { pickKind(Labels.EMAIL_KINDS, true) { k, label -> edit { it.copy(emails = it.emails.replaceAt(i, e.copy(kind = k, label = label))) } } },
                    onRemove = { edit { it.copy(emails = it.emails.removeAt(i)) } },
                ) {
                    Field(e.address, { v -> edit { it.copy(emails = it.emails.replaceAt(i, e.copy(address = v))) } }, str(R.string.email), KeyboardOptions(keyboardType = KeyboardType.Email))
                }
            }
            AddRow(str(R.string.add_email)) { edit { it.copy(emails = it.emails + Email("")) } }
        }

        SectionLabel(str(R.string.addresses))
        GlassCard {
            d.addresses.forEachIndexed { i, a ->
                ItemRow(
                    kind = Labels.of(a),
                    onKind = { pickKind(Labels.ADDRESS_KINDS, false) { k, label -> edit { it.copy(addresses = it.addresses.replaceAt(i, a.copy(kind = k, label = label))) } } },
                    onRemove = { edit { it.copy(addresses = it.addresses.removeAt(i)) } },
                ) {
                    val words = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                    fun set(f: (PostalAddress) -> PostalAddress) = edit { it.copy(addresses = it.addresses.replaceAt(i, f(a))) }
                    Field(a.street.orEmpty(), { v -> set { x -> x.copy(street = v) } }, str(R.string.street), words, singleLine = false)
                    Field(a.city.orEmpty(), { v -> set { x -> x.copy(city = v) } }, str(R.string.city), words)
                    Field(a.region.orEmpty(), { v -> set { x -> x.copy(region = v) } }, str(R.string.region), words)
                    Field(a.postcode.orEmpty(), { v -> set { x -> x.copy(postcode = v) } }, str(R.string.postcode), KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                    Field(a.country.orEmpty(), { v -> set { x -> x.copy(country = v) } }, str(R.string.country), words)
                }
            }
            AddRow(str(R.string.add_address)) { edit { it.copy(addresses = it.addresses + PostalAddress()) } }
        }

        SectionLabel(str(R.string.dates))
        GlassCard {
            d.events.forEachIndexed { i, e ->
                ItemRow(
                    kind = Labels.of(e),
                    onKind = { pickEventKind { k, label -> edit { it.copy(events = it.events.replaceAt(i, ContactEvent(e.month, e.day, e.year, k, label))) } } },
                    onRemove = { edit { it.copy(events = it.events.removeAt(i)) } },
                ) {
                    Text(
                        formatDate(e),
                        Modifier.fillMaxWidth().clickable(role = Role.Button) {
                            pickDate(e) { picked -> edit { it.copy(events = it.events.replaceAt(i, picked)) } }
                        }.padding(vertical = 14.dp),
                        fontSize = 17.sp,
                    )
                }
            }
            AddRow(str(R.string.add_date)) {
                val kind = if (d.events.none { it.kind == EventKind.BIRTHDAY }) EventKind.BIRTHDAY else EventKind.OTHER
                pickDate(ContactEvent(1, 1, null, kind)) { picked -> edit { it.copy(events = it.events + picked) } }
            }
        }

        SectionLabel(str(R.string.websites))
        GlassCard {
            d.websites.forEachIndexed { i, w ->
                ItemRow(kind = null, onKind = null, onRemove = { edit { it.copy(websites = it.websites.removeAt(i)) } }) {
                    Field(w, { v -> edit { it.copy(websites = it.websites.replaceAt(i, v)) } }, str(R.string.website), KeyboardOptions(keyboardType = KeyboardType.Uri))
                }
            }
            AddRow(str(R.string.add_website)) { edit { it.copy(websites = it.websites + "") } }
        }

        SectionLabel(str(R.string.tab_labels))
        LabelsEditor(d.groups, account) { groups -> edit { it.copy(groups = groups) } }

        SectionLabel(str(R.string.note))
        GlassCard {
            Field(d.note.orEmpty(), { v -> edit { it.copy(note = v) } }, str(R.string.note), KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), singleLine = false)
        }

        Spacer(Modifier.height(12.dp))
        GlassCard {
            SwitchRow(str(R.string.favorite), d.starred, { v -> edit { it.copy(starred = v) } })
        }
        Spacer(Modifier.height(16.dp))
        GlassButton(str(R.string.save), save, Modifier.fillMaxWidth(), prominent = true, enabled = !saving)
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- piezas del formulario

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = true,
) {
    // Con texto cargado no se ve la ayuda: TalkBack igual tiene que decir qué campo es. Vacío, la
    // ayuda ya es el nombre del campo y un rótulo aparte lo repetiría.
    GlassTextField(
        value,
        onChange,
        placeholder,
        Modifier.padding(vertical = 4.dp).then(if (value.isEmpty()) Modifier else Modifier.semantics { contentDescription = placeholder }),
        keyboardOptions = keyboard,
        singleLine = singleLine,
    )
}

/** Un dato con su tipo (chip que abre la lista de tipos) y el botón para sacarlo. */
@Composable
private fun ItemRow(kind: String?, onKind: (() -> Unit)?, onRemove: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (kind != null && onKind != null) GlassChip(kind, false, onKind)
            Spacer(Modifier.weight(1f))
            GlassIconButton(Icons.Rounded.Close, str(R.string.remove_item, kind ?: str(R.string.website)), onRemove, size = 48.dp)
        }
        content()
    }
}

@Composable
private fun AddRow(text: String, onClick: () -> Unit) {
    SettingRow(text, onClick = onClick, leading = {
        // La fila entera es el botón: el + no es otra parada para TalkBack.
        GlassIconButton(Icons.Rounded.Add, null, onClick, Modifier.clearAndSetSemantics { }, size = 40.dp)
        Spacer(Modifier.width(12.dp))
    })
}

/** Elegir el tipo de un dato, o escribir uno propio. */
@Composable
private fun rememberKindPicker(): (List<LabelKind>, Boolean, (LabelKind, String?) -> Unit) -> Unit {
    val sheet = LocalActionSheet.current
    val prompt = LocalPrompt.current
    return { kinds, email, onPick ->
        sheet(
            SheetSpec(
                str(R.string.label_type),
                kinds.map { k -> SheetAction(Labels.kind(k, email), Icons.Rounded.Check) { onPick(k, null) } } +
                    SheetAction(str(R.string.kind_custom), Icons.Rounded.Edit) {
                        prompt(PromptSpec(str(R.string.kind_custom), str(R.string.save), str(R.string.custom_label)) { t ->
                            if (t.isNotBlank()) onPick(LabelKind.CUSTOM, t.trim())
                        })
                    },
            ),
        )
    }
}

@Composable
private fun rememberEventKindPicker(): ((EventKind, String?) -> Unit) -> Unit {
    val sheet = LocalActionSheet.current
    val prompt = LocalPrompt.current
    return { onPick ->
        sheet(
            SheetSpec(
                str(R.string.label_type),
                Labels.EVENT_KINDS.map { k -> SheetAction(Labels.event(k), Icons.Rounded.Check) { onPick(k, null) } } +
                    SheetAction(str(R.string.kind_custom), Icons.Rounded.Edit) {
                        prompt(PromptSpec(str(R.string.kind_custom), str(R.string.save), str(R.string.custom_label)) { t ->
                            if (t.isNotBlank()) onPick(EventKind.CUSTOM, t.trim())
                        })
                    },
            ),
        )
    }
}

/** Ruedas de día, mes y año, con la opción "sin año" (muchos cumpleaños se guardan así). */
@Composable
private fun rememberDatePicker(): (ContactEvent, (ContactEvent) -> Unit) -> Unit {
    val dialog = LocalDialog.current
    return { start, done ->
        dialog(
            DialogSpec(Labels.of(start)) { dismiss ->
                val thisYear = LocalDate.now().year
                var day by remember { mutableIntStateOf(start.day - 1) }
                var month by remember { mutableIntStateOf(start.month - 1) }
                var year by remember { mutableIntStateOf((start.year ?: (thisYear - 30)) - FIRST_YEAR) }
                var withYear by remember { mutableStateOf(start.year != null) }
                val months = java.text.DateFormatSymbols.getInstance().shortMonths.take(12)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    // Lo escrito es el número que se ve, no la posición en la rueda.
                    WheelPicker(31, day, { day = it }, width = 64.dp, fontSize = 30, label = { "${it + 1}" }, typed = { d -> (d - 1).takeIf { it in 0..30 } })
                    WheelPicker(12, month, { month = it }, width = 92.dp, fontSize = 26, label = { months[it].trimEnd('.') }, typed = { m -> (m - 1).takeIf { it in 0..11 } })
                    if (withYear) {
                        WheelPicker(thisYear - FIRST_YEAR + 1, year, { year = it }, cyclic = false, width = 96.dp, fontSize = 28, label = { "${FIRST_YEAR + it}" }, typed = { y -> (y - FIRST_YEAR).takeIf { it in 0..thisYear - FIRST_YEAR } })
                    }
                }
                SwitchRow(str(R.string.with_year), withYear, { withYear = it })
                Spacer(Modifier.height(8.dp))
                GlassButton(str(R.string.done), {
                    val y = if (withYear) FIRST_YEAR + year else null
                    // 31 de un mes de 30 días (o 29/2 en un año no bisiesto): el último día que existe.
                    val last = if (y != null) YearMonth.of(y, month + 1).lengthOfMonth() else java.time.Month.of(month + 1).maxLength()
                    dismiss()
                    done(ContactEvent(month + 1, minOf(day + 1, last), y, start.kind, start.label))
                }, Modifier.fillMaxWidth(), prominent = true)
            },
        )
    }
}

private const val FIRST_YEAR = 1900

/** Etiquetas del contacto: las de su cuenta como chips para marcar, y una nueva con un nombre. */
@Composable
private fun LabelsEditor(selected: Set<String>, account: ContactAccount?, onChange: (Set<String>) -> Unit) {
    val prompt = LocalPrompt.current
    var available by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(account) {
        available = withContext(Dispatchers.IO) { Repo.contacts.groups().filter { it.account == account }.map { it.title }.distinct() }
    }
    GlassCard {
        FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (available + selected).distinct().sorted().forEach { t ->
                GlassChip(t, t in selected, { onChange(if (t in selected) selected - t else selected + t) })
            }
            GlassChip("+ " + str(R.string.new_label), false, {
                prompt(PromptSpec(str(R.string.new_label), str(R.string.add), str(R.string.label_name)) { t ->
                    if (t.isNotBlank()) onChange(selected + t.trim())
                })
            })
        }
    }
}

// ---------------------------------------------------------------- utilidades

/** Suma al contacto lo que pidió otra app: números y emails nuevos; el resto, solo si está vacío. */
private fun Contact.plusExtra(e: Contact): Contact = copy(
    name = if (name.shown.isBlank()) e.name else name,
    organization = organization ?: e.organization,
    jobTitle = jobTitle ?: e.jobTitle,
    phones = phones + e.phones.filter { n -> phones.none { it.number == n.number } },
    emails = emails + e.emails.filter { m -> emails.none { it.address.equals(m.address, ignoreCase = true) } },
    addresses = addresses + e.addresses,
    websites = (websites + e.websites).distinct(),
    note = note ?: e.note,
)

private fun <T> List<T>.replaceAt(i: Int, v: T): List<T> = toMutableList().also { it[i] = v }
private fun <T> List<T>.removeAt(i: Int): List<T> = toMutableList().also { it.removeAt(i) }

/** La foto elegida, achicada a 720 px de lado como máximo y en JPEG (las de la cámara pesan varios MB). */
private fun downscaledJpeg(resolver: ContentResolver, uri: Uri): ByteArray {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
        val max = maxOf(info.size.width, info.size.height)
        if (max > 720) decoder.setTargetSampleSize(maxOf(1, max / 720))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    val scale = 720f / maxOf(bitmap.width, bitmap.height)
    val out = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
    return ByteArrayOutputStream().use { s ->
        out.compress(Bitmap.CompressFormat.JPEG, 88, s)
        s.toByteArray()
    }
}
