// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.vcard

import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.model.ContactEvent
import dev.liquidcontacts.core.model.EventKind
import dev.liquidcontacts.core.model.LabelKind
import dev.liquidcontacts.core.model.Name
import dev.liquidcontacts.core.model.Phone
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ninguna vCard pierde lo que la app no modela al editar. Datos inventados. Las de Apple, Nextcloud
 * y la cadena [davx5] siguen la forma que generan esos clientes. La vCard real es el recurso
 * vcard/davx5-4.5.21.vcf: la subió DAVx5 4.5.21-ose a un Radicale local desde un emulador, con un
 * contacto inventado, y se guardó tal como la devuelve el servidor (Radicale reordena las
 * propiedades y pasa los nombres a mayúsculas; por eso la cadena [davx5] cubre también X-ABLabel).
 */
class VCardRoundTripTest {
    private val davx5 = """
        BEGIN:VCARD
        VERSION:3.0
        PRODID:+//IDN bitfire.at//DAVx5/4.4.2-ose ez-vcard/0.12.1
        UID:4f0e6c2a-1111-4222-8333-abcdefabcdef
        FN:Dra. María Inés Rojas
        N:Rojas;María;Inés;Dra.;
        X-PHONETIC-FIRST-NAME:Maria
        X-PHONETIC-LAST-NAME:Rojas
        NICKNAME:Mari
        ORG:Taller Sur;Ventas
        TITLE:Directora
        ROLE:Compras
        item1.TEL;TYPE=VOICE:011 4000-2000
        item1.X-ABLabel:Portería
        TEL;TYPE=CELL,PREF:+54 9 11 4000-2001
        EMAIL;TYPE=WORK:mari@example.com
        item2.EMAIL:otra@example.com
        item2.X-ABLabel:Club
        IMPP;X-SERVICE-TYPE=XMPP:xmpp:mari@example.com
        X-SIP:mari@sip.example.com
        item3.X-ABRELATEDNAMES:Juan Rojas
        item3.X-ABLabel:_${'$'}!<Spouse>!${'$'}_
        URL:https://example.com/mari
        BDAY:1980-02-03
        NOTE:Nota original
        CATEGORIES:Familia,Club
        X-ABADR:ar
        REV:20240101T000000Z
        END:VCARD
    """.trimIndent().replace("\n", "\r\n") + "\r\n"

    private val apple = """
        BEGIN:VCARD
        VERSION:3.0
        PRODID:-//Apple Inc.//iOS 17.0//EN
        N:Paz;Luz;;;
        FN:Luz Paz
        item1.EMAIL;type=INTERNET;type=pref:luz@example.com
        item1.X-ABLabel:_${'$'}!<Other>!${'$'}_
        TEL;type=CELL;type=VOICE;type=pref:+54 9 351 400-0000
        item2.ADR;type=HOME;type=pref:;;Calle 1;Córdoba;;5000;Argentina
        item2.X-ABADR:ar
        X-ABShowAs:COMPANY
        X-SOCIALPROFILE;type=twitter:https://example.com/luz
        UID:apple-uid-1
        END:VCARD
    """.trimIndent().replace("\n", "\r\n") + "\r\n"

    private val nextcloudV4 = """
        BEGIN:VCARD
        VERSION:4.0
        PRODID:-//Sabre//Sabre VObject 4.5.0//EN
        UID:nc-uid-1
        FN:Sol Vega
        N:Vega;Sol;;;
        TEL;TYPE=cell:+54 11 4000-3000
        GENDER:F
        LANG;PREF=1:es
        TZ:America/Argentina/Buenos_Aires
        GEO:geo:-34.6,-58.4
        RELATED;TYPE=friend:urn:uuid:aaaa
        CLOUD:sol@cloud.example.com
        X-MANAGERSNAME:Ana
        ANNIVERSARY:20100615
        END:VCARD
    """.trimIndent().replace("\n", "\r\n") + "\r\n"

