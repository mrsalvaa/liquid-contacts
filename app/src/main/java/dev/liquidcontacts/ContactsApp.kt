// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts

import android.app.Application
import dev.liquidcontacts.data.Repo
import dev.liquidcontacts.data.Stores
import dev.liquidcontacts.data.VcfFiles
import dev.liquidcontacts.util.Strings

class ContactsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Strings.app = this
        Stores.init(this)
        Repo.init(this)
        // Al arrancar no queda nada de la vez anterior; durante el uso, a los 10 minutos.
        VcfFiles.cleanShared(this, keepMs = 0)
    }
}
