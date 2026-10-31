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

/**
 * Confianza de un grupo. [HIGH]: el mismo nombre completo. [LOW]: comparten un número o un email
 * pero tienen nombres distintos ("posible duplicado": puede ser una línea o un email compartido,
 * como el de una familia). La interfaz no preselecciona los de confianza baja.
 */
enum class DuplicateConfidence { HIGH, LOW }

/** Contactos que parecen la misma persona, con los motivos. Siempre 2 o más. */
data class DuplicateGroup(
    val contacts: List<Contact>,
    val reasons: Set<DuplicateReason>,
    val confidence: DuplicateConfidence,
)

/**
 * Detección y unión de duplicados. Solo con coincidencias exactas: el mismo nombre completo (sin
 * importar tildes, mayúsculas ni espacios), el mismo número completo (clave exacta de
 * [PhoneNumbers], nunca la aproximada de 8 dígitos) o el mismo email. Nada se une sin que el
 * usuario vea la vista previa de [merge] y confirme.
 *
 * Los grupos de cada confianza se arman por separado y no se encadenan entre sí: si A y B se
 * llaman igual y B comparte el número con C, salen {A, B} (alta) y {B, C} (baja), nunca {A, B, C}.
 */
class Duplicates(private val numbers: PhoneNumbers) {

    fun find(contacts: List<Contact>): List<DuplicateGroup> {
        val names = contacts.map(::nameKey)
        val high = components(contacts.size, links(contacts.indices.map { i -> listOfNotNull(names[i]) }, DuplicateReason.SAME_NAME))
            .map { (members, reasons) -> DuplicateGroup(members.map { contacts[it] }, reasons, DuplicateConfidence.HIGH) }
        // Baja: mismo número o email entre contactos con distinto nombre (o sin nombre). Los que se
        // llaman igual ya están en un grupo de confianza alta.
        val shared = links(contacts.map { c -> c.phones.mapNotNull { numbers.exact(it.number) } }, DuplicateReason.SAME_PHONE) +
            links(contacts.map { c -> c.emails.map { emailKey(it.address) }.filter { it.isNotEmpty() } }, DuplicateReason.SAME_EMAIL)
        val low = components(contacts.size, shared.filter { (a, b, _) -> names[a] == null || names[a] != names[b] })
            .map { (members, reasons) -> DuplicateGroup(members.map { contacts[it] }, reasons, DuplicateConfidence.LOW) }
        return high + low
    }

    /** Pares (i, j) con alguna clave en común, i < j. */
    private fun links(keys: List<Collection<String>>, reason: DuplicateReason): List<Triple<Int, Int, DuplicateReason>> {
        val firstWith = HashMap<String, Int>()
        val out = mutableListOf<Triple<Int, Int, DuplicateReason>>()
        keys.forEachIndexed { i, ks ->
            for (k in ks.toSet()) {
                val j = firstWith.putIfAbsent(k, i) ?: continue
                if (j != i) out += Triple(j, i, reason)
            }
        }
        return out
    }

    /** Componentes conexas de [links]: solo las de 2 o más, en el orden de la lista original. */
    private fun components(size: Int, links: List<Triple<Int, Int, DuplicateReason>>): List<Pair<List<Int>, Set<DuplicateReason>>> {
        val parent = IntArray(size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var j = i
            while (parent[j] != r) { val next = parent[j]; parent[j] = r; j = next }
            return r
        }
        for ((a, b, _) in links) {
            val (ra, rb) = root(a) to root(b)
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
        }
        val reasons = HashMap<Int, MutableSet<DuplicateReason>>()
        for ((a, _, r) in links) reasons.getOrPut(root(a)) { mutableSetOf() } += r
        return (0 until size).groupBy(::root).filterValues { it.size > 1 }.map { (r, members) -> members to reasons[r].orEmpty().toSet() }
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
