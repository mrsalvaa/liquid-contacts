// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.model

import java.time.LocalDate
import java.time.MonthDay

/**
 * Modelos de la agenda, sin nada de Android. La capa de ContactsContract los arma y los guarda;
 * vCard, búsqueda, duplicados y sincronización trabajan solo con esto.
 */

/** Tipo de un dato con etiqueta (teléfono, email, dirección, evento). [CUSTOM] usa [Labeled.label]. */
enum class LabelKind { MOBILE, HOME, WORK, OTHER, MAIN, FAX_WORK, FAX_HOME, PAGER, CUSTOM }

/** Un valor con su tipo; [label] solo cuenta si [kind] es [LabelKind.CUSTOM]. */
interface Labeled {
    val kind: LabelKind
    val label: String?
}

data class Phone(
    val number: String,
    override val kind: LabelKind = LabelKind.MOBILE,
    override val label: String? = null,
    val primary: Boolean = false,
) : Labeled

data class Email(
    val address: String,
    override val kind: LabelKind = LabelKind.HOME,
    override val label: String? = null,
    val primary: Boolean = false,
) : Labeled

data class PostalAddress(
    val street: String? = null,
    val city: String? = null,
    val region: String? = null,
    val postcode: String? = null,
    val country: String? = null,
    override val kind: LabelKind = LabelKind.HOME,
    override val label: String? = null,
) : Labeled {
    /** Dirección en una sola cadena, para mostrar y para buscar. */
    val formatted: String get() = listOfNotNull(street, city, region, postcode, country).filter { it.isNotBlank() }.joinToString(", ")
}

enum class EventKind { BIRTHDAY, ANNIVERSARY, OTHER, CUSTOM }

/**
 * Cumpleaños, aniversario u otra fecha. Sin año se guarda solo día y mes ([year] null): muchos
 * cumpleaños se cargan así.
 */
data class ContactEvent(
    val month: Int,
    val day: Int,
    val year: Int? = null,
    val kind: EventKind = EventKind.BIRTHDAY,
    val label: String? = null,
) {
    init {
        // 29/2 sin año es válido (MonthDay lo acepta); con año, la fecha tiene que existir.
        MonthDay.of(month, day)
        if (year != null) LocalDate.of(year, month, day)
    }
}

/** Nombre estructurado. [display] es el que se muestra y ordena si no hay partes. */
data class Name(
    val display: String = "",
    val given: String? = null,
    val family: String? = null,
    val middle: String? = null,
    val prefix: String? = null,
    val suffix: String? = null,
    val phoneticGiven: String? = null,
    val phoneticFamily: String? = null,
) {
    /** Lo que se muestra: el nombre armado con las partes o, si no hay, [display]. */
    val shown: String
        get() = listOfNotNull(prefix, given, middle, family, suffix).filter { it.isNotBlank() }.joinToString(" ").ifBlank { display.trim() }
}

/**
 * Un contacto completo. [id] es el de la agenda del sistema (null si todavía no se guardó);
 * [lookupKey] es la referencia estable de Android, que sobrevive a uniones y sincronizaciones.
 */
data class Contact(
    val id: Long? = null,
    val lookupKey: String? = null,
    val name: Name = Name(),
    val nickname: String? = null,
    val organization: String? = null,
    val jobTitle: String? = null,
    val phones: List<Phone> = emptyList(),
    val emails: List<Email> = emptyList(),
    val addresses: List<PostalAddress> = emptyList(),
    val events: List<ContactEvent> = emptyList(),
    val websites: List<String> = emptyList(),
    val note: String? = null,
    /** Etiquetas (grupos). En vCard van como CATEGORIES. */
    val groups: Set<String> = emptySet(),
    val starred: Boolean = false,
    /** Foto en JPEG o PNG, si se cargó entera (en la lista solo se usa la miniatura de Android). */
    val photo: ByteArray? = null,
) {
    /** Nombre para mostrar; si no tiene, el primer dato que lo identifique. */
    val displayName: String
        get() = name.shown.ifBlank { null }
            ?: nickname?.takeIf { it.isNotBlank() }
            ?: organization?.takeIf { it.isNotBlank() }
            ?: phones.firstOrNull()?.number
            ?: emails.firstOrNull()?.address
            ?: ""

    // ByteArray no compara por contenido: equals/hashCode a mano para que las copias se comparen bien.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Contact) return false
        return id == other.id && lookupKey == other.lookupKey && name == other.name && nickname == other.nickname &&
            organization == other.organization && jobTitle == other.jobTitle && phones == other.phones &&
            emails == other.emails && addresses == other.addresses && events == other.events &&
            websites == other.websites && note == other.note && groups == other.groups && starred == other.starred &&
            (photo?.contentEquals(other.photo) ?: (other.photo == null))
    }

    override fun hashCode(): Int {
        var h = listOf(id, lookupKey, name, nickname, organization, jobTitle, phones, emails, addresses, events, websites, note, groups, starred).hashCode()
        h = 31 * h + (photo?.contentHashCode() ?: 0)
        return h
    }
}

/** Fila de la lista: lo mínimo para mostrar, buscar y ordenar sin cargar el contacto entero. */
data class ContactSummary(
    val id: Long,
    val lookupKey: String,
    val displayName: String,
    val starred: Boolean = false,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val photoUri: String? = null,
)
