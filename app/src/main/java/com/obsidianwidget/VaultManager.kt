package com.obsidianwidget

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Manages reading/writing to the Obsidian vault via SAF (Storage Access Framework).
 */
class VaultManager(private val context: Context, private val widgetId: Int = -1) {

    enum class NoteMode { DAILY, PINNED, FOLDER }

    data class ChecklistItem(
        val lineIndex: Int,
        val text: String,
        val isChecked: Boolean,
        val isPlainText: Boolean = false,
        val isHeading: Boolean = false,
        val isBullet: Boolean = false,
        val indentLevel: Int = 0,
        val isNoteStart: Boolean = false,
        val isNoteEnd: Boolean = false,
        val isSpacer: Boolean = false,
        val notePath: String? = null
    )

    companion object {
        private const val PREFS_NAME = "obsidian_widget_prefs"
        // Global keys (shared across all widgets)
        private const val KEY_VAULT_URI = "vault_uri"
        private const val KEY_VAULT_NAME = "vault_name"
        // Per-widget keys (suffixed with _widgetId)
        private const val KEY_DAILY_FOLDER = "daily_folder"
        private const val KEY_DATE_FORMAT = "date_format"
        private const val KEY_NOTE_MODE = "note_mode"
        private const val KEY_PINNED_NOTE_URI = "pinned_note_uri"
        private const val KEY_PINNED_NOTE_NAME = "pinned_note_name"
        private const val KEY_SHOW_BUTTONS = "show_buttons"
        private const val KEY_SORT_UNCHECKED = "sort_unchecked"
        private const val KEY_PINNED_NOTE_URIS = "pinned_note_uris"
        private const val KEY_PINNED_NOTE_NAMES = "pinned_note_names"
        private const val KEY_CURRENT_NOTE_INDEX = "current_note_index"
        private const val KEY_WIDGET_ALPHA = "widget_alpha"
        private const val KEY_TAP_CHECKBOX_ONLY = "tap_checkbox_only"
        private const val KEY_ADD_TO_TOP = "add_to_top"
        private const val KEY_SHOW_ADD_TO_TOP = "show_add_to_top"
        private const val KEY_WIDGET_THEME = "widget_theme"
        private const val KEY_ACCENT_COLOR = "accent_color"
        private const val KEY_SHOW_TODO_COUNT = "show_todo_count"
        private const val KEY_FOLDER_PATH = "folder_path"
        private const val KEY_WIDGET_STYLE = "widget_style"
        private const val KEY_SHOW_HEADER = "show_header"
        private const val DEFAULT_DATE_FORMAT = "yyyy-MM-dd"
        /** Separator between note title and note path in folder mode headings. */
        private const val NOTE_PATH_SEPARATOR = "\u0000"

        private val CHECKLIST_REGEX = Regex("""^(\s*)-\s*\[([ xX])\]\s*(.*)$""")
        private val HEADING_REGEX = Regex("""^(#{1,6})\s+(.+)$""")
        private val BULLET_REGEX = Regex("""^(\s*)[*+-]\s+(.+)$""")

        fun deleteWidgetPrefs(context: Context, widgetId: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .remove("${KEY_DAILY_FOLDER}_$widgetId")
                .remove("${KEY_DATE_FORMAT}_$widgetId")
                .remove("${KEY_NOTE_MODE}_$widgetId")
                .remove("${KEY_PINNED_NOTE_URI}_$widgetId")
                .remove("${KEY_PINNED_NOTE_NAME}_$widgetId")
                .remove("${KEY_SHOW_BUTTONS}_$widgetId")
                .remove("${KEY_SORT_UNCHECKED}_$widgetId")
                .remove("${KEY_PINNED_NOTE_URIS}_$widgetId")
                .remove("${KEY_PINNED_NOTE_NAMES}_$widgetId")
                .remove("${KEY_CURRENT_NOTE_INDEX}_$widgetId")
                .remove("${KEY_WIDGET_ALPHA}_$widgetId")
                .remove("${KEY_TAP_CHECKBOX_ONLY}_$widgetId")
                .remove("${KEY_ADD_TO_TOP}_$widgetId")
                .remove("${KEY_SHOW_ADD_TO_TOP}_$widgetId")
                .remove("${KEY_WIDGET_THEME}_$widgetId")
                .remove("${KEY_ACCENT_COLOR}_$widgetId")
                .remove("${KEY_SHOW_TODO_COUNT}_$widgetId")
                .remove("${KEY_FOLDER_PATH}_$widgetId")
                .remove("${KEY_WIDGET_STYLE}_$widgetId")
                .remove("${KEY_SHOW_HEADER}_$widgetId")
                .apply()
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun wk(key: String): String =
        if (widgetId >= 0) "${key}_$widgetId" else key

    // Global settings (same for all widgets)
    var vaultUri: Uri?
        get() = prefs.getString(KEY_VAULT_URI, null)?.let { Uri.parse(it) }
        set(value) = prefs.edit().putString(KEY_VAULT_URI, value?.toString()).apply()

    var vaultName: String?
        get() = prefs.getString(KEY_VAULT_NAME, null)
        set(value) = prefs.edit().putString(KEY_VAULT_NAME, value).apply()

    // Per-widget settings
    var dailyFolder: String
        get() = prefs.getString(wk(KEY_DAILY_FOLDER), prefs.getString(KEY_DAILY_FOLDER, "") ?: "") ?: ""
        set(value) = prefs.edit().putString(wk(KEY_DAILY_FOLDER), value).apply()

    var dateFormat: String
        get() = prefs.getString(wk(KEY_DATE_FORMAT), prefs.getString(KEY_DATE_FORMAT, DEFAULT_DATE_FORMAT) ?: DEFAULT_DATE_FORMAT) ?: DEFAULT_DATE_FORMAT
        set(value) = prefs.edit().putString(wk(KEY_DATE_FORMAT), value).apply()

    var noteMode: NoteMode
        get() {
            val mode = prefs.getString(wk(KEY_NOTE_MODE), prefs.getString(KEY_NOTE_MODE, "DAILY"))
            return when (mode) {
                "PINNED" -> NoteMode.PINNED
                "FOLDER" -> NoteMode.FOLDER
                else -> NoteMode.DAILY
            }
        }
        set(value) = prefs.edit().putString(wk(KEY_NOTE_MODE), value.name).apply()

    var pinnedNoteUri: Uri?
        get() = prefs.getString(wk(KEY_PINNED_NOTE_URI), prefs.getString(KEY_PINNED_NOTE_URI, null))?.let { Uri.parse(it) }
        set(value) = prefs.edit().putString(wk(KEY_PINNED_NOTE_URI), value?.toString()).apply()

    var pinnedNoteName: String?
        get() = prefs.getString(wk(KEY_PINNED_NOTE_NAME), prefs.getString(KEY_PINNED_NOTE_NAME, null))
        set(value) = prefs.edit().putString(wk(KEY_PINNED_NOTE_NAME), value).apply()

    var pinnedNoteUriList: List<String>
        get() {
            val list = prefs.getString(wk(KEY_PINNED_NOTE_URIS), null)
            if (!list.isNullOrEmpty()) return list.split("|||").filter { it.isNotEmpty() }
            val single = prefs.getString(wk(KEY_PINNED_NOTE_URI), prefs.getString(KEY_PINNED_NOTE_URI, null))
            return if (single != null) listOf(single) else emptyList()
        }
        set(value) { prefs.edit().putString(wk(KEY_PINNED_NOTE_URIS), value.joinToString("|||")).commit() }

    var pinnedNoteNameList: List<String>
        get() {
            val list = prefs.getString(wk(KEY_PINNED_NOTE_NAMES), null)
            if (!list.isNullOrEmpty()) return list.split("|||").filter { it.isNotEmpty() }
            val single = prefs.getString(wk(KEY_PINNED_NOTE_NAME), prefs.getString(KEY_PINNED_NOTE_NAME, null))
            return if (single != null) listOf(single) else emptyList()
        }
        set(value) { prefs.edit().putString(wk(KEY_PINNED_NOTE_NAMES), value.joinToString("|||")).commit() }

    var currentNoteIndex: Int
        get() = prefs.getInt(wk(KEY_CURRENT_NOTE_INDEX), 0)
        set(value) { prefs.edit().putInt(wk(KEY_CURRENT_NOTE_INDEX), value).commit() }

    fun addPinnedNote(uri: Uri, name: String) {
        val uris = pinnedNoteUriList.toMutableList()
        val names = pinnedNoteNameList.toMutableList()
        uris.add(uri.toString())
        names.add(name)
        pinnedNoteUriList = uris
        pinnedNoteNameList = names
    }

    fun removePinnedNote(index: Int) {
        val uris = pinnedNoteUriList.toMutableList()
        val names = pinnedNoteNameList.toMutableList()
        if (index in uris.indices) {
            uris.removeAt(index)
            names.removeAt(index)
            pinnedNoteUriList = uris
            pinnedNoteNameList = names
            if (currentNoteIndex >= uris.size) {
                currentNoteIndex = (uris.size - 1).coerceAtLeast(0)
            }
        }
    }

    fun getPinnedNoteCount(): Int = pinnedNoteUriList.size

    fun navigateNote(direction: Int) {
        val count = getPinnedNoteCount()
        if (count <= 1) return
        var newIndex = currentNoteIndex + direction
        if (newIndex < 0) newIndex = count - 1
        if (newIndex >= count) newIndex = 0
        currentNoteIndex = newIndex
    }

    fun getCurrentPinnedNoteUri(): Uri? {
        val uris = pinnedNoteUriList
        if (uris.isEmpty()) return null
        val idx = currentNoteIndex.coerceIn(0, uris.lastIndex)
        return Uri.parse(uris[idx])
    }

    fun getCurrentPinnedNoteName(): String? {
        val names = pinnedNoteNameList
        if (names.isEmpty()) return null
        val idx = currentNoteIndex.coerceIn(0, names.lastIndex)
        return names[idx]
    }

    var showButtons: Boolean
        get() = prefs.getBoolean(wk(KEY_SHOW_BUTTONS), prefs.getBoolean(KEY_SHOW_BUTTONS, true))
        set(value) = prefs.edit().putBoolean(wk(KEY_SHOW_BUTTONS), value).apply()

    var sortUnchecked: Boolean
        get() = prefs.getBoolean(wk(KEY_SORT_UNCHECKED), prefs.getBoolean(KEY_SORT_UNCHECKED, false))
        set(value) = prefs.edit().putBoolean(wk(KEY_SORT_UNCHECKED), value).apply()

    var widgetAlpha: Int
        get() = prefs.getInt(wk(KEY_WIDGET_ALPHA), 100)
        set(value) = prefs.edit().putInt(wk(KEY_WIDGET_ALPHA), value).apply()

    var tapCheckboxOnly: Boolean
        get() = prefs.getBoolean(wk(KEY_TAP_CHECKBOX_ONLY), false)
        set(value) = prefs.edit().putBoolean(wk(KEY_TAP_CHECKBOX_ONLY), value).apply()

    var addToTop: Boolean
        get() = prefs.getBoolean(wk(KEY_ADD_TO_TOP), false)
        set(value) = prefs.edit().putBoolean(wk(KEY_ADD_TO_TOP), value).apply()

    var showAddToTop: Boolean
        get() = prefs.getBoolean(wk(KEY_SHOW_ADD_TO_TOP), true)
        set(value) = prefs.edit().putBoolean(wk(KEY_SHOW_ADD_TO_TOP), value).apply()

    var widgetTheme: String
        get() = prefs.getString(wk(KEY_WIDGET_THEME), "dark") ?: "dark"
        set(value) = prefs.edit().putString(wk(KEY_WIDGET_THEME), value).apply()

    var accentColor: String
        get() = prefs.getString(wk(KEY_ACCENT_COLOR), "#D97757") ?: "#D97757"
        set(value) = prefs.edit().putString(wk(KEY_ACCENT_COLOR), value).apply()

    var showTodoCount: Boolean
        get() = prefs.getBoolean(wk(KEY_SHOW_TODO_COUNT), false)
        set(value) = prefs.edit().putBoolean(wk(KEY_SHOW_TODO_COUNT), value).apply()

    var folderPath: String
        get() = prefs.getString(wk(KEY_FOLDER_PATH), "") ?: ""
        set(value) = prefs.edit().putString(wk(KEY_FOLDER_PATH), value).apply()

    var widgetStyle: String
        get() = prefs.getString(wk(KEY_WIDGET_STYLE), "obsidian") ?: "obsidian"
        set(value) = prefs.edit().putString(wk(KEY_WIDGET_STYLE), value).apply()

    var showHeader: Boolean
        get() = prefs.getBoolean(wk(KEY_SHOW_HEADER), true)
        set(value) = prefs.edit().putBoolean(wk(KEY_SHOW_HEADER), value).apply()

    /**
     * Resolve the effective theme, handling "system" by checking the device night mode.
     */
    fun resolveTheme(): String {
        val theme = widgetTheme
        if (theme == "system") {
            val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return if (nightMode == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        }
        return theme
    }

    fun getThemeColors(): ThemeColors {
        val isDark = resolveTheme() == "dark"
        val accent = try { android.graphics.Color.parseColor(accentColor) } catch (_: Exception) { 0xFFD97757.toInt() }
        return if (isDark) {
            ThemeColors(
                bg = 0xF21A1A1E.toInt(),
                text = 0xFFE8E6E3.toInt(),
                textSecondary = 0xFF8B8B8F.toInt(),
                accent = accent,
                buttonText = 0xFFFFFFFF.toInt()
            )
        } else {
            ThemeColors(
                bg = 0xF2F5F5F5.toInt(),
                text = 0xFF1A1A1E.toInt(),
                textSecondary = 0xFF6B6B6F.toInt(),
                accent = accent,
                buttonText = 0xFFFFFFFF.toInt()
            )
        }
    }

    data class ThemeColors(
        val bg: Int,
        val text: Int,
        val textSecondary: Int,
        val accent: Int,
        val buttonText: Int
    )

    /**
     * Batch save all widget settings using commit() for reliable persistence.
     */
    fun saveWidgetSettings(
        dailyFolder: String,
        dateFormat: String,
        noteMode: NoteMode,
        showButtons: Boolean,
        sortUnchecked: Boolean,
        widgetAlpha: Int,
        tapCheckboxOnly: Boolean,
        addToTop: Boolean,
        showAddToTop: Boolean,
        widgetTheme: String,
        accentColor: String,
        showTodoCount: Boolean,
        folderPath: String = "",
        widgetStyle: String = "obsidian",
        showHeader: Boolean = true
    ) {
        prefs.edit()
            .putString(wk(KEY_DAILY_FOLDER), dailyFolder)
            .putString(wk(KEY_DATE_FORMAT), dateFormat)
            .putString(wk(KEY_NOTE_MODE), noteMode.name)
            .putBoolean(wk(KEY_SHOW_BUTTONS), showButtons)
            .putBoolean(wk(KEY_SORT_UNCHECKED), sortUnchecked)
            .putInt(wk(KEY_WIDGET_ALPHA), widgetAlpha)
            .putBoolean(wk(KEY_TAP_CHECKBOX_ONLY), tapCheckboxOnly)
            .putBoolean(wk(KEY_ADD_TO_TOP), addToTop)
            .putBoolean(wk(KEY_SHOW_ADD_TO_TOP), showAddToTop)
            .putString(wk(KEY_WIDGET_THEME), widgetTheme)
            .putString(wk(KEY_ACCENT_COLOR), accentColor)
            .putBoolean(wk(KEY_SHOW_TODO_COUNT), showTodoCount)
            .putString(wk(KEY_FOLDER_PATH), folderPath)
            .putString(wk(KEY_WIDGET_STYLE), widgetStyle)
            .putBoolean(wk(KEY_SHOW_HEADER), showHeader)
            .commit()
    }

    val isVaultConfigured: Boolean
        get() = vaultUri != null

    /**
     * Read the widget note based on current mode.
     */
    fun readWidgetNote(): String? {
        return when (noteMode) {
            NoteMode.DAILY -> readDailyNote()
            NoteMode.PINNED -> readPinnedNote()
            NoteMode.FOLDER -> readFolderNotes()
        }
    }

    /**
     * Get the display title for the widget.
     */
    fun getWidgetTitle(): String {
        return when (noteMode) {
            NoteMode.DAILY -> "Daily Note"
            NoteMode.PINNED -> getCurrentPinnedNoteName()?.removeSuffix(".md") ?: "Pinned Note"
            NoteMode.FOLDER -> {
                val path = folderPath
                if (path.isNotBlank()) path.substringAfterLast('/') else "Folder"
            }
        }
    }

    /**
     * Read the pinned note content.
     */
    fun readPinnedNote(): String? {
        val uri = getCurrentPinnedNoteUri() ?: return null
        return readFileContent(uri)
    }

    /**
     * Get the list of .md files in the configured folder, sorted by name.
     */
    fun getFolderNoteFiles(): List<DocumentFile> {
        val uri = vaultUri ?: return emptyList()
        val rootDoc = DocumentFile.fromTreeUri(context, uri) ?: return emptyList()
        val targetDir = if (folderPath.isNotBlank()) {
            findSubDirectory(rootDoc, folderPath)
        } else {
            rootDoc
        } ?: return emptyList()
        return targetDir.listFiles()
            .filter { it.isFile && it.name?.endsWith(".md") == true }
            .sortedBy { it.name?.lowercase() }
    }

    /**
     * Read all notes from the configured folder, concatenated with heading separators.
     * The heading format includes a metadata marker: ## NoteName\u0000folderPath/NoteName
     */
    fun readFolderNotes(): String? {
        val files = getFolderNoteFiles()
        if (files.isEmpty()) return null
        val parts = mutableListOf<String>()
        val fp = folderPath
        for (file in files) {
            val name = file.name?.removeSuffix(".md") ?: "Note"
            val content = readFileContent(file.uri)?.trimEnd() ?: continue
            val notePath = if (fp.isNotBlank()) "$fp/$name" else name
            parts.add("## $name${NOTE_PATH_SEPARATOR}$notePath\n$content")
        }
        return if (parts.isNotEmpty()) parts.joinToString("\n") else null
    }

    /**
     * Read today's daily note content, if it exists.
     */
    fun readDailyNote(): String? {
        val uri = vaultUri ?: return null
        val rootDoc = DocumentFile.fromTreeUri(context, uri) ?: return null

        val todayFileName = getTodayFileName()
        val targetDir = if (dailyFolder.isNotBlank()) {
            findSubDirectory(rootDoc, dailyFolder)
        } else {
            rootDoc
        } ?: return null

        val noteFile = targetDir.findFile(todayFileName) ?: return null
        return readFileContent(noteFile.uri)
    }

    /**
     * Append text to today's daily note (creates the file if it doesn't exist).
     */
    fun appendToWidgetNote(text: String): Boolean {
        val formatted = if (parseChecklist().any { !it.isPlainText }) "- [ ] $text" else text
        return when (noteMode) {
            NoteMode.DAILY -> appendToDailyNote(formatted)
            NoteMode.PINNED -> appendToPinnedNote(formatted)
            NoteMode.FOLDER -> false // Not supported in folder mode
        }
    }

    fun appendToPinnedNote(text: String): Boolean {
        val uri = getCurrentPinnedNoteUri() ?: return false
        val existing = readFileContent(uri) ?: ""
        val newContent = if (existing.isNotBlank()) {
            if (addToTop) "$text\n$existing" else "$existing\n$text"
        } else text
        return writeFileContent(uri, newContent)
    }

    fun appendToDailyNote(text: String): Boolean {
        val uri = vaultUri ?: return false
        val rootDoc = DocumentFile.fromTreeUri(context, uri) ?: return false

        val todayFileName = getTodayFileName()
        val targetDir = if (dailyFolder.isNotBlank()) {
            findOrCreateSubDirectory(rootDoc, dailyFolder)
        } else {
            rootDoc
        } ?: return false

        val noteFile = targetDir.findFile(todayFileName)

        return if (noteFile != null) {
            // Append or prepend to existing file
            val existing = readFileContent(noteFile.uri) ?: ""
            val newContent = if (existing.isNotBlank()) {
                if (addToTop) "$text\n\n$existing" else "$existing\n\n$text"
            } else text
            writeFileContent(noteFile.uri, newContent)
        } else {
            // Create new file — just the captured text, no header
            val newFile = targetDir.createFile("text/markdown", todayFileName) ?: return false
            writeFileContent(newFile.uri, text)
        }
    }

    private fun getTodayFileName(): String {
        val formatter = DateTimeFormatter.ofPattern(dateFormat)
        return "${LocalDate.now().format(formatter)}.md"
    }

    private fun findSubDirectory(root: DocumentFile, path: String): DocumentFile? {
        var current = root
        for (segment in path.split("/")) {
            if (segment.isBlank()) continue
            current = current.findFile(segment) ?: return null
            if (!current.isDirectory) return null
        }
        return current
    }

    private fun findOrCreateSubDirectory(root: DocumentFile, path: String): DocumentFile? {
        var current = root
        for (segment in path.split("/")) {
            if (segment.isBlank()) continue
            val existing = current.findFile(segment)
            current = if (existing != null && existing.isDirectory) {
                existing
            } else {
                current.createDirectory(segment) ?: return null
            }
        }
        return current
    }

    private fun readFileContent(uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).readText()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeFileContent(uri: Uri, content: String): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(content)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Parse checklist items from the current widget note.
     */
    fun parseChecklist(): List<ChecklistItem> {
        val content = readWidgetNote() ?: return emptyList()
        val items = mutableListOf<ChecklistItem>()
        val isFolder = noteMode == NoteMode.FOLDER
        content.lines().forEachIndexed { index, line ->
            val match = CHECKLIST_REGEX.matchEntire(line)
            val headingMatch = HEADING_REGEX.matchEntire(line)
            if (match != null) {
                val indent = match.groupValues[1]
                val indentLevel = indent.count { it == ' ' } / 2 + indent.count { it == '\t' }
                val checked = match.groupValues[2].lowercase() == "x"
                val text = match.groupValues[3].trim()
                items.add(ChecklistItem(lineIndex = index, text = text, isChecked = checked, indentLevel = indentLevel))
            } else if (headingMatch != null) {
                val rawText = headingMatch.groupValues[2].trim()
                // Extract note path from metadata marker (null byte separator)
                val parts = rawText.split(NOTE_PATH_SEPARATOR, limit = 2)
                val text = parts[0]
                val notePath = if (parts.size > 1) parts[1] else null
                items.add(ChecklistItem(lineIndex = index, text = text, isChecked = false, isPlainText = true, isHeading = true, notePath = notePath))
            } else {
                val bulletMatch = BULLET_REGEX.matchEntire(line)
                if (bulletMatch != null) {
                    val indent = bulletMatch.groupValues[1]
                    val indentLevel = indent.count { it == ' ' } / 2 + indent.count { it == '\t' }
                    val text = bulletMatch.groupValues[2].trim()
                    items.add(ChecklistItem(lineIndex = index, text = text, isChecked = false, isPlainText = true, isBullet = true, indentLevel = indentLevel))
                } else if (line.isNotBlank()) {
                    items.add(ChecklistItem(lineIndex = index, text = line.trim(), isChecked = false, isPlainText = true))
                }
            }
        }
        if (sortUnchecked) {
            val nonChecklist = items.filter { it.isPlainText }
            val unchecked = items.filter { !it.isPlainText && !it.isChecked }
            val checked = items.filter { !it.isPlainText && it.isChecked }
            return addFolderBoundaries(nonChecklist + unchecked + checked, isFolder)
        }
        return addFolderBoundaries(items, isFolder)
    }

    /**
     * For folder mode, mark note boundaries and insert spacer items between notes.
     */
    private fun addFolderBoundaries(items: List<ChecklistItem>, isFolder: Boolean): List<ChecklistItem> {
        if (!isFolder || items.isEmpty()) return items

        // Find heading indices (note starts)
        val headingIndices = items.indices.filter { items[it].isHeading }
        if (headingIndices.isEmpty()) return items

        // Mark note boundaries
        val marked = items.toMutableList()
        for (i in headingIndices.indices) {
            val startIdx = headingIndices[i]
            val endIdx = if (i + 1 < headingIndices.size) headingIndices[i + 1] - 1 else marked.lastIndex
            marked[startIdx] = marked[startIdx].copy(isNoteStart = true)
            marked[endIdx] = marked[endIdx].copy(isNoteEnd = true)
        }

        // Insert spacers between notes (between noteEnd and next noteStart)
        val result = mutableListOf<ChecklistItem>()
        for (item in marked) {
            if (item.isNoteStart && result.isNotEmpty()) {
                result.add(ChecklistItem(lineIndex = -1, text = "", isChecked = false, isPlainText = true, isSpacer = true))
            }
            result.add(item)
        }
        return result
    }

    /**
     * Toggle a checklist item by its line index in the current widget note.
     */
    fun toggleChecklistItem(lineIndex: Int): Boolean {
        if (noteMode == NoteMode.FOLDER) {
            return toggleFolderChecklistItem(lineIndex)
        }
        val noteUri = getWidgetNoteUri() ?: return false
        val content = readFileContent(noteUri) ?: return false
        val lines = content.lines().toMutableList()

        if (lineIndex < 0 || lineIndex >= lines.size) return false

        val line = lines[lineIndex]
        val match = CHECKLIST_REGEX.matchEntire(line) ?: return false

        val indent = match.groupValues[1]
        val currentState = match.groupValues[2]
        val text = match.groupValues[3]

        val newState = if (currentState.lowercase() == "x") " " else "x"
        lines[lineIndex] = "$indent- [$newState] $text"

        return writeFileContent(noteUri, lines.joinToString("\n"))
    }

    /**
     * Toggle a checklist item in folder mode by mapping the global line index
     * back to the correct file and local line index.
     */
    private fun toggleFolderChecklistItem(globalLineIndex: Int): Boolean {
        val files = getFolderNoteFiles()
        if (files.isEmpty()) return false

        var offset = 0
        for (file in files) {
            val content = readFileContent(file.uri)?.trimEnd() ?: continue
            val fileLines = content.lines()
            // +1 for the heading line ("## FileName")
            val headingLines = 1
            val fileStart = offset + headingLines
            val fileEnd = fileStart + fileLines.size - 1

            if (globalLineIndex in fileStart..fileEnd) {
                val localIndex = globalLineIndex - fileStart
                val lines = fileLines.toMutableList()
                if (localIndex < 0 || localIndex >= lines.size) return false

                val line = lines[localIndex]
                val match = CHECKLIST_REGEX.matchEntire(line) ?: return false

                val indent = match.groupValues[1]
                val currentState = match.groupValues[2]
                val text = match.groupValues[3]

                val newState = if (currentState.lowercase() == "x") " " else "x"
                lines[localIndex] = "$indent- [$newState] $text"

                return writeFileContent(file.uri, lines.joinToString("\n"))
            }

            offset += headingLines + fileLines.size
        }
        return false
    }

    /**
     * Get the URI of the current widget note file.
     */
    fun getWidgetNoteUri(): Uri? {
        return when (noteMode) {
            NoteMode.PINNED -> getCurrentPinnedNoteUri()
            NoteMode.DAILY -> getDailyNoteUri()
            NoteMode.FOLDER -> null // Folder mode uses multiple files
        }
    }

    private fun getDailyNoteUri(): Uri? {
        val uri = vaultUri ?: return null
        val rootDoc = DocumentFile.fromTreeUri(context, uri) ?: return null
        val todayFileName = getTodayFileName()
        val targetDir = if (dailyFolder.isNotBlank()) {
            findSubDirectory(rootDoc, dailyFolder)
        } else {
            rootDoc
        } ?: return null
        return targetDir.findFile(todayFileName)?.uri
    }
}
