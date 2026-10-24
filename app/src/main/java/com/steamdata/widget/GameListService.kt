package com.steamdata.widget

import android.content.Context
import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.time.LocalDate

class GameListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = GameListFactory(applicationContext)
}

private class Line(val label: String, val bar: Int, val segs: List<Pair<String, Int>>, val linkAppid: Int?)

private class LineIds(
    val root: Int, val label: Int, val bar: Int, val text: Int, val links: Int, val detail: Int, val steamdb: Int,
)

private val LINE_IDS = listOf(
    LineIds(R.id.line0, R.id.line0_label, R.id.line0_bar, R.id.line0_text, R.id.line0_links, R.id.line0_detail, R.id.line0_steamdb),
    LineIds(R.id.line1, R.id.line1_label, R.id.line1_bar, R.id.line1_text, R.id.line1_links, R.id.line1_detail, R.id.line1_steamdb),
    LineIds(R.id.line2, R.id.line2_label, R.id.line2_bar, R.id.line2_text, R.id.line2_links, R.id.line2_detail, R.id.line2_steamdb),
    LineIds(R.id.line3, R.id.line3_label, R.id.line3_bar, R.id.line3_text, R.id.line3_links, R.id.line3_detail, R.id.line3_steamdb),
    LineIds(R.id.line4, R.id.line4_label, R.id.line4_bar, R.id.line4_text, R.id.line4_links, R.id.line4_detail, R.id.line4_steamdb),
    LineIds(R.id.line5, R.id.line5_label, R.id.line5_bar, R.id.line5_text, R.id.line5_links, R.id.line5_detail, R.id.line5_steamdb),
)

/** Builds one RemoteViews per game row from the saved snapshot. */
class GameListFactory(private val ctx: Context) : RemoteViewsService.RemoteViewsFactory {
    private var rows: List<Row> = emptyList()
    private var today: LocalDate = LocalDate.now()
    private var expanded: Set<String> = emptySet()

    override fun onCreate() = load()
    override fun onDataSetChanged() = load()
    override fun onDestroy() {}

    private fun load() {
        val snap = Snapshot.load(ctx)
        rows = snap?.rows ?: emptyList()
        today = snap?.today ?: steamToday(Config(ctx).dayStartHour)
        expanded = WidgetState(ctx).expanded
    }

    override fun getCount(): Int = rows.size
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun hasStableIds(): Boolean = true
    override fun getItemId(position: Int): Long =
        rows.getOrNull(position)?.key?.hashCode()?.toLong() ?: position.toLong()

