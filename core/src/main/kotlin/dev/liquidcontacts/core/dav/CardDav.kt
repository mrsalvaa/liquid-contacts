// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.dav

import dev.liquidcontacts.core.ServerUrl
import dev.liquidcontacts.core.tls.ServerTrust
import okhttp3.Dns
import okhttp3.HttpUrl
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Una libreta del servidor. La URL es su identidad; el nombre puede cambiar. [color] es ARGB, el que
 * le puso el servidor (Radicale lo guarda como calendar-color de Apple), o null si no tiene.
 */
data class RemoteBook(val url: String, val name: String?, val ctag: String?, val readOnly: Boolean, val color: Int? = null)

/** Indicador de cambios y color de una libreta. */
data class BookProps(val ctag: String?, val color: Int?)

data class BookDiscovery(val principalUrl: String, val homeUrl: String, val books: List<RemoteBook>)

/** Un contacto del servidor: su URL, versión y texto vCard (null si no vino). */
data class RemoteCard(val url: String, val etag: String?, val vcard: String?)

/**
 * Operaciones CardDAV sobre un [DavClient], con la misma forma que el CalDAV de Liquid Calendar.
 * Solo lo que hace falta: encontrar libretas, listar versiones, bajar, subir y borrar.
 */
class CardDav(private val dav: DavClient) {

    /** Principal del usuario, su addressbook-home-set y las libretas. Solo lectura. */
    fun discover(): BookDiscovery {
        val base = dav.url(dav.server.toString())
        val principal = findPrincipal(base)
            ?: findPrincipal(dav.url("/.well-known/carddav"))
            ?: throw DavException.BadResponse("sin current-user-principal")
        val home = dav.propfind(principal, 0, listOf(ADDRESSBOOK_HOME_SET)).firstNotNullOfOrNull { r ->
            r[ADDRESSBOOK_HOME_SET]?.child(DavClient.HREF)?.text
        } ?: throw DavException.BadResponse("sin addressbook-home-set")
        val homeUrl = dav.url(home)
        return BookDiscovery(principal.toString(), homeUrl.toString(), listBooks(homeUrl.toString()))
    }

    private fun findPrincipal(url: HttpUrl): HttpUrl? = try {
        dav.propfind(url, 0, listOf(CURRENT_USER_PRINCIPAL)).firstNotNullOfOrNull { r ->
            r[CURRENT_USER_PRINCIPAL]?.child(DavClient.HREF)?.text
        }?.let { dav.url(it) }
    } catch (e: DavException.NotFound) {
        null
    } catch (e: DavException.UnexpectedStatus) {
        null // 405 u otros: esta URL no es un recurso WebDAV.
    }

    fun listBooks(homeUrl: String): List<RemoteBook> =
        dav.propfind(dav.url(homeUrl), 1, BOOK_PROPS)
            .filter { it[RESOURCETYPE]?.has(ADDRESSBOOK) == true }
            .map { r ->
                RemoteBook(
                    url = dav.url(r.href).toString(),
                    name = r[DISPLAYNAME]?.text?.takeIf { it.isNotBlank() },
                    ctag = r[GETCTAG]?.text?.takeIf { it.isNotEmpty() },
                    readOnly = r[CURRENT_USER_PRIVILEGE_SET]?.let { !canWrite(it) } ?: false,
                    color = colorOf(r),
                )
            }

    /** Solo el indicador de cambios de una libreta. */
    fun ctag(bookUrl: String): String? =
        dav.propfind(dav.url(bookUrl), 0, listOf(GETCTAG)).firstNotNullOfOrNull { it[GETCTAG]?.text?.takeIf(String::isNotEmpty) }

    /** Indicador de cambios y color de una libreta, en una sola consulta. */
    fun props(bookUrl: String): BookProps {
        val r = dav.propfind(dav.url(bookUrl), 0, listOf(GETCTAG, CALENDAR_COLOR, ADDRESSBOOK_COLOR)).firstOrNull()
        return BookProps(r?.get(GETCTAG)?.text?.takeIf(String::isNotEmpty), r?.let(::colorOf))
    }

    private fun colorOf(r: DavResponse): Int? =
        (r[CALENDAR_COLOR]?.text?.let(::parseColor)) ?: r[ADDRESSBOOK_COLOR]?.text?.let(::parseColor)

    /** URL y ETag de cada contacto de la libreta, sin el contenido. */
    fun listEtags(bookUrl: String): Map<String, String?> {
        val self = dav.url(bookUrl)
        return dav.propfind(self, 1, listOf(GETETAG, RESOURCETYPE))
            .filter { r -> dav.url(r.href) != self && r[RESOURCETYPE]?.has(COLLECTION) != true && !r.href.endsWith("/") }
            .associate { dav.url(it.href).toString() to it[GETETAG]?.text }
    }

