// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Todos los números son inventados.
class PhoneNumbersTest {
    private val ar = PhoneNumbers("AR")
    private val ar11 = PhoneNumbers("AR", defaultAreaCode = "11")
    private val cba = PhoneNumbers("AR", defaultAreaCode = "0351")

    @Test
    fun movilDeBuenosAiresEnTodosLosFormatos() {
        val expected = "+541123456789"
        for (raw in listOf(
            "011 15 2345-6789",
            "(011) 15-2345-6789",
            "01115 23456789",
            "+54 9 11 2345-6789",
            "+5491123456789",
            "+54 11 2345-6789",
            "005491123456789",
            "11 2345 6789",
        )) assertEquals(expected, ar.exact(raw), raw)
    }

    @Test
    fun movilDelInteriorConAreaDeTresYCuatroDigitos() {
        assertEquals("+543512345678", ar.exact("0351 15 234-5678"))
        assertEquals("+543512345678", ar.exact("+54 9 351 234-5678"))
        assertEquals("+542966423456", ar.exact("02966 15 42-3456"))
        assertEquals("+542966423456", ar.exact("+54 9 2966 42-3456"))
    }

    @Test
    fun fijo() {
        assertEquals("+541143210000", ar.exact("011 4321-0000"))
        assertEquals("+541143210000", ar.exact("+54 11 4321 0000"))
    }

    @Test
    fun internacionales() {
        assertEquals("+34612345678", ar.exact("+34 612 34 56 78"))
        assertEquals("+34612345678", ar.exact("0034 612 345 678"))
        assertEquals("+12025550143", ar.exact("+1 (202) 555-0143"))
    }

    @Test
    fun sinCodigoDeAreaNoHayClaveExacta() {
        assertNull(ar.exact("15 2345-6789"))
        assertNull(ar.exact("4321-0000"))
        assertEquals("23456789", ar.key("15 2345-6789").tail)
        assertEquals("43210000", ar.key("4321-0000").tail)
    }

    @Test
    fun codigoDeAreaPorDefectoCompletaElNumero() {
        assertEquals("+541123456789", ar11.exact("15 2345-6789"))
        assertEquals("+541143210000", ar11.exact("4321-0000"))
        assertEquals("+543512345678", cba.exact("15 234-5678"))
        assertEquals("+543514210000", cba.exact("421-0000"))
        // Un número que ya es completo no cambia por el código por defecto.
        assertEquals("+543512345678", ar11.exact("0351 15 234-5678"))
        assertEquals("+34612345678", ar11.exact("+34 612 34 56 78"))
    }

    @Test
    fun interiorSinAreaNoTieneColaDeOchoDigitos() {
        assertNull(ar.key("15 234-5678").tail)
        assertNull(ar.key("421-0000").tail)
        // Completo, el abonado de Córdoba tiene 7 dígitos: tampoco hay cola.
        assertNull(ar.key("0351 15 234-5678").tail)
        assertEquals("23456789", ar.key("011 15 2345-6789").tail)
        assertNull(ar.key("+34 612 34 56 78").tail)
    }

    @Test
    fun codigosCortosYBasura() {
        assertNull(ar.exact("911"))
        assertNull(ar.exact("*611"))
        assertNull(ar.exact(""))
        assertNull(ar.exact("hola"))
        assertNull(ar.key("911").tail)
    }

    @Test
    fun formatoParaMostrar() {
        assertEquals("011 15-2345-6789", ar.format("+5491123456789"))
        assertEquals("+34 612 34 56 78", ar.format("0034612345678"))
        assertEquals("15 2345", ar.format(" 15 2345 "))
    }

    // --- Coincidencia ---

    private data class C(val name: String, val phones: List<String>)

    private fun matcher(numbers: PhoneNumbers, vararg cs: C) = PhoneMatcher(numbers, cs.toList()) { it.phones }