    override fun getViewAt(position: Int): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_row)
        val r = rows.getOrNull(position) ?: return rv
        val open = r.key in expanded

        rv.setInt(R.id.ring, "setColorFilter", r.color)
        rv.setTextViewText(R.id.name, r.name)
        rv.setImageViewResource(R.id.chevron, if (open) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right)

        // Right side: total wishlists + today's net pill, or a player count when there is no wishlist data.
        val wl = r.wl
        var pill = false
        if (wl != null && wl.total != null) {
            rv.setTextViewText(R.id.total, Fmt.num(wl.total))
            rv.setViewVisibility(R.id.total, View.VISIBLE)
            val t = wl.today
            val net = if (t != null) t.adds - t.deletes else 0
            if (net != 0) {
                pill = true
                rv.setTextViewText(R.id.pill, if (net > 0) "+${Fmt.num(net)}" else "−${Fmt.num(-net)}")
                rv.setInt(R.id.pill, "setBackgroundResource", if (net > 0) R.drawable.pill_green else R.drawable.pill_red)
                rv.setTextColor(R.id.pill, if (net > 0) Palette.GREEN else Palette.RED)
            }
        } else if (r.players.isNotEmpty()) {
            val n = r.players[0].now
            rv.setTextViewText(R.id.total, "${Fmt.num(n)} " + if (n == 1) "player" else "players")
            rv.setViewVisibility(R.id.total, View.VISIBLE)
        } else {
            rv.setViewVisibility(R.id.total, View.GONE)
        }
        rv.setViewVisibility(R.id.pill, if (pill) View.VISIBLE else View.GONE)

        // Live player hint while collapsed: "● 3 in game · 12 in demo"
        var badge = ""
        if (!open) {
            val counts = r.players.mapNotNull { p ->
                p.now?.takeIf { it > 0 }?.let { it to if (p.isGame) "in game" else "in demo" }
            }
            if (counts.isNotEmpty()) {
                // RemoteViews cannot measure text, so fall back to the numbers-only form when the
                // name plus the long badge would not fit a 4-cell-wide widget (like the PC widget does).
                val long = "● " + counts.joinToString(" · ") { "${Fmt.num(it.first)} ${it.second}" }
                val short = "● " + counts.joinToString(" · ") { Fmt.num(it.first) }
                badge = if (r.name.length + long.length > 32) short else long
            }
        }
        rv.setTextViewText(R.id.live, badge)

        rv.setViewVisibility(R.id.details, if (open) View.VISIBLE else View.GONE)
        if (open) {
            val lines = buildLines(r)
            for (i in LINE_IDS.indices) {
                val ids = LINE_IDS[i]
                val line = lines.getOrNull(i)
                if (line == null) {
                    rv.setViewVisibility(ids.root, View.GONE)
                    continue
                }
                rv.setViewVisibility(ids.root, View.VISIBLE)
                rv.setTextViewText(ids.label, line.label)
                rv.setInt(ids.bar, "setBackgroundColor", line.bar)
                val sb = SpannableStringBuilder()
                for ((text, color) in line.segs) {
                    val s = sb.length
                    sb.append(text)
                    sb.setSpan(ForegroundColorSpan(color), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                rv.setTextViewText(ids.text, sb)
                if (line.linkAppid != null) {
                    rv.setViewVisibility(ids.links, View.VISIBLE)
                    rv.setOnClickFillInIntent(
                        ids.steamdb, Intent().putExtra(LinkActivity.EXTRA_URL, Urls.steamDb(line.linkAppid))
                    )
                    rv.setOnClickFillInIntent(
                        ids.detail, Intent().putExtra(LinkActivity.EXTRA_URL, Urls.details(line.linkAppid, today))
                    )
                } else {
                    rv.setViewVisibility(ids.links, View.GONE)
                }
            }
        }
        rv.setOnClickFillInIntent(R.id.row_main, Intent().putExtra(LinkActivity.EXTRA_TOGGLE, r.key))
        return rv
    }

    private fun buildLines(r: Row): List<Line> {
        val lines = ArrayList<Line>()
        val wl = r.wl
        val err = r.wlError
        if (wl != null && err != null) {
            lines.add(Line("Wishlist", Palette.RED, listOf(err.take(40) to Palette.RED), null))
        } else if (wl != null) {
            for ((label, d) in listOf("Today" to wl.today, "Yesterday" to wl.yesterday)) {
                if (d == null) continue
                lines.add(
                    Line(
                        label, if (d.adds >= d.deletes) Palette.GREEN else Palette.RED,
                        listOf(
                            "+${Fmt.num(d.adds)}" to if (d.adds > 0) Palette.GREEN else Palette.DIM,
                            "   " to Palette.DIM,
                            "−${Fmt.num(d.deletes)}" to if (d.deletes > 0) Palette.RED else Palette.DIM,
                        ),
                        null,
                    )
                )
            }
            lines.add(Line("Total", Palette.BLUE, listOf(Fmt.num(wl.total) to Palette.TEXT, " wishlists" to Palette.DIM), null))
        }
        for (p in r.players) {
            val peak = (if (p.coverageH < 23) "~" else "") + Fmt.num(p.peak)
            lines.add(
                Line(
                    if (p.isGame) "Game" else "Demo", Palette.PURPLE,
                    listOf(Fmt.num(p.now) to Palette.TEXT, " now · " to Palette.DIM, peak to Palette.TEXT, " peak" to Palette.DIM),
                    p.appid,
                )
            )
        }
        val s = r.sale
        if (s != null) {
            val segs = mutableListOf(Fmt.money(s.net) to Palette.TEXT, " net · ${Fmt.num(s.units)} sold" to Palette.DIM)
            if (s.day != today) segs.add(" · ${Fmt.day(s.day)}" to Palette.DIM)
            lines.add(Line("Revenue", Palette.ORANGE, segs, null))
        }
        return lines
    }
}
