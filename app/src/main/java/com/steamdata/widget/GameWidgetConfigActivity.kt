package com.steamdata.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Picks which game a compact widget shows. Used when placing the widget and when reconfiguring it. */
class GameWidgetConfigActivity : AppCompatActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_config)
        widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val games = GameWidgetRenderer.knownGames(this)
        val list = findViewById<ListView>(R.id.game_list)
        val empty = findViewById<TextView>(R.id.empty)
        val openSettings = findViewById<Button>(R.id.btn_settings)

        if (games.isEmpty()) {
            list.visibility = View.GONE
            empty.visibility = View.VISIBLE
            openSettings.visibility = View.VISIBLE
            empty.text = if (Config(this).hasPublisherKey) {
                "No games discovered yet. The list fills after the first refresh finishes."
            } else {
                "Add your API keys in the app first. Games appear here after the first refresh."
            }
            openSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
            return
        }

        val labels = games.map { g ->
            val ids = listOfNotNull(g.appid?.let { "app $it" }, g.demoAppid?.let { "demo $it" }).joinToString(" · ")
            "${g.displayName}\n$ids"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        list.setOnItemClickListener { _, _, position, _ ->
            val key = games[position].key?.toString() ?: return@setOnItemClickListener
            GameWidgetStore(this).set(widgetId, key)
            val mgr = AppWidgetManager.getInstance(this)
            mgr.updateAppWidget(widgetId, GameWidgetRenderer.build(this, mgr, widgetId))
            Scheduler.ensureScheduled(this)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }
}
