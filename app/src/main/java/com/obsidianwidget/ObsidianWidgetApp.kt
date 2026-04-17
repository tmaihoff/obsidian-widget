package com.obsidianwidget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper

class ObsidianWidgetApp : Application() {

    private val handler = Handler(Looper.getMainLooper())
    private val contentObservers = mutableListOf<ContentObserver>()
    private var lastNightMode: Int = -1

    private val screenUnlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) {
                ObsidianWidgetProvider.updateAllWidgets(context)
            }
        }
    }

    private val pendingUpdateRunnable = Runnable {
        ObsidianWidgetProvider.updateAllWidgets(this@ObsidianWidgetApp)
    }

    override fun onCreate() {
        super.onCreate()
        lastNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenUnlockReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(screenUnlockReceiver, filter)
        }

        registerVaultContentObservers()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val newNightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (newNightMode != lastNightMode) {
            lastNightMode = newNightMode
            // Theme mode changed (dark/light), update all widgets
            ObsidianWidgetProvider.updateAllWidgets(this)
        }
    }

    /**
     * Register ContentObservers on vault URIs to detect file changes
     * and auto-update widgets.
     */
    private fun registerVaultContentObservers() {
        // Unregister existing observers
        for (observer in contentObservers) {
            contentResolver.unregisterContentObserver(observer)
        }
        contentObservers.clear()

        val appWidgetManager = AppWidgetManager.getInstance(this)
        val widgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(this, ObsidianWidgetProvider::class.java)
        )

        val observedUris = mutableSetOf<String>()
        for (widgetId in widgetIds) {
            val vaultManager = VaultManager(this, widgetId)
            val uri = vaultManager.vaultUri ?: continue
            val uriStr = uri.toString()
            if (uriStr in observedUris) continue
            observedUris.add(uriStr)

            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    onChange(selfChange, null)
                }

                override fun onChange(selfChange: Boolean, changeUri: Uri?) {
                    // Debounce updates to avoid spam
                    handler.removeCallbacks(pendingUpdateRunnable)
                    handler.postDelayed(pendingUpdateRunnable, 2000)
                }
            }
            try {
                contentResolver.registerContentObserver(uri, true, observer)
                contentObservers.add(observer)
            } catch (_: Exception) {
                // Some URIs may not support observation
            }
        }
    }
}