    @Test
    fun coincidenciaExactaEntreFormatos() {
        val ana = C("Ana", listOf("011 15 2345-6789"))
        val m = matcher(ar, ana, C("Beto", listOf("+54 9 351 234-5678")))
        assertEquals(PhoneMatch.One(ana, approximate = false), m.match("+5491123456789"))
    }

    @Test
    fun guardadoSinAreaCoincidePorOchoDigitos() {
        val ana = C("Ana", listOf("15 2345-6789"))
        val m = matcher(ar, ana)
        assertEquals(PhoneMatch.One(ana, approximate = true), m.match("+54 9 11 2345-6789"))
    }

    @Test
    fun dosCompletosConDistintaAreaNoCoinciden() {
        val m = matcher(ar, C("Ana", listOf("011 15 2345-6789")))
        assertEquals(PhoneMatch.None, m.match("+54 9 11 9345-6789"))
    }

    @Test
    fun variosCandidatosNoEligeNinguno() {
        val ana = C("Ana", listOf("15 2345-6789"))
        val beto = C("Beto", listOf("2345-6789"))
        val r = matcher(ar, ana, beto).match("+54 9 11 2345-6789")
        assertIs<PhoneMatch.Ambiguous<C>>(r)
        assertEquals(listOf(ana, beto), r.candidates)
    }

    @Test
    fun exactoGanaAlAproximado() {
        val ana = C("Ana", listOf("+54 9 11 2345-6789"))
        val beto = C("Beto", listOf("15 2345-6789"))
        assertEquals(PhoneMatch.One(ana, approximate = false), matcher(ar, ana, beto).match("011 15 2345 6789"))
    }

    @Test
    fun conAreaPorDefectoLaCoincidenciaEsExacta() {
        val ana = C("Ana", listOf("15 2345-6789"))
        val beto = C("Beto", listOf("0223 15 234-5678"))
        val m = matcher(ar11, ana, beto)
        assertEquals(PhoneMatch.One(ana, approximate = false), m.match("+54 9 11 2345-6789"))
        // Mismos últimos dígitos pero otra área: no coincide (los dos números están completos).
        assertEquals(PhoneMatch.None, m.match("+54 9 2966 23-4567"))
    }

    @Test
    fun mismoContactoConDosFormatosNoEsAmbiguo() {
        val ana = C("Ana", listOf("15 2345-6789", "2345-6789"))
        assertEquals(PhoneMatch.One(ana, approximate = true), matcher(ar, ana).match("+5491123456789"))
    }

    @Test
    fun sinCoincidencia() {
        assertEquals(PhoneMatch.None, matcher(ar, C("Ana", listOf("011 4321-0000"))).match("+34 612 34 56 78"))
        assertEquals(PhoneMatch.None, matcher(ar, C("Ana", listOf("911"))).match("911"))
    }

    // --- Casos especiales ---

    @Test
    fun cerochocientosYCeroochocientosdiez() {
        assertEquals("+548003331234", ar.exact("0800 333 1234"))
        assertEquals("+548003331234", ar.exact("0800-333-1234"))
        assertEquals("+548102224567", ar.exact("0810 222 4567"))
        // Se escriben siempre completos: no hace falta coincidencia aproximada.
        assertNull(ar.key("0800 333 1234").tail)
    }

    @Test
    fun codigosCortosNoTienenClaveNiCola() {
        for (n in listOf("100", "101", "107", "911", "112")) {
            assertNull(ar.exact(n), n)
            assertNull(ar.key(n).tail, n)
        }
        val m = matcher(ar, C("Policía", listOf("101")), C("Bomberos", listOf("100")))
        assertEquals(PhoneMatch.None, m.match("101"))
    }

    @Test
    fun internosNoCambianElNumero() {
        for (raw in listOf("011 4321-0000 int. 123", "011 4321-0000 x123", "+54 11 4321-0000 ext. 12", "011 4321-0000 interno 5")) {
            assertEquals("+541143210000", ar.exact(raw), raw)
        }
        val oficina = C("Oficina", listOf("011 4321-0000 int. 123"))
        assertEquals(PhoneMatch.One(oficina, approximate = false), matcher(ar, oficina).match("+54 11 4321-0000"))
    }