    private val davx5Real = assertNotNull(javaClass.getResource("/vcard/davx5-4.5.21.vcf")).readText()

    private fun props(text: String) = text.replace("\r\n ", "").lines().filter { it.isNotBlank() }

    /** Cada línea de [unknown] sigue estando, igual, en [text]. */
    private fun assertKept(text: String, unknown: List<String>) {
        val lines = props(text).map { it.lowercase() }
        unknown.forEach { assertTrue(lines.any { l -> l == it.lowercase() }, "se perdió: $it\n$text") }
    }

    @Test
    fun davx5ConservaTodoAlEditarLaNota() {
        val c = assertNotNull(VCards.contactOf(davx5))
        val out = VCards.update(davx5, c.copy(note = "Nota nueva"), "x")
        assertContains(out, "NOTE:Nota nueva")
        assertFalse("Nota original" in out)
        assertEquals("4f0e6c2a-1111-4222-8333-abcdefabcdef", VCards.uidOf(out))
        assertKept(
            out,
            listOf(
                "X-PHONETIC-FIRST-NAME:Maria", "X-PHONETIC-LAST-NAME:Rojas", "ORG:Taller Sur;Ventas", "ROLE:Compras",
                "item1.TEL;TYPE=VOICE:011 4000-2000", "item1.X-ABLabel:Portería", "item2.EMAIL:otra@example.com", "item2.X-ABLabel:Club",
                "IMPP;X-SERVICE-TYPE=XMPP:xmpp:mari@example.com", "X-SIP:mari@sip.example.com", "item3.X-ABRELATEDNAMES:Juan Rojas",
                "X-ABADR:ar", "FN:Dra. María Inés Rojas", "CATEGORIES:Familia,Club",
            ),
        )
    }

    @Test
    fun davx5RealConservaCadaLineaAlEditarLaNota() {
        val c = assertNotNull(VCards.contactOf(davx5Real))
        val out = VCards.update(davx5Real, c.copy(note = "Nota nueva"), "x")
        assertContains(out, "NOTE:Nota nueva")
        assertEquals("1af098d9-3465-44e1-bd3c-04d86bf0a1b1", VCards.uidOf(out))
        // Todo lo demás, línea por línea, como lo dejó DAVx5: solo cambian la nota y la fecha de revisión.
        assertKept(out, props(davx5Real).filterNot { it.startsWith("NOTE") || it.startsWith("REV") })
    }

    @Test
    fun davx5RealSeLeeEntero() {
        val c = assertNotNull(VCards.contactOf(davx5Real))
        assertEquals("Ñuke", c.name.given)
        assertEquals("Peña Quiroga", c.name.family)
        assertEquals(Phone("0351 400 5678", LabelKind.CUSTOM, "Guardia ñandú"), c.phones.first { it.number.startsWith("0351") })
        assertEquals("Facturación", c.emails.first { it.address == "otra@example.org" }.label)
        assertEquals("Taller Sur", c.organization)
        assertEquals("Nota con acento, coma; y punto y coma", c.note)
        assertEquals(EventKind.BIRTHDAY, c.events.first { it.year == 1980 }.kind)
        assertEquals(EventKind.ANNIVERSARY, c.events.first { it.year == 2005 }.kind)
    }

    @Test
    fun davx5RealCambiarUnTelefonoNoTocaLoDemas() {
        val c = assertNotNull(VCards.contactOf(davx5Real))
        val out = VCards.update(davx5Real, c.copy(phones = listOf(Phone("011 4000-9999", LabelKind.HOME))), "x")
        assertFalse("0351 400 5678" in out)
        assertFalse("Guardia ñandú" in out, "etiqueta huérfana\n$out")
        assertKept(out, props(davx5Real).filterNot { it.startsWith("REV") || "TEL" in it || it.startsWith("item1.") })
    }

