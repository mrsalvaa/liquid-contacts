// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.text

/**
 * Limpieza de texto que viene de afuera (un .vcf, los extras del intent de otra app): sin
 * caracteres de control ni marcas de dirección invisibles (se usan para disfrazar un nombre) y
 * con un largo máximo. Devuelve null si no queda nada.
 */
object Clean {
    const val MAX_LINE = 1000
    const val MAX_NOTE = 20_000
    const val MAX_PHONE = 64
    const val MAX_EMAIL = 254
    const val MAX_URL = 2048

    private fun String.withoutHidden(keepNewlines: Boolean): String = filterNot { ch ->
        val hidden = ch.isISOControl() && !(keepNewlines && ch == '\n')
        hidden || ch in '‪'..'‮' || ch in '⁦'..'⁩' || ch == '‎' || ch == '‏'
    }

    fun line(text: String?, max: Int = MAX_LINE): String? =
        text?.withoutHidden(false)?.trim()?.take(max)?.trim()?.takeIf { it.isNotEmpty() }

    fun multiline(text: String?, max: Int = MAX_NOTE): String? =
        text?.replace("\r\n", "\n")?.replace('\r', '\n')?.withoutHidden(true)?.trim()?.take(max)?.trim()?.takeIf { it.isNotEmpty() }

    /** Un número tiene que tener al menos un dígito (o ser un código de marcado con * o #). */
    fun phone(text: String?): String? =
        line(text, MAX_PHONE)?.removePrefix("tel:")?.trim()?.takeIf { s -> s.any { it.isDigit() } }

    /** Un email: sin espacios y con una sola @ entre medio. */
    fun email(text: String?): String? = line(text, MAX_EMAIL)?.removePrefix("mailto:")?.takeIf { s ->
        s.none(Char::isWhitespace) && s.count { it == '@' } == 1 && !s.startsWith("@") && !s.endsWith("@")
    }

    /** Solo web: un javascript: o intent: guardado como "sitio" no tiene que poder abrirse. */
    fun url(text: String?): String? = line(text, MAX_URL)?.takeIf { s ->
        val scheme = s.substringBefore(':', "").lowercase()
        s.none(Char::isWhitespace) && (scheme.isEmpty() || !scheme.all(Char::isLetter) || scheme == "http" || scheme == "https")
    }
}
