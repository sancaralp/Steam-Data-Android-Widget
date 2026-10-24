package com.steamdata.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

/** Which game each compact widget instance shows (appWidgetId → row key, i.e. appid or demo appid). */
class GameWidgetStore(context: Context) {
    private val prefs = context.getSharedPreferences("game_widgets", Context.MODE_PRIVATE)
    fun keyFor(appWidgetId: Int): String? = prefs.getString(appWidgetId.toString(), null)
    fun set(appWidgetId: Int, key: String) = prefs.edit().putString(appWidgetId.toString(), key).apply()
    fun remove(appWidgetId: Int) = prefs.edit().remove(appWidgetId.toString()).apply()
}

/** Compact single-game widget: today's net wishlists, total, players and latest revenue. */
class GameWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, GameWidgetRenderer.build(context, appWidgetManager, id))
        Scheduler.ensureScheduled(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?,
    ) {
        appWidgetManager.updateAppWidget(appWidgetId, GameWidgetRenderer.build(context, appWidgetManager, appWidgetId))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = GameWidgetStore(context)
        for (id in appWidgetIds) store.remove(id)
    }

    override fun onEnabled(context: Context) {
        Scheduler.ensureScheduled(context)
        Scheduler.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        if (!Widgets.anyPresent(context)) Scheduler.cancelAll(context)
    }

    companion object {
        fun ids(ctx: Context, mgr: AppWidgetManager = AppWidgetManager.getInstance(ctx)): IntArray =
            mgr.getAppWidgetIds(ComponentName(ctx, GameWidgetProvider::class.java))

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            for (id in ids(ctx, mgr)) mgr.updateAppWidget(id, GameWidgetRenderer.build(ctx, mgr, id))
        }
    }
}

object GameWidgetRenderer {

