// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.ui.screens

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidcontacts.R
import dev.liquidcontacts.core.ServerUrl
import dev.liquidcontacts.core.dav.DavException
import dev.liquidcontacts.core.dav.RemoteBook
import dev.liquidcontacts.core.tls.ServerTrust
import dev.liquidcontacts.data.BookState
import dev.liquidcontacts.sync.SyncEngine
import dev.liquidcontacts.sync.SyncScheduler
import dev.liquidcontacts.sync.TestResult
import dev.liquidcontacts.ui.components.DangerButton
import dev.liquidcontacts.ui.components.Dropdown
import dev.liquidcontacts.ui.components.SwitchRow
import dev.liquidcontacts.ui.components.dangerText
import dev.liquidcontacts.ui.glass.DialogSpec
import dev.liquidcontacts.ui.glass.GlassButton
import dev.liquidcontacts.ui.glass.GlassCard
import dev.liquidcontacts.ui.glass.GlassTextField
import dev.liquidcontacts.ui.glass.LocalDialog
import dev.liquidcontacts.ui.glass.LocalGlass
import dev.liquidcontacts.ui.glass.SectionLabel
import dev.liquidcontacts.ui.glass.SettingRow
import dev.liquidcontacts.util.plural
import dev.liquidcontacts.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class TrustMode { SYSTEM, PIN, CA }

