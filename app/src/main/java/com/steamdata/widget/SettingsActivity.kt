package com.steamdata.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray

/** Settings screen. Also serves as the widget's configuration activity. */
class SettingsActivity : AppCompatActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val configuring get() = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID

    private lateinit var cfg: Config
    private lateinit var finKey: EditText
    private lateinit var pubKey: EditText
    private lateinit var exclude: EditText
    private lateinit var games: EditText
    private lateinit var interval: EditText
    private lateinit var dayStart: EditText
    private lateinit var status: TextView
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        cfg = Config(this)

        widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (configuring) {
            setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        }

        finKey = findViewById(R.id.fin_key)
        pubKey = findViewById(R.id.pub_key)
        exclude = findViewById(R.id.exclude)
        games = findViewById(R.id.games_json)
        interval = findViewById(R.id.interval)
        dayStart = findViewById(R.id.day_start)
        status = findViewById(R.id.status)
        log = findViewById(R.id.log)

        finKey.setText(cfg.financialKey)
        pubKey.setText(cfg.publisherKey)
        exclude.setText(cfg.excludeText)
        games.setText(cfg.manualGamesJson)
        interval.setText(cfg.refreshIntervalMin.toString())
        dayStart.setText(cfg.dayStartHour.toString())

        findViewById<TextView>(R.id.hint_add_widget).visibility = if (configuring) View.GONE else View.VISIBLE

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            if (!save()) return@setOnClickListener
            Scheduler.ensureScheduled(this)
            Scheduler.refreshNow(this)
            if (configuring) {
                AppWidgetManager.getInstance(this).updateAppWidget(widgetId, WidgetRenderer.full(this, widgetId))
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                finish()
            } else {
                Widgets.updateAll(this)
                Toast.makeText(this, "Saved · refreshing", Toast.LENGTH_SHORT).show()
                refreshStatus()
            }
        }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener {
            if (!save()) return@setOnClickListener
            Scheduler.ensureScheduled(this)
            Scheduler.refreshNow(this)
            Toast.makeText(this, "Refreshing…", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            Store.clearAll(this)
            WidgetState(this).clearForNewData()
            Widgets.updateAll(this)
            Toast.makeText(this, "Cached data cleared", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
        findViewById<TextView>(R.id.help_link).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Urls.README)))
        }
        findViewById<Button>(R.id.btn_pin).setOnClickListener { requestPin() }
        if (intent?.getBooleanExtra(EXTRA_PIN, false) == true) requestPin()
    }

    /** Asks the launcher to place the widget (Android 8+ pin request; the launcher shows a confirmation). */
    private fun requestPin() {
        val mgr = AppWidgetManager.getInstance(this)
        val provider = ComponentName(this, SteamWidgetProvider::class.java)
        val ok = mgr.isRequestPinAppWidgetSupported && mgr.requestPinAppWidget(provider, null, null)
        if (!ok) {
            Toast.makeText(this, "Your launcher does not support this. Long-press the home screen → Widgets.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val st = WidgetState(this)
        val down = st.steamDown
        status.text = when {
            st.busy -> st.progress ?: "updating…"
            down != null -> down + if (st.lastUpdate > 0) " · last data ${Fmt.time(st.lastUpdate)}" else ""
            st.lastUpdate > 0 -> "last update ${Fmt.time(st.lastUpdate)}"
            !cfg.hasFinancialKey && !cfg.hasPublisherKey -> "Add at least one API key below."
            else -> "No data yet."
        }
        val tail = Store.logTail(this)
        log.text = tail.ifEmpty { "No errors logged." }
    }

    private fun save(): Boolean {
        val gamesText = games.text.toString().trim()
        if (gamesText.isNotEmpty()) {
            try {
                JSONArray(gamesText)
            } catch (e: Exception) {
                games.error = "Must be a JSON array"
                return false
            }
        }
        val iv = interval.text.toString().trim().toIntOrNull()
        if (iv == null || iv < 1) {
            interval.error = "Minutes, at least 1"
            return false
        }
        val h = dayStart.text.toString().trim().toIntOrNull()
        if (h == null || h !in 0..23) {
            dayStart.error = "Hour from 0 to 23"
            return false
        }
        cfg.financialKey = finKey.text.toString()
        cfg.publisherKey = pubKey.text.toString()
        cfg.excludeText = exclude.text.toString()
        cfg.manualGamesJson = gamesText
        cfg.refreshIntervalMin = iv
        cfg.dayStartHour = h
        return true
    }

    companion object {
        /** Boolean extra: open settings and immediately ask the launcher to place the widget. */
        const val EXTRA_PIN = "pin"
    }
}
