// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import dev.liquidcontacts.R
import dev.liquidcontacts.contacts.ContactAccount
import dev.liquidcontacts.contacts.EditableContact
import dev.liquidcontacts.core.ServerUrl
import dev.liquidcontacts.core.dav.BookDiscovery
import dev.liquidcontacts.core.dav.CardDav
import dev.liquidcontacts.core.dav.Credentials
import dev.liquidcontacts.core.dav.DavException
import dev.liquidcontacts.core.dav.RemoteCard
import dev.liquidcontacts.core.dav.cardDavClient
import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.model.Email
import dev.liquidcontacts.core.model.Name
import dev.liquidcontacts.core.model.Phone
import dev.liquidcontacts.core.sync.ConflictKind
import dev.liquidcontacts.core.sync.ContactSyncPlan
import dev.liquidcontacts.core.sync.LoginGuard
import dev.liquidcontacts.core.sync.MassDeleteHold
import dev.liquidcontacts.core.sync.RetryDecision
import dev.liquidcontacts.core.sync.RetryPolicy
import dev.liquidcontacts.core.sync.SyncOp
import dev.liquidcontacts.core.tls.ServerTrust
import dev.liquidcontacts.core.tls.TrustConfig
import dev.liquidcontacts.core.vcard.ImportMatcher
import dev.liquidcontacts.core.vcard.VCards
import dev.liquidcontacts.data.BookState
import dev.liquidcontacts.data.CredentialStore
import dev.liquidcontacts.data.JsonStore
import dev.liquidcontacts.data.Repo
import dev.liquidcontacts.data.ServerState
import dev.liquidcontacts.data.SyncConflict
import dev.liquidcontacts.data.SyncHold
import dev.liquidcontacts.data.VcfFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID

sealed interface TestResult {
    data class Ok(val discovery: BookDiscovery) : TestResult
    data class Invalid(val reason: ServerUrl.Reason) : TestResult

    /** Demasiadas contraseñas incorrectas seguidas: esperar antes de volver a probar. */
    data class Blocked(val wait: Duration) : TestResult

    /** El certificado no es de confianza; [fingerprint] es para compararla, nunca se acepta sola. */
    data class Untrusted(val fingerprint: String?) : TestResult
    data class Failed(val error: DavException) : TestResult
}

/** Lo que se muestra antes de la primera sincronización de una libreta. */
class FirstSyncSummary(
    val cards: List<RemoteCard>,
    /** Contactos de la libreta que ya están en otras cuentas del celu. */
    val alreadyHere: Int,
    /** Si hay una cuenta de DAVx5 en el celu. */
    val davx5: Boolean,
    /** Contactos que ya tenés en el celu, para ofrecer el respaldo. */
    val existingContacts: Int,
) {
    val total: Int get() = cards.size

    /** La mitad o más ya está: probablemente sea la misma libreta. */
    val looksDuplicated: Boolean get() = total > 0 && alreadyHere * 2 >= total
}

/**
 * Sincronización con el servidor CardDAV, una libreta por cuenta de Android, con las reglas de
 * Liquid Calendar: una sola a la vez, un 401 deja la cuenta en "requiere contraseña" y no se
 * reintenta, un Retry-After se respeta también a mano. Nada de esto corre sin servidor registrado.
 */
object SyncEngine {
    private lateinit var app: Context
    lateinit var store: JsonStore<ServerState>
        private set
    private lateinit var passwords: CredentialStore
    private val mutex = Mutex()
    private val guard = LoginGuard()
    private val retry = RetryPolicy()

    private const val PASSWORD_ID = 1L

    fun init(context: Context) {
        app = context.applicationContext
        store = JsonStore(File(app.filesDir, "server.json"), ServerState.serializer(), ServerState())
        passwords = CredentialStore(app)
        guard.restore(store.value.loginFailures.map(Instant::ofEpochMilli))
        reconcileAccounts()
    }

    private val accountType: String get() = app.getString(R.string.account_type)
    private fun account(b: BookState) = Account(b.accountName, accountType)
    private fun cardsOf(b: BookState) = AccountContacts(app.contentResolver, account(b))

