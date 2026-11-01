// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContactSyncPlanTest {
    private fun card(id: Long, href: String? = "h$id", etag: String? = "e$id", dirty: Boolean = false, deleted: Boolean = false) =
        LocalCard(id, href, etag, dirty, deleted)

    private fun remote(vararg hrefs: String) = hrefs.associateWith { "e" + it.drop(1) }

    @Test
    fun sinCambiosNoHaceNada() {
        assertEquals(emptyList(), ContactSyncPlan.plan(remote("h1", "h2"), listOf(card(1), card(2))))
    }

    @Test
    fun nuevoEnElServidorSeBaja() {
        assertEquals(listOf(SyncOp.Download("h9")), ContactSyncPlan.plan(mapOf("h9" to "x"), emptyList()))
    }

    @Test
    fun cambioEnElServidorSeBaja() {
        assertEquals(listOf(SyncOp.Download("h1")), ContactSyncPlan.plan(mapOf("h1" to "otro"), listOf(card(1))))
    }

    @Test
    fun edicionEnElCeluSeSube() {
        assertEquals(listOf(SyncOp.Update(1, "h1", "e1")), ContactSyncPlan.plan(remote("h1"), listOf(card(1, dirty = true))))
    }

    @Test
    fun nuevoEnElCeluSeCrea() {
        assertEquals(listOf(SyncOp.Create(5)), ContactSyncPlan.plan(emptyMap(), listOf(card(5, href = null, etag = null, dirty = true))))
    }

    @Test
    fun borradosEnCadaLado() {
        assertEquals(listOf(SyncOp.DeleteRemote(1, "h1", "e1")), ContactSyncPlan.plan(remote("h1"), listOf(card(1, deleted = true))))
        assertEquals(listOf(SyncOp.DeleteLocal(1)), ContactSyncPlan.plan(emptyMap(), listOf(card(1))))
        assertEquals(listOf(SyncOp.Forget(1)), ContactSyncPlan.plan(emptyMap(), listOf(card(1, deleted = true))))
        assertEquals(listOf(SyncOp.Forget(1)), ContactSyncPlan.plan(emptyMap(), listOf(card(1, href = null, etag = null, deleted = true))))
    }

    @Test
    fun conflictos() {
        assertEquals(listOf(SyncOp.Conflict(1, "h1", ConflictKind.BOTH_EDITED)), ContactSyncPlan.plan(mapOf("h1" to "otro"), listOf(card(1, dirty = true))))
        assertEquals(listOf(SyncOp.Conflict(1, "h1", ConflictKind.EDITED_HERE_DELETED_THERE)), ContactSyncPlan.plan(emptyMap(), listOf(card(1, dirty = true))))
        assertEquals(listOf(SyncOp.Conflict(1, "h1", ConflictKind.DELETED_HERE_EDITED_THERE)), ContactSyncPlan.plan(mapOf("h1" to "otro"), listOf(card(1, deleted = true))))
    }

    @Test
    fun sinEtagDelServidorNuncaSePisa() {
        assertEquals(listOf(SyncOp.Conflict(1, "h1", ConflictKind.BOTH_EDITED)), ContactSyncPlan.plan(mapOf("h1" to null), listOf(card(1, dirty = true))))
    }

    @Test
    fun loEnConflictoNoSeVuelveATocar() {
        assertEquals(emptyList(), ContactSyncPlan.plan(mapOf("h1" to "otro"), listOf(card(1, dirty = true)), skip = setOf(1)))
    }

    @Test
    fun primeraSincronizacionSoloBaja() {
        val ops = ContactSyncPlan.plan(
            mapOf("h9" to "x", "h1" to "e1"),
            listOf(card(1, deleted = true), card(2), card(3, href = null, etag = null, dirty = true), card(4, dirty = true)),
        )
        assertTrue(ops.any { it is SyncOp.DeleteLocal } && ops.any { it is SyncOp.Create } && ops.any { it is SyncOp.DeleteRemote })
        assertEquals(listOf(SyncOp.Download("h9")), ContactSyncPlan.firstSync(ops))
    }

    private fun locals(n: Int) = (1L..n).map { card(it) }

    @Test
    fun libretaVaciaFrenaSiempre() {
        val ops = ContactSyncPlan.plan(emptyMap(), locals(3))
        assertEquals(MassDeleteHold.Server(3, 0, 3), ContactSyncPlan.massDelete(ops, 3, 0, 3))
    }

    @Test
    fun masDeLaMitadYMasDeDiezFrena() {
        val all = locals(30)
        val r = (19..30).associate { "h$it" to "e$it" } // quedan 12: se borrarían 18 de 30
        val ops = ContactSyncPlan.plan(r, all)
        assertEquals(MassDeleteHold.Server(30, 12, 18), ContactSyncPlan.massDelete(ops, 30, 12, 30))
    }

    @Test
    fun laMitadJustaOPocosNoFrena() {
        val half = ContactSyncPlan.plan((16..30).associate { "h$it" to "e$it" }, locals(30)) // 15 de 30
        assertNull(ContactSyncPlan.massDelete(half, 30, 15, 30))
        val few = ContactSyncPlan.plan((9..12).associate { "h$it" to "e$it" }, locals(12)) // 8 de 12: más de la mitad, no más de 10
        assertNull(ContactSyncPlan.massDelete(few, 12, 4, 12))
        val ten = ContactSyncPlan.plan((11..12).associate { "h$it" to "e$it" }, locals(12)) // exactamente 10
        assertNull(ContactSyncPlan.massDelete(ten, 12, 2, 12))
    }

    @Test
    fun borradoMasivoHaciaElServidorFrena() {
        val cards = (1L..30).map { card(it, deleted = it <= 18) }
        val r = (1..30).associate { "h$it" to "e$it" }
        val hold = ContactSyncPlan.massDelete(ContactSyncPlan.plan(r, cards), 30, 30, 30)
        assertIs<MassDeleteHold.Local>(hold)
        assertEquals(18, hold.deletes)
    }

    @Test
    fun libretaNuevaVaciaNoFrena() {
        assertNull(ContactSyncPlan.massDelete(emptyList(), 0, 0, 0))
    }

    @Test
    fun laUltimaCantidadCuentaAunqueHayaMenosFichas() {
        // Antes había 40; localmente ya quedan 12 y el servidor devuelve 0.
        val ops = ContactSyncPlan.plan(emptyMap(), locals(12))
        assertEquals(MassDeleteHold.Server(40, 0, 12), ContactSyncPlan.massDelete(ops, 40, 0, 12))
    }
}
