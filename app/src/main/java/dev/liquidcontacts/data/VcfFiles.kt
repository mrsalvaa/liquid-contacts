// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.data

import android.content.Context
import android.net.Uri
import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.vcard.VCardFormat
import dev.liquidcontacts.core.vcard.VCards
import java.time.LocalDate

/** Archivos .vcf: siempre con el selector del sistema o con permisos temporales, sin almacenamiento. */
object VcfFiles {
    const val MIME = "text/x-vcard"

    fun backupName(): String = "contactos-respaldo-${LocalDate.now()}.vcf"

    /** Escribe en el archivo elegido con el selector; false si no se pudo (sin escribir a medias en silencio). */
    fun write(context: Context, uri: Uri, contacts: Sequence<Contact>, format: VCardFormat, includePhoto: Boolean): Boolean = runCatching {
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("sin archivo")
        stream.bufferedWriter(Charsets.UTF_8).use { VCards.write(it, contacts, format, includePhoto) }
    }.isSuccess
}