    /** Si una cuenta se quitó desde los ajustes del sistema, la libreta deja de sincronizarse. */
    private fun reconcileAccounts() {
        val existing = AccountManager.get(app).getAccountsByType(accountType).map { it.name }.toSet()
        store.update { s -> s.copy(books = s.books.filter { !it.firstSyncDone || it.accountName in existing }) }
    }

    fun hasPassword(): Boolean = passwords.has(PASSWORD_ID)

    /** Si alguna libreta tiene cambios de acá sin subir. Sin servidor, nunca. */
    fun hasLocalChanges(): Boolean = store.value.books.any { b ->
        val conflicted = b.conflicts.map { it.rawId }
        b.firstSyncDone && b.hold == null && cardsOf(b).cards().any { (it.dirty || it.deleted) && it.rawId !in conflicted }
    }

    private fun recordLoginFailure() {
        guard.recordFailure()
        store.update { it.copy(loginFailures = guard.snapshot().map(Instant::toEpochMilli)) }
    }

    private fun client(s: ServerState, password: String, trust: ServerTrust): CardDav {
        val server = (ServerUrl.parse(s.url, s.allowHttp) as? ServerUrl.Result.Ok)?.url ?: throw DavException.BadResponse("URL inválida")
        return CardDav(cardDavClient(server, Credentials(s.username, password), trust))
    }

    private fun trustOf(s: ServerState) = ServerTrust(TrustConfig(s.pinSha256, s.caPem))

    /** Valida la URL y prueba usuario y contraseña con una búsqueda completa de solo lectura. */
    suspend fun test(url: String, username: String, password: String?, allowHttp: Boolean, pin: String?, caPem: String?): TestResult = withContext(Dispatchers.IO) {
        val parsed = ServerUrl.parse(url, allowHttp)
        if (parsed is ServerUrl.Result.Invalid) return@withContext TestResult.Invalid(parsed.reason)
        guard.waitBeforeNextAttempt()?.let { return@withContext TestResult.Blocked(it) }
        val pass = password ?: passwords.load(PASSWORD_ID) ?: return@withContext TestResult.Failed(DavException.Unauthorized())
        val trust = ServerTrust(TrustConfig(pin, caPem))
        val server = (parsed as ServerUrl.Result.Ok).url
        try {
            val found = CardDav(cardDavClient(server, Credentials(username, pass), trust)).discover()
            guard.reset()
            store.update { it.copy(loginFailures = emptyList()) }
            TestResult.Ok(found)
        } catch (e: DavException.Unauthorized) {
            recordLoginFailure()
            TestResult.Failed(e)
        } catch (e: DavException.Tls) {
            TestResult.Untrusted(trust.lastChain.firstOrNull()?.let(ServerTrust::fingerprint))
        } catch (e: DavException) {
            TestResult.Failed(e)
        }
    }

    /** Si guardar esta dirección dejaría libretas apuntando a otro servidor. */
    fun changesServer(url: String, allowHttp: Boolean): Boolean {
        val s = store.value
        if (s.books.isEmpty()) return false
        val new = (ServerUrl.parse(url, allowHttp) as? ServerUrl.Result.Ok)?.url ?: return false
        val old = (ServerUrl.parse(s.url, s.allowHttp) as? ServerUrl.Result.Ok)?.url ?: return false
        return new.uri.scheme != old.uri.scheme || new.host != old.host || new.port != old.port
    }

    /** Guarda el servidor (y la contraseña cifrada, si se escribió una). No conecta. */
    fun saveServer(url: String, username: String, password: String?, allowHttp: Boolean, pin: String?, caPem: String?) {
        val normalized = (ServerUrl.parse(url, allowHttp) as ServerUrl.Result.Ok).url.toString()
        if (!password.isNullOrEmpty()) passwords.save(PASSWORD_ID, password)
        store.update { it.copy(url = normalized, username = username, allowHttp = allowHttp, pinSha256 = pin, caPem = caPem, needsPassword = false, waitUntil = 0, retryAttempt = 0, lastError = null) }
        SyncScheduler.refresh()
    }

