package com.obsidianwidget

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.text.Html
import android.widget.RemoteViews
import android.widget.RemoteViewsService

class ChecklistWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val widgetId = intent.getIntExtra(
            android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, -1
        )
        return ChecklistRemoteViewsFactory(applicationContext, widgetId)
    }
}

class ChecklistRemoteViewsFactory(
    private val context: Context,
    private val widgetId: Int
) : RemoteViewsService.RemoteViewsFactory {

    private var items = listOf<VaultManager.ChecklistItem>()
    private var tapCheckboxOnly = false
    private var isKeepFolder = false
    private var isDark = false
    private var vaultName: String? = null
    private var themeColors = VaultManager.ThemeColors(
        bg = 0xFF1A1A1E.toInt(),
        text = 0xFFE8E6E3.toInt(),
        textSecondary = 0xFF8B8B8F.toInt(),
        accent = 0xFFD97757.toInt(),
        buttonText = 0xFFFFFFFF.toInt()
    )

    companion object {
        private val BOLD_ITALIC = Regex("""\*\*\*(.+?)\*\*\*""")
        private val BOLD = Regex("""\*\*(.+?)\*\*""")
        private val ITALIC_STAR = Regex("""\*(.+?)\*""")
        private val ITALIC_UNDER = Regex("""_(.+?)_""")
        private val MD_LINK = Regex("""\[([^\]]+)\]\(([^)]+)\)""")
        private val BARE_URL = Regex("""(?<!["'=(])((?:https?://)?[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\.[a-zA-Z]{2,})+(?:/[^\s<>"']*)?)""")

        fun extractFirstUrl(text: String): String? {
            val mdMatch = MD_LINK.find(text)
            if (mdMatch != null) {
                val url = mdMatch.groupValues[2]
                return if (url.startsWith("http")) url else "https://$url"
            }
            val bareMatch = BARE_URL.find(text)
            if (bareMatch != null) {
                val url = bareMatch.groupValues[1]
                return if (url.startsWith("http")) url else "https://$url"
            }
            return null
        }
        fun markdownToHtml(text: String): CharSequence {
            var html = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            html = MD_LINK.replace(html) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
            html = BARE_URL.replace(html) {
                val url = it.groupValues[1]
                val href = if (url.startsWith("http")) url else "https://$url"
                "<a href=\"$href\">$url</a>"
            }
            html = BOLD_ITALIC.replace(html) { "<b><i>${it.groupValues[1]}</i></b>" }
            html = BOLD.replace(html) { "<b>${it.groupValues[1]}</b>" }
            html = ITALIC_STAR.replace(html) { "<i>${it.groupValues[1]}</i>" }
            html = ITALIC_UNDER.replace(html) { "<i>${it.groupValues[1]}</i>" }
            return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
        }
    }

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val vaultManager = VaultManager(context, widgetId)
        val allItems = vaultManager.parseChecklist()
        tapCheckboxOnly = vaultManager.tapCheckboxOnly
        themeColors = vaultManager.getThemeColors()
        isKeepFolder = vaultManager.widgetStyle == "keep" && vaultManager.noteMode == VaultManager.NoteMode.FOLDER
        isDark = vaultManager.resolveTheme() == "dark"
        vaultName = vaultManager.vaultName

        // Hide checked items and add per-note summaries
        val checkedCount = allItems.count { !it.isPlainText && it.isChecked }
        val filtered = allItems.filter { it.isPlainText || !it.isChecked }

        val displayItems = if (isKeepFolder && checkedCount > 0) {
            // Count checked items per note and insert per-note summaries
            val perNoteFiltered = insertPerNoteSummaries(allItems, filtered)
            recalculateFolderBoundaries(perNoteFiltered)
        } else {
            val list = filtered.toMutableList()
            if (checkedCount > 0) {
                val label = if (checkedCount == 1) "item" else "items"
                list.add(VaultManager.ChecklistItem(
                    lineIndex = -1, text = "+ $checkedCount checked $label",
                    isChecked = false, isPlainText = true, isCheckedSummary = true
                ))
            }
            list
        }

        items = displayItems
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val item = items[position]

        // Spacer between folder notes
        if (item.isSpacer) {
            return RemoteViews(context.packageName, R.layout.widget_folder_spacer)
        }

        if (item.isHeading) {
            val views = RemoteViews(context.packageName, R.layout.widget_heading_item)
            views.setTextViewText(R.id.heading_item_content, markdownToHtml(item.text))
            views.setTextColor(R.id.heading_item_content, themeColors.text)

            // Apply improved padding for keep folder mode
            if (isKeepFolder) {
                val density = context.resources.displayMetrics.density
                val padH = (10 * density).toInt()
                val padTop = (10 * density).toInt()
                val padBottom = if (item.isNoteStart && item.isNoteEnd) (10 * density).toInt() else (4 * density).toInt()
                views.setViewPadding(R.id.heading_item_root, padH, padTop, padH, padBottom)
            }

            // Title-only notes (no content) get slightly larger font size
            val headingTextSize = if (item.isNoteStart && item.isNoteEnd) 16.8f else 16f
            views.setFloat(R.id.heading_item_content, "setTextSize", headingTextSize)

            // In folder mode, clicking a heading opens the note in Obsidian
            if (item.notePath != null && vaultName != null) {
                val obsidianUri = android.net.Uri.Builder()
                    .scheme("obsidian")
                    .authority("open")
                    .appendQueryParameter("vault", vaultName!!)
                    .appendQueryParameter("file", item.notePath)
                    .build()
                views.setOnClickFillInIntent(R.id.heading_item_root, Intent().apply {
                    putExtra(ObsidianWidgetProvider.EXTRA_URL, obsidianUri.toString())
                })
            } else {
                val url = extractFirstUrl(item.text)
                if (url != null) {
                    views.setOnClickFillInIntent(R.id.heading_item_root, Intent().apply {
                        putExtra(ObsidianWidgetProvider.EXTRA_URL, url)
                    })
                } else {
                    views.setOnClickFillInIntent(R.id.heading_item_root, Intent())
                }
            }

            // Show bookmark icon for bookmarked notes
            if (item.isBookmarked) {
                views.setViewVisibility(R.id.heading_bookmark_icon, android.view.View.VISIBLE)
                views.setInt(R.id.heading_bookmark_icon, "setColorFilter", themeColors.textSecondary)
            } else {
                views.setViewVisibility(R.id.heading_bookmark_icon, android.view.View.GONE)
            }

            // Apply folder card background for keep style
            if (isKeepFolder) {
                applyFolderCardBackground(views, R.id.heading_item_root, item)
            }
            return views
        }

        if (item.isPlainText) {
            val views = RemoteViews(context.packageName, R.layout.widget_text_item)
            val displayText = if (item.isBullet) "•  ${item.text}" else item.text
            views.setTextViewText(R.id.text_item_content, markdownToHtml(displayText))
            views.setTextColor(R.id.text_item_content,
                if (item.isCheckedSummary) themeColors.textSecondary else themeColors.text)
            val density = context.resources.displayMetrics.density
            val indentPx = (item.indentLevel * 16 * density).toInt()
            if (isKeepFolder) {
                val padH = (10 * density).toInt()
                val padV = (6 * density).toInt()
                val padBottom = if (item.isNoteEnd) (10 * density).toInt() else padV
                views.setViewPadding(R.id.text_item_root, indentPx + padH, padV, padH, padBottom)
            } else {
                views.setViewPadding(R.id.text_item_root, indentPx + (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            }
            val url = extractFirstUrl(item.text)
            if (url != null) {
                views.setOnClickFillInIntent(R.id.text_item_root, Intent().apply {
                    putExtra(ObsidianWidgetProvider.EXTRA_URL, url)
                })
            } else if (tapCheckboxOnly && item.notePath != null && vaultName != null) {
                val obsidianUri = android.net.Uri.Builder()
                    .scheme("obsidian")
                    .authority("open")
                    .appendQueryParameter("vault", vaultName!!)
                    .appendQueryParameter("file", item.notePath)
                    .build()
                views.setOnClickFillInIntent(R.id.text_item_root, Intent().apply {
                    putExtra(ObsidianWidgetProvider.EXTRA_URL, obsidianUri.toString())
                })
            } else if (tapCheckboxOnly) {
                views.setOnClickFillInIntent(R.id.text_item_root, Intent().apply {
                    putExtra(ObsidianWidgetProvider.EXTRA_OPEN_NOTE, true)
                })
            } else {
                views.setOnClickFillInIntent(R.id.text_item_root, Intent())
            }

            // Apply folder card background for keep style
            if (isKeepFolder) {
                applyFolderCardBackground(views, R.id.text_item_root, item)
            }
            return views
        }

        val views = RemoteViews(context.packageName, R.layout.widget_checklist_item)

        // Always set padding (reset for non-indented, indent for nested)
        val density = context.resources.displayMetrics.density
        val indentPx = (item.indentLevel * 16 * density).toInt()
        if (isKeepFolder) {
            val padH = (10 * density).toInt()
            val padV = (6 * density).toInt()
            val padBottom = if (item.isNoteEnd) (10 * density).toInt() else padV
            views.setViewPadding(R.id.checklist_item_root, indentPx + padH, padV, padH, padBottom)
        } else {
            views.setViewPadding(R.id.checklist_item_root, indentPx + (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt())
        }

        // Set checkbox: circle bg tinted to accent/secondary, white checkmark overlay
        if (item.isChecked) {
            views.setImageViewResource(R.id.checklist_checkbox_bg, R.drawable.ic_checkbox_checked)
            views.setInt(R.id.checklist_checkbox_bg, "setColorFilter", themeColors.accent)
            views.setViewVisibility(R.id.checklist_checkbox_mark, android.view.View.VISIBLE)
        } else {
            views.setImageViewResource(R.id.checklist_checkbox_bg, R.drawable.ic_checkbox_unchecked)
            views.setInt(R.id.checklist_checkbox_bg, "setColorFilter", themeColors.textSecondary)
            views.setViewVisibility(R.id.checklist_checkbox_mark, android.view.View.GONE)
        }

        // Set text with strikethrough if checked
        views.setTextViewText(R.id.checklist_text, markdownToHtml(item.text))
        if (item.isChecked) {
            views.setInt(R.id.checklist_text, "setPaintFlags",
                Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG)
            views.setTextColor(R.id.checklist_text, themeColors.textSecondary)
        } else {
            views.setInt(R.id.checklist_text, "setPaintFlags", Paint.ANTI_ALIAS_FLAG)
            views.setTextColor(R.id.checklist_text, themeColors.text)
        }

        // Fill-in intent for toggling this item
        val fillIntent = Intent().apply {
            putExtra(ObsidianWidgetProvider.EXTRA_LINE_INDEX, item.lineIndex)
            putExtra(ObsidianWidgetProvider.EXTRA_WIDGET_ID, widgetId)
        }
        if (tapCheckboxOnly) {
            views.setOnClickFillInIntent(R.id.checklist_checkbox, fillIntent)
            val url = extractFirstUrl(item.text)
            if (url != null) {
                views.setOnClickFillInIntent(R.id.checklist_item_root, Intent().apply {
                    putExtra(ObsidianWidgetProvider.EXTRA_URL, url)
                })
            } else if (item.notePath != null && vaultName != null) {
                val obsidianUri = android.net.Uri.Builder()
                    .scheme("obsidian")
                    .authority("open")
                    .appendQueryParameter("vault", vaultName!!)
                    .appendQueryParameter("file", item.notePath)
                    .build()
                views.setOnClickFillInIntent(R.id.checklist_item_root, Intent().apply {
                    putExtra(ObsidianWidgetProvider.EXTRA_URL, obsidianUri.toString())
                })
            } else {
                views.setOnClickFillInIntent(R.id.checklist_item_root, Intent().apply {
                    putExtra(ObsidianWidgetProvider.EXTRA_OPEN_NOTE, true)
                })
            }
        } else {
            views.setOnClickFillInIntent(R.id.checklist_item_root, fillIntent)
        }

        // Apply folder card background for keep style
        if (isKeepFolder) {
            applyFolderCardBackground(views, R.id.checklist_item_root, item)
        }

        return views
    }

    /**
     * Apply appropriate card background drawable for folder mode items in keep style.
     * Items at note boundaries get rounded corners; middle items get flat edges.
     */
    private fun applyFolderCardBackground(views: RemoteViews, rootId: Int, item: VaultManager.ChecklistItem) {
        val isStart = item.isNoteStart
        val isEnd = item.isNoteEnd
        val bgRes = when {
            isStart && isEnd -> if (isDark) R.drawable.keep_note_card_dark else R.drawable.keep_note_card_light
            isStart -> if (isDark) R.drawable.keep_folder_card_top_dark else R.drawable.keep_folder_card_top_light
            isEnd -> if (isDark) R.drawable.keep_folder_card_bottom_dark else R.drawable.keep_folder_card_bottom_light
            else -> if (isDark) R.drawable.keep_folder_card_mid_dark else R.drawable.keep_folder_card_mid_light
        }
        views.setInt(rootId, "setBackgroundResource", bgRes)

        // Apply dynamic card color tint on API 31+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val cardColor = if (isDark) 0xFF2D2D2D.toInt() else 0xFFFFFFFF.toInt()
            views.setColorStateList(rootId, "setBackgroundTintList",
                android.content.res.ColorStateList.valueOf(cardColor))
        }
    }

    /**
     * Recalculate folder note boundaries after filtering out checked items.
     * Strips spacers, re-marks note start/end, and re-inserts spacers.
     */
    private fun recalculateFolderBoundaries(items: List<VaultManager.ChecklistItem>): List<VaultManager.ChecklistItem> {
        val stripped = items.filter { !it.isSpacer }
            .map { it.copy(isNoteStart = false, isNoteEnd = false) }
            .toMutableList()
        if (stripped.isEmpty()) return stripped

        val headingIndices = stripped.indices.filter { stripped[it].isHeading }
        if (headingIndices.isEmpty()) return stripped

        for (i in headingIndices.indices) {
            val startIdx = headingIndices[i]
            val endIdx = if (i + 1 < headingIndices.size) headingIndices[i + 1] - 1 else stripped.lastIndex
            stripped[startIdx] = stripped[startIdx].copy(isNoteStart = true)
            stripped[endIdx] = stripped[endIdx].copy(isNoteEnd = true)
        }

        val result = mutableListOf<VaultManager.ChecklistItem>()
        for (item in stripped) {
            if (item.isNoteStart && result.isNotEmpty()) {
                result.add(VaultManager.ChecklistItem(lineIndex = -1, text = "", isChecked = false, isPlainText = true, isSpacer = true))
            }
            result.add(item)
        }
        return result
    }

    /**
     * Insert per-note "+X checked items" summaries at the end of each note group.
     * Groups items by heading boundaries and counts how many checked items were
     * filtered out from each note.
     */
    private fun insertPerNoteSummaries(
        allItems: List<VaultManager.ChecklistItem>,
        filtered: List<VaultManager.ChecklistItem>
    ): List<VaultManager.ChecklistItem> {
        // Count checked items per note (heading group) in the original list
        val headingIndices = allItems.indices.filter { allItems[it].isHeading }
        if (headingIndices.isEmpty()) return filtered

        // Map each heading's lineIndex to its checked count
        val checkedPerNote = mutableMapOf<Int, Int>()
        for (i in headingIndices.indices) {
            val startIdx = headingIndices[i]
            val endIdx = if (i + 1 < headingIndices.size) headingIndices[i + 1] else allItems.size
            val headingLineIndex = allItems[startIdx].lineIndex
            val noteChecked = (startIdx until endIdx).count {
                !allItems[it].isPlainText && allItems[it].isChecked
            }
            if (noteChecked > 0) {
                checkedPerNote[headingLineIndex] = noteChecked
            }
        }

        // Insert summaries into the filtered list at the end of each note group
        val result = mutableListOf<VaultManager.ChecklistItem>()
        val filteredHeadingIndices = filtered.indices.filter { filtered[it].isHeading }

        for (i in filteredHeadingIndices.indices) {
            val startIdx = filteredHeadingIndices[i]
            val endIdx = if (i + 1 < filteredHeadingIndices.size) filteredHeadingIndices[i + 1] else filtered.size
            val headingLineIndex = filtered[startIdx].lineIndex
            val checkedCount = checkedPerNote[headingLineIndex] ?: 0

            // Add all items of this note
            for (j in startIdx until endIdx) {
                if (!filtered[j].isSpacer) result.add(filtered[j])
            }

            // Add per-note summary if there were checked items
            if (checkedCount > 0) {
                val label = if (checkedCount == 1) "item" else "items"
                result.add(VaultManager.ChecklistItem(
                    lineIndex = -1, text = "+ $checkedCount checked $label",
                    isChecked = false, isPlainText = true, isCheckedSummary = true
                ))
            }
        }

        // Add any items before the first heading (shouldn't happen in folder mode, but safe)
        if (filteredHeadingIndices.isNotEmpty() && filteredHeadingIndices[0] > 0) {
            val prefix = filtered.subList(0, filteredHeadingIndices[0]).filter { !it.isSpacer }
            return prefix + result
        }

        return result
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 4
    override fun getItemId(position: Int): Long {
        val item = items[position]
        return if (item.isSpacer || item.isCheckedSummary) Long.MIN_VALUE + position.toLong() else item.lineIndex.toLong()
    }
    override fun hasStableIds(): Boolean = true
}
