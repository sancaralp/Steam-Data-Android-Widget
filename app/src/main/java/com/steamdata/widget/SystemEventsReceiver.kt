package com.steamdata.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Keeps refreshes going across the events that would otherwise silently stop them:
 * the slot alarm, reboots (alarms do not survive them), app updates (launchers show the
 * placeholder layout until a redraw) and clock or time-zone changes (slots move).
 */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Widgets.anyPresent(context)) return
        when (intent.action) {
            Scheduler.ACTION_ALARM -> {
                Scheduler.refreshNow(context)
                Scheduler.armAlarm(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Widgets.updateAll(context)
                Scheduler.ensureScheduled(context)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                Scheduler.ensureScheduled(context)
            }
        }
    }
}
