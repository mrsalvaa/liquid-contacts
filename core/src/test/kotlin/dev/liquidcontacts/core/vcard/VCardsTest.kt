// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.vcard

import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.model.ContactEvent
import dev.liquidcontacts.core.model.Email
import dev.liquidcontacts.core.model.EventKind
import dev.liquidcontacts.core.model.LabelKind
import dev.liquidcontacts.core.model.Name
import dev.liquidcontacts.core.model.Phone
import dev.liquidcontacts.core.model.PostalAddress
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Todos los datos son inventados.
class VCardsTest {
    private fun ok(text: String, limits: VCardLimits = VCardLimits()): VCardImport.Ok = ok(text.toByteArray(Charsets.UTF_8), limits)

    private fun ok(bytes: ByteArray, limits: VCardLimits = VCardLimits()): VCardImport.Ok {
        val r = VCards.read(bytes, limits)
        assertIs<VCardImport.Ok>(r)
        return r
    }

    /** PNG de 1×1 válido. */
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")

    @Test
    fun vcard30ConLineasPartidasYTildes() {
        val r = ok(
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ángela Ríos\r\nN:Ríos;Ángela;;;\r\n" +
                "TEL;TYPE=CELL:+54 9 11 2345-6789\r\nTEL;TYPE=WORK,VOICE:011 4321-0000\r\n" +
                "EMAIL;TYPE=INTERNET,HOME:angela@example.com\r\n" +
                "NOTE:Una nota bastante larga que el programa que la exportó partió en dos lí\r\n neas al llegar a 75 caracteres.\r\n" +
                "ADR;TYPE=HOME:;;Av. Siempre Viva 742;Córdoba;Córdoba;5000;Argentina\r\n" +
                "CATEGORIES:Familia,Club\r\nEND:VCARD\r\n",
        )
        val c = r.contacts.single()
        assertEquals("Ángela", c.name.given)
        assertEquals("Ríos", c.name.family)
        assertEquals("Ángela Ríos", c.displayName)
        assertEquals(listOf(Phone("+54 9 11 2345-6789", LabelKind.MOBILE), Phone("011 4321-0000", LabelKind.WORK)), c.phones)
        assertEquals(listOf(Email("angela@example.com", LabelKind.HOME)), c.emails)
        assertEquals("Una nota bastante larga que el programa que la exportó partió en dos líneas al llegar a 75 caracteres.", c.note)
        assertEquals(PostalAddress("Av. Siempre Viva 742", "Córdoba", "Córdoba", "5000", "Argentina", LabelKind.HOME), c.addresses.single())
        assertEquals(setOf("Familia", "Club"), c.groups)
    }

    @Test
    fun vcard21QuotedPrintableUtf8() {
        val r = ok(
            "BEGIN:VCARD\r\nVERSION:2.1\r\n" +
                "N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Mu=C3=B1oz;Mar=C3=ADa Jos=C3=A9;;;\r\n" +
                "FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Mar=C3=ADa Jos=C3=A9 Mu=C3=B1oz\r\n" +
                "NOTE;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Primera parte con =C3=B1 y una l=C3=ADnea partida con=\r\n soft break\r\n" +
                "TEL;CELL:0351 15 234-5678\r\nEND:VCARD\r\n",
        )
        val c = r.contacts.single()
        assertEquals("Muñoz", c.name.family)
        assertEquals("María José", c.name.given)
        assertContains(c.note!!, "Primera parte con ñ")
        assertEquals("0351 15 234-5678", c.phones.single().number)
    }

    @Test
    fun vcard21EnLatin1() {
        val text = "BEGIN:VCARD\r\nVERSION:2.1\r\nN;CHARSET=ISO-8859-1:Peña;Iñaki;;;\r\nFN;CHARSET=ISO-8859-1:Iñaki Peña\r\nEND:VCARD\r\n"
        val c = ok(text.toByteArray(Charsets.ISO_8859_1)).contacts.single()
        assertEquals("Peña", c.name.family)
        assertEquals("Iñaki", c.name.given)
    }

