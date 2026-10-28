package com.steamdata.widget

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.TimeUnit

/** Only one refresh at a time per process (the periodic job, the alarm and a manual refresh can overlap). */
private val refreshMutex = Mutex()

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val state = WidgetState(ctx)
        val cfg = Config(ctx)

        // The periodic job is only a safety net: skip it when the alarm-driven refresh already ran this slot.
        if (tags.contains(Scheduler.TAG_PERIODIC)) {
            val intervalMs = cfg.refreshIntervalMin * 60_000L
            if (System.currentTimeMillis() - state.lastUpdate < intervalMs - 5 * 60_000L) return Result.success()
        }
        if (!refreshMutex.tryLock()) return Result.success()

        var ok = false
        try {
            state.busy = true
            state.progress = null
            Widgets.updateAll(ctx)
            ok = Refresher(
                ctx,
                onProgress = { text ->
                    state.progress = text
                    SteamWidgetProvider.updateStatus(ctx)
                    GameWidgetProvider.updateAll(ctx)
                },
                onData = { Widgets.updateAll(ctx) },
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
            Widgets.updateAll(ctx)
            Scheduler.armAlarm(ctx)
            when {
                // Stopped by the system mid-sync: pick up where the saved history left off.
                isStopped -> Scheduler.refreshSoon(ctx, 15_000)
                ok -> state.failStreak = 0
                // Steam or the network was unreachable: retry a few times before waiting for the next slot.
                else -> {
                    val streak = state.failStreak + 1
                    state.failStreak = streak
                    Scheduler.retryDelayMs(streak)?.let { Scheduler.refreshSoon(ctx, it) }
                }
            }
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

/**
 * Refresh scheduling, in three layers:
 *  1. An "allow while idle" alarm at every clock-aligned slot (13:00, 13:30, …). Alarms of this kind
 *     still fire in Doze, which plain delayed jobs do not; the alarm enqueues an expedited refresh.
 *  2. A periodic WorkManager job as a persistent fallback (survives reboots and app updates).
 *  3. Short retries after a failed run, so a flaky network does not cost a whole slot.
 */
object Scheduler {
    private const val PERIODIC = "refresh-periodic"
    private const val NOW = "refresh-now"
    const val TAG_PERIODIC = "periodic"
    const val ACTION_ALARM = "com.steamdata.widget.ALARM_REFRESH"

    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Milliseconds until the next clock-aligned slot (13:00, 14:00, … for a 60-minute interval). */
    fun msToNextSlot(intervalMin: Int): Long {
        val interval = intervalMin.coerceAtLeast(1) * 60_000L
        val now = System.currentTimeMillis()
        return (now / interval + 1) * interval - now + 2000
    }

    /** Retry delays after consecutive failures; null means wait for the next regular slot. */
    fun retryDelayMs(streak: Int): Long? = when (streak) {
        1 -> 2 * 60_000L
        2 -> 5 * 60_000L
        3 -> 10 * 60_000L
        else -> null
    }

    /** Idempotent: (re)arms the slot alarm and makes sure the periodic fallback exists with the current interval. */
    fun ensureScheduled(ctx: Context) {
        val interval = Config(ctx).refreshIntervalMin.coerceAtLeast(15).toLong()
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(interval, TimeUnit.MINUTES, 5, TimeUnit.MINUTES)
            .setConstraints(connected)
            .addTag(TAG_PERIODIC)
            .build()
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        wm.cancelUniqueWork("refresh-scheduled") // self-rescheduling chain used by 1.0
        armAlarm(ctx)
    }

    private fun alarmIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 100, Intent(ctx, SystemEventsReceiver::class.java).setAction(ACTION_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** True when the user granted exact alarms (Android 12+ setting); otherwise slots fire within ~20 minutes. */
    fun canScheduleExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    fun armAlarm(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + msToNextSlot(Config(ctx).refreshIntervalMin)
        val pi = alarmIntent(ctx)
        try {
            if (canScheduleExact(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun refreshNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(connected)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
    }

    fun refreshSoon(ctx: Context, delayMs: Long) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(connected)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancelAll(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(NOW)
        wm.cancelUniqueWork("refresh-scheduled") // chain used by versions before 1.1
        ctx.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(ctx))
    }
}
