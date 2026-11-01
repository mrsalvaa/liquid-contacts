// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.sync

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder

/**
 * La cuenta de cada libreta. Android exige un autenticador para que los contactos vivan en el
 * proveedor del sistema, pero acá no autentica nada: la contraseña va cifrada en CredentialStore,
 * nunca en el AccountManager (que la guardaría sin cifrar), y las cuentas se crean desde la app.
 */
class AuthenticatorService : Service() {
    private val authenticator by lazy { Authenticator(this) }
    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder

    private class Authenticator(context: Context) : AbstractAccountAuthenticator(context) {
        private fun notFromHere() = Bundle().apply {
            putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
            putString(AccountManager.KEY_ERROR_MESSAGE, "Se configura desde la app")
        }

        override fun editProperties(r: AccountAuthenticatorResponse?, accountType: String?) = notFromHere()
        override fun addAccount(r: AccountAuthenticatorResponse?, accountType: String?, tokenType: String?, features: Array<out String>?, options: Bundle?) = notFromHere()
        override fun confirmCredentials(r: AccountAuthenticatorResponse?, account: Account?, options: Bundle?) = notFromHere()
        override fun getAuthToken(r: AccountAuthenticatorResponse?, account: Account?, tokenType: String?, options: Bundle?) = notFromHere()
        override fun getAuthTokenLabel(tokenType: String?): String? = null
        override fun updateCredentials(r: AccountAuthenticatorResponse?, account: Account?, tokenType: String?, options: Bundle?) = notFromHere()
        override fun hasFeatures(r: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?) = Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
    }
}

/**
 * Hace que Android reconozca la cuenta como de contactos con subida, para que los cambios hechos
 * desde cualquier app queden marcados y otras apps la ofrezcan. No conecta: pide una sincronización
 * al mismo WorkManager de siempre, con las mismas reglas.
 */
class ContactsSyncService : Service() {
    private val adapter by lazy { Adapter(applicationContext) }
    override fun onBind(intent: Intent?): IBinder = adapter.syncAdapterBinder

    private class Adapter(context: Context) : AbstractThreadedSyncAdapter(context, false) {
        override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, syncResult: SyncResult) {
            if (extras.getBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE)) return
            SyncScheduler.syncSoon()
        }
    }
}
