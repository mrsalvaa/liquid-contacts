// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

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
        assertEquals("12345678", ar.key("0351 15 234-5678").tail)
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
}