/** Registrar el servidor CardDAV (opcional): sin esto la app no hace ninguna conexión. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerScreen(padding: PaddingValues, onBack: () -> Unit, onFirstSync: (RemoteBook) -> Unit, onConflicts: (String) -> Unit) {
    val p = LocalGlass.current
    val context = LocalContext.current
    val dialog = LocalDialog.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val s by SyncEngine.store.state.collectAsStateWithLifecycle()

    var url by rememberSaveable { mutableStateOf(s.url) }
    var nickname by rememberSaveable { mutableStateOf(s.nickname) }
    var user by rememberSaveable { mutableStateOf(s.username) }
    var password by remember { mutableStateOf("") }
    var allowHttp by rememberSaveable { mutableStateOf(s.allowHttp) }
    var mode by rememberSaveable { mutableStateOf(if (s.pinSha256 != null) TrustMode.PIN else if (s.caPem != null) TrustMode.CA else TrustMode.SYSTEM) }
    var pin by rememberSaveable { mutableStateOf(s.pinSha256.orEmpty()) }
    var caPem by remember { mutableStateOf(s.caPem) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var seenFingerprint by remember { mutableStateOf<String?>(null) }
    var books by remember { mutableStateOf<List<RemoteBook>?>(null) }

    val pickCa = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(256 * 1024).toByteArray().toString(Charsets.UTF_8) } }.getOrNull() }
                if (text != null && ServerTrust.isUsableCa(text)) caPem = text else toast(str(R.string.server_ca_invalid))
            }
        }
    }
    val isHttp = url.trim().startsWith("http://", ignoreCase = true)
    val validPin = if (mode == TrustMode.PIN) ServerTrust.normalizePin(pin) else null
    val trustReady = mode == TrustMode.SYSTEM || (mode == TrustMode.PIN && validPin != null) || (mode == TrustMode.CA && caPem != null)
    val effectiveCa = caPem.takeIf { mode == TrustMode.CA }

    val result = remember { BringIntoViewRequester() }
    LaunchedEffect(message) { if (message != null) result.bringIntoView() }

    fun connect(saveAfter: Boolean) {
        busy = true
        message = null
        seenFingerprint = null
        scope.launch {
            val pass = password.takeIf { it.isNotEmpty() }
            when (val r = SyncEngine.test(url, user.trim(), pass, allowHttp, validPin, effectiveCa)) {
                is TestResult.Ok -> {
                    books = r.discovery.books
                    SyncEngine.rememberColors(r.discovery.books)
                    message = plural(R.plurals.server_ok, r.discovery.books.size)
                    if (saveAfter && SyncEngine.changesServer(url, allowHttp)) {
                        message = str(R.string.server_err_change)
                    } else if (saveAfter) {
                        SyncEngine.saveServer(url, user.trim(), pass, allowHttp, validPin, effectiveCa)
                        SyncEngine.setNickname(nickname)
                        password = ""
                    }
                }
                is TestResult.Invalid -> message = str(
                    when (r.reason) {
                        ServerUrl.Reason.HTTP_NOT_ALLOWED -> R.string.server_err_http
                        ServerUrl.Reason.HTTP_NOT_LOCAL -> R.string.server_err_http_public
                        else -> R.string.server_err_url
                    },
                )
                is TestResult.Blocked -> message = str(R.string.server_err_blocked, (r.wait.toMinutes() + 1).toInt())
                is TestResult.Untrusted -> {
                    message = str(R.string.server_err_cert)
                    seenFingerprint = r.fingerprint
                }
                is TestResult.Failed -> message = errorText(r.error)
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        BackTitle(str(R.string.server_title), onBack)
        if (s.needsPassword) {
            GlassCard(Modifier.padding(vertical = 6.dp)) {
                Text(str(R.string.server_needs_password), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.dangerText)
            }
        }
        Text(str(R.string.server_hint), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 13.sp, color = p.contentSecondary)
        GlassCard(Modifier.padding(top = 8.dp)) {
            // Con un servidor ya guardado se aplica al escribir: cambiar el apodo no necesita conexión.
            GlassTextField(nickname, { nickname = it.take(60); if (s.configured) SyncEngine.setNickname(nickname) }, str(R.string.server_nickname), Modifier.padding(vertical = 4.dp))
            GlassTextField(url, { url = it; books = null }, str(R.string.server_url), Modifier.padding(vertical = 4.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            GlassTextField(user, { user = it }, str(R.string.server_user), Modifier.padding(vertical = 4.dp))
            GlassTextField(
                password,
                { password = it },
                str(if (s.configured && SyncEngine.hasPassword()) R.string.server_password_saved else R.string.server_password),
                Modifier.padding(vertical = 4.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
            )
            SwitchRow(str(R.string.server_allow_http), allowHttp, { allowHttp = it }, subtitle = str(R.string.server_allow_http_hint))
            if (allowHttp || isHttp) {
                Text(str(R.string.server_http_warning), Modifier.padding(vertical = 6.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = p.dangerText)
            }
        }

        SectionLabel(str(R.string.server_cert))
        GlassCard {
            Dropdown(str(R.string.server_cert_mode), TrustMode.entries, mode, {
                str(
                    when (it) {
                        TrustMode.SYSTEM -> R.string.server_cert_system
                        TrustMode.PIN -> R.string.server_cert_pin
                        TrustMode.CA -> R.string.server_cert_ca
                    },
                )
            }) { mode = it }
            when (mode) {
                TrustMode.SYSTEM -> Text(str(R.string.server_cert_system_hint), fontSize = 13.sp, color = p.contentSecondary)
                TrustMode.PIN -> {
                    GlassTextField(pin, { pin = it }, str(R.string.server_pin_field), Modifier.padding(vertical = 4.dp), singleLine = false)
                    Text(str(R.string.server_cert_pin_hint), fontSize = 13.sp, color = p.contentSecondary)
                }
                TrustMode.CA -> {
                    Text(str(if (caPem != null) R.string.server_ca_loaded else R.string.server_cert_ca_hint), fontSize = 13.sp, color = p.contentSecondary)
                    Spacer(Modifier.height(8.dp))
                    GlassButton(str(R.string.server_ca_import), { pickCa.launch(arrayOf("application/x-x509-ca-cert", "application/x-pem-file", "application/pkix-cert", "text/plain", "application/octet-stream")) }, Modifier.fillMaxWidth())
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        GlassButton(str(R.string.server_test), { connect(saveAfter = false) }, Modifier.fillMaxWidth(), enabled = !busy && trustReady && url.isNotBlank() && user.isNotBlank())
        Spacer(Modifier.height(8.dp))
        GlassButton(str(R.string.server_save), { connect(saveAfter = true) }, Modifier.fillMaxWidth(), prominent = true, enabled = !busy && trustReady && url.isNotBlank() && user.isNotBlank())
        if (busy) Text(str(R.string.server_testing), Modifier.padding(8.dp), fontSize = 14.sp)
        // Debajo de los botones puede quedar fuera de la pantalla: se desplaza hasta el resultado.
        message?.let { Text(it, Modifier.bringIntoViewRequester(result).padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        seenFingerprint?.let { fp ->
            GlassCard(Modifier.padding(vertical = 6.dp)) {
                Text(str(R.string.server_seen_cert), fontSize = 14.sp, color = p.contentSecondary)
                Text(fp, Modifier.padding(vertical = 8.dp), fontSize = 13.sp)
                GlassButton(str(R.string.server_use_pin), {
                    pin = fp
                    mode = TrustMode.PIN
                    seenFingerprint = null
                }, Modifier.fillMaxWidth())
            }
        }

        if (s.configured) {
            SectionLabel(str(R.string.server_books))
            GlassCard {
                val started = s.books.associateBy { it.url }
                s.books.forEach { b -> BookRow(b, onConflicts) }
                books.orEmpty().filter { it.url !in started }.forEach { b ->
                    SettingRow(
                        b.name ?: b.url.trimEnd('/').substringAfterLast('/'),
                        subtitle = str(if (b.readOnly) R.string.server_book_new_readonly else R.string.server_book_new),
                        onClick = { onFirstSync(b) },
                        leading = { BookDot(b.color) },
                    )
                }
                if (s.books.isEmpty() && books == null) {
                    Text(str(R.string.server_find_books_hint), Modifier.padding(vertical = 6.dp), fontSize = 13.sp, color = p.contentSecondary)
                }
                Spacer(Modifier.height(8.dp))
                GlassButton(str(R.string.server_find_books), { connect(saveAfter = false) }, Modifier.fillMaxWidth(), enabled = !busy)
            }

            if (s.books.isNotEmpty()) {
                SectionLabel(str(R.string.server_interval))
                GlassCard {
                    Dropdown(str(R.string.server_interval_every), SyncScheduler.INTERVALS, s.syncIntervalMinutes.takeIf { it in SyncScheduler.INTERVALS }, { m ->
                        when {
                            m == 0 -> str(R.string.server_manual)
                            m < 60 -> str(R.string.server_every_min, m)
                            else -> str(R.string.server_every_h, m / 60)
                        }
                    }) { m ->
                        SyncEngine.store.update { it.copy(syncIntervalMinutes = m) }
                        SyncScheduler.refresh()
                    }
                    Spacer(Modifier.height(8.dp))
                    GlassButton(str(R.string.server_sync_now), {
                        busy = true
                        scope.launch {
                            val outcome = SyncEngine.syncAll()
                            busy = false
                            toast(
                                str(
                                    when (outcome) {
                                        SyncEngine.Outcome.OK -> R.string.server_sync_ok
                                        SyncEngine.Outcome.NEEDS_PASSWORD -> R.string.server_needs_password
                                        SyncEngine.Outcome.WAIT -> R.string.server_sync_wait
                                        else -> R.string.server_sync_failed
                                    },
                                ),
                            )
                        }
                    }, Modifier.fillMaxWidth(), prominent = true, enabled = !busy)
                    s.lastError?.let { Text(str(R.string.server_last_error, it), Modifier.padding(top = 8.dp), fontSize = 13.sp, color = p.dangerText) }
                }
            }

            Spacer(Modifier.height(16.dp))
            DangerButton(str(R.string.server_remove), {
                dialog(
                    DialogSpec(str(R.string.server_remove_title)) { dismiss ->
                        Text(str(R.string.server_remove_text), fontSize = 15.sp, color = p.contentSecondary)
                        Spacer(Modifier.height(16.dp))
                        DangerButton(str(R.string.server_remove), {
                            dismiss()
                            scope.launch {
                                SyncEngine.removeServer()
                                url = ""
                                nickname = ""
                                user = ""
                                pin = ""
                                caPem = null
                                mode = TrustMode.SYSTEM
                                books = null
                                message = null
                            }
                        }, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
                    },
                )
            }, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BookRow(b: BookState, onConflicts: (String) -> Unit) {
    val p = LocalGlass.current
    val dialog = LocalDialog.current
    val scope = rememberCoroutineScope()
    val last = if (b.lastSync > 0) DateUtils.getRelativeTimeSpanString(b.lastSync).toString() else "—"
    SettingRow(b.name, subtitle = str(R.string.server_book_status, plural(R.plurals.contacts_count, b.lastCount), last), leading = { BookDot(b.color) })
    if (b.failedUploads > 0) Text(plural(R.plurals.server_failed_uploads, b.failedUploads), fontSize = 13.sp, color = p.dangerText)
    b.hold?.let { h ->
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(
                if (h.onServerSide) str(R.string.hold_server, h.before, h.now, h.deletes) else str(R.string.hold_local, h.deletes, h.before),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = p.dangerText,
            )
            Spacer(Modifier.height(8.dp))
            DangerButton(str(if (h.onServerSide) R.string.hold_confirm_server else R.string.hold_confirm_local, h.deletes), {
                dialog(
                    DialogSpec(str(R.string.hold_confirm_title)) { dismiss ->
                        Text(str(if (h.onServerSide) R.string.hold_confirm_server_text else R.string.hold_confirm_local_text, h.deletes), fontSize = 15.sp, color = p.contentSecondary)
                        Spacer(Modifier.height(16.dp))
                        DangerButton(str(R.string.hold_confirm_yes), {
                            dismiss()
                            scope.launch { SyncEngine.confirmHold(b.url) }
                        }, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
                    },
                )
            }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            GlassButton(str(if (h.onServerSide) R.string.hold_cancel_server else R.string.hold_cancel_local), { scope.launch { SyncEngine.cancelHold(b.url) } }, Modifier.fillMaxWidth())
        }
    }
    if (b.conflicts.isNotEmpty()) {
        SettingRow(plural(R.plurals.server_conflicts, b.conflicts.size), subtitle = str(R.string.server_conflicts_hint), onClick = { onConflicts(b.url) })
    }
    GlassButton(str(R.string.server_book_remove), {
        dialog(
            DialogSpec(str(R.string.server_book_remove_title, b.name)) { dismiss ->
                Text(str(R.string.server_book_remove_text), fontSize = 15.sp, color = p.contentSecondary)
                Spacer(Modifier.height(16.dp))
                DangerButton(str(R.string.server_book_remove), {
                    dismiss()
                    scope.launch { SyncEngine.removeBook(b.url) }
                }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                GlassButton(str(R.string.cancel), dismiss, Modifier.fillMaxWidth())
            },
        )
    }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
}

/** El círculo con el color de la libreta en el servidor, como los calendarios en Liquid Calendar. */
@Composable
private fun BookDot(color: Int?) {
    if (color == null) return
    Box(Modifier.size(14.dp).clip(CircleShape).background(Color(color)))
    Spacer(Modifier.width(10.dp))
}

fun errorText(e: DavException): String = str(
    when (e) {
        is DavException.Unauthorized -> R.string.server_err_auth
        is DavException.Network -> R.string.server_err_network
        is DavException.Tls -> R.string.server_err_cert
        is DavException.RedirectRefused -> R.string.server_err_redirect
        else -> R.string.server_err_other
    },
)
