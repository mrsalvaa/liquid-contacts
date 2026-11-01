// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.sync

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import dev.liquidcontacts.core.sync.LocalCard

/**
 * Las fichas de UNA cuenta, vistas como las ve un sincronizador. Todas las consultas filtran por la
 * cuenta: la sincronización nunca toca contactos de otras cuentas.
 *
 * En cada ficha: SOURCE_ID es la URL en el servidor, SYNC1 el ETag, SYNC2 el UID y SYNC4 la vCard
 * del servidor sin foto (para no perder lo que la app no modela al volver a subirla).
 */
class AccountContacts(private val resolver: ContentResolver, private val account: Account) {
    private fun Uri.adapter(): Uri = buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
        .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
        .build()

    /** Filtro de esta cuenta, en todas las consultas. */
    private val selection = "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ?"
    private val selectionArgs = arrayOf(account.type, account.name)

    fun cards(): List<LocalCard> =
        resolver.query(
            RawContacts.CONTENT_URI.adapter(),
            arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC1, RawContacts.DIRTY, RawContacts.DELETED),
            selection,
            selectionArgs,
            null,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) add(LocalCard(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) != 0, c.getInt(4) != 0))
            }
        }.orEmpty()

    fun reference(rawId: Long): String? = column(rawId, RawContacts.SYNC4)
    fun uid(rawId: Long): String? = column(rawId, RawContacts.SYNC2)
    fun isDirty(rawId: Long): Boolean = column(rawId, RawContacts.DIRTY) == "1"

    private fun column(rawId: Long, name: String): String? =
        resolver.query(RawContacts.CONTENT_URI.adapter(), arrayOf(name), "${RawContacts._ID} = ? AND $selection", arrayOf(rawId.toString()) + selectionArgs, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** Registra lo que ya coincide con el servidor y deja la ficha limpia. */
    fun markSynced(rawId: Long, href: String, etag: String?, uid: String?, reference: String?) {
        update(
            rawId,
            ContentValues().apply {
                put(RawContacts.SOURCE_ID, href)
                put(RawContacts.SYNC1, etag)
                put(RawContacts.SYNC2, uid)
                put(RawContacts.SYNC4, reference?.takeIf { it.length <= MAX_REFERENCE })
                put(RawContacts.DIRTY, 0)
            },
        )
    }

    /** Para un conflicto: quedarse con lo de acá sobre esa versión del servidor (se sube de nuevo). */
    fun setBaseEtag(rawId: Long, etag: String?) = update(rawId, ContentValues().apply { put(RawContacts.SYNC1, etag) })

    /** Para un conflicto: el servidor ya no lo tiene y se queda lo de acá: se sube como nuevo. */
    fun forgetServer(rawId: Long) = update(rawId, ContentValues().apply {
        putNull(RawContacts.SOURCE_ID)
        putNull(RawContacts.SYNC1)
        put(RawContacts.DIRTY, 1)
    })

    /** Cancelar un borrado masivo: las fichas marcadas como borradas vuelven. */
    fun undelete(rawIds: Collection<Long>) {
        rawIds.forEach { update(it, ContentValues().apply { put(RawContacts.DELETED, 0) }) }
    }

    private fun update(rawId: Long, values: ContentValues) {
        resolver.update(RawContacts.CONTENT_URI.adapter(), values, "${RawContacts._ID} = ? AND $selection", arrayOf(rawId.toString()) + selectionArgs)
    }

    /** Quita la ficha del celu de verdad. Solo fichas de esta cuenta (el filtro está en la consulta). */
    fun purge(rawId: Long) {
        resolver.delete(RawContacts.CONTENT_URI.adapter(), "${RawContacts._ID} = ? AND $selection", arrayOf(rawId.toString()) + selectionArgs)
    }

    /** Que los contactos de la cuenta se vean en todas las apps aunque no tengan etiqueta. */
    fun makeVisible() {
        resolver.insert(
            ContactsContract.Settings.CONTENT_URI.adapter(),
            ContentValues().apply {
                put(ContactsContract.Settings.ACCOUNT_NAME, account.name)
                put(ContactsContract.Settings.ACCOUNT_TYPE, account.type)
                put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
                put(ContactsContract.Settings.SHOULD_SYNC, 1)
            },
        )
    }

    private companion object {
        /** Una vCard sin foto no llega a esto; si llegara, no se guarda como referencia. */
        const val MAX_REFERENCE = 256 * 1024
    }
}
