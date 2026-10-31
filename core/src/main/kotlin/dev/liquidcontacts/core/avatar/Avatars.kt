// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.avatar

import dev.liquidcontacts.core.text.Fold

/**
 * Avatar sin foto: iniciales y un color que sale siempre igual para el mismo contacto (en todas
 * las apps de la suite, y entre una apertura y otra).
 */
object Avatars {
    /**
     * Hasta dos iniciales: primera y última palabra del nombre ("María José Pérez" → "MP").
     * Si el nombre no empieza con letras (un número, un emoji), devuelve "" y la interfaz muestra
     * un ícono de persona.
     */
    fun initials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { w -> w.firstOrNull()?.isLetter() == true }
        if (words.isEmpty()) return ""
        val first = words.first().firstGrapheme()
        val last = if (words.size > 1) words.last().firstGrapheme() else ""
        return (first + last).uppercase()
    }

    /**
     * Índice de color en una paleta de [paletteSize] colores. Usa una clave estable del contacto
     * (la lookup key de Android o, si no hay, el nombre sin tildes), con FNV-1a: no depende de
     * hashCode() de la JVM ni de la versión de Android.
     */
    fun colorIndex(key: String, paletteSize: Int): Int {
        require(paletteSize > 0)
        var h = 0x811C9DC5.toInt()
        for (b in Fold.fold(key.trim()).toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toInt() and 0xFF)
            h *= 0x01000193
        }
        return Math.floorMod(h, paletteSize)
    }

    /** Primer carácter visible, sin partir pares sustitutos (letras fuera del BMP). */
    private fun String.firstGrapheme(): String = if (isEmpty()) "" else String(Character.toChars(codePointAt(0)))
}
