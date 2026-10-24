package com.steamdata.widget

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset

const val RECENT_DAYS = 3L
const val PARALLEL_REQUESTS = 3
const val SALES_KEEP_DAYS = 45L
const val DISCOVER_INTERVAL_S = 24L * 3600

/** Limits concurrent Steam requests to roughly 3 per second, which stayed under the rate limit in testing. */
private val steamSemaphore = Semaphore(PARALLEL_REQUESTS)

class NoWishlistData : Exception("no wishlist data")

class DayCounts(val adds: Int, val deletes: Int, val purchases: Int, val gifts: Int) {
    val net: Int get() = adds - deletes - purchases - gifts

    fun toJson(): JSONObject = JSONObject()
        .put("adds", adds).put("deletes", deletes).put("purchases", purchases).put("gifts", gifts)

    companion object {
        val ZERO = DayCounts(0, 0, 0, 0)
        fun fromJson(o: JSONObject?): DayCounts? = o?.let {
            DayCounts(it.optInt("adds"), it.optInt("deletes"), it.optInt("purchases"), it.optInt("gifts"))
        }
    }
}

class WlSummary(val total: Int?, val today: DayCounts?, val yesterday: DayCounts?)

/** Per-app wishlist history. Steam only reports per-day numbers, so the total is a running sum. */
class WishlistStore(ctx: Context, private val key: String, val appid: Int) {
    private val file = Store.file(ctx, "wishlist_$appid.json")
    private val data: JSONObject = Store.load(file)
        ?: JSONObject().put("min_date", JSONObject.NULL).put("days", JSONObject())
    private val days: JSONObject get() = data.getJSONObject("days")

    private suspend fun fetchDay(date: String): Pair<String?, DayCounts?> = withContext(Dispatchers.IO) {
        val r = Http.getJsonRetry(Urls.WISHLIST, mapOf("key" to key, "appid" to appid, "date" to date))
            .optJSONObject("response") ?: JSONObject()
        val s = r.optJSONObject("wishlist_summary")
        val day = s?.let {
            DayCounts(
                it.intOrNull("wishlist_adds") ?: 0, it.intOrNull("wishlist_deletes") ?: 0,
                it.intOrNull("wishlist_purchases") ?: 0, it.intOrNull("wishlist_gifts") ?: 0,
            )
        }
        r.strOrNull("app_min_date") to day
    }

    /**
     * Downloads every missing day since the app's first wishlist, plus the last [RECENT_DAYS] days.
     * [progress] is called every 25 days with (done, total).
     */
    suspend fun refresh(progress: suspend (Int, Int) -> Unit) {
        val today = LocalDate.now(ZoneOffset.UTC)
        val (minDate, _) = fetchDay(today.toString())
        if (minDate != null) data.put("min_date", minDate)
        val md = data.strOrNull("min_date") ?: throw NoWishlistData()

        val start = parseSteamDate(md)
        val recentCutoff = today.minusDays(RECENT_DAYS)
        val needed = ArrayList<String>()
        var d = start
        while (!d.isAfter(today)) {
            val iso = d.toString()
            if (!days.has(iso) || !d.isBefore(recentCutoff)) needed.add(iso)
            d = d.plusDays(1)
        }

        var done = 0
        try {
            for (chunk in needed.chunked(25)) {
                val results = coroutineScope {
                    chunk.map { iso ->
                        async { steamSemaphore.withPermit { iso to fetchDay(iso).second } }
                    }.awaitAll()
                }
                for ((iso, day) in results) storeDay(iso, day, recentCutoff)
                done += chunk.size
                Store.save(file, data)
                if (done < needed.size) progress(done, needed.size)
            }
        } finally {
            Store.save(file, data)
        }
    }

    private fun storeDay(iso: String, day: DayCounts?, recentCutoff: LocalDate) {
        when {
            day != null -> days.put(iso, day.toJson())
            LocalDate.parse(iso).isBefore(recentCutoff) -> days.put(iso, DayCounts.ZERO.toJson())
            else -> days.remove(iso)
        }
    }

    fun summary(today: LocalDate): WlSummary {
        var total = 0
        var any = false
        val it = days.keys()
        while (it.hasNext()) {
            val k = it.next()
            DayCounts.fromJson(days.optJSONObject(k))?.let { c -> total += c.net; any = true }
        }
        return WlSummary(
            if (any) total else null,
            DayCounts.fromJson(days.optJSONObject(today.toString())),
            DayCounts.fromJson(days.optJSONObject(today.minusDays(1).toString())),
        )
    }
}

