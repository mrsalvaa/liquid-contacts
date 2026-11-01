// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidcontacts.DevTools
import dev.liquidcontacts.R
import dev.liquidcontacts.contacts.ContactAccount
import dev.liquidcontacts.core.phone.PhoneNumbers
import dev.liquidcontacts.core.text.Fold
import dev.liquidcontacts.core.vcard.VCardImport
import dev.liquidcontacts.data.Repo
import dev.liquidcontacts.data.VcfFiles
import dev.liquidcontacts.data.Stores
import dev.liquidcontacts.ui.components.DangerButton
import dev.liquidcontacts.ui.glass.DialogSpec
import dev.liquidcontacts.ui.glass.GlassButton
import dev.liquidcontacts.ui.glass.GlassCard
import dev.liquidcontacts.ui.glass.GlassChip
import dev.liquidcontacts.ui.glass.GlassTextField
import dev.liquidcontacts.ui.glass.LocalDialog
import dev.liquidcontacts.ui.glass.LocalGlass
import dev.liquidcontacts.ui.glass.SectionLabel
import dev.liquidcontacts.ui.glass.SettingRow
import dev.liquidcontacts.util.plural
import dev.liquidcontacts.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(padding: PaddingValues, onBack: () -> Unit, toAreaCode: Boolean = false, onDuplicates: () -> Unit, onImport: (VCardImport.Ok) -> Unit, onExport: () -> Unit) {
    val p = LocalGlass.current
    val context = LocalContext.current
    val dialog = LocalDialog.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val s by Stores.settings.state.collectAsStateWithLifecycle()
    val book by Repo.book.collectAsStateWithLifecycle()
    val edit: ((dev.liquidcontacts.data.AppSettings) -> dev.liquidcontacts.data.AppSettings) -> Unit = { Stores.settings.update(it) }

    // Contactos con números sin código de área, y cuántos completaría el código escrito.
    var area by remember { mutableStateOf(s.defaultAreaCode) }
    var missing by remember { mutableIntStateOf(0) }
    var completed by remember { mutableIntStateOf(0) }
    LaunchedEffect(book, s.defaultCountry, area) {
        val country = s.defaultCountry.ifEmpty { "AR" }
        val bare = PhoneNumbers(country)
        val withArea = area.takeIf { it.isNotBlank() }?.let { PhoneNumbers(country, it) }
        withContext(Dispatchers.Default) {
            val affected = book.rows.filter { r -> r.summary.phones.any(bare::missingAreaCode) }
            missing = affected.size
            completed = if (withArea == null) 0 else affected.count { r -> r.summary.phones.filter(bare::missingAreaCode).all { withArea.exact(it) != null } }
        }
    }
    var local by remember { mutableStateOf<ContactAccount?>(null) }
    var accounts by remember { mutableStateOf<List<ContactAccount>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val loc = Repo.contacts.localAccount()
            local = loc
            accounts = (listOf(loc) + Repo.contacts.accounts()).distinct()
        }
    }

    // Desde el aviso de la lista se llega directo al campo.
    val areaCodeField = remember { BringIntoViewRequester() }
    if (toAreaCode) {
        LaunchedEffect(Unit) {
            delay(250)
            areaCodeField.bringIntoView()
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                when (val r = withContext(Dispatchers.IO) { VcfFiles.read(context, uri) }) {
                    is VCardImport.Ok -> if (r.contacts.isEmpty()) toast(str(R.string.import_empty)) else onImport(r)
                    is VCardImport.TooLarge -> toast(str(R.string.import_too_large))
                    VCardImport.Invalid -> toast(str(R.string.import_invalid))
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        BackTitle(str(R.string.settings), onBack)

        SectionLabel(str(R.string.appearance))
        AppearanceCard(s, edit)

        SectionLabel(str(R.string.numbers))
        GlassCard {
            SettingRow(
                str(R.string.default_country),
                subtitle = countryName(s.defaultCountry) + " (+" + PhoneNumbers.callingCode(s.defaultCountry.ifEmpty { "AR" }) + ")",
                onClick = {
                    dialog(DialogSpec(str(R.string.default_country)) { dismiss -> CountryPicker { c -> edit { it.copy(defaultCountry = c) }; dismiss() } })
                },
            )
            Text(str(R.string.default_country_hint), fontSize = 12.sp, color = p.contentSecondary)
            // El código de área por defecto, por ahora, solo existe para Argentina.
            if (PhoneNumbers(s.defaultCountry.ifEmpty { "AR" }).supportsAreaCode) Column(Modifier.bringIntoViewRequester(areaCodeField)) {
                Spacer(Modifier.height(14.dp))
                Text(str(R.string.default_area_code), fontSize = 16.sp)
                GlassTextField(
                    area,
                    { v ->
                        val digits = v.filter(Char::isDigit).take(5)
                        area = digits
                        edit { it.copy(defaultAreaCode = digits.trimStart('0')) }
                    },
                    str(R.string.default_area_code_hint_field),
                    Modifier.padding(vertical = 6.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    when {
                        missing == 0 -> str(R.string.area_code_none_missing)
                        area.isBlank() -> plural(R.plurals.area_code_missing, missing)
                        else -> plural(R.plurals.area_code_completes, missing, completed, missing)
                    },
                    fontSize = 13.sp,
                    color = p.contentSecondary,
                )
                Text(str(R.string.default_area_code_hint), Modifier.padding(top = 6.dp), fontSize = 12.sp, color = p.contentSecondary)
            }
        }

        SectionLabel(str(R.string.new_contacts))
        GlassCard {
            Text(str(R.string.new_contacts_hint), fontSize = 13.sp, color = p.contentSecondary)
            FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                accounts.forEach { a ->
                    val selected = if (s.newContactAccount.isEmpty()) a == local else a.id == s.newContactAccount
                    GlassChip(Labels.account(a, local), selected, { edit { it.copy(newContactAccount = if (a == local) "" else a.id) } })
                }
            }
        }

        SectionLabel(str(R.string.tools))
        GlassCard {
            SettingRow(str(R.string.import_vcf), subtitle = str(R.string.import_vcf_hint), onClick = { pickFile.launch(VcfFiles.OPEN_TYPES) })
            SettingRow(str(R.string.export_vcf), subtitle = str(R.string.export_vcf_hint), onClick = onExport)
            SettingRow(str(R.string.duplicates), subtitle = str(R.string.duplicates_hint), onClick = onDuplicates)
        }

        if (DevTools.available) {
            SectionLabel(str(R.string.dev_tools))
            GlassCard {
                Text(str(R.string.dev_tools_hint), fontSize = 13.sp, color = p.contentSecondary)
                Spacer(Modifier.height(10.dp))
                GlassButton(str(R.string.dev_generate), {
                    toast(str(R.string.dev_working))
                    Repo.launch {
                        val n = DevTools.generate(Repo.contacts, 520)
                        withContext(Dispatchers.Main) { toast(plural(R.plurals.dev_generated, n)) }
                    }
                }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                DangerButton(str(R.string.dev_clear), {
                    Repo.launch {
                        val n = DevTools.clear(Repo.contacts)
                        withContext(Dispatchers.Main) { toast(plural(R.plurals.dev_cleared, n)) }
                    }
                }, Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Nombre del país en el idioma del teléfono. */
private fun countryName(code: String): String =
    Locale("", code.ifEmpty { "AR" }).getDisplayCountry(Locale.getDefault()).ifEmpty { code }

/** Lista de países con búsqueda (sin tildes), con su código de llamada. */
@Composable
private fun CountryPicker(onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = remember {
        PhoneNumbers.regions().map { it to countryName(it) }.sortedWith { a, b -> dev.liquidcontacts.core.text.SpanishOrder().compare(a.second, b.second) }
    }
    val shown = remember(query) {
        val q = Fold.fold(query.trim())
        if (q.isEmpty()) all else all.filter { (code, name) -> Fold.fold(name).contains(q) || code.equals(q, ignoreCase = true) }
    }
    GlassTextField(query, { query = it }, str(R.string.search_country))
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp).padding(top = 8.dp)) {
        items(shown, key = { it.first }) { (code, name) ->
            SettingRow(name, subtitle = "+" + PhoneNumbers.callingCode(code), onClick = { onPick(code) })
        }
    }
}
