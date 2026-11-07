// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import dev.liquidcontacts.R
import dev.liquidcontacts.contacts.ContactAccount
import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.vcard.VCardFormat
import dev.liquidcontacts.core.vcard.VCards
import dev.liquidcontacts.sync.SyncEngine
import dev.liquidcontacts.sync.SyncScheduler
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/** Una ficha guardada: sus datos en vCard, su cuenta y, si era del servidor, la vCard que tenía allá. */
@Serializable
data class TrashFicha(
    val rawId: Long,
    val accountType: String? = null,
    val accountName: String? = null,
    val vcard: String,
    val starred: Boolean = false,
    val reference: String? = null,
    val hasPhoto: Boolean = false,
)

@Serializable
data class TrashEntry(val id: String, val name: String, val deletedAt: Long, val fichas: List<TrashFicha>)

@Serializable
data class TrashState(val entries: List<TrashEntry> = emptyList())

/**
 * Papelera de contactos. Android no tiene una, así que antes de borrar se guarda una copia en los
 * archivos privados de la app (allowBackup=false) y a los [DAYS] días se elimina sola. Solo guarda
 * lo que se borra desde esta app: lo que se borre en otra app o en el servidor no pasa por acá.
 */
@SuppressLint("StaticFieldLeak") // Solo el contexto de la aplicación.
object Trash {
    const val DAYS = 30
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private lateinit var app: Context
    private lateinit var dir: File
    lateinit var store: JsonStore<TrashState>
        private set

    fun init(context: Context) {
        app = context.applicationContext
        dir = File(app.filesDir, "trash").apply { mkdirs() }
        store = JsonStore(File(app.filesDir, "trash.json"), TrashState.serializer(), TrashState())
    }

    private fun photoFile(entry: String, index: Int) = File(dir, "$entry-$index")

    /** La foto a mostrar en la lista: la de la primera ficha que tenga una. */
    fun photoUri(entry: TrashEntry): String? {
        val index = entry.fichas.indexOfFirst { it.hasPhoto }
        return if (index < 0) null else Uri.fromFile(photoFile(entry.id, index)).toString()
    }

    /** Guarda la copia y recién después borra: si la copia falla, el contacto queda. Devuelve las entradas. */
    fun moveToTrash(contactIds: Collection<Long>, now: Long = System.currentTimeMillis()): List<String> {
        val ours = app.getString(R.string.account_type)
        val entries = contactIds.mapNotNull { contactId ->
            val snapshot = Repo.contacts.snapshot(contactId)
            if (snapshot.isEmpty()) return@mapNotNull null
            val id = UUID.randomUUID().toString()
            val fichas = snapshot.mapIndexed { i, s ->
                s.contact.photo?.let { photoFile(id, i).writeBytes(it) }
                TrashFicha(
                    rawId = s.rawId,
                    accountType = s.account.type,
                    accountName = s.account.name,
                    vcard = VCards.write(listOf(s.contact), VCardFormat.V3, includePhoto = false),
                    starred = s.contact.starred,
                    reference = s.reference?.takeIf { s.account.type == ours },
                    hasPhoto = s.contact.photo != null,
                )
            }
            contactId to TrashEntry(id, snapshot.first().contact.displayName, now, fichas)
        }
        store.update { it.copy(entries = entries.map { e -> e.second } + it.entries) }
        // Antes de borrar: después la ficha puede no existir más y no habría cómo saber qué borrar allá.
        SyncEngine.queueRemoteDeletes(entries.flatMap { (_, e) -> e.fichas.filter { it.accountType == ours && it.accountName != null }.map { it.rawId to it.accountName!! } })
        entries.forEach { Repo.contacts.delete(it.first) }
        // Se borra del servidor en el momento (o queda en espera, con su aviso, si no hay conexión).
        SyncScheduler.pushNow()
        return entries.map { it.second.id }
    }

    /** Vuelve a crear los contactos en su cuenta (o en este celular si esa cuenta ya no está). */
    fun restore(ids: Collection<String>): Int {
        val entries = store.value.entries.filter { it.id in ids }
        val available = Repo.writableAccounts().toSet()
        val local = Repo.contacts.localAccount()
        var restored = 0
        for (entry in entries) {
            val raws = entry.fichas.mapIndexedNotNull { i, f ->
                // "Recuperar" del freno de borrado masivo ya pudo devolver esta misma ficha.
                if (Repo.contacts.isRawLive(f.rawId)) return@mapIndexedNotNull f.rawId
                val wanted = ContactAccount(f.accountType, f.accountName)
                val account = if (wanted in available || wanted == local) wanted else local
                val photo = if (f.hasPhoto) runCatching { photoFile(entry.id, i).readBytes() }.getOrNull() else null
                val contact = (VCards.contactOf(f.vcard) ?: Contact()).copy(starred = f.starred, photo = photo)
                // Otro UID: el servidor todavía puede tener la copia vieja mientras espera su borrado.
                val reference = f.reference?.takeIf { account == wanted }?.let(VCards::withoutUid)
                Repo.contacts.restoreRaw(account, contact, reference)
            }
            if (raws.size > 1) Repo.contacts.link(raws)
            forget(entry)
            restored++
        }
        return restored
    }

    /** Elimina para siempre. */
    fun purge(ids: Collection<String>) {
        store.value.entries.filter { it.id in ids }.forEach(::forget)
    }

    fun purgeAll() = purge(store.value.entries.map { it.id })

    /** Al arrancar: quita lo vencido y las entradas cuyo contacto ya volvió por otro camino. */
    fun cleanUp(now: Long = System.currentTimeMillis()) {
        store.value.entries
            .filter { e -> now - e.deletedAt > DAYS * DAY_MS || e.fichas.all { Repo.contacts.isRawLive(it.rawId) } }
            .forEach(::forget)
    }

    /** Si alguno de estos contactos tiene una ficha en una libreta del servidor. */
    fun onServer(contactIds: Collection<Long>): Boolean {
        val ours = app.getString(R.string.account_type)
        return contactIds.any { id -> Repo.contacts.rawContactIds(id).any { (_, account) -> account.type == ours } }
    }

    /** Si la entrada venía de una libreta del servidor: allá ya se borró, y restaurarla la vuelve a subir. */
    fun fromServer(entry: TrashEntry): Boolean {
        val ours = app.getString(R.string.account_type)
        return entry.fichas.any { it.accountType == ours }
    }

    /** Si al restaurarla vuelve a una libreta del servidor (la libreta sigue sincronizándose). */
    fun returnsToServer(entry: TrashEntry): Boolean {
        val ours = app.getString(R.string.account_type)
        val available = Repo.writableAccounts().toSet()
        return entry.fichas.any { it.accountType == ours && ContactAccount(it.accountType, it.accountName) in available }
    }

    /** Días que le quedan antes de eliminarse solo (al menos 1). */
    fun daysLeft(entry: TrashEntry, now: Long = System.currentTimeMillis()): Int =
        (DAYS - ((now - entry.deletedAt) / DAY_MS).toInt()).coerceIn(1, DAYS)

    private fun forget(entry: TrashEntry) {
        store.update { it.copy(entries = it.entries.filterNot { e -> e.id == entry.id }) }
        entry.fichas.indices.forEach { photoFile(entry.id, it).delete() }
    }
}
