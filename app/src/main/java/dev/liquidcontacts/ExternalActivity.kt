// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.liquidcontacts.core.vcard.VCardImport
import dev.liquidcontacts.data.Repo
import dev.liquidcontacts.data.Stores
import dev.liquidcontacts.data.VcfFiles
import dev.liquidcontacts.external.ExternalIntents
import dev.liquidcontacts.external.ExternalRequest
import dev.liquidcontacts.ui.glass.GlassTheme
import dev.liquidcontacts.ui.glass.isDarkTheme
import dev.liquidcontacts.ui.screens.EditTarget
import dev.liquidcontacts.util.preferHighestRefreshRate
import dev.liquidcontacts.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lo que abren otras apps: ver, insertar y editar un contacto, y abrir un .vcf. Solo muestra lo
 * pedido, y al cerrarlo se vuelve a la otra app. Nada se guarda sin que el usuario toque guardar.
 */
class ExternalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        preferHighestRefreshRate()
        // Que otra app no pueda tapar la pantalla y hacer tocar "guardar" sin que se vea.
        window.decorView.filterTouchesWhenObscured = true
        setResult(RESULT_CANCELED)
        val request = intent
        setContent {
            val settings by Stores.settings.state.collectAsStateWithLifecycle()
            SystemBarsFor(isDarkTheme(settings.themeMode))
            GlassTheme(settings.glassStyle) {
                ContactsUi(
                    settings.animatedBackground,
                    flatNav = settings.uiStyle == 1,
                    external = { withContext(Dispatchers.IO) { open(request) } },
                    onExit = ::exit,
                )
            }
        }
    }

    /** La pantalla para el pedido, ya validado; null (con un aviso) si no vale. */
    private fun open(request: Intent): Overlay? {
        val overlay = when (val r = ExternalIntents.parse(request, Repo.contacts, "$packageName.files")) {
            is ExternalRequest.View -> Overlay.Detail(r.contactId)
            is ExternalRequest.Edit -> Overlay.Edit(EditTarget.Existing(r.contactId, r.extra))
            is ExternalRequest.Insert -> Overlay.Edit(r.contact?.let { EditTarget.Prefilled(it) } ?: EditTarget.New)
            is ExternalRequest.ImportVcf -> when (val file = VcfFiles.read(this, r.uri)) {
                is VCardImport.Ok -> if (file.contacts.isEmpty()) null.also { notice(R.string.import_empty) } else Overlay.Import(file)
                is VCardImport.TooLarge -> null.also { notice(R.string.import_too_large) }
                VCardImport.Invalid -> null.also { notice(R.string.import_invalid) }
            }
            ExternalRequest.Invalid -> null.also { notice(R.string.external_invalid) }
        }
        return overlay
    }

    private fun notice(id: Int) = runOnUiThread { Toast.makeText(this, str(id), Toast.LENGTH_LONG).show() }

    /** Si se guardó, la otra app recibe ese contacto y solo ese, con permiso temporal de lectura. */
    private fun exit(saved: Long?) {
        val uri = saved?.let { Repo.contacts.lookupUri(it) }
        if (uri != null) {
            setResult(RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        finish()
    }
}
