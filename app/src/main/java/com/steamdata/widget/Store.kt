package com.steamdata.widget

import android.content.Context
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** JSON files under filesDir/data, mirroring the PC widget's data/ folder. */
object Store {
    private const val LOG_MAX_BYTES = 512L * 1024

    fun dir(ctx: Context): File = File(ctx.filesDir, "data").also { it.mkdirs() }

    fun file(ctx: Context, name: String): File = File(dir(ctx), name)

    fun load(file: File): JSONObject? = try {
        if (file.exists()) JSONObject(file.readText(Charsets.UTF_8)) else null
    } catch (e: Exception) {
        null
    }

    @Synchronized
    fun save(file: File, obj: JSONObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(obj.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    @Synchronized
    fun logError(ctx: Context, msg: String) {
        try {
            val log = file(ctx, "error.log")
            if (log.exists() && log.length() > LOG_MAX_BYTES) {
                val old = File(log.path + ".old")
                old.delete()
                log.renameTo(old)
            }
            val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now())
            log.appendText("[$ts] $msg\n", Charsets.UTF_8)
        } catch (e: IOException) {
            // ignore
        }
    }

    fun logTail(ctx: Context, lines: Int = 20): String {
        val log = file(ctx, "error.log")
        if (!log.exists()) return ""
        return try {
            log.readLines(Charsets.UTF_8).takeLast(lines).joinToString("\n")
        } catch (e: IOException) {
            ""
        }
    }

    /** Deletes every cached file (wishlist history, sales, players, apps, snapshot, log). */
    fun clearAll(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }
}

class HttpError(val code: Int, val url: String) : IOException("HTTP $code")

object Http {
    private const val UA = "SteamDataWidget/1.0 (Android)"

    /** Blocking GET that parses a JSON object. Call from Dispatchers.IO. */
    fun getJson(url: String, params: Map<String, Any?>, timeoutMs: Int = 20_000): JSONObject {
        val q = params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value.toString(), "UTF-8")
        }
        val conn = URL("$url?$q").openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code >= 400) throw HttpError(code, url)
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    /** Same, but waits and retries on 429 Too Many Requests. */
    suspend fun getJsonRetry(url: String, params: Map<String, Any?>): JSONObject {
        val waits = listOf<Long?>(5, 15, 30, 60, null)
        for (wait in waits) {
            try {
                return getJson(url, params)
            } catch (e: HttpError) {
                if (e.code != 429 || wait == null) throw e
                delay(wait * 1000)
            }
        }
        throw IllegalStateException("unreachable")
    }

    fun isTransient(e: Throwable): Boolean = when (e) {
        is HttpError -> e.code == 429 || e.code >= 500
        is IOException -> true
        else -> false
    }
}
