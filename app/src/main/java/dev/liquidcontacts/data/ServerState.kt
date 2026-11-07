// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.data

import dev.liquidcontacts.core.sync.ConflictKind
import kotlinx.serialization.Serializable

/** Un contacto que cambió en los dos lados: espera a que el usuario elija. */
@Serializable
data class SyncConflict(
    val rawId: Long,
    val href: String,
    val kind: ConflictKind,
    val serverVcard: String? = null,
    val serverEtag: String? = null,
    val title: String = "",
)

/** Frenado antes de borrar: espera confirmar o cancelar. */
@Serializable
data class SyncHold(val onServerSide: Boolean, val before: Int, val now: Int, val deletes: Int)

/** Un contacto borrado acá que falta borrar en el servidor: su URL y la versión que se borró. */
@Serializable
data class PendingDelete(val href: String, val etag: String?)

/** Una libreta elegida, con su cuenta de Android. */
@Serializable
data class BookState(
    val url: String,
    val name: String,
    val accountName: String,
    val readOnly: Boolean = false,
    val ctag: String? = null,
    val lastCount: Int = 0,
    val firstSyncDone: Boolean = false,
    val hold: SyncHold? = null,
    /** El usuario confirmó el borrado masivo: vale para la próxima sincronización y nada más. */
    val massDeleteConfirmed: Boolean = false,
    val conflicts: List<SyncConflict> = emptyList(),
    val failedUploads: Int = 0,
    val lastSync: Long = 0,
    /** Color de la libreta en el servidor (ARGB), o null si no tiene. */
    val color: Int? = null,
    /**
     * Borrados hechos en esta app que faltan subir. Se anotan antes de borrar la ficha porque
     * algunos celulares (Samsung) la eliminan del todo en vez de marcarla como borrada, y entonces
     * la sincronización no tendría cómo enterarse.
     */
    val pendingDeletes: List<PendingDelete> = emptyList(),
)

/**
 * El servidor registrado (uno solo), en files/server.json. La contraseña no está acá: va cifrada
 * en [CredentialStore]. Sin servidor no se hace ninguna conexión.
 */
@Serializable
data class ServerState(
    val url: String = "",
    /** Apodo que elige el usuario, para reconocer el servidor en la app. */
    val nickname: String = "",
    val username: String = "",
    val allowHttp: Boolean = false,
    /** Huella SHA-256 fijada, o CA importada en PEM (nunca las dos). */
    val pinSha256: String? = null,
    val caPem: String? = null,
    val syncIntervalMinutes: Int = 60,
    val books: List<BookState> = emptyList(),
    val needsPassword: Boolean = false,
    val loginFailures: List<Long> = emptyList(),
    val waitUntil: Long = 0,
    val retryAttempt: Int = 0,
    val lastError: String? = null,
) {
    val configured: Boolean get() = url.isNotEmpty()
    /** Algo que el usuario tiene que resolver. */
    val needsAttention: Boolean get() = needsPassword || books.any { it.hold != null || it.conflicts.isNotEmpty() }
}