    fun setNickname(nickname: String) {
        store.update { it.copy(nickname = nickname.trim()) }
    }

    // ---------------------------------------------------------------- primera sincronización

    /** Solo mira: baja la libreta a memoria y cuenta qué ya está en otras cuentas. No guarda nada. */
    suspend fun prepareFirstSync(bookUrl: String): FirstSyncSummary = withContext(Dispatchers.IO) {
        val s = store.value
        val pass = passwords.load(PASSWORD_ID) ?: throw DavException.Unauthorized()
        val dav = client(s, pass, trustOf(s))
        val etags = dav.listEtags(bookUrl)
        val cards = dav.multiget(bookUrl, etags.keys)
        Repo.reload()
        val existing = Repo.book.value.rows.map { r ->
            Contact(id = r.summary.id, name = Name(display = r.summary.displayName), phones = r.summary.phones.map { Phone(it) }, emails = r.summary.emails.map { Email(it) })
        }
        val matcher = ImportMatcher(Repo.numbers.value, existing)
        val already = cards.count { c -> c.vcard?.let(VCards::contactOf)?.let(matcher::match) != null }
        val davx5 = Repo.contacts.accounts().any { it.type?.startsWith("bitfire.at.davdroid") == true } ||
            AccountManager.get(app).accounts.any { it.type.startsWith("bitfire.at.davdroid") }
        FirstSyncSummary(cards, already, davx5, existing.size)
    }

