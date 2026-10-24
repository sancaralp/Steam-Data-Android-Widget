package com.steamdata.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.TimeUnit

/** Only one refresh at a time per process (the scheduled chain and a manual refresh can overlap). */
private val refreshMutex = Mutex()

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!refreshMutex.tryLock()) return Result.success()
        val state = WidgetState(ctx)
        try {
            state.busy = true
            state.progress = null
            SteamWidgetProvider.updateAll(ctx)
            Refresher(
                ctx,
                onProgress = { text ->
                    state.progress = text
                    SteamWidgetProvider.updateStatus(ctx)
                },
                onData = { SteamWidgetProvider.updateAll(ctx) },
                onLongRun = {
                    // Lifts the 10-minute background limit while the first wishlist history downloads.
                    try {
                        setForeground(foregroundInfo())
                    } catch (e: Exception) {
                        Store.logError(ctx, "foreground: $e")
                    }
                },
            ).run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Store.logError(ctx, "refresh: ${e.stackTraceToString()}")
        } finally {
            state.busy = false
            state.progress = null
            refreshMutex.unlock()
            SteamWidgetProvider.updateAll(ctx)
            Scheduler.scheduleNext(ctx)
            // Stopped by the system mid-sync: pick up where the saved history left off.
            if (isStopped) Scheduler.refreshSoon(ctx)
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo(): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Sync", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the wishlist history is downloading"
            }
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_gamepad)
            .setContentTitle("Steam Data Widget")
            .setContentText("Downloading wishlist history…")
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "sync"
        private const val NOTIF_ID = 1001
    }
}

object Scheduler {
    private const val SCHEDULED = "refresh-scheduled"
    private const val NOW = "refresh-now"

    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Milliseconds until the next clock-aligned slot (13:00, 14:00, … for a 60-minute interval). */
    fun msToNextSlot(intervalMin: Int): Long {
        val interval = intervalMin.coerceAtLeast(1) * 60_000L
        val now = System.currentTimeMillis()
        return (now / interval + 1) * interval - now + 2000
    }

    fun scheduleNext(ctx: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        val delay = msToNextSlot(Config(ctx).refreshIntervalMin)
        val req = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(connected)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(SCHEDULED, policy, req)
    }

    fun ensureScheduled(ctx: Context) = scheduleNext(ctx, ExistingWorkPolicy.KEEP)

    fun refreshNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(connected)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
    }

    fun refreshSoon(ctx: Context, delayMs: Long = 15_000) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(connected)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancelAll(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(SCHEDULED)
        wm.cancelUniqueWork(NOW)
    }
}
