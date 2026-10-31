// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.dupes

import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.model.ContactEvent
import dev.liquidcontacts.core.model.Email
import dev.liquidcontacts.core.model.Name
import dev.liquidcontacts.core.model.Phone
import dev.liquidcontacts.core.phone.PhoneNumbers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Datos inventados.
class DuplicatesTest {
    private val d = Duplicates(PhoneNumbers("AR"))

    private fun c(id: Long, name: String, phones: List<String> = emptyList(), emails: List<String> = emptyList()) =
        Contact(id = id, name = Name(display = name), phones = phones.map { Phone(it) }, emails = emails.map { Email(it) })

    @Test
    fun mismoNombreSinImportarTildesNiEspacios() {
        val g = d.find(listOf(c(1, "José  Pérez"), c(2, "jose perez"), c(3, "José Pereira")))
        assertEquals(1, g.size)
        assertEquals(listOf(1L, 2L), g[0].contacts.map { it.id })
        assertEquals(setOf(DuplicateReason.SAME_NAME), g[0].reasons)
        assertEquals(DuplicateConfidence.HIGH, g[0].confidence)
    }

    @Test
    fun mismoNumeroEnDistintoFormato() {
        val g = d.find(listOf(c(1, "Ana", listOf("011 15 2345-6789")), c(2, "Anita", listOf("+54 9 11 2345 6789"))))
        assertEquals(1, g.size)
        assertEquals(setOf(DuplicateReason.SAME_PHONE), g[0].reasons)
        assertEquals(DuplicateConfidence.LOW, g[0].confidence)
    }

    @Test
    fun mismoNombreYMismoNumeroEsConfianzaAltaYNoSeRepiteEnBaja() {
        val g = d.find(listOf(c(1, "Ana Torres", listOf("011 4321-0000")), c(2, "ana torres", listOf("+54 11 4321 0000"))))
        assertEquals(1, g.size)
        assertEquals(DuplicateConfidence.HIGH, g.single().confidence)
    }

    @Test
    fun laCoincidenciaAproximadaNuncaMarcaDuplicados() {
        // Mismos últimos 8 dígitos, pero uno sin código de área: puede ser otra persona.
        val g = d.find(listOf(c(1, "Ana", listOf("15 2345-6789")), c(2, "Beto", listOf("+54 9 11 2345 6789"))))
        assertTrue(g.isEmpty())
        // Y dos números incompletos iguales tampoco (no hay clave exacta).
        assertTrue(d.find(listOf(c(1, "Ana", listOf("15 2345-6789")), c(2, "Beto", listOf("15 2345-6789")))).isEmpty())
    }

    @Test
    fun mismoEmailSinImportarMayusculas() {
        val g = d.find(listOf(c(1, "Ana", emails = listOf("Ana@Ejemplo.com ")), c(2, "A. Torres", emails = listOf("ana@ejemplo.com"))))
        assertEquals(setOf(DuplicateReason.SAME_EMAIL), g.single().reasons)
    }

    @Test
    fun cadenaDentroDeLaConfianzaBaja() {
        val g = d.find(
            listOf(
                c(1, "Ana Torres", listOf("011 4321-0000")),
                c(2, "Anita", listOf("+54 11 4321 0000"), listOf("ana@ejemplo.com")),
                c(3, "A. T.", emails = listOf("ANA@ejemplo.com")),
                c(4, "Otro"),
            ),
        )
        assertEquals(listOf(1L, 2L, 3L), g.single().contacts.map { it.id })
        assertEquals(setOf(DuplicateReason.SAME_PHONE, DuplicateReason.SAME_EMAIL), g.single().reasons)
        assertEquals(DuplicateConfidence.LOW, g.single().confidence)
    }

    @Test
    fun noSeEncadenaEntreConfianzasDistintas() {
        // A y B se llaman igual; B comparte el número con C (otro nombre).
        val g = d.find(
            listOf(
                c(1, "Ana Torres"),
                c(2, "Ana Torres", listOf("011 4321-0000")),
                c(3, "Taller Sur", listOf("+54 11 4321 0000")),
            ),
        )
        assertEquals(2, g.size)
        val high = g.single { it.confidence == DuplicateConfidence.HIGH }
        val low = g.single { it.confidence == DuplicateConfidence.LOW }
        assertEquals(listOf(1L, 2L), high.contacts.map { it.id })
        assertEquals(listOf(2L, 3L), low.contacts.map { it.id })
        assertTrue(g.none { it.contacts.size == 3 })
    }

    @Test
    fun vistaPreviaSinUnoDelGrupo() {
        val group = d.find(listOf(c(1, "Ana", listOf("011 4321-0000")), c(2, "ana"), c(3, "ANA", emails = listOf("x@ejemplo.com")))).single()
        // La interfaz saca al 2 antes de confirmar: la unión solo junta 1 y 3.
        val m = d.merge(group.contacts.filter { it.id != 2L })
        assertEquals(1L, m.id)
        assertEquals(listOf("011 4321-0000"), m.phones.map { it.number })
        assertEquals(listOf("x@ejemplo.com"), m.emails.map { it.address })
    }

    @Test
    fun sinNombreNoSonDuplicadosPorNombre() {
        assertTrue(d.find(listOf(c(1, ""), c(2, " "))).isEmpty())
    }

    @Test
    fun unirJuntaTodoSinRepetir() {
        val a = Contact(
            id = 1,
            name = Name(display = "Ana"),
            phones = listOf(Phone("011 15 2345-6789", primary = true)),
            emails = listOf(Email("ana@ejemplo.com")),
            events = listOf(ContactEvent(3, 14)),
            note = "Vecina",
            groups = setOf("Familia"),
        )
        val b = Contact(
            id = 2,
            name = Name(given = "Ana", family = "Torres"),
            organization = "Taller Sur",
            phones = listOf(Phone("+54 9 11 2345 6789", primary = true), Phone("011 4321-0000")),
            emails = listOf(Email("ANA@ejemplo.com"), Email("ana.torres@ejemplo.com")),
            events = listOf(ContactEvent(3, 14, 1990)),
            note = "Vecina",
            groups = setOf("Trabajo"),
            starred = true,
            photo = byteArrayOf(1, 2, 3),
        )
        val m = d.merge(listOf(a, b))
        assertEquals(1L, m.id)
        assertEquals("Ana Torres", m.displayName)
        assertEquals("Taller Sur", m.organization)
        assertEquals(listOf("011 15 2345-6789", "011 4321-0000"), m.phones.map { it.number })
        assertEquals(1, m.phones.count { it.primary })
        assertEquals(listOf("ana@ejemplo.com", "ana.torres@ejemplo.com"), m.emails.map { it.address })
        assertEquals(listOf(ContactEvent(3, 14, 1990)), m.events)
        assertEquals("Vecina", m.note)
        assertEquals(setOf("Familia", "Trabajo"), m.groups)
        assertTrue(m.starred)
        assertTrue(m.photo!!.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun eventoInvalidoSeRechaza() {
        assertTrue(runCatching { ContactEvent(2, 30) }.isFailure)
        assertTrue(runCatching { ContactEvent(2, 29, 2023) }.isFailure)
        assertTrue(runCatching { ContactEvent(2, 29) }.isSuccess)
        assertTrue(runCatching { ContactEvent(2, 29, 2024) }.isSuccess)
    }
}
