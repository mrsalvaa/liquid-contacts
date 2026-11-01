// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.tls

import dev.liquidcontacts.core.ServerUrl
import dev.liquidcontacts.core.dav.CardDav
import dev.liquidcontacts.core.dav.Credentials
import dev.liquidcontacts.core.dav.DavException
import dev.liquidcontacts.core.dav.LocalOnlyDns
import dev.liquidcontacts.core.dav.cardDavClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After

/** Con TLS real contra un servidor local: nada de "confiar en todo". */
class ServerTrustTest {
    private val servers = ArrayList<MockWebServer>()

    @After
    fun stop() = servers.forEach { it.close() }

    private fun cert(host: String = "localhost", issuer: HeldCertificate? = null, notBefore: Long? = null, notAfter: Long? = null, ca: Boolean = false): HeldCertificate =
        HeldCertificate.Builder().commonName(host).addSubjectAlternativeName(host).apply {
            if (issuer != null) signedBy(issuer)
            if (ca) certificateAuthority(0)
            if (notBefore != null && notAfter != null) validityInterval(notBefore, notAfter)
        }.build()

    private fun serve(held: HeldCertificate, vararg chain: HeldCertificate): MockWebServer {
        val tls = HandshakeCertificates.Builder().heldCertificate(held, *chain.map { it.certificate }.toTypedArray()).build()
        val s = MockWebServer()
        s.useHttps(tls.sslSocketFactory())
        s.start()
        servers += s
        return s
    }

    private fun connect(s: MockWebServer, config: TrustConfig, host: String = "localhost"): ServerTrust {
        val url = "https://$host:${s.port}/"
        val trust = ServerTrust(config)
        val client = cardDavClient((ServerUrl.parse(url) as ServerUrl.Result.Ok).url, Credentials("u", "p"), trust)
        s.enqueue(MockResponse.Builder().code(207).body("<multistatus xmlns=\"DAV:\"/>").build())
        CardDav(client).ctag(url)
        return trust
    }

    @Test
    fun soloElSistemaRechazaUnAutofirmado() {
        val s = serve(cert())
        val e = assertFailsWith<DavException.Tls> { connect(s, TrustConfig()) }
        assertNotNull(e)
    }

    @Test
    fun laHuellaCorrectaAceptaUnAutofirmado() {
        val c = cert()
        val s = serve(c)
        connect(s, TrustConfig(pinSha256 = ServerTrust.fingerprint(c.certificate)))
    }

    @Test
    fun laHuellaSeLeeEnCualquierFormato() {
        val c = cert()
        val fp = ServerTrust.fingerprint(c.certificate)
        connect(serve(c), TrustConfig(pinSha256 = fp.replace(":", "").lowercase()))
        assertEquals(fp, ServerTrust.normalizePin(fp.replace(":", " ")))
        assertNull(ServerTrust.normalizePin("AB:CD"))
        assertNull(ServerTrust.normalizePin("z".repeat(64)))
    }

    @Test
    fun otraHuellaSeRechaza() {
        val otra = ServerTrust.fingerprint(cert().certificate)
        assertFailsWith<DavException.Tls> { connect(serve(cert()), TrustConfig(pinSha256 = otra)) }
    }

    @Test
    fun laHuellaNoSaltaLaVerificacionDelNombre() {
        // Certificado de otro nombre, con la huella correcta: se rechaza igual.
        val c = cert(host = "otro.example.org")
        val s = serve(c)
        assertFailsWith<DavException> { connect(s, TrustConfig(pinSha256 = ServerTrust.fingerprint(c.certificate))) }
    }

    @Test
    fun unCertificadoVencidoSeRechazaAunConHuella() {
        val now = System.currentTimeMillis()
        val c = cert(notBefore = now - 86_400_000L * 10, notAfter = now - 86_400_000L)
        assertFailsWith<DavException.Tls> { connect(serve(c), TrustConfig(pinSha256 = ServerTrust.fingerprint(c.certificate))) }
    }

    @Test
    fun laCaImportadaAceptaLoQueFirma() {
        val ca = cert(host = "Mi CA", ca = true)
        val leaf = cert(issuer = ca)
        connect(serve(leaf, ca), TrustConfig(caPem = ca.certificatePem()))
    }

    @Test
    fun laCaImportadaNoAceptaOtraCa() {
        val mine = cert(host = "Mi CA", ca = true)
        val other = cert(host = "Otra CA", ca = true)
        val leaf = cert(issuer = other)
        assertFailsWith<DavException.Tls> { connect(serve(leaf, other), TrustConfig(caPem = mine.certificatePem())) }
    }

    @Test
    fun laCaImportadaNoSaltaLaVerificacionDelNombre() {
        val ca = cert(host = "Mi CA", ca = true)
        val leaf = cert(host = "otro.example.org", issuer = ca)
        assertFailsWith<DavException> { connect(serve(leaf, ca), TrustConfig(caPem = ca.certificatePem())) }
    }

    @Test
    fun unaCaVencidaSeRechaza() {
        val now = System.currentTimeMillis()
        val ca = cert(host = "Mi CA", ca = true, notBefore = now - 86_400_000L * 10, notAfter = now - 86_400_000L)
        val leaf = cert(issuer = ca)
        assertFailsWith<DavException.Tls> { connect(serve(leaf, ca), TrustConfig(caPem = ca.certificatePem())) }
    }

    @Test
    fun alRechazarSeVeLaHuellaParaCompararla() {
        val c = cert()
        val s = serve(c)
        val url = "https://localhost:${s.port}/"
        val trust = ServerTrust()
        val client = cardDavClient((ServerUrl.parse(url) as ServerUrl.Result.Ok).url, Credentials("u", "p"), trust)
        assertFailsWith<DavException.Tls> { CardDav(client).ctag(url) }
        assertEquals(ServerTrust.fingerprint(c.certificate), ServerTrust.fingerprint(trust.lastChain.first()))
    }

    @Test
    fun noHayFormaDeNoElegirVerificacion() {
        val t = ServerTrust()
        assertFailsWith<java.security.cert.CertificateException> { t.trustManager.checkServerTrusted(emptyArray(), "RSA") }
        assertFailsWith<java.security.cert.CertificateException> { t.trustManager.checkClientTrusted(emptyArray(), "RSA") }
        assertFailsWith<IllegalArgumentException> { TrustConfig(pinSha256 = "x", caPem = "y") }
        assertFailsWith<IllegalArgumentException> { ServerTrust(TrustConfig(pinSha256 = "no es una huella")) }
    }

    @Test
    fun usableCa() {
        assertTrue(ServerTrust.isUsableCa(cert(host = "Mi CA", ca = true).certificatePem()))
        assertFalse(ServerTrust.isUsableCa(cert().certificatePem()), "un certificado que no es CA")
        assertFalse(ServerTrust.isUsableCa("no es un pem"))
    }

    @Test
    fun httpSoloConDireccionLocalReal() {
        fun dns(ip: String) = Dns { listOf(InetAddress.getByName(ip)) }
        assertEquals(1, LocalOnlyDns(dns("192.168.1.5")).lookup("nas.lan").size)
        assertEquals(1, LocalOnlyDns(dns("100.100.1.1")).lookup("nas.tail.ts.net").size)
        assertFailsWith<UnknownHostException> { LocalOnlyDns(dns("93.184.216.34")).lookup("nas.lan") }
        val mixed = Dns { listOf(InetAddress.getByName("192.168.1.5"), InetAddress.getByName("93.184.216.34")) }
        assertFailsWith<UnknownHostException> { LocalOnlyDns(mixed).lookup("nas.lan") }
    }
}
