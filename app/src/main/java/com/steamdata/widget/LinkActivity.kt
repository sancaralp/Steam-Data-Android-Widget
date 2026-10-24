package com.steamdata.widget

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Invisible trampoline for taps inside the widget list: expands or collapses a row,
 * or opens a SteamDB / Steamworks link. Finishes immediately.
 */
class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.getStringExtra(EXTRA_URL)?.let { url ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                // no browser
            }
        }
        intent?.getStringExtra(EXTRA_TOGGLE)?.let { key ->
            WidgetState(this).toggleExpanded(key)
            SteamWidgetProvider.notifyData(this)
        }
        finish()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TOGGLE = "toggle"
    }
}
