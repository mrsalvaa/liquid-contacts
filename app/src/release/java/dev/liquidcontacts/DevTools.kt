// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts

import dev.liquidcontacts.contacts.SystemContacts

/** En la release no hay herramientas de prueba: la agenda inventada solo existe en debug. */
object DevTools {
    const val available = false

    @Suppress("UNUSED_PARAMETER")
    fun generate(contacts: SystemContacts, count: Int): Int = 0

    @Suppress("UNUSED_PARAMETER")
    fun clear(contacts: SystemContacts): Int = 0
}
