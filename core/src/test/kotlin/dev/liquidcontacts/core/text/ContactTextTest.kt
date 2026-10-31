// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Nombres inventados.
class ContactTextTest {
    @Test
    fun foldQuitaTildesEnesYMayusculas() {
        assertEquals("munoz avila", Fold.fold("Muñoz Ávila"))
        assertEquals("pinguino", Fold.fold("PINGÜINO"))
        assertEquals("jose", Fold.fold("José"))
        assertEquals("cecilia", Fold.fold("Cecília"))
    }

    @Test
    fun ordenEspanol() {
        val names = listOf("Ñandú Pérez", "nacho", "Óscar", "Oscar Arias", "Ángela", "andrés", "Zoe", "Nora", "123 Taller", "😀 Sonrisa", "Ana")
        val sorted = names.sortedWith(SpanishOrder())
        assertEquals(
            listOf("Ana", "andrés", "Ángela", "nacho", "Nora", "Ñandú Pérez", "Óscar", "Oscar Arias", "Zoe", "123 Taller", "😀 Sonrisa"),
            sorted,
        )
    }

    @Test
    fun enieVaDespuesDeNEnMedioDeLaPalabra() {
        assertEquals(listOf("Muno", "Muñoz"), listOf("Muñoz", "Muno").sortedWith(SpanishOrder()))
        assertEquals(listOf("Peña", "Penzo").sortedWith(SpanishOrder()), listOf("Penzo", "Peña"))
    }

    @Test
    fun secciones() {
        assertEquals("A", SpanishOrder.section("Ángela"))
        assertEquals("Ñ", SpanishOrder.section("ñandú"))
        assertEquals("N", SpanishOrder.section("Nora"))
        assertEquals("O", SpanishOrder.section("Óscar"))
        assertEquals("U", SpanishOrder.section("Úrsula"))
        assertEquals("#", SpanishOrder.section("123"))
        assertEquals("#", SpanishOrder.section("😀"))
        assertEquals("#", SpanishOrder.section("Дмитрий"))
        assertEquals("#", SpanishOrder.section(""))
        assertEquals("Ñ", SpanishOrder.SECTIONS[SpanishOrder.SECTIONS.indexOf("N") + 1])
        assertEquals("#", SpanishOrder.SECTIONS.last())
    }

    @Test
    fun buscarPorNombreSinTildesNiEnie() {
        assertTrue(ContactSearch("munoz").matches("Florencia Muñoz"))
        assertTrue(ContactSearch("MUÑOZ").matches("florencia munoz"))
        assertTrue(ContactSearch("angela").matches("Ángela Ríos"))
        assertTrue(ContactSearch("rio ang").matches("Ángela Ríos"))
        assertTrue(ContactSearch("  ").matches("Cualquiera"))
        assertFalse(ContactSearch("gela").matches("Ángela Ríos"))
        assertFalse(ContactSearch("ana x").matches("Ana Pérez"))
    }

    @Test
    fun guionesYApostrofesSeparanPalabras() {
        assertTrue(ContactSearch("maria").matches("Ana-María O'Neill"))
        assertTrue(ContactSearch("neill").matches("Ana-María O'Neill"))
    }

    @Test
    fun buscarPorNumeroIgnoraElFormato() {
        val phones = listOf("+54 9 11 2345-6789")
        assertTrue(ContactSearch("2345").matches("Ana", phones))
        assertTrue(ContactSearch("2345-67").matches("Ana", phones))
        assertTrue(ContactSearch("(11) 2345").matches("Ana", phones))
        assertFalse(ContactSearch("9999").matches("Ana", phones))
        assertTrue(ContactSearch("+549").isNumber)
        assertFalse(ContactSearch("ana 2").isNumber)
        assertFalse(ContactSearch("5").isNumber)
    }

    @Test
    fun buscarPorNumeroEnOtroFormato() {
        val ar = dev.liquidcontacts.core.phone.PhoneNumbers("AR")
        val stored = listOf("+54 9 11 2345-6789")
        assertTrue(ContactSearch("15 2345", ar::searchForms).matches("Ana", stored))
        assertTrue(ContactSearch("011 15 2345", ar::searchForms).matches("Ana", stored))
        assertTrue(ContactSearch("+54 9 11", ar::searchForms).matches("Ana", listOf("011 15 2345-6789")))
        assertFalse(ContactSearch("15 2345", ar::searchForms).matches("Ana", listOf("+34 612 34 56 78")))
    }

    @Test
    fun buscarPorEmail() {
        assertTrue(ContactSearch("ejemplo.com").matches("Ana", emails = listOf("ana@ejemplo.com")))
        assertFalse(ContactSearch("otro").matches("Ana", emails = listOf("ana@ejemplo.com")))
    }

    @Test
    fun puntajeOrdenaPorComienzo() {
        val s = ContactSearch("ana")
        assertTrue(s.score("Ana Pérez") > s.score("Juana Anaya"))
        assertTrue(s.score("Juana Anaya") > 0)
    }
}
