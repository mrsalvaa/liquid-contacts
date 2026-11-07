// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.contacts

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.AggregationExceptions
import android.provider.ContactsContract.CommonDataKinds
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.model.ContactEvent
import dev.liquidcontacts.core.model.ContactSummary
import dev.liquidcontacts.core.model.Email
import dev.liquidcontacts.core.model.EventKind
import dev.liquidcontacts.core.model.LabelKind
import dev.liquidcontacts.core.model.Name
import dev.liquidcontacts.core.model.Phone
import dev.liquidcontacts.core.model.PostalAddress
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Acceso a la agenda del sistema (ContactsContract), sin nada de la interfaz. Lo usan Contactos y
 * se puede copiar tal cual a phone y messages (solo depende de Android y de los modelos de :core).
 * Todas las funciones hacen E/S: llamarlas fuera del hilo principal.
 */

/** Una cuenta de la agenda. [type] y [name] null = contactos locales sin cuenta (Android puro). */
data class ContactAccount(val type: String?, val name: String?) {
    /** "tipo/nombre", para guardar en ajustes; "" para la local sin cuenta. */
    val id: String get() = if (type == null) "" else "$type/$name"

    companion object {
        fun fromId(id: String): ContactAccount =
            if (id.isEmpty()) ContactAccount(null, null) else ContactAccount(id.substringBefore('/'), id.substringAfter('/'))
    }
}

/** Un contacto listo para editar: los datos de UNA de sus fichas (raw contact) y su cuenta. */
data class EditableContact(val rawContactId: Long?, val account: ContactAccount, val contact: Contact)

/** Copia en memoria de lo que una unión reemplazó y borró; no se guarda en ningún lado. */
class MergeUndo internal constructor(
    internal val keepRaw: Long,
    internal val account: ContactAccount,
    internal val first: Contact,
    internal val others: List<Contact>,
)

/** Resultado de unir. [undo] solo existe si la unión borró contactos. */
class MergeResult(val contactId: Long, val undo: MergeUndo?)

/** Copia de una ficha antes de borrarla. [reference] es la vCard del servidor, si la tiene. */
class RawSnapshot(val rawId: Long, val account: ContactAccount, val contact: Contact, val reference: String?)

/** Una etiqueta (grupo) con su cuenta y cuántos contactos tiene. */
data class ContactGroup(val id: Long, val title: String, val account: ContactAccount, val count: Int)

class SystemContacts(private val app: android.content.Context) {
    private val resolver: ContentResolver = app.contentResolver


    // ---------------------------------------------------------------- lectura

