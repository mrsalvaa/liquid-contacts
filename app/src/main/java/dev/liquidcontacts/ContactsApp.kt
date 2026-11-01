// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts

import android.app.Application
import androidx.work.Configuration
import dev.liquidcontacts.data.Repo
import dev.liquidcontacts.data.Stores
import dev.liquidcontacts.data.VcfFiles
import dev.liquidcontacts.sync.SyncEngine
import dev.liquidcontacts.sync.SyncScheduler
import dev.liquidcontacts.util.Strings

/**
 * WorkManager se inicia a pedido (Configuration.Provider) porque el manifest quita androidx.startup:
 * sin servidor registrado no se programa nada.
 */
class ContactsApp : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        Strings.app = this
        Stores.init(this)
        Repo.init(this)
        SyncEngine.init(this)
        SyncScheduler.init(this)
        // Al arrancar no queda nada de la vez anterior; durante el uso, a los 10 minutos.
        VcfFiles.cleanShared(this, keepMs = 0)
    }
}
