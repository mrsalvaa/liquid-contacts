// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.vcard.VCardFormat
import dev.liquidcontacts.core.vcard.VCardImport
import dev.liquidcontacts.core.vcard.VCardLimits
import dev.liquidcontacts.core.vcard.VCards
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/** Archivos .vcf: siempre con el selector del sistema o con permisos temporales, sin almacenamiento. */
object VcfFiles {
    const val MIME = "text/x-vcard"

    /** Tipos con los que aparece un .vcf en el selector de archivos. */
    val OPEN_TYPES = arrayOf("text/x-vcard", "text/vcard", "text/directory", "application/octet-stream")

    fun backupName(): String = "contactos-respaldo-${LocalDate.now()}.vcf"

    /**
     * Lee un .vcf de otra app o del selector, sin pasar del límite: el archivo no se carga entero
     * si es más grande. Nunca lanza: cualquier problema (sin permiso, archivo roto, memoria) es
     * un resultado.
     */
    fun read(context: Context, uri: Uri, limits: VCardLimits = VCardLimits()): VCardImport = runCatching {
        val stream = context.contentResolver.openInputStream(uri) ?: return VCardImport.Invalid
        val bytes = stream.use { it.readNBytes(limits.maxBytes + 1) }
        VCards.read(bytes, limits)
    }.getOrDefault(VCardImport.Invalid)

    /**
     * La foto de un .vcf lista para guardar: tiene que ser una imagen de verdad, y se achica a
     * [PHOTO_SIDE] px en JPEG (una foto grande no entra en una transacción de la agenda).
     * Null si los bytes no son una imagen.
     */
    fun normalizePhoto(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        // Una imagen chica que dice medir 30000 px no llega a decodificarse entera.
        if (longest <= 0 || bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) return null
        var sample = 1
        while (longest / (sample * 2) >= PHOTO_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = PHOTO_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        return ByteArrayOutputStream().use { s ->
            out.compress(Bitmap.CompressFormat.JPEG, 88, s)
            s.toByteArray()
        }
    }

    private const val PHOTO_SIDE = 720
    private const val MAX_PIXELS = 50_000_000L

    /** Escribe en el archivo elegido con el selector; false si no se pudo (sin escribir a medias en silencio). */
    fun write(context: Context, uri: Uri, contacts: Sequence<Contact>, format: VCardFormat, includePhoto: Boolean): Boolean = runCatching {
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("sin archivo")
        stream.bufferedWriter(Charsets.UTF_8).use { VCards.write(it, contacts, format, includePhoto) }
    }.isSuccess
}
