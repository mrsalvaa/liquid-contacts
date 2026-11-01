// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.external

import dev.liquidcontacts.core.model.LabelKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Datos inventados.
class ExternalInputTest {
    private fun ref(vararg s: String, scheme: String = "content", auth: String = "com.android.contacts") =
        ExternalInput.contactRef(scheme, auth, s.toList())

    @Test
    fun urisValidasDeLaAgenda() {
        assertEquals(ContactRef.Id(42), ref("contacts", "42"))
        assertEquals(ContactRef.Raw(7), ref("raw_contacts", "7"))
        assertEquals(ContactRef.Lookup("0r1-ABC"), ref("contacts", "lookup", "0r1-ABC"))
        assertEquals(ContactRef.Lookup("0r1-ABC"), ref("contacts", "lookup", "0r1-ABC", "42"))
    }

    @Test
    fun urisRechazadas() {
        assertNull(ref("contacts", "42", scheme = "file"))
        assertNull(ref("contacts", "42", scheme = "http"))
        assertNull(ref("contacts", "42", auth = "com.evil.provider"))
        assertNull(ref("contacts"))
        assertNull(ref("contacts", "-1"))
        assertNull(ref("contacts", "0"))
        assertNull(ref("contacts", "42 OR 1=1"))
        assertNull(ref("contacts", "99999999999999999999999"))
        assertNull(ref("contacts", "42", "photo"))
        assertNull(ref("contacts", "lookup"))
        assertNull(ref("contacts", "lookup", ""))
        assertNull(ref("contacts", "lookup", "abc", "no-es-id"))
        assertNull(ref("contacts", "lookup", "abc\u0000"))
        assertNull(ref("contacts", "lookup", "x".repeat(3000)))
        assertNull(ref("data", "42"))
        assertNull(ref("raw_contacts", "1", "data"))
    }

    @Test
    fun vcfSoloPorContentYDeOtraApp() {
        val own = "dev.liquidcontacts.files"
        assertTrue(ExternalInput.vcfUriAllowed("content", "com.android.providers.downloads.documents", own))
        assertFalse(ExternalInput.vcfUriAllowed("file", null, own))
        assertFalse(ExternalInput.vcfUriAllowed("https", "example.com", own))
        assertFalse(ExternalInput.vcfUriAllowed("content", own, own))
        assertFalse(ExternalInput.vcfUriAllowed("content", "com.android.contacts", own))
        assertFalse(ExternalInput.vcfUriAllowed("content", null, own))
    }

    @Test
    fun extrasLimpios() {
        val c = ExternalInput.contact(
            ExternalFields(
                name = "  Ana‮\u0000 Torres ",
                company = "x".repeat(5000),
                notes = "línea\r\notra\u0007",
                phones = listOf("011 4321-0000" to LabelKind.WORK, "sin numeros" to null, null to null),
                emails = listOf("no es email" to null, "ana@example.com" to null),
                websites = listOf("javascript:alert(1)", "https://example.com"),
            ),
        )!!
        assertEquals("Ana Torres", c.displayName)
        assertEquals(1000, c.organization!!.length)
        assertEquals("línea\notra", c.note)
        assertEquals(listOf("011 4321-0000"), c.phones.map { it.number })
        assertEquals(LabelKind.WORK, c.phones.single().kind)
        assertEquals(listOf("ana@example.com"), c.emails.map { it.address })
        assertEquals(listOf("https://example.com"), c.websites)
    }

    @Test
    fun cantidadesLimitadasYVacios() {
        val many = ExternalInput.contact(ExternalFields(phones = (1..500).map { "011 4000-%04d".format(it) to null }))!!
        assertEquals(20, many.phones.size)
        assertNull(ExternalInput.contact(ExternalFields()))
        assertNull(ExternalInput.contact(ExternalFields(name = "  \u0000 ", phones = listOf("abc" to null))))
    }
}
