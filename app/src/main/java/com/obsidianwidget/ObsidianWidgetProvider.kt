package com.obsidianwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.view.View
import android.widget.RemoteViews

class ObsidianWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val ACTION_REFRESH = "com.obsidianwidget.ACTION_REFRESH"
        private const val ACTION_CAPTURE = "com.obsidianwidget.ACTION_CAPTURE"
        private const val ACTION_OPEN = "com.obsidianwidget.ACTION_OPEN"
        private const val ACTION_TOGGLE = "com.obsidianwidget.ACTION_TOGGLE"
        private const val ACTION_ADD = "com.obsidianwidget.ACTION_ADD"
        private const val ACTION_NAV_LEFT = "com.obsidianwidget.ACTION_NAV_LEFT"
        private const val ACTION_NAV_RIGHT = "com.obsidianwidget.ACTION_NAV_RIGHT"
        private const val ACTION_OPEN_DAILY = "com.obsidianwidget.ACTION_OPEN_DAILY"
        private const val ACTION_NEW_NOTE = "com.obsidianwidget.ACTION_NEW_NOTE"
        const val EXTRA_LINE_INDEX = "extra_line_index"
        const val EXTRA_APPEND_TO_WIDGET = "extra_append_to_widget"
        const val EXTRA_WIDGET_ID = "extra_widget_id"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_OPEN_NOTE = "extra_open_note"

        fun updateAllWidgets(context: Context) {
            val intent = Intent(context, ObsidianWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, ObsidianWidgetProvider::class.java)
            )
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            VaultManager.deleteWidgetPrefs(context, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_REFRESH -> updateAllWidgets(context)
            ACTION_CAPTURE -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                openNewNote(context, widgetId)
            }
            ACTION_ADD -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                val addIntent = Intent(context, QuickCaptureActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra(EXTRA_APPEND_TO_WIDGET, true)
                    putExtra(EXTRA_WIDGET_ID, widgetId)
                }
                context.startActivity(addIntent)
            }
            ACTION_OPEN -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                openObsidian(context, widgetId)
            }
            ACTION_TOGGLE -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (!url.isNullOrEmpty()) {
                    try {
                        val browseIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        context.startActivity(browseIntent)
                    } catch (_: Exception) { }
                    return
                }
                val openNote = intent.getBooleanExtra(EXTRA_OPEN_NOTE, false)
                if (openNote) {
                    val widgetId2 = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                    if (widgetId2 >= 0) {
                        openObsidian(context, widgetId2)
                    }
                    return
                }
                val lineIndex = intent.getIntExtra(EXTRA_LINE_INDEX, -1)
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                if (lineIndex >= 0 && widgetId >= 0) {
                    val vaultManager = VaultManager(context, widgetId)
                    vaultManager.toggleChecklistItem(lineIndex)
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    updateWidget(context, appWidgetManager, widgetId)
                }
            }
            ACTION_NAV_LEFT, ACTION_NAV_RIGHT -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                if (widgetId >= 0) {
                    val vaultManager = VaultManager(context, widgetId)
                    vaultManager.navigateNote(if (intent.action == ACTION_NAV_LEFT) -1 else 1)
                    val appWidgetManager = AppWidgetManager.getInstance(context)
                    updateWidget(context, appWidgetManager, widgetId)
                }
            }
            ACTION_OPEN_DAILY -> {
                openDailyNote(context)
            }
            ACTION_NEW_NOTE -> {
                val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
                openNewNote(context, widgetId)
            }
        }
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val vaultManager = VaultManager(context, appWidgetId)
        val isKeepStyle = vaultManager.widgetStyle == "keep"
        val layoutId = if (isKeepStyle) R.layout.widget_layout_keep else R.layout.widget_layout
        val views = RemoteViews(context.packageName, layoutId)

        // Set title based on mode
        val noteCount = when (vaultManager.noteMode) {
            VaultManager.NoteMode.PINNED -> vaultManager.getPinnedNoteCount()
            else -> 0
        }
        views.setTextViewText(R.id.widget_date, vaultManager.getWidgetTitle())

        // Check if note has checklist items
        val allItems = vaultManager.parseChecklist()
        val hasChecklist = allItems.any { !it.isPlainText }

        // Show TODO count if enabled
        if (vaultManager.showTodoCount && hasChecklist) {
            val unchecked = allItems.count { !it.isPlainText && !it.isChecked }
            val total = allItems.count { !it.isPlainText }
            views.setTextViewText(R.id.widget_todo_count, "$unchecked of $total remaining")
            views.setViewVisibility(R.id.widget_todo_count, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_todo_count, View.GONE)
        }

        if (hasChecklist) {
            // Show interactive checklist ListView
            views.setViewVisibility(R.id.widget_checklist, View.VISIBLE)
            views.setViewVisibility(R.id.widget_note_preview, View.GONE)

            // Set up RemoteViews adapter for ListView
            val serviceIntent = Intent(context, ChecklistWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_checklist, serviceIntent)

            // Set up pending intent template for item clicks (toggle)
            val toggleIntent = Intent(context, ObsidianWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE
                putExtra(EXTRA_WIDGET_ID, appWidgetId)
            }
            val togglePendingIntent = PendingIntent.getBroadcast(
                context, appWidgetId, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_checklist, togglePendingIntent)

            // Notify data changed
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_checklist)

        } else {
            // Show plain text preview
            views.setViewVisibility(R.id.widget_checklist, View.GONE)
            views.setViewVisibility(R.id.widget_note_preview, View.VISIBLE)

            if (vaultManager.isVaultConfigured || vaultManager.noteMode == VaultManager.NoteMode.PINNED) {
                val noteContent = vaultManager.readWidgetNote()
                val preview = noteContent?.take(500) ?: context.getString(R.string.no_daily_note)
                views.setTextViewText(R.id.widget_note_preview, preview)
            } else {
                views.setTextViewText(
                    R.id.widget_note_preview,
                    context.getString(R.string.no_vault_selected)
                )
            }
        }

        // Title click always opens note in Obsidian
        views.setOnClickPendingIntent(
            R.id.widget_date,
            createActionIntent(context, ACTION_OPEN, appWidgetId)
        )

        // Cycle note arrow (visible only for multi-note)
        if (noteCount > 1) {
            views.setViewVisibility(R.id.widget_cycle_note, View.VISIBLE)
            views.setOnClickPendingIntent(
                R.id.widget_cycle_note,
                createActionIntent(context, ACTION_NAV_RIGHT, appWidgetId)
            )
        } else {
            views.setViewVisibility(R.id.widget_cycle_note, View.GONE)
        }

        // Settings button opens widget config
        val configIntent = Intent(context, WidgetConfigActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        views.setOnClickPendingIntent(
            R.id.widget_settings,
            PendingIntent.getActivity(
                context, appWidgetId + 10000, configIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )

        // Refresh button
        views.setOnClickPendingIntent(
            R.id.widget_refresh,
            createActionIntent(context, ACTION_REFRESH, appWidgetId)
        )

        // Absorb taps on empty space so they don't trigger launcher reconfigure
        views.setOnClickPendingIntent(
            R.id.widget_root,
            createActionIntent(context, ACTION_REFRESH, appWidgetId)
        )

        // Apply widget transparency
        views.setFloat(R.id.widget_root, "setAlpha", vaultManager.widgetAlpha / 100f)

        if (isKeepStyle) {
            applyKeepStyle(context, views, vaultManager, appWidgetId)
        } else {
            applyObsidianStyle(context, views, vaultManager, appWidgetId)
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun applyObsidianStyle(
        context: Context,
        views: RemoteViews,
        vaultManager: VaultManager,
        appWidgetId: Int
    ) {
        // Add to note button
        views.setOnClickPendingIntent(
            R.id.widget_add,
            createActionIntent(context, ACTION_ADD, appWidgetId)
        )

        // Quick capture button
        views.setOnClickPendingIntent(
            R.id.widget_btn_capture,
            createActionIntent(context, ACTION_CAPTURE, appWidgetId)
        )

        // Show/hide header based on setting
        views.setViewVisibility(
            R.id.widget_header,
            if (vaultManager.showHeader) View.VISIBLE else View.GONE
        )

        // Show/hide button bar based on setting
        views.setViewVisibility(
            R.id.widget_button_bar,
            if (vaultManager.showButtons) View.VISIBLE else View.GONE
        )

        // Apply theme colors
        val colors = vaultManager.getThemeColors()
        val isDark = vaultManager.resolveTheme() == "dark"
        views.setInt(R.id.widget_root, "setBackgroundResource",
            if (isDark) R.drawable.widget_background else R.drawable.widget_background_light)
        views.setTextColor(R.id.widget_date, colors.text)
        views.setTextColor(R.id.widget_note_preview, colors.textSecondary)
        views.setTextColor(R.id.widget_todo_count, colors.textSecondary)

        // Tint header icons to match theme
        views.setInt(R.id.widget_refresh, "setColorFilter", colors.text)
        views.setInt(R.id.widget_settings, "setColorFilter", colors.text)
        views.setInt(R.id.widget_cycle_note, "setColorFilter", colors.text)

        // Tint accent-colored buttons (preserves rounded drawable shape)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val accentTint = ColorStateList.valueOf(colors.accent)
            views.setColorStateList(R.id.widget_btn_capture, "setBackgroundTintList", accentTint)
            views.setColorStateList(R.id.widget_add, "setBackgroundTintList", accentTint)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyKeepStyle(
        context: Context,
        views: RemoteViews,
        vaultManager: VaultManager,
        appWidgetId: Int
    ) {
        val isDark = vaultManager.resolveTheme() == "dark"

        // FAB click = create new note in Obsidian
        views.setOnClickPendingIntent(
            R.id.widget_fab,
            createActionIntent(context, ACTION_NEW_NOTE, appWidgetId)
        )

        // Daily note button
        views.setOnClickPendingIntent(
            R.id.widget_daily_btn,
            createActionIntent(context, ACTION_OPEN_DAILY, appWidgetId)
        )

        // Show/hide header based on setting
        views.setViewVisibility(
            R.id.widget_header,
            if (vaultManager.showHeader) View.VISIBLE else View.GONE
        )

        // In folder mode, make note card transparent so individual items show their own card backgrounds
        val isFolder = vaultManager.noteMode == VaultManager.NoteMode.FOLDER

        // Apply dynamic Material You colors on API 31+, or fallback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Use Material You dynamic colors for widget background (darker for dark mode)
            val bgColorRes = if (isDark)
                android.R.color.system_accent2_900
            else
                android.R.color.system_accent2_100
            val bgColor = context.getColor(bgColorRes)
            views.setInt(R.id.widget_root, "setBackgroundResource",
                if (isDark) R.drawable.keep_widget_background_dark else R.drawable.keep_widget_background)
            views.setColorStateList(R.id.widget_root, "setBackgroundTintList",
                ColorStateList.valueOf(bgColor))

            // Note card
            if (isFolder) {
                // Transparent note card for folder mode - individual items have their own backgrounds
                views.setInt(R.id.widget_note_card, "setBackgroundResource", android.R.color.transparent)
                views.setColorStateList(R.id.widget_note_card, "setBackgroundTintList",
                    ColorStateList.valueOf(0x00000000))
                views.setViewPadding(R.id.widget_note_card, 0, 0, 0, 0)
            } else {
                val cardColor = if (isDark) 0xFF2D2D2D.toInt() else 0xFFFFFFFF.toInt()
                views.setInt(R.id.widget_note_card, "setBackgroundResource",
                    if (isDark) R.drawable.keep_note_card_dark else R.drawable.keep_note_card_light)
                views.setColorStateList(R.id.widget_note_card, "setBackgroundTintList",
                    ColorStateList.valueOf(cardColor))
                val density = context.resources.displayMetrics.density
                val padH = (14 * density).toInt()
                val padV = (16 * density).toInt()
                views.setViewPadding(R.id.widget_note_card, padH, padV, padH, padV)
            }

            // FAB with different dynamic accent color (lighter on dark mode)
            val fabColorRes = if (isDark)
                android.R.color.system_accent1_500
            else
                android.R.color.system_accent1_200
            val fabColor = context.getColor(fabColorRes)
            views.setColorStateList(R.id.widget_fab, "setBackgroundTintList",
                ColorStateList.valueOf(fabColor))

            // FAB icon color (slightly less opaque)
            val fabIconColor = if (isDark) 0xCCE6E1E5.toInt() else 0xCC1C1B1F.toInt()
            views.setInt(R.id.widget_fab, "setColorFilter", fabIconColor)

            // Daily note button - use widget background tint
            views.setColorStateList(R.id.widget_daily_btn, "setBackgroundTintList",
                ColorStateList.valueOf(bgColor))
            val dailyIconColor = if (isDark) 0xFFCAC4D0.toInt() else 0xFF49454F.toInt()
            views.setInt(R.id.widget_daily_btn, "setColorFilter", dailyIconColor)
        } else {
            // Fallback for pre-S devices
            views.setInt(R.id.widget_root, "setBackgroundResource",
                if (isDark) R.drawable.keep_widget_background_dark else R.drawable.keep_widget_background)
            if (isFolder) {
                views.setInt(R.id.widget_note_card, "setBackgroundResource", android.R.color.transparent)
                views.setViewPadding(R.id.widget_note_card, 0, 0, 0, 0)
            } else {
                views.setInt(R.id.widget_note_card, "setBackgroundResource",
                    if (isDark) R.drawable.keep_note_card_dark else R.drawable.keep_note_card_light)
                val density = context.resources.displayMetrics.density
                val padH = (14 * density).toInt()
                val padV = (16 * density).toInt()
                views.setViewPadding(R.id.widget_note_card, padH, padV, padH, padV)
            }
            // FAB icon (slightly less opaque)
            val fabIconColor = if (isDark) 0xCCE6E1E5.toInt() else 0xCC1C1B1F.toInt()
            views.setInt(R.id.widget_fab, "setColorFilter", fabIconColor)

            // Daily note button
            val dailyBgColor = if (isDark) 0xFF1C1B1F.toInt() else 0xFFE8DEF8.toInt()
            views.setColorStateList(R.id.widget_daily_btn, "setBackgroundTintList",
                ColorStateList.valueOf(dailyBgColor))
            val dailyIconColor = if (isDark) 0xFFCAC4D0.toInt() else 0xFF49454F.toInt()
            views.setInt(R.id.widget_daily_btn, "setColorFilter", dailyIconColor)
        }

        // Text colors
        val titleColor = if (isDark) 0xFFE6E1E5.toInt() else 0xFF1C1B1F.toInt()
        val bodyColor = if (isDark) 0xFFCAC4D0.toInt() else 0xFF49454F.toInt()
        val iconColor = if (isDark) 0xFFCAC4D0.toInt() else 0xFF49454F.toInt()

        views.setTextColor(R.id.widget_date, titleColor)
        views.setTextColor(R.id.widget_note_preview, bodyColor)
        views.setTextColor(R.id.widget_todo_count, bodyColor)

        // Tint header icons
        views.setInt(R.id.widget_refresh, "setColorFilter", iconColor)
        views.setInt(R.id.widget_settings, "setColorFilter", iconColor)
        views.setInt(R.id.widget_cycle_note, "setColorFilter", iconColor)
    }

    private fun createActionIntent(context: Context, action: String, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, ObsidianWidgetProvider::class.java).apply {
            this.action = action
            putExtra(EXTRA_WIDGET_ID, appWidgetId)
        }
        return PendingIntent.getBroadcast(
            context, action.hashCode() + appWidgetId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openObsidian(context: Context, widgetId: Int) {
        val vaultManager = VaultManager(context, widgetId)
        val vaultName = vaultManager.vaultName
        val vaultUri = vaultManager.vaultUri

        // Try to open the specific note in Obsidian via its URI scheme
        if (vaultName != null) {
            val noteName = when (vaultManager.noteMode) {
                VaultManager.NoteMode.PINNED ->
                    resolvePinnedNotePath(vaultUri, vaultManager)
                VaultManager.NoteMode.DAILY -> {
                    val folder = vaultManager.dailyFolder
                    val date = java.time.LocalDate.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern(vaultManager.dateFormat))
                    if (folder.isNotBlank()) "$folder/$date" else date
                }
                VaultManager.NoteMode.FOLDER -> {
                    // Open the folder path in Obsidian (no specific note)
                    null
                }
            }

            if (noteName != null) {
                try {
                    val obsidianUri = Uri.Builder()
                        .scheme("obsidian")
                        .authority("open")
                        .appendQueryParameter("vault", vaultName)
                        .appendQueryParameter("file", noteName)
                        .build()
                    val deepLinkIntent = Intent(Intent.ACTION_VIEW, obsidianUri).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    context.startActivity(deepLinkIntent)
                    return
                } catch (_: Exception) {
                    // Obsidian not installed, fall through
                }
            }
        }

        // Fallback: try launching Obsidian app directly
        try {
            val obsidianIntent = context.packageManager
                .getLaunchIntentForPackage("md.obsidian")
            if (obsidianIntent != null) {
                obsidianIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(obsidianIntent)
                return
            }
        } catch (_: Exception) {
            // Obsidian not installed
        }

        // Final fallback: open our settings
        val fallbackIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(fallbackIntent)
    }

    private fun resolvePinnedNotePath(vaultUri: Uri?, vaultManager: VaultManager): String? {
        val fallbackName = vaultManager.getCurrentPinnedNoteName()?.removeSuffix(".md")
        val noteUri = vaultManager.getCurrentPinnedNoteUri() ?: return fallbackName
        val rootUri = vaultUri ?: return fallbackName

        return try {
            val treeId = DocumentsContract.getTreeDocumentId(rootUri)
            val docId = DocumentsContract.getDocumentId(noteUri)

            val relativePath = when {
                docId.startsWith("$treeId/") -> docId.removePrefix("$treeId/")
                docId == treeId -> ""
                ':' in docId -> docId.substringAfter(':')
                else -> docId
            }

            relativePath
                .trim('/')
                .takeIf { it.isNotEmpty() }
                ?.removeSuffix(".md")
                ?: fallbackName
        } catch (_: Exception) {
            fallbackName
        }
    }

    /**
     * Open today's daily note in Obsidian using the obsidian://daily URI.
     */
    private fun openDailyNote(context: Context) {
        try {
            val dailyUri = Uri.parse("obsidian://daily")
            val deepLinkIntent = Intent(Intent.ACTION_VIEW, dailyUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(deepLinkIntent)
        } catch (_: Exception) {
            // Obsidian not installed, try launching directly
            try {
                val obsidianIntent = context.packageManager
                    .getLaunchIntentForPackage("md.obsidian")
                if (obsidianIntent != null) {
                    obsidianIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(obsidianIntent)
                }
            } catch (_: Exception) { }
        }
    }

    /**
     * Open Obsidian with a new note using the obsidian://new URI.
     */
    private fun openNewNote(context: Context, widgetId: Int) {
        try {
            val vaultManager = VaultManager(context, widgetId)
            val vaultName = vaultManager.vaultName

            val uriBuilder = Uri.Builder()
                .scheme("obsidian")
                .authority("new")
            if (vaultName != null) {
                uriBuilder.appendQueryParameter("vault", vaultName)
            }
            val newNoteUri = uriBuilder.build()

            val deepLinkIntent = Intent(Intent.ACTION_VIEW, newNoteUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(deepLinkIntent)
        } catch (_: Exception) {
            // Obsidian not installed, try launching directly
            try {
                val obsidianIntent = context.packageManager
                    .getLaunchIntentForPackage("md.obsidian")
                if (obsidianIntent != null) {
                    obsidianIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(obsidianIntent)
                }
            } catch (_: Exception) { }
        }
    }
}