    @Test
    fun codigosDeMarcado() {
        for (n in listOf("*611", "#31#", "*#06#", "*99#")) {
            assertNull(ar.exact(n), n)
            assertNull(ar.key(n).tail, n)
        }
    }

    @Test
    fun elNueveYElQuinceSoloSeDescartanEnArgentina() {
        assertEquals("+34911234567", ar.exact("+34 91 123 45 67"))
        assertEquals("+34911234567", ar.exact("0034 91 123 45 67"))
        assertEquals("+61412345678", ar.exact("+61 412 345 678"))
        assertEquals("+61412345678", ar.exact("+61 4 1234 5678"))
        assertEquals("+19195550143", ar.exact("+1 919 555 0143"))
        // Un +1 con área que empieza con 9 tampoco pierde dígitos.
        assertEquals("+19155550143", ar.exact("+1 915 555 0143"))
        // Y con el país por defecto de otro lado, igual.
        assertEquals("+34911234567", PhoneNumbers("ES").exact("91 123 45 67"))
        assertEquals("+34912345678", PhoneNumbers("ES").exact("912 345 678"))
    }

    @Test
    fun otrosPaisesSeComparanExactos() {
        val es = C("Madrid", listOf("+34 91 123 45 67"))
        val au = C("Sídney", listOf("+61 412 345 678"))
        val us = C("Washington", listOf("+1 202 955 0143"))
        val m = matcher(ar, es, au, us)
        assertEquals(PhoneMatch.One(es, approximate = false), m.match("0034 911 234 567"))
        assertEquals(PhoneMatch.One(au, approximate = false), m.match("+61 4 1234 5678"))
        assertEquals(PhoneMatch.One(us, approximate = false), m.match("+1 (202) 955-0143"))
        // Mismos últimos 8 dígitos en otro país: los dos están completos, no coinciden.
        assertEquals(PhoneMatch.None, m.match("+44 20 1234 5678"))
        assertEquals(PhoneMatch.None, m.match("+1 303 955 0143"))
    }

    @Test
    fun conElPaisDeLaSimElFormatoLocalSigueValiendoDeViaje() {
        // El celu está en España (la red dice ES), pero el país por defecto sale de la SIM (AR).
        val sim = PhoneNumbers("AR")
        val ana = C("Ana", listOf("011 15 2345-6789"))
        val beto = C("Beto", listOf("0351 15 234-5678"))
        val m = matcher(sim, ana, beto)
        // Las llamadas llegan en formato internacional estando afuera.
        assertEquals(PhoneMatch.One(ana, approximate = false), m.match("+54 9 11 2345-6789"))
        assertEquals(PhoneMatch.One(beto, approximate = false), m.match("+54 9 351 234-5678"))
        // Con el país de la red, el mismo contacto se interpretaría mal: por eso se usa la SIM.
        assertTrue(PhoneNumbers("ES").exact("011 15 2345-6789") != sim.exact("011 15 2345-6789"))
        // Un número local de España escrito de viaje (sin +34) no se confunde con uno argentino.
        assertEquals(PhoneMatch.None, m.match("612 34 56 78"))
    }

    @Test
    fun numerosSinCodigoDeArea() {
        for (n in listOf("15 2345-6789", "4321-0000", "15 234-5678", "421-0000")) assertTrue(ar.missingAreaCode(n), n)
        for (n in listOf("011 15 2345-6789", "+34 612 34 56 78", "0034 612 345 678", "911", "*611", "#31#", "0800 333 1234", "")) {
            assertFalse(ar.missingAreaCode(n), n)
        }
        // Con el código de área por defecto ya se completan.
        assertFalse(ar11.missingAreaCode("15 2345-6789"))
        assertFalse(cba.missingAreaCode("15 234-5678"))
    }
}
