// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.vcard

import dev.liquidcontacts.core.dupes.DuplicateConfidence
import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.model.Email
import dev.liquidcontacts.core.model.Name
import dev.liquidcontacts.core.model.Phone
import dev.liquidcontacts.core.phone.PhoneNumbers
import dev.liquidcontacts.core.text.Clean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Datos inventados.
class ImportMatcherTest {
    private fun c(name: String, phone: String? = null, email: String? = null) = Contact(
        name = Name(display = name),
        phones = listOfNotNull(phone).map { Phone(it) },
        emails = listOfNotNull(email).map { Email(it) },
    )

    private val ana = c("Ana Pérez", "011 15 2345-6789", "ana@example.com")
    private val m = ImportMatcher(PhoneNumbers("AR"), listOf(ana, c("Beto", "15 9999-0000")))

    @Test
    fun mismoNombreEsConfianzaAlta() {
        assertEquals(ImportMatch(listOf(ana), DuplicateConfidence.HIGH), m.match(c("ana perez")))
        assertEquals(ImportAction.SKIP, m.defaultAction(c("ANA PÉREZ")))
    }

    @Test
    fun mismoNumeroOEmailEsConfianzaBaja() {
        assertEquals(ImportMatch(listOf(ana), DuplicateConfidence.LOW), m.match(c("Anita", "+54 9 11 2345 6789")))
        assertEquals(ImportMatch(listOf(ana), DuplicateConfidence.LOW), m.match(c("A. P.", email = "ANA@example.com")))
    }

    @Test
    fun sinCoincidenciaEsNuevo() {
        assertNull(m.match(c("Carla", "011 4321-0000")))
        assertEquals(ImportAction.NEW, m.defaultAction(c("Carla")))
    }

    @Test
    fun laCoincidenciaAproximadaNoCuenta() {
        // Mismos últimos 8 dígitos que un número sin código de área: puede ser otra persona.
        assertNull(m.match(c("Carla", "+54 9 11 9999-0000")))
    }

    @Test
    fun limpiezaDeTextoExterno() {
        assertEquals("Ana", Clean.line(" A\u0000na‮ "))
        assertNull(Clean.line("  \n "))
        assertEquals(10, Clean.line("x".repeat(50), 10)!!.length)
        assertEquals("a\nb", Clean.multiline("a\r\nb\u0007"))
        assertEquals("+54 11", Clean.phone("tel:+54 11"))
        assertNull(Clean.phone("sin numeros"))
        assertEquals("a@b.com", Clean.email("mailto:a@b.com"))
        for (bad in listOf("a b@c.com", "@b.com", "a@", "a@@b", "ab")) assertNull(Clean.email(bad), bad)
        assertEquals("example.com/a", Clean.url("example.com/a"))
        assertEquals("https://e.com", Clean.url("https://e.com"))
        for (bad in listOf("javascript:alert(1)", "intent:x", "file:///etc", "content://x")) assertNull(Clean.url(bad), bad)
    }
}
