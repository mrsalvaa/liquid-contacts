// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import dev.liquidcontacts.core.model.Contact
import dev.liquidcontacts.core.vcard.VCardFormat
import dev.liquidcontacts.core.vcard.VCardImport
import dev.liquidcontacts.core.vcard.VCardLimits
import dev.liquidcontacts.core.vcard.VCards
import java.io.ByteArrayOutputStream
import java.io.File
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

    fun exportName(): String = "contactos-${LocalDate.now()}.vcf"

    /** Los contactos de a uno, para no tener toda la agenda (con fotos) en memoria. */
    fun contacts(ids: List<Long>, withPhotos: Boolean): Sequence<Contact> =
        ids.asSequence().mapNotNull { if (withPhotos) Repo.contacts.loadFull(it) else Repo.contacts.load(it) }

    /**
     * Prepara compartir: un .vcf temporal en cache/shared y un intent con permiso de lectura
     * temporal sobre ese archivo solo, sin permisos persistentes. Null si no se pudo escribir.
     */
    fun shareIntent(context: Context, ids: List<Long>, format: VCardFormat, withPhotos: Boolean): Intent? = runCatching {
        cleanShared(context)
        val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
        val name = (if (ids.size == 1) "contacto" else "contactos") + "-${System.currentTimeMillis()}.vcf"
        val file = File(dir, name)
        file.bufferedWriter(Charsets.UTF_8).use { VCards.write(it, contacts(ids, withPhotos), format, withPhotos) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // El permiso tiene que viajar también a través del selector de apps.
        send.clipData = ClipData.newRawUri(name, uri)
        Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }.getOrNull()

    /**
     * Borra los .vcf temporales que ya no hacen falta. No hay forma de saber cuándo terminó la app
     * que los recibió, así que se les da [SHARED_KEEP_MS]; se llama al abrir la app, al volver a
     * ella y antes de compartir de nuevo.
     */
    fun cleanShared(context: Context, keepMs: Long = SHARED_KEEP_MS) {
        val limit = System.currentTimeMillis() - keepMs
        File(context.cacheDir, SHARED_DIR).listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
    }

    private const val SHARED_DIR = "shared"
    private const val SHARED_KEEP_MS = 10 * 60 * 1000L

    /** Escribe en el archivo elegido con el selector; false si no se pudo (sin escribir a medias en silencio). */
    fun write(context: Context, uri: Uri, contacts: Sequence<Contact>, format: VCardFormat, includePhoto: Boolean): Boolean = runCatching {
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("sin archivo")
        stream.bufferedWriter(Charsets.UTF_8).use { VCards.write(it, contacts, format, includePhoto) }
    }.isSuccess
}