    /** Contenido de los contactos pedidos, en tandas de [batch] para no recibir respuestas enormes. */
    fun multiget(bookUrl: String, urls: Collection<String>, batch: Int = 50): List<RemoteCard> =
        urls.chunked(batch).flatMap { chunk ->
            val body = DavClient.xmlRequest("addressbook-multiget", Ns.CARDDAV) {
                append("<d:prop><d:getetag/><card:address-data/></d:prop>")
                chunk.forEach { append("<d:href>").append(xmlEscape(dav.url(it).encodedPath)).append("</d:href>") }
            }
            dav.report(dav.url(bookUrl), 1, body).map { r ->
                RemoteCard(dav.url(r.href).toString(), r[GETETAG]?.text, r[ADDRESS_DATA]?.text)
            }
        }

    /** Sube un contacto nuevo; falla con 412 si ya hay algo en esa URL. Devuelve el ETag si lo informa. */
    fun create(url: String, vcard: String): String? = dav.put(dav.url(url), vcard, ifNoneMatch = true)

    /** Actualiza un contacto solo si su versión sigue siendo [etag] (412 si cambió). */
    fun update(url: String, etag: String, vcard: String): String? = dav.put(dav.url(url), vcard, ifMatch = etag)

    /** Borra un contacto solo si su versión sigue siendo [etag] (412 si cambió). */
    fun delete(url: String, etag: String) = dav.delete(dav.url(url), etag)

    /** Versión actual de un contacto (para un conflicto). Null si ya no existe. */
    fun get(url: String): RemoteCard? = try {
        val r = dav.get(dav.url(url))
        RemoteCard(url, r.etag, r.body.toString(Charsets.UTF_8))
    } catch (e: DavException.NotFound) {
        null
    }

    /** URL para un contacto nuevo. El nombre es un UUID, así que no revela nada del contacto. */
    fun newCardUrl(bookUrl: String, uid: String): String =
        dav.url(bookUrl).newBuilder().addPathSegment("$uid.vcf").build().toString()

    companion object {
        val CURRENT_USER_PRINCIPAL = QName(Ns.DAV, "current-user-principal")
        val ADDRESSBOOK_HOME_SET = QName(Ns.CARDDAV, "addressbook-home-set")
        val ADDRESSBOOK = QName(Ns.CARDDAV, "addressbook")
        val ADDRESS_DATA = QName(Ns.CARDDAV, "address-data")
        val RESOURCETYPE = QName(Ns.DAV, "resourcetype")
        val COLLECTION = QName(Ns.DAV, "collection")
        val DISPLAYNAME = QName(Ns.DAV, "displayname")
        val GETETAG = QName(Ns.DAV, "getetag")
        val GETCTAG = QName(Ns.CALSERVER, "getctag")
        val CURRENT_USER_PRIVILEGE_SET = QName(Ns.DAV, "current-user-privilege-set")
        /** El color de Radicale (y de Apple), también en libretas. */
        val CALENDAR_COLOR = QName(Ns.APPLE_ICAL, "calendar-color")
        /** El de InfCloud y otros clientes. */
        val ADDRESSBOOK_COLOR = QName(Ns.INF_IT_AB, "addressbook-color")
        private val PRIVILEGE = QName(Ns.DAV, "privilege")
        private val WRITE = QName(Ns.DAV, "write")
        private val WRITE_CONTENT = QName(Ns.DAV, "write-content")
        private val ALL = QName(Ns.DAV, "all")
        private val BOOK_PROPS = listOf(RESOURCETYPE, DISPLAYNAME, GETCTAG, CURRENT_USER_PRIVILEGE_SET, CALENDAR_COLOR, ADDRESSBOOK_COLOR)

        /** "#RRGGBB" o "#RRGGBBAA" (formato de Apple) → ARGB. Igual que en Liquid Calendar. */
        fun parseColor(text: String): Int? {
            val hex = text.trim().removePrefix("#")
            if (hex.length != 6 && hex.length != 8 || hex.any { it.digitToIntOrNull(16) == null }) return null
            val rgb = hex.substring(0, 6).toLong(16)
            val alpha = if (hex.length == 8) hex.substring(6, 8).toLong(16) else 0xFF
            // Un color transparente no se vería: se usa opaco.
            return ((if (alpha == 0L) 0xFF else alpha) shl 24 or rgb).toInt()
        }

        private fun canWrite(set: XmlElement): Boolean = set.all(PRIVILEGE).any { p -> p.has(WRITE) || p.has(WRITE_CONTENT) || p.has(ALL) }
    }
}

/**
 * Con http, antes de conectar se revisa la dirección real: un nombre ".lan" que resuelva a una IP
 * pública (o un cambio de DNS) no recibe la contraseña sin cifrar.
 */
class LocalOnlyDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = delegate.lookup(hostname)
        if (all.isEmpty() || !all.all(ServerUrl::isLocalAddress)) throw UnknownHostException("Sin cifrar solo a la red local")
        return all
    }
}

/** Conexión a un servidor con sus reglas: https con [ServerTrust], o http solo a la red local. */
fun cardDavClient(server: ServerUrl, credentials: Credentials, trust: ServerTrust): DavClient =
    DavClient(
        server,
        credentials,
        if (server.isHttps) DavClient.defaultHttpClient(true, tls = trust) else DavClient.defaultHttpClient(false, dns = LocalOnlyDns()),
    )
