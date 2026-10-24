package com.steamdata.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews

class SteamWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, WidgetRenderer.full(context, id))
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.games_list)
        Scheduler.ensureScheduled(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?,
    ) {
        appWidgetManager.updateAppWidget(appWidgetId, WidgetRenderer.full(context, appWidgetId))
    }

    override fun onEnabled(context: Context) {
        Scheduler.ensureScheduled(context)
        Scheduler.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        Scheduler.cancelAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) Scheduler.refreshNow(context)
    }

    companion object {
        const val ACTION_REFRESH = "com.steamdata.widget.REFRESH"

        private fun ids(ctx: Context, mgr: AppWidgetManager): IntArray =
            mgr.getAppWidgetIds(ComponentName(ctx, SteamWidgetProvider::class.java))

        /** Redraws every widget instance and tells the list to reload its rows. */
        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = ids(ctx, mgr)
            if (ids.isEmpty()) return
            for (id in ids) mgr.updateAppWidget(id, WidgetRenderer.full(ctx, id))
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.games_list)
        }

        /** Cheap update of the status line only (used for download progress). */
        fun updateStatus(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = ids(ctx, mgr)
            if (ids.isEmpty()) return
            val rv = WidgetRenderer.status(ctx)
            for (id in ids) mgr.partiallyUpdateAppWidget(id, rv)
        }

        /** Rows changed (for example a row was expanded) but the header did not. */
        fun notifyData(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = ids(ctx, mgr)
            if (ids.isNotEmpty()) mgr.notifyAppWidgetViewDataChanged(ids, R.id.games_list)
        }
    }
}

object WidgetRenderer {

    fun full(ctx: Context, appWidgetId: Int): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget)
        val cfg = Config(ctx)
        val state = WidgetState(ctx)
        val snap = Snapshot.load(ctx)
        val today = snap?.today ?: steamToday(cfg.dayStartHour)

        rv.setTextViewText(R.id.date, "${Fmt.weekday(today)} ${Fmt.day(today)}")
        rv.setTextViewText(R.id.tile_today_value, snap?.adds?.let { "+${Fmt.num(it)}" } ?: "—")
        rv.setTextViewText(R.id.tile_wl_value, Fmt.num(snap?.total))
        rv.setTextViewText(R.id.tile_players_value, Fmt.num(snap?.players))
        rv.setTextViewText(R.id.tile_rev_value, snap?.saleTotal?.let { Fmt.money(it) } ?: "—")
        val saleDay = snap?.saleDay
        rv.setTextViewText(
            R.id.tile_rev_label,
            if (saleDay == null || saleDay == today) "Revenue" else "Revenue · ${Fmt.day(saleDay)}",
        )

        val emptyMsg = when {
            snap != null -> snap.emptyMessage
            !cfg.hasFinancialKey && !cfg.hasPublisherKey -> "tap the title to add your API keys"
            else -> "loading games…"
        }
        rv.setTextViewText(R.id.empty_text, emptyMsg)

        val adapter = Intent(ctx, GameListService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        @Suppress("DEPRECATION")
        rv.setRemoteAdapter(R.id.games_list, adapter)
        rv.setEmptyView(R.id.games_list, R.id.empty_text)

        val template = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, LinkActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        rv.setPendingIntentTemplate(R.id.games_list, template)

        val refresh = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, SteamWidgetProvider::class.java).setAction(SteamWidgetProvider.ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        rv.setOnClickPendingIntent(R.id.refresh_btn, refresh)

        val settings = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, SettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        rv.setOnClickPendingIntent(R.id.header_click, settings)

        applyStatus(rv, state, cfg)
        return rv
    }

    /** A partial RemoteViews carrying only the status line and refresh-icon colour. */
    fun status(ctx: Context): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget)
        applyStatus(rv, WidgetState(ctx), Config(ctx))
        return rv
    }

    private fun applyStatus(rv: RemoteViews, state: WidgetState, cfg: Config) {
        val down = state.steamDown
        val (text, color) = when {
            state.busy -> (state.progress ?: "updating…") to Palette.BLUE
            down != null -> {
                val last = if (state.lastUpdate > 0) " · last data ${Fmt.time(state.lastUpdate)}" else ""
                "$down$last" to Palette.RED
            }
            state.lastUpdate > 0 -> "updated ${Fmt.time(state.lastUpdate)}" to Palette.DIM
            !cfg.hasFinancialKey && !cfg.hasPublisherKey -> "no API keys yet" to Palette.DIM
            else -> "loading…" to Palette.DIM
        }
        rv.setTextViewText(R.id.status, text)
        rv.setTextColor(R.id.status, color)
        rv.setInt(R.id.refresh_btn, "setColorFilter", if (state.busy) Palette.BLUE else Palette.TEXT)
    }
}
