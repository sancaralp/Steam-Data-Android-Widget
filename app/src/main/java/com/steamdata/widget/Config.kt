package com.steamdata.widget

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Game(val name: String?, val appid: Int?, val demoAppid: Int?) {
    val key: Int? get() = appid ?: demoAppid
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: key.toString()

    fun toJson(): JSONObject = JSONObject().apply {
        name?.let { put("name", it) }
        appid?.let { put("appid", it) }
        demoAppid?.let { put("demo_appid", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = Game(o.strOrNull("name"), o.intOrNull("appid"), o.intOrNull("demo_appid"))
        fun listFromJson(a: JSONArray?): List<Game> = a?.objects()?.map { fromJson(it) } ?: emptyList()
        fun listToJson(list: List<Game>): JSONArray = JSONArray().apply { list.forEach { put(it.toJson()) } }
    }
}

/** Discovered games first; manual entries override names or demo pairing. */
fun mergeGames(discovered: List<Game>, manual: List<Game>): List<Game> {
    val out = LinkedHashMap<Int, Game>()
    for (g in discovered) g.key?.let { out[it] = g }
    for (m in manual) {
        val k = m.key ?: continue
        val base = out[k] ?: Game(null, null, null)
        out[k] = Game(
            m.name?.takeIf { it.isNotBlank() } ?: base.name,
            m.appid ?: base.appid,
            m.demoAppid ?: base.demoAppid,
        )
    }
    return out.values.toList()
}

/** User settings. Stored in app-private SharedPreferences (backup is disabled in the manifest). */
class Config(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("config", Context.MODE_PRIVATE)

    var financialKey: String
        get() = prefs.getString(K_FIN, "")!!.trim()
        set(v) = prefs.edit().putString(K_FIN, v.trim()).apply()

    var publisherKey: String
        get() = prefs.getString(K_PUB, "")!!.trim()
        set(v) = prefs.edit().putString(K_PUB, v.trim()).apply()

    var excludeText: String
        get() = prefs.getString(K_EXCLUDE, "")!!
        set(v) = prefs.edit().putString(K_EXCLUDE, v.trim()).apply()

    var manualGamesJson: String
        get() = prefs.getString(K_GAMES, "")!!
        set(v) = prefs.edit().putString(K_GAMES, v.trim()).apply()

    var refreshIntervalMin: Int
        get() = prefs.getInt(K_INTERVAL, 60).coerceAtLeast(1)
        set(v) = prefs.edit().putInt(K_INTERVAL, v.coerceAtLeast(1)).apply()

    var dayStartHour: Int
        get() = prefs.getInt(K_DAY_START, 10).coerceIn(0, 23)
        set(v) = prefs.edit().putInt(K_DAY_START, v.coerceIn(0, 23)).apply()

    val hasFinancialKey: Boolean get() = !isPlaceholder(financialKey)
    val hasPublisherKey: Boolean get() = !isPlaceholder(publisherKey)
    val effectivePublisherKey: String get() = if (hasPublisherKey) publisherKey else ""

    val excludeAppids: Set<Int>
        get() = excludeText.split(',', ' ', ';', '\n').mapNotNull { it.trim().toIntOrNull() }.toSet()

    fun manualGames(): List<Game> = try {
        val t = manualGamesJson
        if (t.isBlank()) emptyList() else Game.listFromJson(JSONArray(t)).filter { it.key != null }
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        private const val K_FIN = "financial_api_key"
        private const val K_PUB = "publisher_api_key"
        private const val K_EXCLUDE = "exclude_appids"
        private const val K_GAMES = "games"
        private const val K_INTERVAL = "refresh_interval_min"
        private const val K_DAY_START = "day_start_hour"

        fun isPlaceholder(v: String) = v.isBlank() || v.startsWith("YOUR_")
    }
}

/** Runtime state shared between the worker, the widget renderer and the list factory. */
class WidgetState(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("state", Context.MODE_PRIVATE)

    var busy: Boolean
        get() = prefs.getBoolean("busy", false)
        set(v) = prefs.edit().putBoolean("busy", v).apply()

    var progress: String?
        get() = prefs.getString("progress", null)
        set(v) = prefs.edit().putString("progress", v).apply()

    var lastUpdate: Long
        get() = prefs.getLong("last_update", 0L)
        set(v) = prefs.edit().putLong("last_update", v).apply()

    var steamDown: String?
        get() = prefs.getString("steam_down", null)
        set(v) = prefs.edit().putString("steam_down", v).apply()

    var expanded: Set<String>
        get() = prefs.getStringSet("expanded", emptySet())!!.toSet()
        set(v) = prefs.edit().putStringSet("expanded", v.toSet()).apply()

    var failStreak: Int
        get() = prefs.getInt("fail_streak", 0)
        set(v) = prefs.edit().putInt("fail_streak", v).apply()

    fun toggleExpanded(key: String) {
        val s = expanded.toMutableSet()
        if (!s.add(key)) s.remove(key)
        expanded = s
    }

    fun clearForNewData() {
        prefs.edit().remove("last_update").remove("steam_down").remove("progress").apply()
    }
}
