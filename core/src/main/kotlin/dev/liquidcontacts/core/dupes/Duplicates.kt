// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.dupes

import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.model.ContactEvent
import dev.liquidcontacts.core.model.Email
import dev.liquidcontacts.core.model.Phone
import dev.liquidcontacts.core.model.PostalAddress
import dev.liquidcontacts.core.phone.PhoneNumbers
import dev.liquidcontacts.core.text.Fold

/** Por qué dos contactos parecen el mismo. */
enum class DuplicateReason { SAME_NAME, SAME_PHONE, SAME_EMAIL }

/** Contactos que parecen la misma persona, con los motivos. Siempre 2 o más. */
data class DuplicateGroup(val contacts: List<Contact>, val reasons: Set<DuplicateReason>)

/**
 * Detección y unión de duplicados. Solo con coincidencias exactas: el mismo nombre completo (sin
 * importar tildes, mayúsculas ni espacios), el mismo número completo (clave exacta de
 * [PhoneNumbers], nunca la aproximada de 8 dígitos) o el mismo email. Nada se une sin que el
 * usuario vea la vista previa de [merge] y confirme.
 */
class Duplicates(private val numbers: PhoneNumbers) {

    fun find(contacts: List<Contact>): List<DuplicateGroup> {
        val parent = IntArray(contacts.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var j = i
            while (parent[j] != r) { val next = parent[j]; parent[j] = r; j = next }
            return r
        }
        // Motivos por par unido: se acumulan en la raíz al final.
        val links = mutableListOf<Triple<Int, Int, DuplicateReason>>()

        fun linkBy(reason: DuplicateReason, keysOf: (Contact) -> Collection<String>) {
            val seen = HashMap<String, Int>()
            contacts.forEachIndexed { i, c ->
                for (k in keysOf(c).toSet()) {
                    val j = seen.putIfAbsent(k, i) ?: continue
                    if (j != i) {
                        links += Triple(j, i, reason)
                        val (a, b) = root(j) to root(i)
                        if (a != b) parent[b] = a
                    }
                }
            }
        }
        linkBy(DuplicateReason.SAME_NAME) { c -> listOfNotNull(nameKey(c)) }
        linkBy(DuplicateReason.SAME_PHONE) { c -> c.phones.mapNotNull { numbers.exact(it.number) } }
        linkBy(DuplicateReason.SAME_EMAIL) { c -> c.emails.map { emailKey(it.address) }.filter { it.isNotEmpty() } }

        val reasons = HashMap<Int, MutableSet<DuplicateReason>>()
        for ((a, _, reason) in links) reasons.getOrPut(root(a)) { mutableSetOf() } += reason
        return contacts.indices.groupBy(::root)
            .filterValues { it.size > 1 }
            .map { (r, members) -> DuplicateGroup(members.map { contacts[it] }, reasons[r].orEmpty()) }
    }

    /**
     * Vista previa de la unión: un contacto con todo lo de [contacts] sin repetir. El primero manda
     * en lo que es uno solo (nombre, empresa, foto); el resto se suma.
     */
    fun merge(contacts: List<Contact>): Contact {
        require(contacts.isNotEmpty())
        val first = contacts.first()
        val name = contacts.maxBy { c -> listOfNotNull(c.name.given, c.name.family, c.name.middle, c.name.prefix, c.name.suffix).count { it.isNotBlank() } }.name
            .takeIf { it.shown.isNotBlank() } ?: first.name
        fun <T> firstNotBlank(get: (Contact) -> String?): String? = contacts.firstNotNullOfOrNull { get(it)?.takeIf(String::isNotBlank) }
        return Contact(
            id = first.id,
            lookupKey = first.lookupKey,
            name = name,
            nickname = firstNotBlank<String> { it.nickname },
            organization = firstNotBlank<String> { it.organization },
            jobTitle = firstNotBlank<String> { it.jobTitle },
            phones = distinctPhones(contacts.flatMap { it.phones }),
            emails = contacts.flatMap { it.emails }.distinctBy { emailKey(it.address) }.let(::onePrimaryEmail),
            addresses = contacts.flatMap { it.addresses }.distinctBy(::addressKey),
            events = contacts.flatMap { it.events }.distinctBy { Triple(it.kind, it.month to it.day, it.year) }.let(::preferYear),
            websites = contacts.flatMap { it.websites }.distinctBy { it.trim().trimEnd('/').lowercase() },
            note = contacts.mapNotNull { it.note?.trim()?.takeIf(String::isNotEmpty) }.distinct().joinToString("\n\n").ifEmpty { null },
            groups = contacts.flatMapTo(LinkedHashSet()) { it.groups },
            starred = contacts.any { it.starred },
            photo = contacts.firstNotNullOfOrNull { it.photo },
        )
    }

    /** Mismo número = misma clave exacta; si no tiene, los mismos dígitos. Queda un solo principal. */
    private fun distinctPhones(phones: List<Phone>): List<Phone> {
        val out = phones.distinctBy { numbers.exact(it.number) ?: it.number.filter(Char::isDigit) }
        val primary = out.indexOfFirst { it.primary }
        return out.mapIndexed { i, p -> if (p.primary && i != primary) p.copy(primary = false) else p }
    }

    private fun onePrimaryEmail(emails: List<Email>): List<Email> {
        val primary = emails.indexOfFirst { it.primary }
        return emails.mapIndexed { i, e -> if (e.primary && i != primary) e.copy(primary = false) else e }
    }

    /** Un mismo evento con y sin año: queda el que tiene año. */
    private fun preferYear(events: List<ContactEvent>): List<ContactEvent> =
        events.filterNot { e -> e.year == null && events.any { o -> o.year != null && o.kind == e.kind && o.month == e.month && o.day == e.day } }

    companion object {
        fun nameKey(c: Contact): String? =
            Fold.fold(c.name.shown).split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }

        fun emailKey(address: String): String = address.trim().lowercase()

        fun addressKey(a: PostalAddress): String = Fold.fold(a.formatted).replace(Regex("[\\s,.]+"), " ").trim()
    }
}
