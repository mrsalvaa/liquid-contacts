// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.data

import android.annotation.SuppressLint
import android.content.Context
import dev.liquidcontacts.R
import dev.liquidcontacts.contacts.ContactAccount
import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.vcard.VCardFormat
import dev.liquidcontacts.core.vcard.VCards
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
        entries.forEach { Repo.contacts.delete(it.first) }
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

    /** Días que le quedan antes de eliminarse solo (al menos 1). */
    fun daysLeft(entry: TrashEntry, now: Long = System.currentTimeMillis()): Int =
        (DAYS - ((now - entry.deletedAt) / DAY_MS).toInt()).coerceIn(1, DAYS)

    private fun forget(entry: TrashEntry) {
        store.update { it.copy(entries = it.entries.filterNot { e -> e.id == entry.id }) }
        entry.fichas.indices.forEach { photoFile(entry.id, it).delete() }
    }
}
