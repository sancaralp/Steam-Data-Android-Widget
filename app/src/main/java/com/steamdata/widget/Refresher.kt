package com.steamdata.widget

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * One full refresh: discover games, poll players, update wishlist history, update sales,
 * then write a [Snapshot] for the widget. Mirrors the PC widget's background worker.
 */
class Refresher(
    private val ctx: Context,
    /** Progress text for the status line (null clears it). Cheap partial widget update. */
    private val onProgress: suspend (String?) -> Unit,
    /** New data was written to the snapshot; redraw the whole widget. */
    private val onData: suspend () -> Unit,
    /** The first wishlist download can take many minutes; the worker promotes itself to foreground. */
    private val onLongRun: suspend () -> Unit,
) {
    private val cfg = Config(ctx)
    private val state = WidgetState(ctx)
    private val finKey = cfg.financialKey
    private val hasKey = cfg.hasFinancialKey
    private val pubKey = cfg.effectivePublisherKey

    private val appsFile = Store.file(ctx, "apps.json")
    private val wlStateFile = Store.file(ctx, "wl_state.json")

    private var discovered: JSONObject? = if (pubKey.isNotEmpty()) Store.load(appsFile) else null
    private var games: List<Game> =
        mergeGames(Game.listFromJson(discovered?.optJSONArray("games")), cfg.manualGames())
    private var discoverError: String? = null
    private val wlErrors = HashMap<Int, String>()
    private val wlNodata = HashSet<Int>()
    private val playerNow = HashMap<Int, Int?>()
    private val players = PlayerTracker(ctx, pubKey)
    private val sales = SalesStore(ctx, finKey)
    private var wishlists: Map<Int, WishlistStore> = emptyMap()
    private var steamDown: String? = null
    private var ok = true

    init {
        Store.load(wlStateFile)?.let { s ->
            s.optJSONObject("errors")?.let { e ->
                val it = e.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    k.toIntOrNull()?.let { id -> wlErrors[id] = e.optString(k) }
                }
            }
            s.optJSONArray("nodata")?.let { a -> for (i in 0 until a.length()) wlNodata.add(a.optInt(i)) }
        }
        discoverError = discovered?.strOrNull("error")
    }

    private fun markDown(e: Throwable) {
        steamDown = if (e is HttpError) "Steam not responding (${e.code})" else "offline"
    }

    private fun setGames(list: List<Game>) {
        games = list
        wishlists = list.mapNotNull { it.appid }.distinct().associateWith { WishlistStore(ctx, finKey, it) }
    }

    private suspend fun discover() {
        if (pubKey.isEmpty()) return
        val ts = discovered?.optLong("ts") ?: 0L
        if (games.isNotEmpty() && System.currentTimeMillis() / 1000 - ts < DISCOVER_INTERVAL_S) return
        try {
            val found = discoverApps(pubKey, cfg.excludeAppids)
            discoverError = if (found.isEmpty()) "no apps are linked to this publisher key" else null
            discovered = JSONObject().put("ts", System.currentTimeMillis() / 1000)
                .put("games", Game.listToJson(found)).putNullable("error", discoverError)
            Store.save(appsFile, discovered!!)
            setGames(mergeGames(found, cfg.manualGames()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpError) {
            discoverError = if (e.code == 401 || e.code == 403) "invalid publisher key (403)" else "HTTP ${e.code}"
            Store.logError(ctx, "discover: $e")
        } catch (e: Exception) {
            discoverError = e.message ?: e.toString()
            Store.logError(ctx, "discover: $e")
        }
    }

    private fun buildSnapshot(): Snapshot {
        val today = steamToday(cfg.dayStartHour)
        val rows = ArrayList<Row>()
        games.sortedBy { it.key ?: 0 }.forEachIndexed { i, g ->
            val appid = g.appid
            val key = (appid ?: g.demoAppid).toString()
            var wl: WlSummary? = null
            if (appid != null && hasKey && appid !in wlNodata) {
                wishlists[appid]?.let { store ->
                    val s = store.summary(today)
                    wl = if (s.total == null && appid !in wlErrors) null else s
                }
            }
            val pl = listOf("game" to appid, "demo" to g.demoAppid)
                .filter { (_, a) -> a != null && (!playerNow.containsKey(a) || playerNow[a] != null) }
                .map { (kind, a) ->
                    val (peak, cov) = players.peak24h(a!!)
                    PlayerInfo(kind, a, playerNow[a], peak, cov)
                }
            val sale = if (appid != null && hasKey) sales.forApp(appid, today) else null
            if (wl == null && pl.isEmpty() && sale == null) return@forEachIndexed
            rows.add(
                Row(
                    key, g.displayName, appid, Palette.RING[i % Palette.RING.size], wl,
                    appid?.let { wlErrors[it] }, pl, sale?.let { SaleInfo(it.second.net, it.second.units, it.first) },
                )
            )
        }
        rows.sortWith(compareBy({ -(it.wl?.today?.adds ?: -1) }, { -(it.wl?.total ?: 0) }))

        val wlRows = rows.mapNotNull { it.wl }
        val adds = if (wlRows.isEmpty()) null else wlRows.sumOf { it.today?.adds ?: 0 }
        val total = if (wlRows.isEmpty()) null else wlRows.sumOf { it.total ?: 0 }
        val nowPlayers = playerNow.values.filterNotNull()
        val playersSum = if (nowPlayers.isEmpty()) null else nowPlayers.sum()
        val saleTotal = if (hasKey) sales.total(today) else null
        val emptyMsg = discoverError
            ?: if (pubKey.isNotEmpty()) "loading games…" else "add a publisher API key in settings"
        return Snapshot(today, adds, total, playersSum, saleTotal?.second, saleTotal?.first, rows, emptyMsg)
    }

    private suspend fun publish() {
        Snapshot.save(ctx, buildSnapshot())
        state.steamDown = steamDown
        onData()
    }

    private fun saveWlState() {
        val errors = JSONObject()
        wlErrors.forEach { (k, v) -> errors.put(k.toString(), v) }
        val nodata = JSONArray()
        wlNodata.forEach { nodata.put(it) }
        Store.save(wlStateFile, JSONObject().put("errors", errors).put("nodata", nodata))
    }

    /** Returns true when every step succeeded (false after a transient Steam or network failure). */
    suspend fun run(): Boolean {
        setGames(games)
        try {
            discover()

            for (g in games) {
                for (a in listOfNotNull(g.appid, g.demoAppid)) {
                    try {
                        playerNow[a] = players.poll(a)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ok = false
                        markDown(e)
                        Store.logError(ctx, "players $a: $e")
                    }
                }
            }
            players.save()
            publish()

            if (hasKey) {
                val names = games.associate { it.appid to it.displayName }
                var longRun = false
                for ((appid, store) in wishlists) {
                    val name = names[appid] ?: appid.toString()
                    try {
                        store.refresh { done, total ->
                            if (!longRun && total - done > 100) {
                                longRun = true
                                onLongRun()
                            }
                            onProgress("$name: wishlist history $done/$total")
                        }
                        wlErrors.remove(appid)
                        wlNodata.remove(appid)
                    } catch (e: NoWishlistData) {
                        wlNodata.add(appid)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e is HttpError && (e.code == 401 || e.code == 403)) {
                            wlErrors[appid] = "invalid financial API key (403)"
                        } else if (Http.isTransient(e)) {
                            ok = false
                            markDown(e)
                        } else {
                            wlErrors[appid] = e.message ?: e.toString()
                            Store.logError(ctx, "wishlist $appid: ${e.stackTraceToString()}")
                            continue
                        }
                        Store.logError(ctx, "wishlist $appid: $e")
                    }
                    onProgress(null)
                    publish()
                }
                try {
                    onProgress("updating sales…")
                    sales.refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ok = false
                    markDown(e)
                    Store.logError(ctx, "sales: " + if (Http.isTransient(e)) e.toString() else e.stackTraceToString())
                }
            }
            if (ok) state.lastUpdate = System.currentTimeMillis()
            return ok
        } finally {
            saveWlState()
            onProgress(null)
            publish()
        }
    }
}
