// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.dav

import dev.liquidcontacts.core.ServerUrl
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Respuestas del servidor con forma de Radicale, sin servidor real. */
class CardDavTest {
    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun client(): CardDav {
        val url = (ServerUrl.parse(server.url("/").toString(), allowHttp = true) as ServerUrl.Result.Ok).url
        return CardDav(DavClient(url, Credentials("u", "p")))
    }

    private fun multi(body: String) = MockResponse.Builder().code(207).addHeader("Content-Type", "application/xml").body(
        """<?xml version="1.0"?><multistatus xmlns="DAV:" xmlns:C="urn:ietf:params:xml:ns:carddav" xmlns:CS="http://calendarserver.org/ns/" xmlns:ICAL="http://apple.com/ns/ical/" xmlns:AB="http://inf-it.com/ns/ab/">$body</multistatus>""",
    ).build()

    private fun ok(href: String, props: String) =
        "<response><href>$href</href><propstat><prop>$props</prop><status>HTTP/1.1 200 OK</status></propstat></response>"

    @Test
    fun encuentraLasLibretas() {
        server.enqueue(multi(ok("/", "<current-user-principal><href>/ana/</href></current-user-principal>")))
        server.enqueue(multi(ok("/ana/", "<C:addressbook-home-set><href>/ana/</href></C:addressbook-home-set>")))
        server.enqueue(
            multi(
                ok("/ana/", "<resourcetype><collection/></resourcetype>") +
                    ok("/ana/casa/", "<resourcetype><collection/><C:addressbook/></resourcetype><displayname>Casa</displayname><CS:getctag>c1</CS:getctag><current-user-privilege-set><privilege><all/></privilege></current-user-privilege-set>") +
                    ok("/ana/cal/", "<resourcetype><collection/></resourcetype><displayname>Calendario</displayname>") +
                    ok("/ana/ajena/", "<resourcetype><collection/><C:addressbook/></resourcetype><current-user-privilege-set><privilege><read/></privilege></current-user-privilege-set>"),
            ),
        )
        val d = client().discover()
        assertEquals(listOf("Casa", null), d.books.map { it.name })
        assertEquals(listOf(false, true), d.books.map { it.readOnly })
        assertEquals("c1", d.books[0].ctag)
    }

    @Test
    fun leeElColorDeCadaLibreta() {
        server.enqueue(
            multi(
                ok("/ana/casa/", "<resourcetype><collection/><C:addressbook/></resourcetype><ICAL:calendar-color>#1a73e8ff</ICAL:calendar-color>") +
                    ok("/ana/trabajo/", "<resourcetype><collection/><C:addressbook/></resourcetype><AB:addressbook-color>#E5484D</AB:addressbook-color>") +
                    ok("/ana/otra/", "<resourcetype><collection/><C:addressbook/></resourcetype><ICAL:calendar-color>rojo</ICAL:calendar-color>"),
            ),
        )
        val books = client().listBooks(server.url("/ana/").toString())
        assertEquals(listOf(0xFF1A73E8.toInt(), 0xFFE5484D.toInt(), null), books.map { it.color })
        assertTrue(server.takeRequest().body!!.utf8().contains("calendar-color"))
    }

    @Test
    fun propiedadesDeUnaLibreta() {
        server.enqueue(multi(ok("/ana/casa/", "<CS:getctag>c9</CS:getctag><ICAL:calendar-color>#00000000</ICAL:calendar-color>")))
        val props = client().props(server.url("/ana/casa/").toString())
        assertEquals("c9", props.ctag)
        // Transparente no se vería: queda opaco.
        assertEquals(0xFF000000.toInt(), props.color)
    }

    @Test
    fun listaVersionesSinLaColeccion() {
        server.enqueue(
            multi(
                ok("/ana/casa/", "<resourcetype><collection/><C:addressbook/></resourcetype>") +
                    ok("/ana/casa/a.vcf", "<getetag>\"1\"</getetag><resourcetype/>") +
                    ok("/ana/casa/b.vcf", "<getetag>\"2\"</getetag><resourcetype/>"),
            ),
        )
        val e = client().listEtags(server.url("/ana/casa/").toString())
        assertEquals(setOf("\"1\"", "\"2\""), e.values.toSet())
        assertTrue(e.keys.all { it.endsWith(".vcf") })
    }

    @Test
    fun bajaElContenidoEnTandas() {
        val urls = (1..3).map { server.url("/ana/casa/$it.vcf").toString() }
        server.enqueue(multi(ok("/ana/casa/1.vcf", "<getetag>\"1\"</getetag><C:address-data>BEGIN:VCARD</C:address-data>") + ok("/ana/casa/2.vcf", "<getetag>\"2\"</getetag><C:address-data>X</C:address-data>")))
        server.enqueue(multi(ok("/ana/casa/3.vcf", "<getetag>\"3\"</getetag><C:address-data>Y</C:address-data>")))
        val r = client().multiget(server.url("/ana/casa/").toString(), urls, batch = 2)
        assertEquals(3, r.size)
        assertEquals("BEGIN:VCARD", r[0].vcard)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun todaEscrituraLlevaCondicion() {
        val c = client()
        val url = server.url("/ana/casa/a.vcf").toString()
        server.enqueue(MockResponse.Builder().code(201).addHeader("ETag", "\"n\"").build())
        assertEquals("\"n\"", c.create(url, "BEGIN:VCARD"))
        assertEquals("*", server.takeRequest().headers["If-None-Match"])
        server.enqueue(MockResponse.Builder().code(204).addHeader("ETag", "\"m\"").build())
        c.update(url, "\"n\"", "BEGIN:VCARD")
        val put = server.takeRequest()
        assertEquals("\"n\"", put.headers["If-Match"])
        assertTrue(put.headers["Content-Type"]!!.startsWith("text/vcard"))
        server.enqueue(MockResponse.Builder().code(204).build())
        c.delete(url, "\"m\"")
        assertEquals("\"m\"", server.takeRequest().headers["If-Match"])
    }

    @Test
    fun siCambioEnElServidorEsUn412() {
        server.enqueue(MockResponse.Builder().code(412).build())
        assertFailsWith<DavException.PreconditionFailed> { client().update(server.url("/a.vcf").toString(), "\"v\"", "x") }
    }

    @Test
    fun laUrlNuevaNoRevelaElContacto() {
        assertEquals(server.url("/ana/casa/uuid-1.vcf").toString(), client().newCardUrl(server.url("/ana/casa/").toString(), "uuid-1"))
    }
}