    @Test
    fun elAniversarioEscritoEn30SeLeeComoAniversario() {
        val c = assertNotNull(VCards.contactOf(VCards.write(listOf(Contact(name = Name(given = "Ana"), events = listOf(ContactEvent(11, 2, 2005, EventKind.ANNIVERSARY)))), VCardFormat.V3)))
        assertEquals(listOf(ContactEvent(11, 2, 2005, EventKind.ANNIVERSARY)), c.events)
    }

    @Test
    fun laPapeleraDevuelveLaTarjetaConOtroUidYSinPerderNada() {
        val sinUid = VCards.withoutUid(davx5Real)
        assertFalse("1af098d9" in sinUid)
        val out = VCards.update(sinUid, assertNotNull(VCards.contactOf(sinUid)), "uid-nuevo")
        assertEquals("uid-nuevo", VCards.uidOf(out))
        assertKept(out, props(davx5Real).filterNot { it.startsWith("UID") || it.startsWith("REV") })
    }

    @Test
    fun laCopiaDeLaPapeleraConservaTodosLosDatos() {
        val c = assertNotNull(VCards.contactOf(davx5Real)).copy(groups = setOf("Familia", "Club"))
        val copia = assertNotNull(VCards.contactOf(VCards.write(listOf(c), VCardFormat.V3, includePhoto = false)))
        assertEquals(c.name, copia.name)
        assertEquals(c.phones, copia.phones)
        assertEquals(c.emails, copia.emails)
        assertEquals(c.addresses, copia.addresses)
        assertEquals(c.events, copia.events)
        assertEquals(c.groups, copia.groups)
        assertEquals(c.note, copia.note)
        assertEquals(c.organization, copia.organization)
        assertEquals(c.jobTitle, copia.jobTitle)
        assertEquals(c.nickname, copia.nickname)
        assertEquals(c.websites, copia.websites)
    }

    @Test
    fun davx5LaEtiquetaPropiaSeLee() {
        val c = assertNotNull(VCards.contactOf(davx5))
        assertEquals(Phone("011 4000-2000", LabelKind.CUSTOM, "Portería"), c.phones.first())
        assertEquals("Club", c.emails.first { it.address == "otra@example.com" }.label)
        assertEquals(LabelKind.WORK, c.emails.first { it.address == "mari@example.com" }.kind)
    }

    @Test
    fun cambiarUnTelefonoNoTocaLoDemasNiDejaEtiquetasHuerfanas() {
        val c = assertNotNull(VCards.contactOf(davx5))
        val out = VCards.update(davx5, c.copy(phones = listOf(Phone("011 4000-9999", LabelKind.HOME))), "x")
        assertContains(out, "011 4000-9999")
        assertFalse("4000-2000" in out || "4000-2001" in out)
        assertFalse("Portería" in out, "la etiqueta del teléfono sacado se va con él")
        assertKept(out, listOf("item3.X-ABRELATEDNAMES:Juan Rojas", "item2.X-ABLabel:Club", "item2.EMAIL:otra@example.com", "X-SIP:mari@sip.example.com"))
    }

    @Test
    fun unaEtiquetaNuevaNoChocaConGruposQueYaEstan() {
        val c = assertNotNull(VCards.contactOf(davx5))
        val out = VCards.update(davx5, c.copy(phones = listOf(Phone("011 4000-7777", LabelKind.CUSTOM, "Casa quinta"))), "x")
        val back = assertNotNull(VCards.contactOf(out))
        assertEquals("Casa quinta", back.phones.single().label)
        assertEquals("Club", back.emails.first { it.address == "otra@example.com" }.label)
        assertKept(out, listOf("item3.X-ABRELATEDNAMES:Juan Rojas"))
    }

