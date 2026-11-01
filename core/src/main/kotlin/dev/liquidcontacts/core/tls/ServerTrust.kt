// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.tls

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Qué certificado acepta una cuenta, además del almacén del sistema. Nunca "confiar en todo": no
 * existe esa opción. El nombre del servidor lo verifica siempre OkHttp, en todos los casos.
 * - Sin nada: solo el sistema.
 * - [caPem]: el sistema o una cadena que llegue a esa CA importada.
 * - [pinSha256]: el certificado del servidor tiene que tener esa huella, aunque el sistema lo
 *   acepte; sirve para un certificado autofirmado. Tiene que estar vigente.
 */
data class TrustConfig(val pinSha256: String? = null, val caPem: String? = null) {
    init {
        require(pinSha256 == null || caPem == null) { "Huella o CA, no las dos" }
    }
}

/**
 * Verificador de una cuenta: delega en el del sistema y agrega por encima la huella o la CA
 * importada. Se queda con la cadena vista, para poder mostrar la huella cuando se rechaza.
 */
class ServerTrust(
    config: TrustConfig = TrustConfig(),
    private val system: X509TrustManager = systemTrustManager(),
) {
    private val pin: String? = config.pinSha256?.let(::normalize)
    private val ca: X509TrustManager? = config.caPem?.let(::parsePem)?.let(::trustManagerFor)

    /** Cadena del último intento, aceptada o no (solo para mostrar su huella). */
    @Volatile
    var lastChain: List<X509Certificate> = emptyList()
        private set

    val trustManager: X509TrustManager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + (ca?.acceptedIssuers.orEmpty())

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            throw CertificateException("Sin certificados de cliente")

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            if (chain.isEmpty()) throw CertificateException("Cadena vacía")
            lastChain = chain.toList()
            if (pin != null) {
                // La huella manda: si no coincide se rechaza aunque el sistema confíe en él.
                if (fingerprint(chain[0]) != pin) throw CertificateException("La huella del certificado no coincide")
                chain[0].checkValidity()
                return
            }
            try {
                system.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                (ca ?: throw e).checkServerTrusted(chain, authType)
            }
        }
    }

    val socketFactory: SSLSocketFactory = SSLContext.getInstance("TLS").run {
        init(null, arrayOf(trustManager), null)
        socketFactory
    }

    companion object {
        fun systemTrustManager(): X509TrustManager = trustManagerFor(null)

        private fun trustManagerFor(store: KeyStore?): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(store)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        /**
         * Almacén con los certificados vigentes de un PEM; null si no queda ninguno. Se filtra acá
         * porque el verificador no revisa la vigencia de sus autoridades de confianza: una CA
         * importada vencida seguiría sirviendo.
         */
        private fun parsePem(pem: String): KeyStore? {
            val certs = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(pem.toByteArray()))
                .filterIsInstance<X509Certificate>().filter { runCatching { it.checkValidity() }.isSuccess }
            if (certs.isEmpty()) return null
            return KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                certs.forEachIndexed { i, c -> setCertificateEntry("ca$i", c) }
            }
        }

        /** Si un texto es un PEM válido con al menos un certificado de CA vigente. */
        fun isUsableCa(pem: String): Boolean = runCatching {
            CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(pem.toByteArray()))
                .filterIsInstance<X509Certificate>().any { it.basicConstraints >= 0 && runCatching { it.checkValidity() }.isSuccess }
        }.getOrDefault(false)

        /** Huella SHA-256 del certificado, en mayúsculas con ":" (la misma de `openssl x509 -fingerprint -sha256`). */
        fun fingerprint(c: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString(":") { "%02X".format(it) }

        /** Huella escrita a mano o pegada: sin importar mayúsculas, espacios ni ":". Null si no es SHA-256. */
        fun normalizePin(text: String): String? {
            val hex = text.filter { it.isLetterOrDigit() }.uppercase()
            if (hex.length != 64 || !hex.all { it in "0123456789ABCDEF" }) return null
            return hex.chunked(2).joinToString(":")
        }

        private fun normalize(text: String): String = normalizePin(text) ?: throw IllegalArgumentException("Huella inválida")
    }
}