class AppSale(val net: Double, val gross: Double, val units: Int)

/** Per-day net revenue per primary app for the last [SALES_KEEP_DAYS] days. */
class SalesStore(ctx: Context, private val key: String) {
    private val file = Store.file(ctx, "sales.json")
    private val data: JSONObject = Store.load(file) ?: JSONObject().put("hw", "0").put("days", JSONObject())
    private val days: JSONObject get() = data.getJSONObject("days")

    private suspend fun dayTotals(date: String): JSONObject = withContext(Dispatchers.IO) {
        var hw = 0L
        val totals = JSONObject()
        while (true) {
            val r = Http.getJsonRetry(Urls.SALES, mapOf("key" to key, "date" to date, "highwatermark_id" to hw))
                .optJSONObject("response") ?: JSONObject()
            for (it in r.optJSONArray("results")?.objects() ?: emptyList()) {
                val a = (it.intOrNull("primary_appid") ?: it.intOrNull("appid") ?: 0).toString()
                val t = totals.optJSONObject(a) ?: JSONObject().put("net", 0.0).put("gross", 0.0).put("units", 0)
                    .also { n -> totals.put(a, n) }
                t.put("net", t.optDouble("net", 0.0) + (it.dblOrNull("net_sales_usd") ?: 0.0))
                t.put("gross", t.optDouble("gross", 0.0) + (it.dblOrNull("gross_sales_usd") ?: 0.0))
                t.put("units", t.optInt("units", 0) + (it.intOrNull("net_units_sold") ?: 0))
            }
            val maxId = r.longOrNull("max_id") ?: hw
            if (maxId == hw) break
            hw = maxId
        }
        totals
    }

    suspend fun refresh() {
        val r = withContext(Dispatchers.IO) {
            Http.getJsonRetry(Urls.CHANGED_DATES, mapOf("key" to key, "highwatermark" to data.optString("hw", "0")))
        }.optJSONObject("response") ?: JSONObject()
        val cutoff = LocalDate.now().minusDays(SALES_KEEP_DAYS)
        val dates = (r.optJSONArray("dates") ?: JSONArray()).let { a ->
            (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } }
        }.filter { !parseSteamDate(it).isBefore(cutoff) }

        val fetched = coroutineScope {
            dates.map { d -> async { steamSemaphore.withPermit { d to dayTotals(d) } } }.awaitAll()
        }
        for ((d, totals) in fetched) days.put(d.replace('/', '-'), totals)

        val keep = JSONObject()
        val it = days.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (!LocalDate.parse(k).isBefore(cutoff)) keep.put(k, days.get(k))
        }
        data.put("days", keep)
        data.put("hw", r.strOrNull("result_highwatermark") ?: data.optString("hw", "0"))
        Store.save(file, data)
    }

    private fun sortedDays(): List<String> = days.keys().asSequence().toList().sortedDescending()

    fun forApp(appid: Int, today: LocalDate): Pair<LocalDate, AppSale>? {
        val todayIso = today.toString()
        val d = sortedDays().firstOrNull { it <= todayIso && days.optJSONObject(it)?.has(appid.toString()) == true }
            ?: return null
        val t = days.getJSONObject(d).getJSONObject(appid.toString())
        return LocalDate.parse(d) to AppSale(t.optDouble("net", 0.0), t.optDouble("gross", 0.0), t.optInt("units", 0))
    }

    fun total(today: LocalDate): Pair<LocalDate, Double>? {
        val todayIso = today.toString()
        val d = sortedDays().firstOrNull { it <= todayIso && (days.optJSONObject(it)?.length() ?: 0) > 0 }
            ?: return null
        val obj = days.getJSONObject(d)
        var sum = 0.0
        val it = obj.keys()
        while (it.hasNext()) sum += obj.getJSONObject(it.next()).optDouble("net", 0.0)
        return LocalDate.parse(d) to sum
    }
}

/** Current player counts, with a locally collected 24h history for the peak. */
class PlayerTracker(ctx: Context, private val publisherKey: String) {
    private val file = Store.file(ctx, "players_history.json")
    private val history: JSONObject = Store.load(file) ?: JSONObject()

