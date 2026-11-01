// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.external

import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.model.Email
import dev.liquidcontacts.core.model.LabelKind
import dev.liquidcontacts.core.model.Name
import dev.liquidcontacts.core.model.Phone
import dev.liquidcontacts.core.model.PostalAddress
import dev.liquidcontacts.core.text.Clean

/** A qué contacto apunta una URI que mandó otra app. */
sealed interface ContactRef {
    data class Id(val id: Long) : ContactRef
    data class Lookup(val key: String) : ContactRef
    data class Raw(val id: Long) : ContactRef
}

/** Los extras de un intent de otra app, sin nada de Android. */
data class ExternalFields(
    val name: String? = null,
    val company: String? = null,
    val jobTitle: String? = null,
    val notes: String? = null,
    val postal: String? = null,
    val phones: List<Pair<String?, LabelKind?>> = emptyList(),
    val emails: List<Pair<String?, LabelKind?>> = emptyList(),
    val websites: List<String?> = emptyList(),
)

/**
 * Validación de lo que llega de otras apps por intents: entrada no confiable. Solo URIs de la
 * agenda del sistema con forma conocida, y solo datos de texto limpios y con largo máximo. El
 * resultado únicamente rellena un formulario: nada se guarda sin que el usuario toque guardar.
 */
object ExternalInput {
    const val CONTACTS_AUTHORITY = "com.android.contacts"
    private const val MAX_ITEMS = 20

    /** content://com.android.contacts/contacts/ID, /contacts/lookup/KEY[/ID] o /raw_contacts/ID. */
    fun contactRef(scheme: String?, authority: String?, segments: List<String>): ContactRef? {
        if (scheme != "content" || authority != CONTACTS_AUTHORITY) return null
        return when {
            segments.size == 2 && segments[0] == "contacts" -> id(segments[1])?.let(ContactRef::Id)
            segments.size == 2 && segments[0] == "raw_contacts" -> id(segments[1])?.let(ContactRef::Raw)
            segments.size in 3..4 && segments[0] == "contacts" && segments[1] == "lookup" -> {
                if (segments.size == 4 && id(segments[3]) == null) return null
                segments[2].takeIf { k -> k.isNotEmpty() && k.length <= 2000 && k.none(Char::isISOControl) }?.let(ContactRef::Lookup)
            }
            else -> null
        }
    }

    private fun id(text: String): Long? = text.takeIf { it.length <= 18 && it.all(Char::isDigit) }?.toLongOrNull()?.takeIf { it > 0 }

    /**
     * Un .vcf que otra app pide abrir: solo por content:// (file:// expondría archivos de quien
     * lo abre) y nunca de nuestro propio proveedor de archivos ni de la agenda.
     */
    fun vcfUriAllowed(scheme: String?, authority: String?, ownAuthority: String): Boolean =
        scheme == "content" && !authority.isNullOrEmpty() && authority != ownAuthority && authority != CONTACTS_AUTHORITY

    /** El contacto para el formulario, o null si no vino nada que sirva. */
    fun contact(f: ExternalFields): Contact? {
        val c = Contact(
            name = Name(display = Clean.line(f.name).orEmpty()),
            organization = Clean.line(f.company),
            jobTitle = Clean.line(f.jobTitle),
            note = Clean.multiline(f.notes),
            phones = f.phones.take(MAX_ITEMS).mapNotNull { (n, k) -> Clean.phone(n)?.let { Phone(it, k ?: LabelKind.MOBILE) } },
            emails = f.emails.take(MAX_ITEMS).mapNotNull { (e, k) -> Clean.email(e)?.let { Email(it, k ?: LabelKind.HOME) } },
            addresses = listOfNotNull(Clean.multiline(f.postal, Clean.MAX_LINE)?.let { PostalAddress(street = it) }),
            websites = f.websites.take(MAX_ITEMS).mapNotNull(Clean::url),
        )
        val empty = c.name.display.isEmpty() && c.organization == null && c.jobTitle == null && c.note == null &&
            c.phones.isEmpty() && c.emails.isEmpty() && c.addresses.isEmpty() && c.websites.isEmpty()
        return c.takeUnless { empty }
    }
}
