// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.vcard

import dev.liquidcontacts.core.dupes.DuplicateConfidence
import dev.liquidcontacts.core.dupes.Duplicates
import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.phone.PhoneNumbers

/** Qué hacer con un contacto del archivo. */
enum class ImportAction { NEW, SKIP, MERGE }

/** Contactos de la agenda que parecen ser el importado. Confianza como en los duplicados. */
data class ImportMatch(val existing: List<Contact>, val confidence: DuplicateConfidence)

/**
 * Busca si un contacto del archivo ya está en la agenda, con las mismas reglas exactas que los
 * duplicados: mismo nombre (alta) o mismo número completo o email (baja). Nunca por los últimos 8
 * dígitos. Lo que coincide se omite por defecto y solo se une si el usuario lo pide.
 */
class ImportMatcher(private val numbers: PhoneNumbers, existing: List<Contact>) {
    private val byName = HashMap<String, MutableList<Contact>>()
    private val byContact = HashMap<String, MutableSet<Contact>>()

    init {
        for (c in existing) {
            Duplicates.nameKey(c)?.let { byName.getOrPut(it) { ArrayList() }.add(c) }
            keys(c).forEach { byContact.getOrPut(it) { LinkedHashSet() }.add(c) }
        }
    }

    private fun keys(c: Contact): List<String> =
        c.phones.mapNotNull { numbers.exact(it.number) } + c.emails.map { Duplicates.emailKey(it.address) }.filter { it.isNotEmpty() }

    fun match(c: Contact): ImportMatch? {
        Duplicates.nameKey(c)?.let { key -> byName[key]?.let { return ImportMatch(it, DuplicateConfidence.HIGH) } }
        val shared = keys(c).flatMap { byContact[it].orEmpty() }.distinct()
        return if (shared.isEmpty()) null else ImportMatch(shared, DuplicateConfidence.LOW)
    }

    /** Acción inicial: nuevo si no está en la agenda; si ya está, se omite. */
    fun defaultAction(c: Contact): ImportAction = if (match(c) == null) ImportAction.NEW else ImportAction.SKIP
}
