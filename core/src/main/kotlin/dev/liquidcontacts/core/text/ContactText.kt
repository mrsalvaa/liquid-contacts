// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.text

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** Texto para buscar: minúsculas y sin tildes, diéresis ni eñes ("Muñoz Ávila" → "munoz avila"). */
object Fold {
    private val marks = Regex("\\p{Mn}+")

    fun fold(text: String): String =
        marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
}

/**
 * Orden alfabético en español: ignora tildes y mayúsculas, la ñ va después de la n, y lo que no
 * empieza con letra (números, símbolos, emojis) va al final, como en la agenda de Android.
 */
class SpanishOrder(locale: Locale = Locale.forLanguageTag("es")) : Comparator<String> {
    // Collator no es seguro entre hilos: uno por instancia, y la instancia no se comparte.
    private val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }

    override fun compare(a: String, b: String): Int {
        val la = startsWithLetter(a)
        val lb = startsWithLetter(b)
        if (la != lb) return if (la) -1 else 1
        val c = collator.compare(a.trim(), b.trim())
        return if (c != 0) c else a.compareTo(b)
    }

    companion object {
        fun startsWithLetter(s: String): Boolean = s.trimStart().firstOrNull()?.isLetter() == true

        /**
         * Letra del índice lateral: la inicial sin tilde y en mayúscula ("Ángela" → A), Ñ aparte
         * (va entre N y O) y "#" para lo que no empieza con una letra latina.
         */
        fun section(name: String): String {
            val first = name.trimStart().firstOrNull() ?: return "#"
            if (first == 'ñ' || first == 'Ñ') return "Ñ"
            val base = Fold.fold(first.toString()).firstOrNull()?.uppercaseChar() ?: return "#"
            return if (base in 'A'..'Z') base.toString() else "#"
        }

        /** Las letras del índice en orden, con Ñ después de N y # al final. */
        val SECTIONS: List<String> = ('A'..'N').map(Char::toString) + "Ñ" + ('O'..'Z').map(Char::toString) + "#"
    }
}

/**
 * Búsqueda de contactos por nombre (sin importar tildes, eñes ni mayúsculas, cada palabra de la
 * búsqueda tiene que ser el comienzo de alguna palabra del nombre) o por número (los dígitos
 * buscados aparecen dentro del número, sin importar espacios, guiones ni el formato).
 */
class ContactSearch(
    query: String,
    /** Formas de un número para buscar (ver PhoneNumbers.searchForms); por defecto, sus dígitos. */
    private val phoneForms: (String) -> List<String> = { listOf(it.filter(Char::isDigit)) },
) {
    private val folded = Fold.fold(query).trim()
    private val words = folded.split(SEPARATORS).filter { it.isNotEmpty() }
    private val digits = query.filter(Char::isDigit)

    /** Si la búsqueda parece un número: solo dígitos y signos de teléfono, con al menos 2 dígitos. */
    val isNumber: Boolean = digits.length >= 2 && query.all { it.isDigit() || it in PHONE_SIGNS }

    val isEmpty: Boolean get() = words.isEmpty()

    /** 0 si no coincide; más alto cuanto mejor (para ordenar resultados). */
    fun score(name: String, phones: Collection<String> = emptyList(), emails: Collection<String> = emptyList()): Int {
        if (isEmpty) return 1
        if (isNumber) {
            return if (phones.any { p -> phoneForms(p).any { it.contains(digits) } }) 2 else 0
        }
        val nameWords = Fold.fold(name).split(SEPARATORS).filter { it.isNotEmpty() }
        val matchesName = words.all { w -> nameWords.any { it.startsWith(w) } }
        if (matchesName) {
            // Mejor si el nombre empieza con la búsqueda ("ana" → "Ana Pérez" antes que "Juana Ana").
            return if (nameWords.firstOrNull()?.startsWith(words[0]) == true) 4 else 3
        }
        return if (emails.any { Fold.fold(it).contains(folded) }) 1 else 0
    }

    fun matches(name: String, phones: Collection<String> = emptyList(), emails: Collection<String> = emptyList()): Boolean =
        score(name, phones, emails) > 0

    private companion object {
        val SEPARATORS = Regex("[\\s,.;:()\\-_/'\"]+")
        const val PHONE_SIGNS = "+ -().#*"
    }
}
