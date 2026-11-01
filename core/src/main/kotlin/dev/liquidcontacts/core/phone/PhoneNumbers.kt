// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.phone

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber

/**
 * Un número tal como sirve para comparar.
 * - [exact]: forma canónica completa (E.164, y en Argentina sin el 9 de los móviles), o null si
 *   el número no se pudo completar (le falta el código de área, es un código corto, etc.).
 * - [tail]: los últimos 8 dígitos, para la coincidencia aproximada de números sin código de área.
 * - [digits]: todos los dígitos, tal cual se escribieron.
 */
data class PhoneKey(val exact: String?, val tail: String?, val digits: String)

/**
 * Normalización de números pensada para Argentina, sin red (libphonenumber trae los metadatos
 * de todos los países dentro del jar).
 *
 * En Argentina el número nacional tiene 10 dígitos (código de área + abonado) y es único entre
 * fijos y móviles: el "15" (al marcar dentro del país) y el "9" (después de +54) solo indican que
 * se llama a un móvil. Por eso 011 15 1234-5678, +54 9 11 1234-5678 y +54 11 1234-5678 tienen la
 * misma clave exacta, +541112345678.
 */
class PhoneNumbers(
    /** País (ISO 3166, p. ej. "AR") para los números sin prefijo internacional. */
    private val region: String = "AR",
    /**
     * Código de área para los números que se guardaron sin él (p. ej. "11" o "351"), sin el 0.
     * Vacío o null: esos números no tienen clave exacta y solo coinciden por [PhoneKey.tail].
     */
    defaultAreaCode: String? = null,
) {
    private val util = PhoneNumberUtil.getInstance()
    private val countryCode = util.getCountryCodeForRegion(region)
    private val area: String? = defaultAreaCode?.filter(Char::isDigit)?.trimStart('0')?.takeIf { it.length in 2..4 }

    /** Si el código de área por defecto se puede usar con este país (por ahora, solo Argentina). */
    val supportsAreaCode: Boolean get() = region == "AR"

    fun key(raw: String): PhoneKey {
        val digits = raw.filter(Char::isDigit)
        val parsed = parse(raw) ?: completeWithArea(raw)
        // La cola son los últimos 8 dígitos del abonado (sin código de área), y solo si tiene 8 o
        // más: en el interior son de 6 o 7, y tomar 8 mezclaría dígitos del código de área. Un
        // número completo de otro país no tiene cola: nunca coincide con uno local incompleto.
        val tail = if (parsed != null) {
            val nsn = util.getNationalSignificantNumber(parsed)
            val subscriber = nsn.length - util.getLengthOfNationalDestinationCode(parsed)
            nsn.takeLast(TAIL).takeIf { parsed.countryCode == countryCode && subscriber >= TAIL }
        } else {
            localDigits(digits).takeIf { it.length >= TAIL }?.takeLast(TAIL)
        }
        return PhoneKey(parsed?.let(::canonical), tail, digits)
    }

    private fun localDigits(digits: String): String {
        if (region != "AR") return digits
        val d = digits.trimStart('0')
        return if (d.startsWith("15") && d.length - 2 in 6..8) d.drop(2) else d
    }

    /** Forma canónica completa, o null si el número no está completo. */
    fun exact(raw: String): String? {
        val parsed = parse(raw) ?: completeWithArea(raw) ?: return null
        return canonical(parsed)
    }

    /**
     * Si parece un número local al que le falta el código de área ("15 2345-6789", "421-0000"):
     * no se pudo completar, no es internacional y tiene entre 6 y 10 dígitos (los códigos cortos
     * y de marcado no cuentan). Es lo que arregla el ajuste "Código de área por defecto", que por
     * ahora solo existe para Argentina: con otro país, siempre false.
     */
    fun missingAreaCode(raw: String): Boolean {
        if (!supportsAreaCode) return false
        val text = raw.trim()
        if (text.startsWith("+") || text.startsWith("00") || text.any { it == '*' || it == '#' }) return false
        val digits = text.filter(Char::isDigit)
        return digits.length in 6..10 && parse(raw) == null && completeWithArea(raw) == null
    }

    /** Para mostrar: formato internacional o nacional según el país; si no se entiende, tal cual. */
    fun format(raw: String): String {
        val parsed = parse(raw) ?: completeWithArea(raw) ?: return raw.trim()
        val national = util.getRegionCodeForNumber(parsed) == region
        return util.format(parsed, if (national) PhoneNumberFormat.NATIONAL else PhoneNumberFormat.INTERNATIONAL)
    }

    /**
     * Los dígitos de un número en todas sus formas habituales, para buscar escribiendo una parte:
     * tal como se guardó, internacional (5491123456789) y nacional con 0 y 15 (01115 23456789).
     */
    fun searchForms(raw: String): List<String> {
        val digits = raw.filter(Char::isDigit)
        val parsed = parse(raw) ?: completeWithArea(raw) ?: return listOf(digits)
        val international = util.format(parsed, PhoneNumberFormat.E164).drop(1)
        val national = util.format(parsed, PhoneNumberFormat.NATIONAL).filter(Char::isDigit)
        return listOf(digits, international, national).distinct()
    }

    private fun parse(raw: String): PhoneNumber? {
        // libphonenumber entiende "ext." y "x" pero no "int." ni "interno".
        val text = EXTENSION.replace(raw.trim(), " ext. $1")
        if (text.count(Char::isDigit) < 6) return null
        return try {
            util.parse(text, region).takeIf { util.isValidNumber(it) }
        } catch (e: NumberParseException) {
            null
        }
    }

    /**
     * Completa un número local con [area]: "15 1234-5678" o "4567-8901" con área 11. En Argentina
     * área + abonado suman 10 dígitos, así que si sobra un "15" adelante, es un móvil.
     */
    private fun completeWithArea(raw: String): PhoneNumber? {
        val area = area ?: return null
        if (region != "AR" || raw.trim().startsWith("+")) return null
        val digits = raw.filter(Char::isDigit)
        val subscriber = 10 - area.length
        val national = when {
            digits.length == subscriber -> "0$area$digits"
            digits.length == subscriber + 2 && digits.startsWith("15") -> "0${area}15${digits.drop(2)}"
            else -> return null
        }
        return parse(national)
    }

    private fun canonical(n: PhoneNumber): String {
        val e164 = util.format(n, PhoneNumberFormat.E164)
        // +54 9 AREA NÚMERO → +54 AREA NÚMERO (el 9 solo indica móvil, ver arriba).
        return if (n.countryCode == 54 && e164.length == 14 && e164[3] == '9') "+54" + e164.substring(4) else e164
    }

    companion object {
        /** Países que libphonenumber sabe interpretar (códigos ISO 3166, p. ej. "AR"). */
        fun regions(): Set<String> = PhoneNumberUtil.getInstance().supportedRegions

        /** Código de llamada de un país ("AR" → 54), o 0 si no se conoce. */
        fun callingCode(region: String): Int = PhoneNumberUtil.getInstance().getCountryCodeForRegion(region)

        /** Dígitos de la coincidencia aproximada: el abonado más corto de Argentina tiene 6, el de Buenos Aires 8. */
        const val TAIL = 8

        private val EXTENSION = Regex("\\s*(?:int\\.?|interno|extensi[oó]n)\\s*(\\d{1,6})\\s*$", RegexOption.IGNORE_CASE)
    }
}

