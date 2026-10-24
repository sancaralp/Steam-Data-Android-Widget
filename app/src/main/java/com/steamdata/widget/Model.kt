package com.steamdata.widget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

class PlayerInfo(val kind: String, val appid: Int, val now: Int?, val peak: Int?, val coverageH: Double) {
    val isGame: Boolean get() = kind == "game"

    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("appid", appid)
        .putNullable("now", now).putNullable("peak", peak).put("cov", coverageH)

    companion object {
        fun fromJson(o: JSONObject) = PlayerInfo(
            o.optString("kind"), o.optInt("appid"), o.intOrNull("now"), o.intOrNull("peak"), o.optDouble("cov", 0.0)
        )
    }
}

class SaleInfo(val net: Double, val units: Int, val day: LocalDate) {
    fun toJson(): JSONObject = JSONObject().put("net", net).put("units", units).put("day", day.toString())

    companion object {
        fun fromJson(o: JSONObject?) =
            o?.let { SaleInfo(it.optDouble("net"), it.optInt("units"), LocalDate.parse(it.getString("day"))) }
    }
}

class Row(
    val key: String,
    val name: String,
    val appid: Int?,
    val color: Int,
    val wl: WlSummary?,
    val wlError: String?,
    val players: List<PlayerInfo>,
    val sale: SaleInfo?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("name", name).putNullable("appid", appid).put("color", color)
        .putNullable("wl", wl?.let {
            JSONObject().putNullable("total", it.total)
                .putNullable("today", it.today?.toJson()).putNullable("yesterday", it.yesterday?.toJson())
        })
        .putNullable("wl_error", wlError)
        .put("players", JSONArray().apply { players.forEach { put(it.toJson()) } })
        .putNullable("sale", sale?.toJson())

    companion object {
        fun fromJson(o: JSONObject) = Row(
            o.getString("key"), o.optString("name"), o.intOrNull("appid"), o.optInt("color"),
            o.optJSONObject("wl")?.let {
                WlSummary(
                    it.intOrNull("total"),
                    DayCounts.fromJson(it.optJSONObject("today")),
                    DayCounts.fromJson(it.optJSONObject("yesterday")),
                )
            },
            o.strOrNull("wl_error"),
            o.optJSONArray("players")?.objects()?.map { PlayerInfo.fromJson(it) } ?: emptyList(),
            SaleInfo.fromJson(o.optJSONObject("sale")),
        )
    }
}

/** Everything the widget needs to draw, computed by [Refresher] and stored as snapshot.json. */
class Snapshot(
    val today: LocalDate,
    val adds: Int?,
    val total: Int?,
    val players: Int?,
    val saleTotal: Double?,
    val saleDay: LocalDate?,
    val rows: List<Row>,
    val emptyMessage: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("today", today.toString()).putNullable("adds", adds).putNullable("total", total)
        .putNullable("players", players).putNullable("sale_total", saleTotal)
        .putNullable("sale_day", saleDay?.toString())
        .put("rows", JSONArray().apply { rows.forEach { put(it.toJson()) } })
        .put("empty", emptyMessage)

    companion object {
        private const val FILE = "snapshot.json"

        fun fromJson(o: JSONObject) = Snapshot(
            LocalDate.parse(o.getString("today")), o.intOrNull("adds"), o.intOrNull("total"), o.intOrNull("players"),
            o.dblOrNull("sale_total"), o.strOrNull("sale_day")?.let { LocalDate.parse(it) },
            o.optJSONArray("rows")?.objects()?.map { Row.fromJson(it) } ?: emptyList(),
            o.optString("empty"),
        )

        fun load(ctx: Context): Snapshot? = try {
            Store.load(Store.file(ctx, FILE))?.let { fromJson(it) }
        } catch (e: Exception) {
            null
        }

        fun save(ctx: Context, s: Snapshot) = Store.save(Store.file(ctx, FILE), s.toJson())
    }
}