    @Test
    fun utf8ConBom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ñandú\r\nEND:VCARD\r\n".toByteArray()
        assertEquals("Ñandú", ok(bytes).contacts.single().displayName)
    }

    @Test
    fun lfSinCr() {
        assertEquals("Ana", ok("BEGIN:VCARD\nVERSION:3.0\nFN:Ana\nEND:VCARD\n").contacts.single().displayName)
    }

    @Test
    fun fotoDentroDelArchivo30y40() {
        val b64 = Base64.getEncoder().encodeToString(png)
        val v3 = ok("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ana\r\nPHOTO;ENCODING=b;TYPE=PNG:$b64\r\nEND:VCARD\r\n")
        assertTrue(v3.contacts.single().photo!!.contentEquals(png))
        val v4 = ok("BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Ana\r\nPHOTO:data:image/png;base64,$b64\r\nEND:VCARD\r\n")
        assertTrue(v4.contacts.single().photo!!.contentEquals(png))
        assertEquals(0, v4.photosDropped)
    }

    @Test
    fun fotoComoEnlaceNuncaSeDescarga() {
        val r = ok("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ana\r\nPHOTO;VALUE=uri:https://example.com/ana.jpg\r\nEND:VCARD\r\n")
        assertNull(r.contacts.single().photo)
        assertEquals(1, r.photosDropped)
        val r4 = ok("BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Ana\r\nPHOTO:http://example.com/ana.jpg\r\nEND:VCARD\r\n")
        assertNull(r4.contacts.single().photo)
        assertEquals(1, r4.photosDropped)
    }

    @Test
    fun fotoDemasiadoGrandeSeDescartaPeroElContactoQueda() {
        val big = ByteArray(5000) { (it % 251).toByte() }
        val b64 = Base64.getEncoder().encodeToString(big)
        val r = ok("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ana\r\nPHOTO;ENCODING=b;TYPE=JPEG:$b64\r\nEND:VCARD\r\n", VCardLimits(maxPhotoBytes = 4000))
        assertEquals("Ana", r.contacts.single().displayName)
        assertNull(r.contacts.single().photo)
        assertEquals(1, r.photosDropped)
    }

    @Test
    fun cumpleanosConYSinAnio() {
        val r = ok(
            "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:A\r\nBDAY:--0314\r\nEND:VCARD\r\n" +
                "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:B\r\nBDAY;X-APPLE-OMIT-YEAR=1604:1604-03-14\r\nEND:VCARD\r\n" +
                "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:C\r\nBDAY:1990-03-14\r\nEND:VCARD\r\n" +
                "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:D\r\nBDAY:19900314\r\nEND:VCARD\r\n" +
                "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:E\r\nBDAY:no es una fecha\r\nEND:VCARD\r\n",
        )
        val events = r.contacts.associate { it.displayName to it.events }
        assertEquals(listOf(ContactEvent(3, 14, null)), events["A"])
        assertEquals(listOf(ContactEvent(3, 14, null)), events["B"])
        assertEquals(listOf(ContactEvent(3, 14, 1990)), events["C"])
        assertEquals(listOf(ContactEvent(3, 14, 1990)), events["D"])
        // Una fecha ilegible no impide importar el contacto.
        assertEquals(emptyList(), events["E"])
    }

    @Test
    fun fechasConNombreDeApple() {
        val c = ok(
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ana\r\n" +
                "item1.X-ABDATE:2015-06-20\r\nitem1.X-ABLabel:_\$!<Anniversary>!\$_\r\n" +
                "item2.X-ABDATE:1604-09-01\r\nitem2.X-ABLabel:Santo\r\nEND:VCARD\r\n",
        ).contacts.single()
        assertEquals(
            listOf(ContactEvent(6, 20, 2015, EventKind.ANNIVERSARY), ContactEvent(9, 1, null, EventKind.CUSTOM, "Santo")),
            c.events,
        )
    }

    @Test
    fun archivoQueNoEsVcard() {
        assertEquals(VCardImport.Invalid, VCards.read("hola, esto no es un vCard".toByteArray()))
        assertEquals(VCardImport.Invalid, VCards.read(ByteArray(0)))
        assertEquals(VCardImport.Invalid, VCards.read(ByteArray(300) { 0x00 }))
    }

    @Test
    fun tarjetasRotasOVaciasSeSaltean() {
        val r = ok(
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ana\r\nEND:VCARD\r\n" +
                "BEGIN:VCARD\r\nVERSION:3.0\r\nN:;;;;\r\nEND:VCARD\r\n" +
                "BEGIN:VCARD\r\nVERSION:3.0\r\nlínea sin dos puntos\r\nTEL;TYPE=CELL\r\nFN:Beto\r\nEND:VCARD\r\n" +
                "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Carla\r\n",
        )
        assertEquals(listOf("Ana", "Beto", "Carla"), r.contacts.map { it.displayName })
        assertEquals(1, r.skipped)
    }

    @Test
    fun limites() {
        val many = (1..30).joinToString("") { "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Contacto $it\r\nEND:VCARD\r\n" }
        val r = ok(many, VCardLimits(maxContacts = 10))
        assertEquals(10, r.contacts.size)
        assertTrue(r.truncated)
        assertFalse(ok(many).truncated)
        assertIs<VCardImport.TooLarge>(VCards.read(many.toByteArray(), VCardLimits(maxBytes = 100)))
    }

    // ---------------------------------------------------------------- ida y vuelta

    private val full = Contact(
        name = Name(given = "María José", family = "Muñoz", prefix = "Dra.", middle = "Ana"),
        nickname = "Majo",
        organization = "Taller Sur",
        jobTitle = "Directora",
        phones = listOf(
            Phone("+54 9 11 2345-6789", LabelKind.MOBILE, primary = true),
            Phone("011 4321-0000", LabelKind.WORK),
            Phone("011 4321-0001", LabelKind.FAX_WORK),
        ),
        emails = listOf(Email("majo@example.com", LabelKind.HOME), Email("mmunoz@example.com", LabelKind.WORK)),
        addresses = listOf(PostalAddress("Calle Falsa 123", "Rosario", "Santa Fe", "2000", "Argentina", LabelKind.WORK)),
        events = listOf(
            ContactEvent(3, 14, 1990, EventKind.BIRTHDAY),
            ContactEvent(6, 20, null, EventKind.ANNIVERSARY),
            ContactEvent(9, 1, 2020, EventKind.CUSTOM, "Recibida"),
        ),
        websites = listOf("https://example.com/majo"),
        note = "Nota con ñ, tildes (áéíóú) y una línea\nnueva.",
        groups = setOf("Trabajo", "Familia"),
        photo = png,
    )

    @Test
    fun idaYVuelta30() = roundTrip(VCardFormat.V3)

    @Test
    fun idaYVuelta40() = roundTrip(VCardFormat.V4)

    private fun roundTrip(version: VCardFormat) {
        val text = VCards.write(listOf(full), version)
        assertFalse("PRODID" in text, "no tiene que identificar a la app")
        val back = ok(text).contacts.single()
        assertEquals(full.name.given, back.name.given)
        assertEquals(full.name.family, back.name.family)
        assertEquals(full.name.prefix, back.name.prefix)
        assertEquals(full.name.middle, back.name.middle)
        assertEquals(full.nickname, back.nickname)
        assertEquals(full.organization, back.organization)
        assertEquals(full.jobTitle, back.jobTitle)
        assertEquals(full.phones, back.phones)
        assertEquals(full.emails, back.emails)
        assertEquals(full.addresses, back.addresses)
        assertEquals(full.websites, back.websites)
        assertEquals(full.note, back.note)
        assertEquals(full.groups, back.groups)
        assertTrue(back.photo!!.contentEquals(png))
        assertEquals(ContactEvent(3, 14, 1990, EventKind.BIRTHDAY), back.events.first { it.kind == EventKind.BIRTHDAY })
        val anniversary = back.events.first { it.month == 6 }
        assertEquals(20, anniversary.day)
        assertNull(anniversary.year)
        assertEquals(ContactEvent(9, 1, 2020, EventKind.CUSTOM, "Recibida"), back.events.first { it.month == 9 })
    }

    @Test
    fun exportarSinFoto() {
        val text = VCards.write(listOf(full), includePhoto = false)
        assertFalse("PHOTO" in text)
        assertNull(ok(text).contacts.single().photo)
    }

    @Test
    fun exportarVarios() {
        val text = VCards.write(listOf(Contact(name = Name(display = "Ana")), Contact(name = Name(display = "Beto"))))
        assertEquals(listOf("Ana", "Beto"), ok(text).contacts.map { it.displayName })
    }

    @Test
    fun loQueEntraSeLimpia() {
        val c = ok(
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ana\u202E Falsa\r\nTEL:sin digitos\r\nTEL:011 4321-0000\r\n" +
                "EMAIL:no es un email\r\nEMAIL:ana@example.com\r\nURL:javascript:alert(1)\r\nURL:https://example.com\r\n" +
                "NOTE:${"x".repeat(30_000)}\r\nEND:VCARD\r\n",
        ).contacts.single()
        assertEquals("Ana Falsa", c.displayName)
        assertEquals(listOf("011 4321-0000"), c.phones.map { it.number })
        assertEquals(listOf("ana@example.com"), c.emails.map { it.address })
        assertEquals(listOf("https://example.com"), c.websites)
        assertEquals(20_000, c.note!!.length)
    }
}