/** Resultado de buscar a quién pertenece un número. */
sealed interface PhoneMatch<out T> {
    data class One<T>(val item: T, val approximate: Boolean) : PhoneMatch<T>

    /** Varios candidatos: no se elige ninguno; la interfaz los muestra o deja el número sin vincular. */
    data class Ambiguous<T>(val candidates: List<T>, val approximate: Boolean) : PhoneMatch<T>
    data object None : PhoneMatch<Nothing>
}

/**
 * Reconoce a quién pertenece un número. Primero por la clave exacta; si no hay, y alguno de los
 * dos números está incompleto (sin código de área), por los últimos 8 dígitos. Dos números
 * completos con distinto código de área nunca coinciden.
 *
 * Esta coincidencia aproximada es solo para reconocer números (llamadas, mensajes). Nunca se usa
 * para detectar ni unir duplicados (ver Duplicates).
 */
class PhoneMatcher<T>(
    private val numbers: PhoneNumbers,
    items: Collection<T>,
    phonesOf: (T) -> Collection<String>,
) {
    private val byExact = HashMap<String, MutableSet<T>>()

    /** Por los últimos 8 dígitos: (dueño, si ese número suyo está completo). */
    private val byTail = HashMap<String, MutableList<Pair<T, Boolean>>>()

    init {
        for (item in items) {
            for (raw in phonesOf(item)) {
                val k = numbers.key(raw)
                k.exact?.let { byExact.getOrPut(it) { LinkedHashSet() }.add(item) }
                k.tail?.let { byTail.getOrPut(it) { ArrayList() }.add(item to (k.exact != null)) }
            }
        }
    }

    fun match(raw: String): PhoneMatch<T> {
        val k = numbers.key(raw)
        k.exact?.let { exact ->
            byExact[exact]?.let { return result(it.toList(), approximate = false) }
        }
        val tail = k.tail ?: return PhoneMatch.None
        // Si el número buscado está completo, solo vale contra números guardados incompletos.
        val candidates = byTail[tail].orEmpty()
            .filter { (_, complete) -> k.exact == null || !complete }
            .map { it.first }
            .distinct()
        return result(candidates, approximate = true)
    }

    private fun result(list: List<T>, approximate: Boolean): PhoneMatch<T> = when (list.size) {
        0 -> PhoneMatch.None
        1 -> PhoneMatch.One(list[0], approximate)
        else -> PhoneMatch.Ambiguous(list, approximate)
    }
}