    /**
     * Crea la cuenta de la libreta y baja sus contactos. Durante la primera sincronización solo se
     * baja: no se sube ni se borra nada, y los contactos de otras cuentas no se tocan.
     */
    suspend fun runFirstSync(book: dev.liquidcontacts.core.dav.RemoteBook, summary: FirstSyncSummary, onProgress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val s = store.value
            val name = accountNameFor(book, s)
            val account = Account(name, accountType)
            AccountManager.get(app).addAccountExplicitly(account, null, null)
            val cards = AccountContacts(app.contentResolver, account)
            cards.makeVisible()
            var saved = 0
            summary.cards.forEach { remote ->
                if (runCatching { applyRemote(cards, name, remote, null) }.isSuccess) saved++
                onProgress(saved)
            }
            val ctag = runCatching { client(s, passwords.load(PASSWORD_ID)!!, trustOf(s)).ctag(book.url) }.getOrNull()
            store.update { st ->
                st.copy(books = st.books.filterNot { it.url == book.url } + BookState(book.url, book.name ?: name, name, book.readOnly, ctag, summary.total, true, lastSync = System.currentTimeMillis(), color = book.color))
            }
            SyncScheduler.refresh()
            saved
        }
    }

    private fun accountNameFor(book: dev.liquidcontacts.core.dav.RemoteBook, s: ServerState): String {
        val host = (ServerUrl.parse(s.url, s.allowHttp) as? ServerUrl.Result.Ok)?.url?.host.orEmpty()
        val base = "${book.name ?: book.url.trimEnd('/').substringAfterLast('/')} (${s.username}@$host)"
        val taken = s.books.map { it.accountName }.toSet()
        return if (base !in taken) base else "$base ${book.url.hashCode().toUInt().toString(16)}"
    }

    /** Los colores de las libretas ya elegidas, tomados de una búsqueda en el servidor. */
    fun rememberColors(found: List<dev.liquidcontacts.core.dav.RemoteBook>) {
        val byUrl = found.associateBy { it.url }
        if (store.value.books.none { b -> byUrl[b.url]?.let { it.color != b.color } == true }) return
        store.update { s -> s.copy(books = s.books.map { b -> byUrl[b.url]?.let { b.copy(color = it.color) } ?: b }) }
    }

    /** Volvió la red: un error de red anterior ya no tiene por qué hacer esperar. */
    fun forgetNetworkWait() {
        if (store.value.lastError == "Network" && store.value.waitUntil > 0) store.update { it.copy(waitUntil = 0) }
    }

    /** El último intento falló por la red (sin conexión, DNS, tiempo de espera). */
    val lastFailedByNetwork: Boolean get() = store.value.lastError == "Network"

    // ---------------------------------------------------------------- sincronización

    enum class Outcome { OK, NO_SERVER, NEEDS_PASSWORD, WAIT, FAILED }

    /** Sincroniza las libretas ya iniciadas. Sin servidor o sin libretas, no conecta a nada. */
    suspend fun syncAll(): Outcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            reconcileAccounts()
            val s = store.value
            val books = s.books.filter { it.firstSyncDone }
            if (!s.configured || books.isEmpty()) return@withLock Outcome.NO_SERVER
            if (s.needsPassword) return@withLock Outcome.NEEDS_PASSWORD
            if (System.currentTimeMillis() < s.waitUntil) return@withLock Outcome.WAIT
            val pass = passwords.load(PASSWORD_ID)
            if (pass == null) {
                store.update { it.copy(needsPassword = true) }
                return@withLock Outcome.NEEDS_PASSWORD
            }
            try {
                val dav = client(s, pass, trustOf(s))
                books.forEach { syncBook(dav, it.url) }
                store.update { it.copy(retryAttempt = 0, waitUntil = 0, lastError = null) }
                Outcome.OK
            } catch (e: DavException) {
                when (val d = retry.decide(e, s.retryAttempt)) {
                    RetryDecision.NeedsPassword -> {
                        recordLoginFailure()
                        store.update { it.copy(needsPassword = true, lastError = e.code) }
                        Outcome.NEEDS_PASSWORD
                    }
                    is RetryDecision.RetryIn -> {
                        store.update { it.copy(waitUntil = System.currentTimeMillis() + d.delay.toMillis(), retryAttempt = it.retryAttempt + 1, lastError = e.code) }
                        Outcome.WAIT
                    }
                    is RetryDecision.GiveUp -> {
                        store.update { it.copy(lastError = e.code) }
                        Outcome.FAILED
                    }
                }
            }
        }
    }

    private fun updateBook(url: String, f: (BookState) -> BookState) {
        store.update { s -> s.copy(books = s.books.map { if (it.url == url) f(it) else it }) }
    }

    private fun book(url: String) = store.value.books.first { it.url == url }

    private fun syncBook(dav: CardDav, url: String) {
        val b = book(url)
        // Frenada: no se hace nada hasta que el usuario decida.
        if (b.hold != null && !b.massDeleteConfirmed) return
        val cards = cardsOf(b)
        val local = cards.cards()
        val changedHere = local.any { (it.dirty || it.deleted) && it.rawId !in b.conflicts.map { c -> c.rawId } }
        val props = dav.props(url)
        val ctag = props.ctag
        // El color se puede cambiar en el servidor en cualquier momento: se toma en cada pasada.
        if (props.color != b.color) updateBook(url) { it.copy(color = props.color) }
        if (ctag != null && ctag == b.ctag && !changedHere && !b.massDeleteConfirmed) return

        val remote = dav.listEtags(url)
        // Conflictos cuya ficha ya no existe se dan por resueltos.
        val conflicts = b.conflicts.filter { c -> local.any { it.rawId == c.rawId } }
        val ops = ContactSyncPlan.plan(remote, local, skip = conflicts.map { it.rawId }.toSet())
        val here = local.count { it.href != null && !it.deleted }
        if (!b.massDeleteConfirmed) {
            val hold = ContactSyncPlan.massDelete(ops, b.lastCount, remote.size, here)
            if (hold != null) {
                updateBook(url) {
                    it.copy(
                        hold = when (hold) {
                            is MassDeleteHold.Server -> SyncHold(true, hold.before, hold.now, hold.deletes)
                            is MassDeleteHold.Local -> SyncHold(false, hold.before, hold.before - hold.deletes, hold.deletes)
                        },
                        conflicts = conflicts,
                    )
                }
                return
            }
        }

        var failed = 0
        val newConflicts = conflicts.toMutableList()
        fun addConflict(rawId: Long, href: String, kind: ConflictKind) {
            val server = dav.get(href)
            val mine = runCatching { Repo.contacts.readRawContact(rawId).displayName }.getOrDefault("")
            val title = mine.ifBlank { server?.vcard?.let(VCards::contactOf)?.displayName.orEmpty() }
            newConflicts += SyncConflict(rawId, href, kind, server?.vcard, server?.etag, title)
        }

        // Primero se sube lo de acá. Un 401, 429/503, 5xx o error de red corta con las reglas de siempre.
        for (op in ops) {
            try {
                when (op) {
                    is SyncOp.Create -> {
                        val uid = cards.uid(op.rawId) ?: UUID.randomUUID().toString()
                        val text = vcardFor(cards, op.rawId, uid)
                        val href = dav.newCardUrl(url, uid)
                        val etag = dav.create(href, text)
                        cards.markSynced(op.rawId, href, etag, uid, VCards.withoutEmbeddedPhoto(text))
                    }
                    is SyncOp.Update -> {
                        val uid = cards.uid(op.rawId) ?: UUID.randomUUID().toString()
                        val text = vcardFor(cards, op.rawId, uid)
                        val etag = dav.update(op.href, op.etag, text)
                        cards.markSynced(op.rawId, op.href, etag, uid, VCards.withoutEmbeddedPhoto(text))
                    }
                    is SyncOp.DeleteRemote -> {
                        dav.delete(op.href, op.etag)
                        cards.purge(op.rawId)
                    }
                    is SyncOp.Conflict -> addConflict(op.rawId, op.href, op.kind)
                    is SyncOp.Forget -> cards.purge(op.rawId)
                    is SyncOp.Download, is SyncOp.DeleteLocal -> Unit
                }
            } catch (e: DavException.PreconditionFailed) {
                // Cambió en el servidor mientras se subía: no se pisa, es un conflicto.
                when (op) {
                    is SyncOp.Update -> addConflict(op.rawId, op.href, ConflictKind.BOTH_EDITED)
                    is SyncOp.DeleteRemote -> addConflict(op.rawId, op.href, ConflictKind.DELETED_HERE_EDITED_THERE)
                    else -> failed++
                }
            } catch (e: DavException.Forbidden) {
                failed++ // Libreta de solo lectura: queda pendiente, sin perder nada.
            } catch (e: DavException.UnexpectedStatus) {
                failed++
            }
        }

        // Después se baja lo del servidor y se quita lo que ya no está. Lo que cambió acá mientras
        // tanto no se pisa: se resuelve en la próxima sincronización.
        val downloads = ops.filterIsInstance<SyncOp.Download>().map { it.href }
        val byHref = local.filter { it.href != null }.associateBy { it.href!! }
        dav.multiget(url, downloads).forEach { remoteCard ->
            val existing = byHref[remoteCard.url]
            if (existing == null || !cards.isDirty(existing.rawId)) {
                runCatching { applyRemote(cards, b.accountName, remoteCard, existing?.rawId) }.onFailure { failed++ }
            }
        }
        ops.filterIsInstance<SyncOp.DeleteLocal>().forEach { op ->
            if (!cards.isDirty(op.rawId)) cards.purge(op.rawId)
        }

        val finalCtag = runCatching { dav.ctag(url) }.getOrNull()
        val count = runCatching { dav.listEtags(url).size }.getOrDefault(remote.size)
        updateBook(url) {
            it.copy(
                ctag = if (failed == 0) finalCtag else null,
                lastCount = count,
                hold = null,
                massDeleteConfirmed = false,
                conflicts = newConflicts.distinctBy { c -> c.rawId },
                failedUploads = failed,
                lastSync = System.currentTimeMillis(),
            )
        }
    }

    /** Guarda en el celu lo que vino del servidor. Una tarjeta ilegible se saltea sin borrar nada. */
    private fun applyRemote(cards: AccountContacts, accountName: String, remote: RemoteCard, rawId: Long?) {
        val text = remote.vcard ?: error("sin contenido")
        val parsed = VCards.contactOf(text) ?: error("vCard ilegible")
        val contact = parsed.copy(photo = parsed.photo?.let(VcfFiles::normalizePhoto))
        val account = ContactAccount(accountType, accountName)
        val saved = Repo.contacts.saveAsAdapter(EditableContact(rawId, account, contact))
        cards.markSynced(saved, remote.url, remote.etag, VCards.uidOf(text), VCards.withoutEmbeddedPhoto(text))
    }

    /** La vCard para subir: la del servidor con lo que cambió, sin perder lo que la app no modela. */
    private fun vcardFor(cards: AccountContacts, rawId: Long, uid: String): String =
        VCards.update(cards.reference(rawId), Repo.contacts.readRawContact(rawId), uid)

    // ---------------------------------------------------------------- decisiones del usuario

    /** Confirma el borrado masivo: vale solo para la próxima sincronización. */
    suspend fun confirmHold(url: String): Outcome {
        updateBook(url) { it.copy(massDeleteConfirmed = true) }
        return syncAll()
    }

    /**
     * Cancela. Si los borrados eran de acá, esas fichas vuelven. Si es el servidor el que quedó
     * vacío, no se borra nada y la libreta queda frenada hasta resolverlo (o quitarla).
     */
    suspend fun cancelHold(url: String): Outcome = withContext(Dispatchers.IO) {
        val b = book(url)
        if (b.hold?.onServerSide == false) {
            val cards = cardsOf(b)
            cards.undelete(cards.cards().filter { it.deleted && it.href != null }.map { it.rawId })
            updateBook(url) { it.copy(hold = null) }
            return@withContext syncAll()
        }
        Outcome.OK
    }

    /** Quedarse con la versión de acá: se sube en la próxima sincronización. */
    suspend fun keepMine(url: String, c: SyncConflict): Outcome {
        withContext(Dispatchers.IO) {
            val cards = cardsOf(book(url))
            when (c.kind) {
                ConflictKind.BOTH_EDITED, ConflictKind.DELETED_HERE_EDITED_THERE -> cards.setBaseEtag(c.rawId, c.serverEtag)
                ConflictKind.EDITED_HERE_DELETED_THERE -> cards.forgetServer(c.rawId)
            }
            removeConflict(url, c)
        }
        return syncAll()
    }

    /** Quedarse con la versión del servidor: lo de acá se descarta. */
    suspend fun keepServer(url: String, c: SyncConflict): Outcome {
        withContext(Dispatchers.IO) {
            val b = book(url)
            val cards = cardsOf(b)
            val text = c.serverVcard
            when {
                c.kind == ConflictKind.BOTH_EDITED && text != null -> applyRemote(cards, b.accountName, RemoteCard(c.href, c.serverEtag, text), c.rawId)
                // Borrada acá: se quita la ficha y vuelve a bajar. Borrada allá: se va.
                else -> cards.purge(c.rawId)
            }
            removeConflict(url, c)
            updateBook(url) { it.copy(ctag = null) }
        }
        return syncAll()
    }

    private fun removeConflict(url: String, c: SyncConflict) = updateBook(url) { b -> b.copy(conflicts = b.conflicts.filterNot { it.rawId == c.rawId }) }

    /** Deja de sincronizar la libreta y quita del celu sus contactos. En el servidor no cambia nada. */
    suspend fun removeBook(url: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val b = book(url)
            AccountManager.get(app).removeAccountExplicitly(account(b))
            store.update { s -> s.copy(books = s.books.filterNot { it.url == url }) }
            SyncScheduler.refresh()
        }
    }

    /** Quita el servidor, todas sus libretas y la contraseña del celu. En el servidor no cambia nada. */
    suspend fun removeServer() = withContext(Dispatchers.IO) {
        mutex.withLock {
            store.value.books.forEach { AccountManager.get(app).removeAccountExplicitly(account(it)) }
            passwords.delete(PASSWORD_ID)
            store.update { ServerState(syncIntervalMinutes = it.syncIntervalMinutes) }
            SyncScheduler.refresh()
        }
    }
}