    @Test
    fun appleConservaLoSuyo() {
        val c = assertNotNull(VCards.contactOf(apple))
        val out = VCards.update(apple, c.copy(nickname = "Lucecita"), "x")
        assertContains(out, "NICKNAME:Lucecita")
        assertEquals("apple-uid-1", VCards.uidOf(out))
        assertKept(out, listOf("X-ABShowAs:COMPANY", "X-SOCIALPROFILE;TYPE=twitter:https://example.com/luz", "item2.X-ABADR:ar"))
        assertTrue(props(out).any { it.startsWith("item1.EMAIL") && "luz@example.com" in it })
        assertTrue(props(out).any { it.startsWith("item2.ADR") })
    }

    @Test
    fun vcard40SeQuedaEn40YConservaLoSuyo() {
        val c = assertNotNull(VCards.contactOf(nextcloudV4))
        val out = VCards.update(nextcloudV4, c.copy(note = "Hola"), "x")
        assertContains(out, "VERSION:4.0")
        assertKept(out, listOf("GENDER:F", "LANG;PREF=1:es", "TZ:America/Argentina/Buenos_Aires", "CLOUD:sol@cloud.example.com", "X-MANAGERSNAME:Ana"))
        assertTrue(props(out).any { it.startsWith("GEO") && "-34.6" in it })
        assertTrue(props(out).any { it.startsWith("RELATED") && "aaaa" in it })
        assertEquals(listOf(ContactEvent(6, 15, 2010, EventKind.ANNIVERSARY)), VCards.contactOf(out)!!.events)
    }

    @Test
    fun sinCambiosNoSePierdeNadaEntreIdaYVuelta() {
        for (original in listOf(davx5, davx5Real, apple, nextcloudV4)) {
            val once = VCards.update(original, assertNotNull(VCards.contactOf(original)), "x")
            val twice = VCards.update(once, assertNotNull(VCards.contactOf(once)), "x")
            val noRev = { t: String -> props(t).filterNot { it.startsWith("REV") }.sorted() }
            assertEquals(noRev(once), noRev(twice))
            val kept = props(original).filterNot { it.startsWith("REV") || it.startsWith("BEGIN") || it.startsWith("END") }
            assertEquals(kept.size, props(once).filterNot { it.startsWith("REV") || it.startsWith("BEGIN") || it.startsWith("END") }.size, "cantidad de propiedades\n$once")
        }
    }

    @Test
    fun laFotoComoEnlaceSeConservaYNuncaSeBaja() {
        val text = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ana\r\nUID:u\r\nPHOTO;VALUE=uri:https://example.com/a.jpg\r\nEND:VCARD\r\n"
        val out = VCards.update(text, assertNotNull(VCards.contactOf(text)), "x")
        assertContains(out, "https://example.com/a.jpg")
    }

    @Test
    fun laFotoDelCeluReemplazaALaDeAdentroYLaReferenciaNoLaGuarda() {
        val b64 = java.util.Base64.getEncoder().encodeToString(ByteArray(40) { 7 })
        val text = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ana\r\nUID:u\r\nX-OTRO:si\r\nPHOTO;ENCODING=b;TYPE=JPEG:$b64\r\nEND:VCARD\r\n"
        val ref = assertNotNull(VCards.withoutEmbeddedPhoto(text))
        assertFalse("PHOTO" in ref)
        assertContains(ref, "X-OTRO:si")
        val local = ByteArray(10) { 9 }
        val out = VCards.update(ref, assertNotNull(VCards.contactOf(ref)).copy(photo = local), "x")
        assertTrue(VCards.contactOf(out)!!.photo!!.contentEquals(local))
        val without = VCards.update(ref, assertNotNull(VCards.contactOf(ref)), "x")
        assertFalse("PHOTO" in without)
    }

    @Test
    fun contactoNuevoLlevaElUidPedido() {
        val out = VCards.update(null, dev.liquidcontacts.core.model.Contact(name = dev.liquidcontacts.core.model.Name(display = "Nueva")), "uid-nuevo")
        assertEquals("uid-nuevo", VCards.uidOf(out))
        assertEquals("Nueva", VCards.contactOf(out)!!.displayName)
    }
}
