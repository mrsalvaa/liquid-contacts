// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import dev.liquidcontacts.R
import dev.liquidcontacts.contacts.ContactAccount
import dev.liquidcontacts.contacts.SystemContacts
import dev.liquidcontacts.core.model.ContactSummary
import dev.liquidcontacts.core.phone.PhoneNumbers
import dev.liquidcontacts.core.text.SpanishOrder
import dev.liquidcontacts.sync.SyncEngine
import dev.liquidcontacts.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/** Contacto de la lista, ya con su letra del índice. */
data class Row(val summary: ContactSummary, val section: String)

/** La agenda lista para mostrar: ordenada en español y con las letras que tienen contactos. */
data class Book(val rows: List<Row>, val loaded: Boolean) {
    val sections: Set<String> = rows.mapTo(LinkedHashSet()) { it.section }
}

/**
 * Estado de la app: la agenda del sistema leída y ordenada, que se vuelve a leer cuando cambia
 * (desde esta app o cualquier otra), y el normalizador de números según los ajustes.
 */
@SuppressLint("StaticFieldLeak") // Solo el contexto de la aplicación: vive lo mismo que el proceso.
object Repo {
    lateinit var contacts: SystemContacts
        private set
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watching: Job? = null

    private val _book = MutableStateFlow(Book(emptyList(), loaded = false))
    val book: StateFlow<Book> = _book.asStateFlow()

    private val _numbers = MutableStateFlow(PhoneNumbers("AR"))

    /** Normalizador con el país y el código de área de los ajustes. */
    val numbers: StateFlow<PhoneNumbers> = _numbers.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
        contacts = SystemContacts(app)
        Trash.init(app)
        // País por defecto: la primera vez, el de la SIM (no el de la red, que cambia al viajar).
        if (Stores.settings.value.defaultCountry.isEmpty()) {
            Stores.settings.update { it.copy(defaultCountry = simCountry(app)) }
        }
        scope.launch {
            Stores.settings.state.map { it.defaultCountry to it.defaultAreaCode }.distinctUntilChanged().collect { (country, area) ->
                _numbers.value = PhoneNumbers(country.ifEmpty { "AR" }, area.ifEmpty { null })
            }
        }
    }

    /** Las cuentas de la agenda, más las libretas del servidor aunque todavía estén vacías. */
    fun allAccounts(): List<ContactAccount> {
        val type = app.getString(R.string.account_type)
        return contacts.accounts(SyncEngine.store.value.books.filter { it.firstSyncDone }.map { ContactAccount(type, it.accountName) })
    }

    /**
     * Donde se puede guardar: sin las libretas de solo lectura ni las cuentas cuyo sincronizador no
     * sube cambios (por ejemplo, las de apps de mensajería, que solo publican sus propias fichas).
     */
    fun writableAccounts(): List<ContactAccount> {
        val readOnlyBooks = SyncEngine.store.value.books.filter { it.readOnly }.map { it.accountName }
        val type = app.getString(R.string.account_type)
        val noUpload = android.content.ContentResolver.getSyncAdapterTypes()
            .filter { it.authority == android.provider.ContactsContract.AUTHORITY && !it.supportsUploading() }
            .map { it.accountType }
        return allAccounts().filterNot { (it.type == type && it.name in readOnlyBooks) || it.type in noUpload || it.type in MESSAGING_ACCOUNTS }
    }

    fun hasPermission(): Boolean =
        listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
            .all { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Empieza a seguir la agenda (una sola vez, después de tener el permiso). */
    @OptIn(FlowPreview::class)
    fun start() {
        if (watching != null || !hasPermission()) return
        scope.launch { runCatching { Trash.cleanUp() } }
        watching = scope.launch {
            // Un guardado o una sincronización generan varios avisos seguidos: se lee una vez.
            contacts.changes().debounce(250).collect {
                reload()
                // Un cambio hecho acá o en otra app sobre una libreta se sube pronto.
                if (SyncEngine.hasLocalChanges()) SyncScheduler.syncSoon()
            }
        }
    }

    fun reload() {
        val order = SpanishOrder()
        val rows = contacts.summaries()
            .sortedWith { a, b -> order.compare(a.displayName, b.displayName) }
            .map { Row(it, SpanishOrder.section(it.displayName)) }
        _book.value = Book(rows, loaded = true)
    }

    /** Apps de mensajería: publican sus fichas para Android, pero un contacto guardado ahí no les llega. */
    private val MESSAGING_ACCOUNTS = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.thoughtcrime.securesms",
        "org.telegram.messenger",
        "org.telegram.messenger.account",
        "com.viber.voip",
        "com.viber.voip.account",
        "ch.threema.app",
        "com.skype.contacts.sync",
    )

    /** Trabajo que tiene que terminar aunque se cierre la pantalla (guardar, borrar, unir). */
    fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)

    /** País de la SIM en mayúsculas (sin permisos), o el del idioma si no hay SIM. */
    fun simCountry(context: Context): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val sim = tm?.simCountryIso?.takeIf { it.length == 2 }
        return (sim ?: Locale.getDefault().country.takeIf { it.length == 2 } ?: "AR").uppercase(Locale.ROOT)
    }
}
