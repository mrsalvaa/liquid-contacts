// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.liquidcontacts.data.Repo
import dev.liquidcontacts.data.Stores

/**
 * Solo debug y profile. Desde adb (nunca desde otra app, ver el manifest de debug):
 *   adb shell am broadcast -a dev.liquidcontacts.DEV_GENERATE -p dev.liquidcontacts.debug [--ei count 520]
 *   adb shell am broadcast -a dev.liquidcontacts.DEV_CLEAR -p dev.liquidcontacts.debug
 *   adb shell am broadcast -a dev.liquidcontacts.DEV_MULTI -p dev.liquidcontacts.debug
 *   adb shell am broadcast -a dev.liquidcontacts.DEV_STYLE -p dev.liquidcontacts.debug --ei background 0 --ei ui 0
 * En el registro solo va la cantidad, nunca datos de contactos.
 */
class DevReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Repo.launch {
            try {
                val n = when (intent.action) {
                    "dev.liquidcontacts.DEV_GENERATE" -> DevTools.generate(Repo.contacts, intent.getIntExtra("count", 520))
                    "dev.liquidcontacts.DEV_CLEAR" -> DevTools.clear(Repo.contacts)
                    "dev.liquidcontacts.DEV_MULTI" -> DevTools.generateMultiAccount(Repo.contacts)
                    // Fondo (0 movimiento, 1 énfasis, 2 liso) y estilo (0 Liquid, 1 sólido), para medir cada caso.
                    "dev.liquidcontacts.DEV_STYLE" -> {
                        val bg = intent.getIntExtra("background", 2)
                        val ui = intent.getIntExtra("ui", 0)
                        Stores.settings.update { it.copy(backgroundMode = bg, uiStyle = ui) }
                        bg
                    }
                    else -> 0
                }
                Log.i("LiquidContactsDev", "${intent.action}: $n")
            } finally {
                pending.finish()
            }
        }
    }
}
