package com.steamdata.widget

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object Urls {
    private const val FIN_BASE = "https://partner.steam-api.com/IPartnerFinancialsService/"
    const val WISHLIST = FIN_BASE + "GetAppWishlistReporting/v001/"
    const val CHANGED_DATES = FIN_BASE + "GetChangedDatesForPartner/v001/"
    const val SALES = FIN_BASE + "GetDetailedSales/v001/"
    const val PLAYERS = "https://api.steampowered.com/ISteamUserStats/GetNumberOfCurrentPlayers/v1/"
    const val PARTNER_PLAYERS = "https://partner.steam-api.com/ISteamUserStats/GetNumberOfCurrentPlayers/v1/"
    const val APPLIST = "https://partner.steam-api.com/ISteamApps/GetPartnerAppListForWebAPIKey/v2/"
    const val STORE = "https://store.steampowered.com/api/appdetails"
    const val README = "https://github.com/KaganAyten/Steam-Data-PC-Widget#getting-your-api-keys"

    fun steamDb(appid: Int) = "https://steamdb.info/app/$appid/charts/"
    fun details(appid: Int, today: LocalDate) =
        "https://partner.steampowered.com/app/details/$appid/?dateStart=2000-01-01&dateEnd=$today"
}

object Palette {
    val TEXT = Color.parseColor("#ffffff")
    val BODY = Color.parseColor("#deddda")
    val DIM = Color.parseColor("#9a9996")
    val GREEN = Color.parseColor("#33d17a")
    val RED = Color.parseColor("#f0566a")
    val BLUE = Color.parseColor("#4f8fe6")
    val PURPLE = Color.parseColor("#c061cb")
    val ORANGE = Color.parseColor("#e09a5c")
    val RING = listOf(
        "#33d17a", "#3584e4", "#ff7800", "#c061cb", "#2190a4", "#e01b24", "#f6d32d", "#9a9996"
    ).map { Color.parseColor(it) }
}

object Fmt {
    fun num(n: Int?): String = if (n == null) "—" else String.format(Locale.US, "%,d", n)

    fun money(v: Double): String =
        if (v < 0) "-\$" + String.format(Locale.US, "%,.2f", -v) else "\$" + String.format(Locale.US, "%,.2f", v)

    fun day(d: LocalDate): String = d.month.getDisplayName(TextStyle.SHORT, Locale.US) + " " + d.dayOfMonth

    fun weekday(d: LocalDate): String = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)

    fun time(epochMs: Long): String =
        DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
}

/** The Steam reporting day: local time shifted back by `dayStartHour` hours. */
fun steamToday(dayStartHour: Int): LocalDate =
    LocalDateTime.now().minusHours(dayStartHour.toLong()).toLocalDate()

/** Steam returns dates as "2026/10/11"; accept that or ISO. */
fun parseSteamDate(s: String): LocalDate = LocalDate.parse(s.replace('/', '-').take(10))

// ---- org.json helpers (JSONObject.NULL aware) ----

fun JSONObject.strOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else opt(key)?.toString()?.takeIf { it.isNotEmpty() }

fun JSONObject.intOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return when (val v = opt(key)) {
        is Number -> v.toInt()
        else -> v?.toString()?.trim()?.toDoubleOrNull()?.toInt()
    }
}

fun JSONObject.longOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return when (val v = opt(key)) {
        is Number -> v.toLong()
        else -> v?.toString()?.trim()?.toDoubleOrNull()?.toLong()
    }
}

fun JSONObject.dblOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val v = opt(key)) {
        is Number -> v.toDouble()
        else -> v?.toString()?.trim()?.toDoubleOrNull()
    }
}

fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
