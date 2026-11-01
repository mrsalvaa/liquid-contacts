// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.external

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import androidx.core.content.IntentCompat
import dev.liquidcontacts.contacts.SystemContacts
import dev.liquidcontacts.core.external.ContactRef
import dev.liquidcontacts.core.external.ExternalFields
import dev.liquidcontacts.core.external.ExternalInput
import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.model.LabelKind

/** Lo que una app externa puede pedir, ya validado. */
sealed interface ExternalRequest {
    data class View(val contactId: Long) : ExternalRequest

    /** Abre el formulario; [extra] son datos para sumar. Solo se guarda si el usuario toca guardar. */
    data class Edit(val contactId: Long, val extra: Contact?) : ExternalRequest

    /** Formulario de contacto nuevo; idem. */
    data class Insert(val contact: Contact?) : ExternalRequest
    data class ImportVcf(val uri: Uri) : ExternalRequest

    /** No se entendió o no pasó la validación: no se abre nada. */
    data object Invalid : ExternalRequest
}

/**
 * Traduce un intent de otra app. Todo lo que trae es entrada no confiable: las URIs y los extras
 * pasan por [ExternalInput], y leer extras mal armados (pueden lanzar al desarmarlos) es Invalid.
 */
object ExternalIntents {
    private const val VCARD_TYPES = "text/x-vcard text/vcard text/directory"

    /** Llamar con el permiso ya concedido: resolver una URI consulta la agenda. */
    fun parse(intent: Intent, contacts: SystemContacts, ownFilesAuthority: String): ExternalRequest = runCatching {
        val type = intent.type?.lowercase()
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return ExternalRequest.Invalid
                if (type != null && type in VCARD_TYPES.split(' ') || uri.lastPathSegment?.endsWith(".vcf", ignoreCase = true) == true) {
                    if (ExternalInput.vcfUriAllowed(uri.scheme, uri.authority, ownFilesAuthority)) ExternalRequest.ImportVcf(uri) else ExternalRequest.Invalid
                } else {
                    resolve(uri, contacts)?.let { ExternalRequest.View(it) } ?: ExternalRequest.Invalid
                }
            }
            Intent.ACTION_EDIT -> {
                val uri = intent.data ?: return ExternalRequest.Invalid
                resolve(uri, contacts)?.let { ExternalRequest.Edit(it, fields(intent)) } ?: ExternalRequest.Invalid
            }
            Intent.ACTION_INSERT, Intent.ACTION_INSERT_OR_EDIT -> ExternalRequest.Insert(fields(intent))
            else -> ExternalRequest.Invalid
        }
    }.getOrDefault(ExternalRequest.Invalid)

    private fun resolve(uri: Uri, contacts: SystemContacts): Long? =
        when (val ref = ExternalInput.contactRef(uri.scheme, uri.authority, uri.pathSegments)) {
            is ContactRef.Id -> ref.id.takeIf { contacts.exists(it) }
            is ContactRef.Lookup -> contacts.resolve(ref.key)
            is ContactRef.Raw -> contacts.contactIdOf(ref.id)
            null -> null
        }

    private fun fields(intent: Intent): Contact? {
        fun text(key: String) = intent.getCharSequenceExtra(key)?.toString()
        val rows = IntentCompat.getParcelableArrayListExtra(intent, ContactsContract.Intents.Insert.DATA, ContentValues::class.java).orEmpty().take(50)
        fun column(mime: String, column: String) = rows.filter { it.getAsString(ContactsContract.Data.MIMETYPE) == mime }.map { it.getAsString(column) }
        return ExternalInput.contact(
            ExternalFields(
                name = text(ContactsContract.Intents.Insert.NAME),
                company = text(ContactsContract.Intents.Insert.COMPANY),
                jobTitle = text(ContactsContract.Intents.Insert.JOB_TITLE),
                notes = text(ContactsContract.Intents.Insert.NOTES),
                postal = text(ContactsContract.Intents.Insert.POSTAL),
                phones = listOf(
                    text(ContactsContract.Intents.Insert.PHONE) to phoneKind(intent, ContactsContract.Intents.Insert.PHONE_TYPE),
                    text(ContactsContract.Intents.Insert.SECONDARY_PHONE) to phoneKind(intent, ContactsContract.Intents.Insert.SECONDARY_PHONE_TYPE),
                    text(ContactsContract.Intents.Insert.TERTIARY_PHONE) to phoneKind(intent, ContactsContract.Intents.Insert.TERTIARY_PHONE_TYPE),
                ) + column(CommonDataKinds.Phone.CONTENT_ITEM_TYPE, CommonDataKinds.Phone.NUMBER).map { it to null },
                emails = listOf(
                    text(ContactsContract.Intents.Insert.EMAIL) to null,
                    text(ContactsContract.Intents.Insert.SECONDARY_EMAIL) to null,
                    text(ContactsContract.Intents.Insert.TERTIARY_EMAIL) to null,
                ) + column(CommonDataKinds.Email.CONTENT_ITEM_TYPE, CommonDataKinds.Email.ADDRESS).map { it to null },
                websites = column(CommonDataKinds.Website.CONTENT_ITEM_TYPE, CommonDataKinds.Website.URL),
            ),
        )
    }

    /** Los tipos pueden venir como número o como texto libre: solo se aceptan números conocidos. */
    private fun phoneKind(intent: Intent, key: String): LabelKind? =
        intent.getIntExtra(key, -1).takeIf { it > 0 }?.let(SystemContacts::phoneKind)

    /** Tipo de lo que se pide elegir, o null si no es válido. */
    fun pickKind(intent: Intent, resolvedType: String?): PickKind? {
        val data = intent.data
        // Una URI ajena (con un tipo de contactos declarado a mano) no se acepta.
        if (data != null && data.authority != ExternalInput.CONTACTS_AUTHORITY && data.authority != "contacts") return null
        if (intent.action != Intent.ACTION_PICK && intent.action != Intent.ACTION_GET_CONTENT) return null
        return when (resolvedType?.lowercase()) {
            "vnd.android.cursor.dir/contact", "vnd.android.cursor.item/contact", "vnd.android.cursor.dir/person", "vnd.android.cursor.item/person" -> PickKind.CONTACT
            CommonDataKinds.Phone.CONTENT_TYPE, CommonDataKinds.Phone.CONTENT_ITEM_TYPE, "vnd.android.cursor.dir/phone" -> PickKind.PHONE
            CommonDataKinds.Email.CONTENT_TYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE -> PickKind.EMAIL
            CommonDataKinds.StructuredPostal.CONTENT_TYPE, CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> PickKind.POSTAL
            else -> null
        }
    }
}

enum class PickKind { CONTACT, PHONE, EMAIL, POSTAL }