    /** Todos los contactos, con lo necesario para la lista, la búsqueda y el índice. */
    fun summaries(): List<ContactSummary> {
        val phones = HashMap<Long, MutableList<String>>()
        val emails = HashMap<Long, MutableList<String>>()
        resolver.query(
            Data.CONTENT_URI,
            arrayOf(Data.CONTACT_ID, Data.MIMETYPE, Data.DATA1),
            "${Data.MIMETYPE} IN (?, ?)",
            arrayOf(CommonDataKinds.Phone.CONTENT_ITEM_TYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val value = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                val target = if (c.getString(1) == CommonDataKinds.Phone.CONTENT_ITEM_TYPE) phones else emails
                target.getOrPut(c.getLong(0)) { ArrayList(2) }.add(value)
            }
        }
        val out = ArrayList<ContactSummary>()
        resolver.query(
            Contacts.CONTENT_URI,
            arrayOf(Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY, Contacts.STARRED, Contacts.PHOTO_URI),
            null,
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += ContactSummary(
                    id = id,
                    lookupKey = c.getString(1) ?: continue,
                    displayName = c.getString(2).orEmpty(),
                    starred = c.getInt(3) != 0,
                    phones = phones[id].orEmpty().distinct(),
                    emails = emails[id].orEmpty().distinct(),
                    photoUri = c.getString(4),
                )
            }
        }
        return out
    }

    fun exists(contactId: Long): Boolean =
        resolver.query(ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId), arrayOf(Contacts._ID), null, null, null)?.use { it.moveToFirst() } == true

    /** La URI estable de un contacto (la que se devuelve a otras apps). */
    fun lookupUri(contactId: Long): Uri? = Contacts.getLookupUri(resolver, ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId))

    /**
     * Los números (o emails, o direcciones) de un contacto, con su fila: [content] es la URI base
     * del tipo (Phone.CONTENT_URI...), y la fila de datos se devuelve a la app que pidió elegir.
     */
    fun dataRows(contactId: Long, content: Uri, column: String): List<Pair<Long, String>> =
        resolver.query(content, arrayOf(Data._ID, column), "${Data.CONTACT_ID} = ?", arrayOf(contactId.toString()), null)
            ?.use { c -> buildList { while (c.moveToNext()) c.getString(1)?.takeIf { it.isNotBlank() }?.let { add(c.getLong(0) to it) } } }.orEmpty()

    /** Id actual de un contacto por su lookup key (cambia si Android lo une o separa), o null. */
    fun resolve(lookupKey: String): Long? {
        val uri = Contacts.lookupContact(resolver, Uri.withAppendedPath(Contacts.CONTENT_LOOKUP_URI, lookupKey)) ?: return null
        return ContentUris.parseId(uri)
    }

    /** El contacto entero tal como se ve (todas sus fichas juntas, sin repetir). */
    fun load(contactId: Long): Contact? {
        val header = resolver.query(
            ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId),
            arrayOf(Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY, Contacts.STARRED),
            null,
            null,
            null,
        )?.use { c -> if (c.moveToFirst()) Triple(c.getString(0), c.getString(1).orEmpty(), c.getInt(2) != 0) else null } ?: return null
        val raw = rawContactIds(contactId)
        val parts = raw.map { readRaw(it.first) }
        val merged = parts.fold(Contact()) { acc, part -> acc.plusDistinct(part) }
        return merged.copy(
            id = contactId,
            lookupKey = header.first,
            name = if (merged.name.shown.isBlank()) Name(display = header.second) else merged.name,
            starred = header.third,
        )
    }

    /**
     * La ficha que se edita: la de la cuenta preferida si el contacto tiene una ahí; si no, la
     * primera. Un contacto que Android armó con fichas de varias cuentas se edita de a una.
     */
    fun loadForEdit(contactId: Long, preferred: ContactAccount? = null, rawChoice: Long? = null): EditableContact? {
        val raws = rawContactIds(contactId)
        if (raws.isEmpty()) return null
        val (rawId, account) = raws.firstOrNull { it.first == rawChoice } ?: raws.firstOrNull { it.second == preferred } ?: raws.first()
        val starred = load(contactId)?.starred ?: false
        val photo = readPhoto(rawId)
        return EditableContact(rawId, account, readRaw(rawId).copy(id = contactId, starred = starred, photo = photo))
    }

    /** El contacto tal como se ve, con la foto de la primera ficha que tenga (para respaldos y vistas previas). */
    fun loadFull(contactId: Long): Contact? {
        val c = load(contactId) ?: return null
        return c.copy(photo = rawContactIds(contactId).firstNotNullOfOrNull { readPhoto(it.first) })
    }

    /** Fichas (raw contacts) de un contacto con su cuenta, sin las borradas. */
    fun rawContactIds(contactId: Long): List<Pair<Long, ContactAccount>> =
        resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME),
            "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0",
            arrayOf(contactId.toString()),
            RawContacts._ID,
        )?.use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to ContactAccount(c.getString(1), c.getString(2))) }
        }.orEmpty()

    private fun readRaw(rawId: Long): Contact {
        var name = Name()
        var nickname: String? = null
        var organization: String? = null
        var jobTitle: String? = null
        var note: String? = null
        val phones = ArrayList<Phone>()
        val emails = ArrayList<Email>()
        val addresses = ArrayList<PostalAddress>()
        val events = ArrayList<ContactEvent>()
        val websites = ArrayList<String>()
        val groupIds = ArrayList<Long>()
        resolver.query(
            Data.CONTENT_URI,
            DATA_PROJECTION,
            "${Data.RAW_CONTACT_ID} = ?",
            arrayOf(rawId.toString()),
            "${Data.IS_PRIMARY} DESC, ${Data._ID}",
        )?.use { c ->
            while (c.moveToNext()) {
                when (c.str(Data.MIMETYPE)) {
                    CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> name = Name(
                        display = c.str(CommonDataKinds.StructuredName.DISPLAY_NAME).orEmpty(),
                        given = c.str(CommonDataKinds.StructuredName.GIVEN_NAME),
                        family = c.str(CommonDataKinds.StructuredName.FAMILY_NAME),
                        middle = c.str(CommonDataKinds.StructuredName.MIDDLE_NAME),
                        prefix = c.str(CommonDataKinds.StructuredName.PREFIX),
                        suffix = c.str(CommonDataKinds.StructuredName.SUFFIX),
                        phoneticGiven = c.str(CommonDataKinds.StructuredName.PHONETIC_GIVEN_NAME),
                        phoneticFamily = c.str(CommonDataKinds.StructuredName.PHONETIC_FAMILY_NAME),
                    )
                    CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> c.str(CommonDataKinds.Phone.NUMBER)?.takeIf { it.isNotBlank() }?.let {
                        phones += Phone(it, phoneKind(c.int(CommonDataKinds.Phone.TYPE)), c.str(CommonDataKinds.Phone.LABEL), c.int(Data.IS_PRIMARY) != 0)
                    }
                    CommonDataKinds.Email.CONTENT_ITEM_TYPE -> c.str(CommonDataKinds.Email.ADDRESS)?.takeIf { it.isNotBlank() }?.let {
                        emails += Email(it, emailKind(c.int(CommonDataKinds.Email.TYPE)), c.str(CommonDataKinds.Email.LABEL), c.int(Data.IS_PRIMARY) != 0)
                    }
                    CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                        val street = c.str(CommonDataKinds.StructuredPostal.STREET)
                        val city = c.str(CommonDataKinds.StructuredPostal.CITY)
                        val region = c.str(CommonDataKinds.StructuredPostal.REGION)
                        val postcode = c.str(CommonDataKinds.StructuredPostal.POSTCODE)
                        val country = c.str(CommonDataKinds.StructuredPostal.COUNTRY)
                        // Si otra app guardó solo la dirección armada, va entera en "calle".
                        val structured = listOf(street, city, region, postcode, country).any { !it.isNullOrBlank() }
                        addresses += PostalAddress(
                            street = if (structured) street else c.str(CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS),
                            city = city, region = region, postcode = postcode, country = country,
                            kind = postalKind(c.int(CommonDataKinds.StructuredPostal.TYPE)),
                            label = c.str(CommonDataKinds.StructuredPostal.LABEL),
                        ).takeIf { it.formatted.isNotBlank() } ?: continue
                    }
                    CommonDataKinds.Event.CONTENT_ITEM_TYPE -> parseEvent(
                        c.str(CommonDataKinds.Event.START_DATE),
                        eventKind(c.int(CommonDataKinds.Event.TYPE)),
                        c.str(CommonDataKinds.Event.LABEL),
                    )?.let { events += it }
                    CommonDataKinds.Nickname.CONTENT_ITEM_TYPE -> nickname = nickname ?: c.str(CommonDataKinds.Nickname.NAME)
                    CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                        organization = organization ?: c.str(CommonDataKinds.Organization.COMPANY)
                        jobTitle = jobTitle ?: c.str(CommonDataKinds.Organization.TITLE)
                    }
                    CommonDataKinds.Note.CONTENT_ITEM_TYPE -> note = c.str(CommonDataKinds.Note.NOTE)?.takeIf { it.isNotBlank() } ?: note
                    CommonDataKinds.Website.CONTENT_ITEM_TYPE -> c.str(CommonDataKinds.Website.URL)?.takeIf { it.isNotBlank() }?.let { websites += it }
                    CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE -> groupIds += c.long(CommonDataKinds.GroupMembership.GROUP_ROW_ID)
                }
            }
        }
        return Contact(
            name = name,
            nickname = nickname,
            organization = organization,
            jobTitle = jobTitle,
            phones = phones,
            emails = emails,
            addresses = addresses,
            events = events,
            websites = websites,
            note = note,
            groups = groupTitles(groupIds),
        )
    }

    /** Foto grande de una ficha (o la miniatura si no hay grande), o null. */
    fun readPhoto(rawId: Long): ByteArray? {
        val display = Uri.withAppendedPath(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), RawContacts.DisplayPhoto.CONTENT_DIRECTORY)
        runCatching { resolver.openAssetFileDescriptor(display, "r")?.use { it.createInputStream().readBytes() } }.getOrNull()?.let { return it }
        return resolver.query(
            Data.CONTENT_URI,
            arrayOf(CommonDataKinds.Photo.PHOTO),
            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
            arrayOf(rawId.toString(), CommonDataKinds.Photo.CONTENT_ITEM_TYPE),
            null,
        )?.use { c -> if (c.moveToFirst()) c.getBlob(0) else null }
    }

    private fun groupTitles(ids: List<Long>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        return resolver.query(
            Groups.CONTENT_URI,
            arrayOf(Groups.TITLE),
            "${Groups._ID} IN (${ids.joinToString(",")}) AND $EDITABLE_GROUP",
            null,
            null,
        )?.use { c -> buildSet { while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let(::add) } }.orEmpty()
    }

    // ---------------------------------------------------------------- cuentas y etiquetas

    /** Cuentas que ya tienen contactos o etiquetas en la agenda (sin pedir GET_ACCOUNTS). */
    fun accounts(extra: List<ContactAccount> = emptyList()): List<ContactAccount> {
        val found = LinkedHashSet<ContactAccount>(extra)
        for (uri in listOf(RawContacts.CONTENT_URI, Groups.CONTENT_URI)) {
            val (typeCol, nameCol) = if (uri == Groups.CONTENT_URI) Groups.ACCOUNT_TYPE to Groups.ACCOUNT_NAME else RawContacts.ACCOUNT_TYPE to RawContacts.ACCOUNT_NAME
            resolver.query(uri, arrayOf(typeCol, nameCol), null, null, null)?.use { c ->
                while (c.moveToNext()) found += ContactAccount(c.getString(0), c.getString(1))
            }
        }
        return found.toList()
    }

    /**
     * La cuenta local del celu para los contactos nuevos: la que declara Android (14+), la del
     * fabricante si ya hay contactos en ella (Samsung, Xiaomi, Huawei...) o, si no, sin cuenta.
     */
    fun localAccount(): ContactAccount {
        if (Build.VERSION.SDK_INT >= 34) {
            val type = RawContacts.getLocalAccountType(app)
            if (type != null) return ContactAccount(type, RawContacts.getLocalAccountName(app))
        }
        return accounts().firstOrNull { it.type in KNOWN_LOCAL_TYPES } ?: ContactAccount(null, null)
    }

    /**
     * Etiquetas editables (sin las del sistema ni las de solo lectura), con cuántos contactos
     * tiene cada una. Se cuentan contactos distintos: el contador del sistema incluye fichas
     * recién borradas o unidas.
     */
    fun groups(): List<ContactGroup> {
        val members = HashMap<Long, MutableSet<Long>>()
        resolver.query(
            Data.CONTENT_URI,
            arrayOf(CommonDataKinds.GroupMembership.GROUP_ROW_ID, Data.CONTACT_ID),
            "${Data.MIMETYPE} = ?",
            arrayOf(CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE),
            null,
        )?.use { c -> while (c.moveToNext()) members.getOrPut(c.getLong(0)) { HashSet() }.add(c.getLong(1)) }
        return resolver.query(
            Groups.CONTENT_URI,
            arrayOf(Groups._ID, Groups.TITLE, Groups.ACCOUNT_TYPE, Groups.ACCOUNT_NAME),
            EDITABLE_GROUP,
            null,
            Groups.TITLE,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    val id = c.getLong(0)
                    add(ContactGroup(id, title, ContactAccount(c.getString(2), c.getString(3)), members[id]?.size ?: 0))
                }
            }
        }.orEmpty()
    }

    private fun editableGroupIds(account: ContactAccount): List<Long> {
        val (sel, args) = accountSelection(Groups.ACCOUNT_TYPE, Groups.ACCOUNT_NAME, account)
        return resolver.query(Groups.CONTENT_URI, arrayOf(Groups._ID), "$EDITABLE_GROUP AND $sel", args, null)
            ?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }.orEmpty()
    }

    /** Ids de contacto de los miembros de una etiqueta. */
    fun groupMembers(groupId: Long): Set<Long> =
        resolver.query(
            Data.CONTENT_URI,
            arrayOf(Data.CONTACT_ID),
            "${Data.MIMETYPE} = ? AND ${CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ?",
            arrayOf(CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, groupId.toString()),
            null,
        )?.use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }.orEmpty()

    fun renameGroup(groupId: Long, title: String) {
        resolver.update(ContentUris.withAppendedId(Groups.CONTENT_URI, groupId), ContentValues().apply { put(Groups.TITLE, title.trim()) }, null, null)
    }

    /** Borra la etiqueta; los contactos quedan, solo pierden la etiqueta. */
    fun deleteGroup(groupId: Long) {
        resolver.delete(ContentUris.withAppendedId(Groups.CONTENT_URI, groupId), null, null)
    }

    fun createGroup(title: String, account: ContactAccount): Long = findOrCreateGroup(title.trim(), account)

    private fun findOrCreateGroup(title: String, account: ContactAccount): Long {
        val (sel, args) = accountSelection(Groups.ACCOUNT_TYPE, Groups.ACCOUNT_NAME, account)
        resolver.query(
            Groups.CONTENT_URI,
            arrayOf(Groups._ID),
            "${Groups.TITLE} = ? AND ${Groups.DELETED} = 0 AND $sel",
            arrayOf(title) + args,
            null,
        )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        val uri = resolver.insert(
            Groups.CONTENT_URI,
            ContentValues().apply {
                put(Groups.TITLE, title)
                put(Groups.ACCOUNT_TYPE, account.type)
                put(Groups.ACCOUNT_NAME, account.name)
                put(Groups.GROUP_VISIBLE, 1)
            },
        ) ?: error("no se pudo crear la etiqueta")
        return ContentUris.parseId(uri)
    }

    /** Agrega o saca un contacto de una etiqueta (en todas sus fichas de la cuenta de la etiqueta). */
    fun setInGroup(contactId: Long, group: ContactGroup, member: Boolean) {
        val raws = rawContactIds(contactId).filter { it.second == group.account }.map { it.first }
        if (raws.isEmpty()) return
        val ops = ArrayList<ContentProviderOperation>()
        for (raw in raws) {
            ops += ContentProviderOperation.newDelete(Data.CONTENT_URI)
                .withSelection(
                    "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${CommonDataKinds.GroupMembership.GROUP_ROW_ID} = ?",
                    arrayOf(raw.toString(), CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, group.id.toString()),
                ).build()
            if (member) {
                ops += ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValue(Data.RAW_CONTACT_ID, raw)
                    .withValue(Data.MIMETYPE, CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.GroupMembership.GROUP_ROW_ID, group.id)
                    .build()
            }
        }
        resolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    // ---------------------------------------------------------------- escritura

    /**
     * Crea o actualiza una ficha. Al actualizar se reemplazan solo los datos que maneja esta app
     * (nombre, teléfonos, emails, direcciones, fechas, apodo, empresa, nota, webs, etiquetas y,
     * si [photoChanged], la foto); lo que hayan guardado otras apps con sus propios tipos queda.
     * [sourceId] solo se usa al crear (lo usan las herramientas de prueba para marcar sus fichas).
     * Devuelve el id del contacto.
     */
    fun save(edit: EditableContact, photoChanged: Boolean, sourceId: String? = null): Long {
        val raw = saveRaw(edit, photoChanged, sourceId, asAdapter = false)
        val contactId = contactIdOf(raw) ?: error("la ficha no tiene contacto")
        setStarred(contactId, edit.contact.starred)
        return contactId
    }

    /**
     * Guarda lo que vino de un sincronizador de esa cuenta: la ficha no queda marcada como cambiada
     * y la foto se reemplaza siempre. Devuelve el id de la ficha.
     */
    fun saveAsAdapter(edit: EditableContact): Long = saveRaw(edit, photoChanged = true, sourceId = null, asAdapter = true)

    /** Una ficha tal como está, con su foto (para subirla al servidor). */
    fun readRawContact(rawId: Long): Contact = readRaw(rawId).copy(photo = readPhoto(rawId))

    private fun saveRaw(edit: EditableContact, photoChanged: Boolean, sourceId: String?, asAdapter: Boolean): Long {
        fun Uri.w(): Uri = if (!asAdapter) this else buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(RawContacts.ACCOUNT_NAME, edit.account.name)
            .appendQueryParameter(RawContacts.ACCOUNT_TYPE, edit.account.type)
            .build()
        val c = edit.contact
        val ops = ArrayList<ContentProviderOperation>()
        val rawId = edit.rawContactId
        val rawRef: (ContentProviderOperation.Builder) -> ContentProviderOperation.Builder
        var keepNickname = false
        var keepOrganization = false
        var keepNote = false
        if (rawId == null) {
            ops += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI.w())
                .withValue(RawContacts.ACCOUNT_TYPE, edit.account.type)
                .withValue(RawContacts.ACCOUNT_NAME, edit.account.name)
                .apply { if (sourceId != null) withValue(RawContacts.SOURCE_ID, sourceId) }
                .build()
            rawRef = { it.withValueBackReference(Data.RAW_CONTACT_ID, 0) }
        } else {
            // Apodo, empresa y nota solo se reescriben si cambiaron: la app muestra uno de cada uno,
            // y reescribirlos perdería un segundo apodo o el departamento que cargó otra app.
            val before = readRaw(rawId)
            keepNickname = before.nickname?.trim().orEmpty() == c.nickname?.trim().orEmpty()
            keepOrganization = before.organization?.trim().orEmpty() == c.organization?.trim().orEmpty() &&
                before.jobTitle?.trim().orEmpty() == c.jobTitle?.trim().orEmpty()
            keepNote = before.note?.trim().orEmpty() == c.note?.trim().orEmpty()
            val replaced = MANAGED_TYPES.filter { t ->
                !(keepNickname && t == CommonDataKinds.Nickname.CONTENT_ITEM_TYPE) &&
                    !(keepOrganization && t == CommonDataKinds.Organization.CONTENT_ITEM_TYPE) &&
                    !(keepNote && t == CommonDataKinds.Note.CONTENT_ITEM_TYPE)
            } + if (photoChanged) listOf(CommonDataKinds.Photo.CONTENT_ITEM_TYPE) else emptyList()
            ops += ContentProviderOperation.newDelete(Data.CONTENT_URI.w())
                .withSelection(
                    "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} IN (${replaced.joinToString(",") { "?" }})",
                    (listOf(rawId.toString()) + replaced).toTypedArray(),
                ).build()
            // De las etiquetas, solo las del usuario: la pertenencia a grupos del sistema no se toca.
            val editable = editableGroupIds(edit.account)
            if (editable.isNotEmpty()) {
                ops += ContentProviderOperation.newDelete(Data.CONTENT_URI.w())
                    .withSelection(
                        "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ? AND ${CommonDataKinds.GroupMembership.GROUP_ROW_ID} IN (${editable.joinToString(",")})",
                        arrayOf(rawId.toString(), CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE),
                    ).build()
            }
            rawRef = { it.withValue(Data.RAW_CONTACT_ID, rawId) }
        }
        fun row(mime: String, vararg values: Pair<String, Any?>) {
            val b = rawRef(ContentProviderOperation.newInsert(Data.CONTENT_URI.w())).withValue(Data.MIMETYPE, mime)
            for ((k, v) in values) b.withValue(k, v)
            ops += b.build()
        }

        val n = c.name
        if (n.shown.isNotBlank()) {
            row(
                CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                CommonDataKinds.StructuredName.GIVEN_NAME to n.given?.trim().nullIfBlank(),
                CommonDataKinds.StructuredName.FAMILY_NAME to n.family?.trim().nullIfBlank(),
                CommonDataKinds.StructuredName.MIDDLE_NAME to n.middle?.trim().nullIfBlank(),
                CommonDataKinds.StructuredName.PREFIX to n.prefix?.trim().nullIfBlank(),
                CommonDataKinds.StructuredName.SUFFIX to n.suffix?.trim().nullIfBlank(),
                CommonDataKinds.StructuredName.PHONETIC_GIVEN_NAME to n.phoneticGiven?.trim().nullIfBlank(),
                CommonDataKinds.StructuredName.PHONETIC_FAMILY_NAME to n.phoneticFamily?.trim().nullIfBlank(),
                // Sin partes, el nombre armado: Android lo divide solo.
                CommonDataKinds.StructuredName.DISPLAY_NAME to n.shown,
            )
        }
        c.phones.filter { it.number.isNotBlank() }.forEach { p ->
            row(
                CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                CommonDataKinds.Phone.NUMBER to p.number.trim(),
                CommonDataKinds.Phone.TYPE to phoneType(p.kind),
                CommonDataKinds.Phone.LABEL to p.label.takeIf { p.kind == LabelKind.CUSTOM },
                Data.IS_PRIMARY to (if (p.primary) 1 else 0),
                Data.IS_SUPER_PRIMARY to (if (p.primary) 1 else 0),
            )
        }
        c.emails.filter { it.address.isNotBlank() }.forEach { e ->
            row(
                CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                CommonDataKinds.Email.ADDRESS to e.address.trim(),
                CommonDataKinds.Email.TYPE to emailType(e.kind),
                CommonDataKinds.Email.LABEL to e.label.takeIf { e.kind == LabelKind.CUSTOM },
                Data.IS_PRIMARY to (if (e.primary) 1 else 0),
            )
        }
        c.addresses.filter { it.formatted.isNotBlank() }.forEach { a ->
            row(
                CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
                CommonDataKinds.StructuredPostal.STREET to a.street?.trim().nullIfBlank(),
                CommonDataKinds.StructuredPostal.CITY to a.city?.trim().nullIfBlank(),
                CommonDataKinds.StructuredPostal.REGION to a.region?.trim().nullIfBlank(),
                CommonDataKinds.StructuredPostal.POSTCODE to a.postcode?.trim().nullIfBlank(),
                CommonDataKinds.StructuredPostal.COUNTRY to a.country?.trim().nullIfBlank(),
                CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS to a.formatted,
                CommonDataKinds.StructuredPostal.TYPE to postalType(a.kind),
                CommonDataKinds.StructuredPostal.LABEL to a.label.takeIf { a.kind == LabelKind.CUSTOM },
            )
        }
        c.events.forEach { e ->
            row(
                CommonDataKinds.Event.CONTENT_ITEM_TYPE,
                CommonDataKinds.Event.START_DATE to formatEvent(e),
                CommonDataKinds.Event.TYPE to eventType(e.kind),
                CommonDataKinds.Event.LABEL to e.label.takeIf { e.kind == EventKind.CUSTOM },
            )
        }
        if (!keepNickname) c.nickname?.trim()?.takeIf { it.isNotEmpty() }?.let { row(CommonDataKinds.Nickname.CONTENT_ITEM_TYPE, CommonDataKinds.Nickname.NAME to it) }
        if (!keepOrganization && (!c.organization.isNullOrBlank() || !c.jobTitle.isNullOrBlank())) {
            row(
                CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                CommonDataKinds.Organization.COMPANY to c.organization?.trim().nullIfBlank(),
                CommonDataKinds.Organization.TITLE to c.jobTitle?.trim().nullIfBlank(),
            )
        }
        if (!keepNote) c.note?.trim()?.takeIf { it.isNotEmpty() }?.let { row(CommonDataKinds.Note.CONTENT_ITEM_TYPE, CommonDataKinds.Note.NOTE to it) }
        c.websites.map { it.trim() }.filter { it.isNotEmpty() }.forEach { row(CommonDataKinds.Website.CONTENT_ITEM_TYPE, CommonDataKinds.Website.URL to it) }
        for (title in c.groups.map { it.trim() }.filter { it.isNotEmpty() }.toSet()) {
            row(CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE, CommonDataKinds.GroupMembership.GROUP_ROW_ID to findOrCreateGroup(title, edit.account))
        }
        if (photoChanged && c.photo != null) {
            // Miniatura en la fila; la foto grande se escribe aparte (abajo).
            row(CommonDataKinds.Photo.CONTENT_ITEM_TYPE, CommonDataKinds.Photo.PHOTO to c.photo)
        }

        val results = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        val savedRaw = rawId ?: ContentUris.parseId(results[0].uri!!)
        if (photoChanged && c.photo != null) {
            val uri = Uri.withAppendedPath(ContentUris.withAppendedId(RawContacts.CONTENT_URI, savedRaw), RawContacts.DisplayPhoto.CONTENT_DIRECTORY).w()
            runCatching { resolver.openAssetFileDescriptor(uri, "rw")?.use { fd -> fd.createOutputStream().use { it.write(c.photo!!) } } }
        }
        return savedRaw
    }

    fun contactIdOf(rawId: Long): Long? =
        resolver.query(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), arrayOf(RawContacts.CONTACT_ID), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    fun setStarred(contactId: Long, starred: Boolean) {
        resolver.update(
            ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId),
            ContentValues().apply { put(Contacts.STARRED, if (starred) 1 else 0) },
            null,
            null,
        )
    }

    /** Borra el contacto entero (todas sus fichas). Solo después de que el usuario lo confirme. */
    fun delete(contactId: Long) {
        resolver.delete(ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId), null, null)
    }

    /** Cada ficha del contacto con su foto, para poder volver a crearla. */
    fun snapshot(contactId: Long): List<RawSnapshot> {
        val starred = load(contactId)?.starred ?: false
        return rawContactIds(contactId).map { (raw, account) ->
            val reference = resolver.query(ContentUris.withAppendedId(RawContacts.CONTENT_URI, raw), arrayOf(RawContacts.SYNC4), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            RawSnapshot(raw, account, readRawContact(raw).copy(starred = starred), reference)
        }
    }

    fun isRawLive(rawId: Long): Boolean =
        resolver.query(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), arrayOf(RawContacts.DELETED), null, null, null)
            ?.use { c -> c.moveToFirst() && c.getInt(0) == 0 } ?: false

    /** Crea de nuevo una ficha de una copia. Queda marcada como cambiada: si es de un servidor, se sube. */
    fun restoreRaw(account: ContactAccount, contact: Contact, reference: String?): Long {
        val raw = saveRaw(EditableContact(null, account, contact), photoChanged = contact.photo != null, sourceId = null, asAdapter = false)
        if (contact.starred) contactIdOf(raw)?.let { setStarred(it, true) }
        if (reference != null && account.type != null) {
            // Como sincronizador para que escribir la referencia no cambie nada más.
            val uri = ContentUris.withAppendedId(RawContacts.CONTENT_URI, raw).buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
                .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
                .build()
            resolver.update(uri, ContentValues().apply { put(RawContacts.SYNC4, reference) }, null, null)
        }
        return raw
    }

    /** Pide a Android que muestre estas fichas como un solo contacto. */
    fun link(rawIds: List<Long>) {
        val ops = ArrayList<ContentProviderOperation>()
        for (i in rawIds.indices) for (j in i + 1 until rawIds.size) {
            ops += ContentProviderOperation.newUpdate(AggregationExceptions.CONTENT_URI)
                .withValue(AggregationExceptions.TYPE, AggregationExceptions.TYPE_KEEP_TOGETHER)
                .withValue(AggregationExceptions.RAW_CONTACT_ID1, rawIds[i])
                .withValue(AggregationExceptions.RAW_CONTACT_ID2, rawIds[j])
                .build()
        }
        resolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    /** Agrega a la etiqueta, en la cuenta de la primera ficha de cada contacto (la crea si no existe ahí). */
    fun addToLabel(contactIds: Collection<Long>, title: String) {
        for (id in contactIds) {
            val account = rawContactIds(id).firstOrNull()?.second ?: continue
            setInGroup(id, ContactGroup(findOrCreateGroup(title.trim(), account), title.trim(), account, 0), true)
        }
    }

    /** El lado máximo que guarda Android para una foto de contacto en este celular. */
    fun maxPhotoSide(): Int =
        resolver.query(ContactsContract.DisplayPhoto.CONTENT_MAX_DIMENSIONS_URI, arrayOf(ContactsContract.DisplayPhoto.DISPLAY_MAX_DIM), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getInt(0) else null }?.takeIf { it > 0 } ?: 720

    /** La foto en la mayor resolución que tenga Android; null si no tiene. */
    fun fullPhoto(contactId: Long): ByteArray? =
        Contacts.openContactPhotoInputStream(resolver, ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId), true)?.use { it.readBytes() }

    /** Si unir estos contactos borra fichas (todas de una sola ficha y de la misma cuenta). */
    fun mergeDeletes(contactIds: List<Long>): Boolean {
        val raws = contactIds.map { rawContactIds(it) }
        return raws.all { it.size == 1 } && raws.flatten().map { it.second }.toSet().size == 1
    }

    /**
     * Une contactos que el usuario confirmó con la vista previa [merged]:
     * - Si son todos de una sola ficha y de la misma cuenta ([mergeDeletes]), la unión se escribe
     *   en la ficha del primero y se borran los demás (en un servidor CardDAV queda uno solo).
 *   [beforeDelete] recibe las fichas que se van a borrar, antes de borrarlas.
     *   Devuelve una copia en memoria de los originales para [undoMerge].
     * - Si hay fichas de distintas cuentas, se vinculan (Android los muestra como uno) sin borrar
     *   nada de ninguna cuenta.
     */
    fun merge(contactIds: List<Long>, merged: Contact, beforeDelete: (List<Pair<Long, ContactAccount>>) -> Unit = {}): MergeResult {
        require(contactIds.size >= 2)
        val raws = contactIds.map { rawContactIds(it) }
        if (mergeDeletes(contactIds)) {
            val (keepRaw, account) = raws.first().single()
            val originals = contactIds.map { loadForEdit(it) ?: error("el contacto ya no existe") }
            val photo = merged.photo ?: originals.firstNotNullOfOrNull { it.contact.photo }
            val photoChanged = photo != null && originals.first().contact.photo == null
            save(EditableContact(keepRaw, account, merged.copy(photo = photo)), photoChanged)
            // Por ficha y con los ids de antes de escribir: al quedar con el mismo nombre y número,
            // Android junta las fichas en un contacto y el id de contacto de las otras deja de existir.
            // Antes de borrar se avisa (la sincronización anota qué borrar en el servidor).
            beforeDelete(raws.drop(1).map { it.single() })
            raws.drop(1).forEach { deleteRaw(it.single().first) }
            val id = contactIdOf(keepRaw) ?: error("la ficha no tiene contacto")
            return MergeResult(id, MergeUndo(keepRaw, account, originals.first().contact, originals.drop(1).map { it.contact }))
        }
        val all = raws.flatten().map { it.first }
        link(all)
        if (merged.starred) contactIdOf(all.first())?.let { setStarred(it, true) }
        return MergeResult(contactIdOf(all.first()) ?: contactIds.first(), undo = null)
    }

    /**
     * Revierte una unión: la ficha que quedó vuelve a su contenido y los borrados se crean de
     * nuevo con sus datos (con otro id: lo que otras apps guardaron en ellos no se recupera).
     */
    fun undoMerge(undo: MergeUndo): Long {
        val id = save(EditableContact(undo.keepRaw, undo.account, undo.first), photoChanged = true)
        undo.others.forEach { save(EditableContact(null, undo.account, it), photoChanged = it.photo != null) }
        return id
    }

    /** Fichas cuyo SOURCE_ID empieza con [prefix] (sin las ya borradas). */
    fun rawIdsWithSourcePrefix(prefix: String): List<Long> =
        resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID),
            "${RawContacts.SOURCE_ID} LIKE ? AND ${RawContacts.DELETED} = 0",
            arrayOf(prefix.replace("%", "").replace("_", "") + "%"),
            null,
        )?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }.orEmpty()

    /** Borra una sola ficha (no el contacto entero: si estaba unida a otra, esa queda). */
    fun deleteRaw(rawId: Long) {
        resolver.delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), null, null)
    }

    /** Devuelve fichas a una copia tomada con [loadForEdit] antes de cambiarlas. */
    fun restore(snapshots: List<EditableContact>) {
        snapshots.forEach { save(it, photoChanged = true) }
    }

    // ---------------------------------------------------------------- cambios

    /** Emite cada vez que cambia algo en la agenda (de esta app o de cualquier otra). */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(Contacts.CONTENT_URI, true, observer)
        // Las etiquetas también: otra app (o el sincronizador) puede crearlas o renombrarlas.
        resolver.registerContentObserver(Groups.CONTENT_URI, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()

    companion object {
        /** Tipos que esta app escribe y por lo tanto reemplaza al guardar (las etiquetas van aparte). */
        val MANAGED_TYPES = listOf(
            CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
            CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            CommonDataKinds.Nickname.CONTENT_ITEM_TYPE,
            CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            CommonDataKinds.Note.CONTENT_ITEM_TYPE,
            CommonDataKinds.Website.CONTENT_ITEM_TYPE,
        )

        /** Etiquetas que maneja el usuario: fuera las del sistema ("Mis contactos") y las de solo lectura. */
        private val EDITABLE_GROUP =
            "${Groups.DELETED} = 0 AND ${Groups.GROUP_IS_READ_ONLY} = 0 AND ${Groups.AUTO_ADD} = 0 AND ${Groups.SYSTEM_ID} IS NULL"

        /** Cuentas "del teléfono" de algunos fabricantes. */
        val KNOWN_LOCAL_TYPES = setOf(
            "vnd.sec.contact.phone", // Samsung
            "com.android.huawei.phone",
            "com.xiaomi", // MIUI guarda los locales sin cuenta o con esta
            "com.oppo.contacts.device",
            "com.android.localphone",
        )

        private val DATA_PROJECTION = arrayOf(
            Data._ID, Data.MIMETYPE, Data.IS_PRIMARY,
            Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5, Data.DATA6, Data.DATA7, Data.DATA8, Data.DATA9, Data.DATA10,
        )

        // Los tipos de dato comparten columnas DATA1..DATA10: cada constante es uno de esos nombres.
        private fun Cursor.str(col: String): String? = getColumnIndex(col).takeIf { it >= 0 }?.let { if (isNull(it)) null else getString(it) }
        private fun Cursor.int(col: String): Int = getColumnIndex(col).takeIf { it >= 0 }?.let { if (isNull(it)) 0 else getInt(it) } ?: 0
        private fun Cursor.long(col: String): Long = getColumnIndex(col).takeIf { it >= 0 }?.let { getLong(it) } ?: 0L

        private fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

        fun phoneKind(t: Int): LabelKind = when (t) {
            CommonDataKinds.Phone.TYPE_MOBILE -> LabelKind.MOBILE
            CommonDataKinds.Phone.TYPE_HOME -> LabelKind.HOME
            CommonDataKinds.Phone.TYPE_WORK, CommonDataKinds.Phone.TYPE_WORK_MOBILE -> LabelKind.WORK
            CommonDataKinds.Phone.TYPE_MAIN -> LabelKind.MAIN
            CommonDataKinds.Phone.TYPE_FAX_WORK -> LabelKind.FAX_WORK
            CommonDataKinds.Phone.TYPE_FAX_HOME -> LabelKind.FAX_HOME
            CommonDataKinds.Phone.TYPE_PAGER -> LabelKind.PAGER
            CommonDataKinds.Phone.TYPE_CUSTOM -> LabelKind.CUSTOM
            else -> LabelKind.OTHER
        }

        fun phoneType(k: LabelKind): Int = when (k) {
            LabelKind.MOBILE -> CommonDataKinds.Phone.TYPE_MOBILE
            LabelKind.HOME -> CommonDataKinds.Phone.TYPE_HOME
            LabelKind.WORK -> CommonDataKinds.Phone.TYPE_WORK
            LabelKind.MAIN -> CommonDataKinds.Phone.TYPE_MAIN
            LabelKind.FAX_WORK -> CommonDataKinds.Phone.TYPE_FAX_WORK
            LabelKind.FAX_HOME -> CommonDataKinds.Phone.TYPE_FAX_HOME
            LabelKind.PAGER -> CommonDataKinds.Phone.TYPE_PAGER
            LabelKind.CUSTOM -> CommonDataKinds.Phone.TYPE_CUSTOM
            LabelKind.OTHER -> CommonDataKinds.Phone.TYPE_OTHER
        }

        fun emailKind(t: Int): LabelKind = when (t) {
            CommonDataKinds.Email.TYPE_HOME -> LabelKind.HOME
            CommonDataKinds.Email.TYPE_WORK -> LabelKind.WORK
            CommonDataKinds.Email.TYPE_MOBILE -> LabelKind.MOBILE
            CommonDataKinds.Email.TYPE_CUSTOM -> LabelKind.CUSTOM
            else -> LabelKind.OTHER
        }

        fun emailType(k: LabelKind): Int = when (k) {
            LabelKind.HOME -> CommonDataKinds.Email.TYPE_HOME
            LabelKind.WORK -> CommonDataKinds.Email.TYPE_WORK
            LabelKind.MOBILE -> CommonDataKinds.Email.TYPE_MOBILE
            LabelKind.CUSTOM -> CommonDataKinds.Email.TYPE_CUSTOM
            else -> CommonDataKinds.Email.TYPE_OTHER
        }

        fun postalKind(t: Int): LabelKind = when (t) {
            CommonDataKinds.StructuredPostal.TYPE_HOME -> LabelKind.HOME
            CommonDataKinds.StructuredPostal.TYPE_WORK -> LabelKind.WORK
            CommonDataKinds.StructuredPostal.TYPE_CUSTOM -> LabelKind.CUSTOM
            else -> LabelKind.OTHER
        }

        fun postalType(k: LabelKind): Int = when (k) {
            LabelKind.HOME -> CommonDataKinds.StructuredPostal.TYPE_HOME
            LabelKind.WORK -> CommonDataKinds.StructuredPostal.TYPE_WORK
            LabelKind.CUSTOM -> CommonDataKinds.StructuredPostal.TYPE_CUSTOM
            else -> CommonDataKinds.StructuredPostal.TYPE_OTHER
        }

        fun eventKind(t: Int): EventKind = when (t) {
            CommonDataKinds.Event.TYPE_BIRTHDAY -> EventKind.BIRTHDAY
            CommonDataKinds.Event.TYPE_ANNIVERSARY -> EventKind.ANNIVERSARY
            CommonDataKinds.Event.TYPE_CUSTOM -> EventKind.CUSTOM
            else -> EventKind.OTHER
        }

        fun eventType(k: EventKind): Int = when (k) {
            EventKind.BIRTHDAY -> CommonDataKinds.Event.TYPE_BIRTHDAY
            EventKind.ANNIVERSARY -> CommonDataKinds.Event.TYPE_ANNIVERSARY
            EventKind.CUSTOM -> CommonDataKinds.Event.TYPE_CUSTOM
            EventKind.OTHER -> CommonDataKinds.Event.TYPE_OTHER
        }

        /** "1990-03-14" o, sin año, "--03-14" (el formato que usan Android y vCard). */
        fun formatEvent(e: ContactEvent): String =
            if (e.year != null) "%04d-%02d-%02d".format(e.year, e.month, e.day) else "--%02d-%02d".format(e.month, e.day)

        /** Lee las fechas que guardan las apps de contactos: "1990-03-14", "--03-14", "19900314" o con hora. */
        fun parseEvent(text: String?, kind: EventKind, label: String?): ContactEvent? {
            val t = text?.trim()?.substringBefore('T') ?: return null
            val m = Regex("^(\\d{4}|-)-?(\\d{2})-?(\\d{2})$").find(t.replace("--", "-")) ?: return null
            val year = m.groupValues[1].toIntOrNull()
            return runCatching { ContactEvent(m.groupValues[2].toInt(), m.groupValues[3].toInt(), year, kind, label) }.getOrNull()
        }

        private fun accountSelection(typeCol: String, nameCol: String, a: ContactAccount): Pair<String, Array<String>> =
            if (a.type == null) "$typeCol IS NULL AND $nameCol IS NULL" to emptyArray()
            else "$typeCol = ? AND $nameCol = ?" to arrayOf(a.type, a.name.orEmpty())
    }
}

/** Une dos lecturas del mismo contacto sin repetir datos (para mostrar un contacto de varias fichas). */
private fun Contact.plusDistinct(o: Contact): Contact = copy(
    name = if (name.shown.isBlank()) o.name else name,
    nickname = nickname ?: o.nickname,
    organization = organization ?: o.organization,
    jobTitle = jobTitle ?: o.jobTitle,
    phones = (phones + o.phones).distinctBy { it.number.filter { ch -> ch.isDigit() || ch == '+' } },
    emails = (emails + o.emails).distinctBy { it.address.trim().lowercase() },
    addresses = (addresses + o.addresses).distinctBy { it.formatted.lowercase() },
    events = (events + o.events).distinct(),
    websites = (websites + o.websites).distinct(),
    note = listOfNotNull(note, o.note).distinct().joinToString("\n\n").ifEmpty { null },
    groups = groups + o.groups,
)
