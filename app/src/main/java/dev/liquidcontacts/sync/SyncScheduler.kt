// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Sincronización en segundo plano con WorkManager, solo con red y solo si hay libretas. */
object SyncScheduler {
    private lateinit var app: Context
    val INTERVALS = listOf(30, 60, 180, 360, 720, 0)
    private const val PERIODIC = "contacts-sync"
    private const val SOON = "contacts-sync-soon"
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** Sin servidor o sin libretas se cancela todo: ninguna conexión. */
    fun refresh() {
        val s = SyncEngine.store.value
        val wm = WorkManager.getInstance(app)
        if (!s.configured || s.books.isEmpty() || s.syncIntervalMinutes <= 0) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(s.syncIntervalMinutes.coerceAtLeast(30).toLong(), TimeUnit.MINUTES).setConstraints(network).build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Hubo un cambio local (de esta app o de otra): se sube pronto, agrupando varios seguidos. */
    fun syncSoon() {
        val s = SyncEngine.store.value
        if (!s.configured || s.books.isEmpty()) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network).setInitialDelay(20, TimeUnit.SECONDS).build()
        WorkManager.getInstance(app).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, request)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        SyncEngine.syncAll()
        return Result.success()
    }
}