    /** Returns the current player count, or null when Steam has no public number for the app. */
    suspend fun poll(appid: Int): Int? = withContext(Dispatchers.IO) {
        val r: JSONObject? = try {
            Http.getJson(Urls.PLAYERS, mapOf("appid" to appid)).optJSONObject("response")
        } catch (e: HttpError) {
            if (e.code != 404) throw e
            if (publisherKey.isEmpty()) return@withContext null
            try {
                Http.getJson(Urls.PARTNER_PLAYERS, mapOf("appid" to appid, "key" to publisherKey))
                    .optJSONObject("response")
            } catch (e2: HttpError) {
                if (e2.code == 404) return@withContext null
                throw e2
            }
        }
        if (r == null || r.intOrNull("result") != 1) return@withContext null
        val count = r.intOrNull("player_count") ?: 0
        val now = System.currentTimeMillis() / 1000
        val h = history.optJSONArray(appid.toString()) ?: JSONArray()
        h.put(JSONArray().put(now).put(count))
        val cutoff = now - 26 * 3600
        val trimmed = JSONArray()
        for (i in 0 until h.length()) {
            val p = h.optJSONArray(i) ?: continue
            if (p.optLong(0) >= cutoff) trimmed.put(p)
        }
        history.put(appid.toString(), trimmed)
        count
    }

    /** (peak, hours of coverage). Coverage below ~23h means the peak may be incomplete. */
    fun peak24h(appid: Int): Pair<Int?, Double> {
        val h = history.optJSONArray(appid.toString()) ?: return null to 0.0
        val now = System.currentTimeMillis() / 1000
        var first = -1L
        var peak: Int? = null
        for (i in 0 until h.length()) {
            val p = h.optJSONArray(i) ?: continue
            val ts = p.optLong(0)
            if (ts < now - 24 * 3600) continue
            if (first < 0 || ts < first) first = ts
            val c = p.optInt(1)
            if (peak == null || c > peak) peak = c
        }
        if (peak == null) return null to 0.0
        return peak to (now - first) / 3600.0
    }

    fun save() = Store.save(file, history)
}

private fun storeDetails(appid: Int): JSONObject? = try {
    val v = Http.getJson(Urls.STORE, mapOf("appids" to appid)).optJSONObject(appid.toString())
    if (v?.optBoolean("success") == true) v.optJSONObject("data") else null
} catch (e: Exception) {
    null
}

private fun baseName(name: String): String {
    val n = name.trim()
    for (suffix in listOf(" Free Demo", " (Demo)", " - Demo", " Demo", " Playtest")) {
        if (n.lowercase().endsWith(suffix.lowercase())) {
            return n.dropLast(suffix.length).trim(' ', '-', ':').lowercase()
        }
    }
    return n.lowercase()
}

/** Lists the games and demos the publisher key can see, and pairs each demo with its game. */
suspend fun discoverApps(publisherKey: String, exclude: Set<Int>): List<Game> = withContext(Dispatchers.IO) {
    val r = Http.getJson(Urls.APPLIST, mapOf("key" to publisherKey, "type_filter" to "game,demo"))
    val apps = (r.optJSONObject("applist")?.optJSONObject("apps")?.optJSONArray("app")?.objects() ?: emptyList())
        .filter { it.intOrNull("appid") != null && it.intOrNull("appid") !in exclude }

    val games = LinkedHashMap<Int, Game>()
    for (a in apps) {
        if (a.optString("app_type").lowercase() != "game") continue
        val id = a.intOrNull("appid")!!
        games[id] = Game(a.strOrNull("app_name") ?: id.toString(), id, null)
    }
    val unmatched = ArrayList<Game>()
    for (d in apps) {
        if (d.optString("app_type").lowercase() != "demo") continue
        val did = d.intOrNull("appid")!!
        var parent: Int? = storeDetails(did)?.optJSONObject("fullgame")?.intOrNull("appid")
        if (parent == null || parent !in games) {
            val want = baseName(d.optString("app_name"))
            parent = games.entries.firstOrNull { it.value.name?.lowercase() == want }?.key
        }
        if (parent != null && parent in games) {
            games[parent] = games[parent]!!.copy(demoAppid = did)
        } else {
            unmatched.add(Game(d.strOrNull("app_name") ?: did.toString(), null, did))
        }
    }
    games.values.sortedBy { it.name?.lowercase() ?: "" } + unmatched
}
