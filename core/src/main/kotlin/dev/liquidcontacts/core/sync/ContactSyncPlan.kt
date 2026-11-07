// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.sync

/** Una ficha local de la cuenta: su URL en el servidor (null si nunca se subió) y su estado. */
data class LocalCard(val rawId: Long, val href: String?, val etag: String?, val dirty: Boolean, val deleted: Boolean)

enum class ConflictKind {
    /** Cambió en el celu y en el servidor. */
    BOTH_EDITED,

    /** Se editó en el celu y se borró en el servidor. */
    EDITED_HERE_DELETED_THERE,

    /** Se borró en el celu y se editó en el servidor. */
    DELETED_HERE_EDITED_THERE,
}

sealed interface SyncOp {
    data class Download(val href: String) : SyncOp
    data class Create(val rawId: Long) : SyncOp
    data class Update(val rawId: Long, val href: String, val etag: String) : SyncOp
    data class DeleteRemote(val rawId: Long, val href: String, val etag: String) : SyncOp
    data class DeleteLocal(val rawId: Long) : SyncOp

    /** La ficha ya estaba borrada en los dos lados: solo falta quitarla del celu. */
    data class Forget(val rawId: Long) : SyncOp
    data class Conflict(val rawId: Long, val href: String, val kind: ConflictKind) : SyncOp
}

/** Por qué se frena una sincronización antes de borrar. */
sealed interface MassDeleteHold {
    /** El servidor devolvió mucho menos que antes: se borrarían [deletes] de [before] en el celu. */
    data class Server(val before: Int, val now: Int, val deletes: Int) : MassDeleteHold

    /** Se borraron muchos en el celu: se borrarían [deletes] de [before] en el servidor. */
    data class Local(val before: Int, val deletes: Int) : MassDeleteHold
}

/**
 * Qué hacer en una sincronización, sin tocar nada: se compara por ETag lo del servidor con las
 * fichas de la cuenta (nunca otras cuentas: solo se pasan las propias). Nada se pisa solo: si
 * cambió en los dos lados es un conflicto. Función pura: se prueba sin servidor.
 */
object ContactSyncPlan {
    /** Más de la mitad y más de esta cantidad es un borrado masivo. */
    const val MASS_DELETE_MIN = 10

    fun plan(remote: Map<String, String?>, local: List<LocalCard>, skip: Set<Long> = emptySet()): List<SyncOp> {
        val ops = ArrayList<SyncOp>()
        val known = HashSet<String>()
        for (c in local) {
            if (c.href != null) known += c.href
            if (c.rawId in skip) continue
            val href = c.href
            if (href == null) {
                ops += if (c.deleted) SyncOp.Forget(c.rawId) else SyncOp.Create(c.rawId)
            } else if (href !in remote) {
                ops += when {
                    c.deleted -> SyncOp.Forget(c.rawId)
                    c.dirty -> SyncOp.Conflict(c.rawId, href, ConflictKind.EDITED_HERE_DELETED_THERE)
                    else -> SyncOp.DeleteLocal(c.rawId)
                }
            } else {
                val serverEtag = remote[href]
                val same = serverEtag != null && serverEtag == c.etag
                when {
                    same && c.deleted -> ops += SyncOp.DeleteRemote(c.rawId, href, serverEtag!!)
                    same && c.dirty -> ops += SyncOp.Update(c.rawId, href, serverEtag!!)
                    same -> Unit
                    c.deleted -> ops += SyncOp.Conflict(c.rawId, href, ConflictKind.DELETED_HERE_EDITED_THERE)
                    c.dirty -> ops += SyncOp.Conflict(c.rawId, href, ConflictKind.BOTH_EDITED)
                    else -> ops += SyncOp.Download(href)
                }
            }
        }
        remote.keys.filterNot { it in known }.forEach { ops += SyncOp.Download(it) }
        return ops
    }

    /**
     * Primera sincronización de una libreta: solo se baja. Nada se sube ni se borra, aunque la
     * cuenta tuviera algo. Lo demás queda para las siguientes, con todas las reglas.
     */
    fun firstSync(ops: List<SyncOp>): List<SyncOp> = ops.filterIsInstance<SyncOp.Download>()

    /**
     * Si hay que frenar antes de borrar: libreta vacía cuando antes tenía algo, o más de la mitad
     * y más de [MASS_DELETE_MIN]. [lastCount] es cuántos había la última vez.
     */
    fun massDelete(ops: List<SyncOp>, lastCount: Int, remoteCount: Int, localCount: Int): MassDeleteHold? {
        val deletesHere = ops.count { it is SyncOp.DeleteLocal }
        val before = maxOf(lastCount, localCount)
        if (deletesHere > 0 && ((remoteCount == 0 && before > 0) || isMass(deletesHere, before))) {
            return MassDeleteHold.Server(before, remoteCount, deletesHere)
        }
        val deletesThere = ops.count { it is SyncOp.DeleteRemote }
        val onServer = maxOf(lastCount, remoteCount)
        if (isMass(deletesThere, onServer)) return MassDeleteHold.Local(onServer, deletesThere)
        return null
    }

    /** Si borrar [deletes] de [before] es un borrado masivo (hay que confirmarlo antes). */
    fun isMass(deletes: Int, before: Int) = deletes > MASS_DELETE_MIN && deletes * 2 > before
}
