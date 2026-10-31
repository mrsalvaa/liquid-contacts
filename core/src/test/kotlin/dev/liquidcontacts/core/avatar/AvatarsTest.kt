// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.avatar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvatarsTest {
    @Test
    fun iniciales() {
        assertEquals("MP", Avatars.initials("María José Pérez"))
        assertEquals("ÁR", Avatars.initials("ángela ríos"))
        assertEquals("Ñ", Avatars.initials("  ñandú "))
        assertEquals("AT", Avatars.initials("Ana (trabajo) Torres"))
        assertEquals("", Avatars.initials("123 456"))
        assertEquals("", Avatars.initials("😀"))
        assertEquals("", Avatars.initials(""))
        assertEquals("T", Avatars.initials("123 Taller"))
    }

    @Test
    fun colorDeterministaYSinImportarTildes() {
        val a = Avatars.colorIndex("Ángela Ríos", 12)
        assertEquals(a, Avatars.colorIndex("Ángela Ríos", 12))
        assertEquals(a, Avatars.colorIndex("angela rios", 12))
        // Valor fijo: si cambia el algoritmo, cambiarían los colores de todos en todas las apps.
        assertEquals(Avatars.colorIndex("Ana", 12), Avatars.colorIndex(" ana ", 12))
        assertTrue((0 until 500).map { Avatars.colorIndex("Contacto $it", 12) }.toSet().size == 12)
        assertTrue((0 until 100).all { Avatars.colorIndex("x$it", 7) in 0 until 7 })
    }
}
