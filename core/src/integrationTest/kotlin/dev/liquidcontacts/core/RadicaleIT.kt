// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core

import dev.liquidcontacts.core.dav.CardDav
import dev.liquidcontacts.core.dav.Credentials
import dev.liquidcontacts.core.dav.DavClient
import dev.liquidcontacts.core.dav.DavException
import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.model.Name
import dev.liquidcontacts.core.model.Phone
import dev.liquidcontacts.core.vcard.VCards
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contra el Radicale local de prueba (127.0.0.1:5232), nunca contra un servidor real. La dirección,
 * el usuario y la libreta salen de RADICALE_URL, RADICALE_USER, RADICALE_PASS y RADICALE_BOOK.
 */
class RadicaleIT {
    private val base = System.getenv("RADICALE_URL") ?: "http://127.0.0.1:5232/"
    private val user = System.getenv("RADICALE_USER") ?: "prueba"
    private val pass = System.getenv("RADICALE_PASS") ?: "clave-de-prueba" // secret-check: ignore
    private val book = System.getenv("RADICALE_BOOK") ?: "jvm"

    private fun server() = (ServerUrl.parse(base, allowHttp = true) as ServerUrl.Result.Ok).url
    private fun dav(password: String = pass) = CardDav(DavClient(server(), Credentials(user, password)))
    private val bookUrl get() = "${base}$user/$book/"

    @Test
    fun encuentraLaLibreta() {
        val d = dav().discover()
        assertTrue(d.books.any { it.url == bookUrl }, d.books.toString())
        assertNotNull(d.books.first { it.url == bookUrl }.ctag)
    }

    @Test
    fun laContrasenaIncorrectaEs401YNoSeReintenta() {
        assertFailsWith<DavException.Unauthorized> { dav("clave-mala").discover() }
    }

    @Test
    fun cicloCompletoConVersiones() {
        val d = dav()
        val uid = UUID.randomUUID().toString()
        val url = d.newCardUrl(bookUrl, uid)
        val text = VCards.update(null, Contact(name = Name(display = "Integración Ñandú"), phones = listOf(Phone("011 4000-0000"))), uid)
        val etag = d.create(url, text)
        assertNotNull(etag)
        assertFailsWith<DavException.PreconditionFailed> { d.create(url, text) }
        assertEquals(etag, d.listEtags(bookUrl)[url])
        val card = d.multiget(bookUrl, listOf(url)).single()
        assertEquals("Integración Ñandú", VCards.contactOf(card.vcard!!)!!.displayName)
        val edited = VCards.update(card.vcard, VCards.contactOf(card.vcard!!)!!.copy(note = "nota"), uid)
        val etag2 = d.update(url, etag!!, edited)
        assertFailsWith<DavException.PreconditionFailed> { d.update(url, etag, edited) }
        assertFailsWith<DavException.PreconditionFailed> { d.delete(url, etag) }
        d.delete(url, etag2!!)
        assertNull(d.get(url))
    }

    @Test
    fun loQueNoSeModelaLlegaAlServidorYVuelve() {
        val d = dav()
        val uid = UUID.randomUUID().toString()
        val url = d.newCardUrl(bookUrl, uid)
        val original = "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:$uid\r\nFN:Ana Extra\r\nN:Extra;Ana;;;\r\nX-OTRO-CLIENTE:valor\r\nIMPP;X-SERVICE-TYPE=XMPP:xmpp:a@example.com\r\nEND:VCARD\r\n"
        val etag = d.create(url, original)!!
        val down = d.get(url)!!.vcard!!
        d.update(url, etag, VCards.update(down, VCards.contactOf(down)!!.copy(note = "cambio"), uid))
        val back = d.get(url)!!
        assertTrue("X-OTRO-CLIENTE:valor" in back.vcard!!, back.vcard)
        assertTrue("xmpp:a@example.com" in back.vcard!!, back.vcard)
        assertTrue("NOTE:cambio" in back.vcard!!, back.vcard)
        d.delete(url, back.etag!!)
    }
}