    fun build(ctx: Context, mgr: AppWidgetManager, appWidgetId: Int): RemoteViews {
        val opts = mgr.getAppWidgetOptions(appWidgetId)
        val minH = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val minW = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        // 1 row high → two-line strip; 4+ cells wide → tile grid; otherwise the 2×2 card.
        val small = minH in 1 until 95
        val wide = !small && minW >= 200
        val layout = when {
            small -> R.layout.widget_game_small
            wide -> R.layout.widget_game_wide
            else -> R.layout.widget_game
        }
        val rv = RemoteViews(ctx.packageName, layout)

        val key = GameWidgetStore(ctx).keyFor(appWidgetId)
        val snap = Snapshot.load(ctx)
        val row = key?.let { k -> snap?.rows?.firstOrNull { it.key == k } }
        val cfg = Config(ctx)
        val state = WidgetState(ctx)
        val today = snap?.today ?: steamToday(cfg.dayStartHour)

        val name = row?.name ?: key?.let { k -> knownGames(ctx).firstOrNull { it.key.toString() == k }?.displayName }
            ?: "Steam game"
        rv.setTextViewText(R.id.g_name, name)
        rv.setInt(R.id.g_ring, "setColorFilter", row?.color ?: Palette.DIM)

        val t = row?.wl?.today
        val net = if (t != null) t.adds - t.deletes else 0
        val counts = row?.players?.mapNotNull { p ->
            p.now?.let { it to if (p.isGame) "game" else "demo" }
        } ?: emptyList()
        val down = state.steamDown
        val (status, statusColor) = when {
            row == null && key == null -> "tap to choose a game" to Palette.DIM
            row == null && snap == null -> "loading…" to Palette.DIM
            row == null -> "no data for this game" to Palette.DIM
            state.busy -> "updating…" to Palette.BLUE
            down != null -> down to Palette.RED
            state.lastUpdate > 0 -> "updated ${Fmt.time(state.lastUpdate)}" to Palette.DIM
            else -> "" to Palette.DIM
        }

        if (wide) {
            rv.setTextViewText(R.id.g_status, status)
            rv.setTextColor(R.id.g_status, statusColor)
            rv.setTextViewText(R.id.g_today_value, if (row?.wl?.total == null) "—" else if (net >= 0) "+${Fmt.num(net)}" else "−${Fmt.num(-net)}")
            rv.setTextColor(R.id.g_today_value, if (net < 0) Palette.RED else Palette.GREEN)
            rv.setTextViewText(R.id.g_today_label, if (t == null) "Today" else "Today · +${Fmt.num(t.adds)} −${Fmt.num(t.deletes)}")
            rv.setTextViewText(R.id.g_total_value, Fmt.num(row?.wl?.total))
            val game = counts.firstOrNull { it.second == "game" }?.first
            val demo = counts.firstOrNull { it.second == "demo" }?.first
            when {
                game != null && demo != null -> {
                    rv.setTextViewText(R.id.g_players_value, "${Fmt.num(game)} · ${Fmt.num(demo)}")
                    rv.setTextViewText(R.id.g_players_label, "Game · Demo")
                }
                counts.isNotEmpty() -> {
                    rv.setTextViewText(R.id.g_players_value, Fmt.num(counts[0].first))
                    rv.setTextViewText(R.id.g_players_label, if (counts[0].second == "demo") "Demo players" else "Players")
                }
                else -> {
                    rv.setTextViewText(R.id.g_players_value, "—")
                    rv.setTextViewText(R.id.g_players_label, "Players")
                }
            }
            val s = row?.sale
            rv.setTextViewText(R.id.g_rev_value, if (s == null) "—" else Fmt.money(s.net))
            rv.setTextViewText(R.id.g_rev_label, if (s == null || s.day == today) "Revenue" else "Revenue · ${Fmt.day(s.day)}")
            rv.setOnClickPendingIntent(R.id.g_root, clickIntent(ctx, appWidgetId, row, today))
            return rv
        }

        // Today's net wishlist change
        if (row?.wl?.total != null && net != 0) {
            rv.setViewVisibility(R.id.g_pill, View.VISIBLE)
            rv.setTextViewText(R.id.g_pill, if (net > 0) "+${Fmt.num(net)}" else "−${Fmt.num(-net)}")
            rv.setInt(R.id.g_pill, "setBackgroundResource", if (net > 0) R.drawable.pill_green else R.drawable.pill_red)
            rv.setTextColor(R.id.g_pill, if (net > 0) Palette.GREEN else Palette.RED)
        } else {
            rv.setViewVisibility(R.id.g_pill, if (small) View.GONE else View.INVISIBLE)
            if (!small) rv.setTextViewText(R.id.g_pill, "")
        }

        rv.setTextViewText(R.id.g_total, Fmt.num(row?.wl?.total))

        // Players: "1 game · 14 demo"
        val gameNow = counts.firstOrNull { it.second == "game" }?.first
        val demoNow = counts.firstOrNull { it.second == "demo" }?.first
        val playersText = when {
            counts.isEmpty() -> "—"
            counts.all { it.first == 0 } -> "0 playing"
            gameNow != null && gameNow > 0 && demoNow != null && demoNow > 0 ->
                "${Fmt.num(gameNow)} + ${Fmt.num(demoNow)} demo"
            demoNow != null && demoNow > 0 -> "${Fmt.num(demoNow)} in demo"
            else -> "${Fmt.num(gameNow)} in game"
        }
        rv.setTextViewText(R.id.g_players, playersText)

        if (!small) {
            val adds = t?.adds
            val dels = t?.deletes
            rv.setTextViewText(
                R.id.g_today_detail,
                if (adds == null || dels == null) "—" else "+${Fmt.num(adds)}  −${Fmt.num(dels)}",
            )
            val s = row?.sale
            if (s != null) {
                rv.setTextViewText(R.id.g_revenue, Fmt.money(s.net))
                rv.setTextViewText(R.id.g_revenue_day, if (s.day == today) "" else Fmt.day(s.day))
            } else {
                rv.setTextViewText(R.id.g_revenue, "—")
                rv.setTextViewText(R.id.g_revenue_day, "")
            }
            rv.setTextViewText(R.id.g_status, status)
            rv.setTextColor(R.id.g_status, statusColor)
        }
        rv.setOnClickPendingIntent(R.id.g_root, clickIntent(ctx, appWidgetId, row, today))
        return rv
    }

    /** Tap: Steamworks detail page for the game, or the picker when nothing is chosen yet. */
    private fun clickIntent(ctx: Context, appWidgetId: Int, row: Row?, today: java.time.LocalDate): PendingIntent {
        val intent = if (row?.appid != null) {
            Intent(ctx, LinkActivity::class.java).putExtra(LinkActivity.EXTRA_URL, Urls.details(row.appid, today))
        } else {
            Intent(ctx, GameWidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        return PendingIntent.getActivity(
            ctx, appWidgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Games the user can pick from: discovered apps plus manual entries. */
    fun knownGames(ctx: Context): List<Game> {
        val discovered = Store.load(Store.file(ctx, "apps.json"))?.optJSONArray("games")
        return mergeGames(Game.listFromJson(discovered), Config(ctx).manualGames())
    }
}

/** Helpers spanning both widget types. */
object Widgets {
    fun updateAll(ctx: Context) {
        SteamWidgetProvider.updateAll(ctx)
        GameWidgetProvider.updateAll(ctx)
    }

    fun anyPresent(ctx: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx)
        return mgr.getAppWidgetIds(ComponentName(ctx, SteamWidgetProvider::class.java)).isNotEmpty() ||
            GameWidgetProvider.ids(ctx, mgr).isNotEmpty()
    }
}
