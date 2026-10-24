package com.steamdata.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After an app update, launchers show the widgets' initial layout until the provider redraws them. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Widgets.updateAll(context)
        if (Widgets.anyPresent(context)) Scheduler.ensureScheduled(context)
    }
}
