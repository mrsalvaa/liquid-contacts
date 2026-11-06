// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidcontacts.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Sincronización en segundo plano con WorkManager, solo con red y solo si hay libretas. */
object SyncScheduler {
    private lateinit var app: Context
    /** 15 minutos es el mínimo que permite WorkManager. 0 es "a mano". */
    val INTERVALS = listOf(15, 30, 60, 180, 360, 720, 0)
    private const val PERIODIC = "contacts-sync"
    private const val SOON = "contacts-sync-soon"
    private const val PENDING = "contacts-sync-pending"
    const val AFTER_OFFLINE = "afterOffline"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val again = AtomicBoolean(false)

    /** Un cambio quedó en el celular porque no hubo conexión. La ventana se muestra una vez por corte. */
    private val _offline = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val offline: SharedFlow<Unit> = _offline
    private val noticeShown = AtomicBoolean(false)
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
        val request = PeriodicWorkRequestBuilder<SyncWorker>(s.syncIntervalMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES).setConstraints(network).build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Hubo un cambio local (de esta app o de otra): se sube pronto, agrupando varios seguidos. */
    fun syncSoon() {
        val s = SyncEngine.store.value
        if (!s.configured || s.books.isEmpty()) return
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network).setInitialDelay(20, TimeUnit.SECONDS).build()
        WorkManager.getInstance(app).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Un cambio sobre una libreta (guardar, importar, borrar...): se sube en el momento. Sin conexión
     * queda guardado en el celular, se avisa, y WorkManager lo sube apenas vuelva la red.
     */
    fun pushNow() {
        val s = SyncEngine.store.value
        if (!s.configured || s.books.none { it.firstSyncDone } || s.needsPassword) return
        if (!online()) {
            savedOffline()
            return
        }
        // Si ya hay una subida en curso, se repite una vez al terminar, con lo que haya cambiado.
        if (!running.compareAndSet(false, true)) {
            again.set(true)
            return
        }
        scope.launch {
            try {
                do {
                    again.set(false)
                    val outcome = SyncEngine.syncAll()
                    if (outcome == SyncEngine.Outcome.OK) {
                        synced()
                    } else if (SyncEngine.lastFailedByNetwork && (outcome == SyncEngine.Outcome.WAIT || outcome == SyncEngine.Outcome.FAILED)) {
                        savedOffline()
                        break
                    }
                } while (again.get())
            } finally {
                running.set(false)
            }
        }
    }

    /** Se subió todo: si vuelve a cortarse la red, se avisa de nuevo. */
    fun synced() = noticeShown.set(false)

    private fun savedOffline() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setInputData(workDataOf(AFTER_OFFLINE to true))
            // Con red pero sin llegar al servidor (por ejemplo, uno de la casa estando afuera), se reintenta.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(PENDING, ExistingWorkPolicy.KEEP, request)
        if (noticeShown.compareAndSet(false, true)) _offline.tryEmit(Unit)
    }

    private fun online(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Volvió la red después de un corte: el error de red de antes no hace esperar.
        val afterOffline = inputData.getBoolean(SyncScheduler.AFTER_OFFLINE, false)
        if (afterOffline) SyncEngine.forgetNetworkWait()
        val outcome = SyncEngine.syncAll()
        if (outcome == SyncEngine.Outcome.OK) SyncScheduler.synced()
        // Lo guardado sin conexión se sigue intentando hasta que llegue al servidor.
        if (afterOffline && outcome != SyncEngine.Outcome.OK && SyncEngine.lastFailedByNetwork) return Result.retry()
        return Result.success()
    }
}
