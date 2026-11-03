// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.ui.screens

import dev.liquidcontacts.R
import dev.liquidcontacts.core.model.ContactEvent
import dev.liquidcontacts.core.model.EventKind
import dev.liquidcontacts.core.model.LabelKind
import dev.liquidcontacts.core.model.Labeled
import dev.liquidcontacts.core.model.Email
import dev.liquidcontacts.contacts.ContactAccount
import dev.liquidcontacts.util.str

/** Nombres de los tipos de dato (Móvil, Casa, Cumpleaños...) y de las cuentas. */
object Labels {
    val PHONE_KINDS = listOf(LabelKind.MOBILE, LabelKind.HOME, LabelKind.WORK, LabelKind.MAIN, LabelKind.FAX_WORK, LabelKind.FAX_HOME, LabelKind.PAGER, LabelKind.OTHER)
    val EMAIL_KINDS = listOf(LabelKind.HOME, LabelKind.WORK, LabelKind.MOBILE, LabelKind.OTHER)
    val ADDRESS_KINDS = listOf(LabelKind.HOME, LabelKind.WORK, LabelKind.OTHER)
    val EVENT_KINDS = listOf(EventKind.BIRTHDAY, EventKind.ANNIVERSARY, EventKind.OTHER)

    fun kind(k: LabelKind, email: Boolean = false): String = str(
        when (k) {
            LabelKind.MOBILE -> R.string.kind_mobile
            LabelKind.HOME -> if (email) R.string.kind_personal else R.string.kind_home
            LabelKind.WORK -> R.string.kind_work
            LabelKind.MAIN -> R.string.kind_main
            LabelKind.FAX_WORK -> R.string.kind_fax_work
            LabelKind.FAX_HOME -> R.string.kind_fax_home
            LabelKind.PAGER -> R.string.kind_pager
            LabelKind.OTHER -> R.string.kind_other
            LabelKind.CUSTOM -> R.string.kind_custom
        },
    )

    fun event(k: EventKind): String = str(
        when (k) {
            EventKind.BIRTHDAY -> R.string.kind_birthday
            EventKind.ANNIVERSARY -> R.string.kind_anniversary
            EventKind.OTHER -> R.string.kind_other_date
            EventKind.CUSTOM -> R.string.kind_custom
        },
    )

    fun of(item: Labeled): String =
        if (item.kind == LabelKind.CUSTOM && !item.label.isNullOrBlank()) item.label!! else kind(item.kind, email = item is Email)

    fun of(e: ContactEvent): String =
        e.label?.takeIf { e.kind == EventKind.CUSTOM && it.isNotBlank() } ?: event(e.kind)

    /** "Este celular" para la cuenta local; si no, el nombre de la cuenta. */
    fun account(a: ContactAccount, local: ContactAccount?): String = when {
        a.type == null || a == local -> str(R.string.account_local)
        else -> serverLabel(a) ?: a.name ?: a.type
    }

    /** Una libreta del servidor con apodo: "Apodo · Libreta". */
    private fun serverLabel(a: ContactAccount): String? {
        if (a.type != str(R.string.account_type)) return null
        val server = dev.liquidcontacts.sync.SyncEngine.store.value
        if (server.nickname.isEmpty()) return null
        val book = server.books.firstOrNull { it.accountName == a.name } ?: return server.nickname
        return "${server.nickname} · ${book.name}"
    }
}
