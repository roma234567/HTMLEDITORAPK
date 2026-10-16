package com.htmleditor.app

import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var root: View
    private lateinit var settingsPanel: View
    private lateinit var fabSettings: View

    private lateinit var tabs: List<View>
    private lateinit var tabIcons: List<ImageView>
    private lateinit var tabLabels: List<TextView>
    private lateinit var tabViews: List<View>

    private lateinit var codeInput: EditText
    private lateinit var editorFileName: TextView
    private lateinit var browseFileName: TextView
    private lateinit var browseStatus: TextView
    private lateinit var btnModePhone: TextView
    private lateinit var btnModePc: TextView
    private lateinit var btnThemeLight: TextView
    private lateinit var btnThemeDark: TextView
    private lateinit var zoomLabel: TextView

    private lateinit var searchRow: View
    private lateinit var searchInput: EditText
    private lateinit var fileTabsContainer: android.widget.LinearLayout

    private lateinit var editor: CodeEditor
    private lateinit var preview: PreviewController
    private lateinit var prefs: Prefs

    private var currentTab = 0
    private var lastPreviewed = ""

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) openFile(uri)
        }

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
            if (uri != null) writeNewFile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        AppCompatDelegate.setDefaultNightMode(
            if (prefs.themeMode == Prefs.THEME_DARK) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        )
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        bindViews()
        setupTabs()
        setupEditorActions()
        setupSearch()
        setupBrowse()
        setupSettingsPanel()
        restoreState()

        preview.mode = prefs.mode
        preview.zoom = prefs.zoom
        updateModeButtons()
        updateThemeButtons()
        updateZoomLabel()

        selectTab(prefs.tabIndex.coerceIn(0, 2))
    }

    override fun onPause() {
        persistAll()
        super.onPause()
    }

    // ---------------------------------------------------------------- привязка

    private fun bindViews() {
        settingsPanel = findViewById(R.id.settingsPanel)
        fabSettings = findViewById(R.id.fabSettings)
        searchRow = findViewById(R.id.searchRow)
        searchInput = findViewById(R.id.searchInput)
        fileTabsContainer = findViewById(R.id.fileTabsContainer)

        tabs = listOf(
            findViewById(R.id.tabEditor),
            findViewById(R.id.tabBrowse),
            findViewById(R.id.tabResult)
        )
        tabIcons = listOf(
            findViewById(R.id.tabEditorIcon),
            findViewById(R.id.tabBrowseIcon),
            findViewById(R.id.tabResultIcon)
        )
        tabLabels = listOf(
            findViewById(R.id.tabEditorLabel),
            findViewById(R.id.tabBrowseLabel),
            findViewById(R.id.tabResultLabel)
        )
        tabViews = listOf(
            findViewById(R.id.editorView),
            findViewById(R.id.browseView),
            findViewById(R.id.resultView)
        )

        codeInput = findViewById(R.id.codeInput)
        editorFileName = findViewById(R.id.editorFileName)
        browseFileName = findViewById(R.id.browseFileName)
        browseStatus = findViewById(R.id.browseStatus)
        btnModePhone = findViewById(R.id.btnModePhone)
        btnModePc = findViewById(R.id.btnModePc)
        btnThemeLight = findViewById(R.id.btnThemeLight)
        btnThemeDark = findViewById(R.id.btnThemeDark)
        zoomLabel = findViewById(R.id.zoomLabel)

        editor = CodeEditor(codeInput, findViewById(R.id.lineNumbers)) { text ->
            if (openTabs.isNotEmpty()) activeFile().content = text
        }
        preview = PreviewController(findViewById(R.id.previewWeb))
        preview.setup(this)
    }

    // --------------------------------------------------------- вкладки файлов

    private fun activeFile(): FileTab = openTabs[activeTabIdx]

    private fun restoreState() {
        if (openTabs.isEmpty()) restoreTabsFromPrefs()
        editor.setText(activeFile().content)
        updateFileNameLabel()
        renderFileTabs()
    }

    private fun restoreTabsFromPrefs() {
        val raw = prefs.tabsJson
        if (!raw.isNullOrEmpty()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val uriStr = o.optString("uri", "")
                    val nameStr = o.optString("name", "")
                    openTabs.add(
                        FileTab(
                            uri = if (uriStr.isEmpty()) null else Uri.parse(uriStr),
                            name = if (nameStr.isEmpty()) null else nameStr,
                            content = o.optString("content", ""),
                            saved = o.optString("saved", "")
                        )
                    )
                }
            } catch (e: Exception) {
                openTabs.clear()
            }
        }
        if (openTabs.isEmpty()) {
            val draft = prefs.draft
            if (draft != null) {
                openTabs.add(
                    FileTab(
                        uri = prefs.fileUri?.let(Uri::parse),
                        name = prefs.fileName,
                        content = draft,
                        saved = prefs.savedCode ?: draft
                    )
                )
            } else {
                openTabs.add(FileTab(null, null, DEFAULT_HTML, DEFAULT_HTML))
            }
        }
        activeTabIdx = prefs.activeTab.coerceIn(0, openTabs.size - 1)
    }

    /**
     * Автосохрание вкладки перед переключением: файл с путём пишется на диск,
     * файл без пути сохраняется в памяти — заново с диска он не загружается.
     */
    private fun persistTabToDisk(tab: FileTab) {
        val uri = tab.uri
        if (uri == null) {
            tab.saved = tab.content
            return
        }
        if (TextDiff.changedCharCount(tab.saved, tab.content) == 0) return
        if (FileStore.write(this, uri, tab.content)) {
            tab.saved = tab.content
        } else {
            Toast.makeText(this, R.string.toast_save_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchFileTab(index: Int) {
        if (index == activeTabIdx || index !in openTabs.indices) return
        persistTabToDisk(activeFile())
        activeTabIdx = index
        editor.setText(activeFile().content)
        updateFileNameLabel()
        renderFileTabs()
        persistAll()
    }

    private fun closeFileTab(index: Int) {
        if (index !in openTabs.indices) return
        persistTabToDisk(openTabs[index])
        val wasActive = index == activeTabIdx
        openTabs.removeAt(index)
        if (openTabs.isEmpty()) {
            openTabs.add(FileTab(null, null, DEFAULT_HTML, DEFAULT_HTML))
            activeTabIdx = 0
        } else if (wasActive) {
            activeTabIdx = index.coerceAtMost(openTabs.size - 1)
        } else if (index < activeTabIdx) {
            activeTabIdx--
        }
        editor.setText(activeFile().content)
        updateFileNameLabel()
        renderFileTabs()
        persistAll()
    }

    private fun addOrActivateTab(uri: Uri?, name: String?, content: String) {
        if (uri != null) {
            val existing = openTabs.indexOfFirst { it.uri == uri }
            if (existing >= 0) {
                switchFileTab(existing)
                return
            }
        }
        persistTabToDisk(activeFile())
        openTabs.add(FileTab(uri, name, content, content))
        activeTabIdx = openTabs.size - 1
        editor.setText(content)
        updateFileNameLabel()
        renderFileTabs()
        persistAll()
    }

    private fun renderFileTabs() {
        fileTabsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        openTabs.forEachIndexed { index, tab ->
            val chip = inflater.inflate(R.layout.item_file_tab, fileTabsContainer, false)
            val label = chip.findViewById<TextView>(R.id.chipLabel)
            val close = chip.findViewById<android.widget.ImageButton>(R.id.chipClose)
            label.text = tab.name ?: getString(R.string.editor_file_none)

            val active = index == activeTabIdx
            chip.setBackgroundResource(
                if (active) R.drawable.bg_chip_active else R.drawable.bg_chip_normal
            )
            val color = getColor(
                if (active) R.color.chip_text_active else R.color.chip_text_inactive
            )
            label.setTextColor(color)
            close.setColorFilter(color)

            chip.setOnClickListener { switchFileTab(index) }
            close.setOnClickListener { closeFileTab(index) }
            fileTabsContainer.addView(chip)
        }

        val add = inflater.inflate(R.layout.item_file_add, fileTabsContainer, false)
        add.setOnClickListener {
            openDocument.launch(arrayOf("*/*"))
        }
        fileTabsContainer.addView(add)
    }

    // ------------------------------------------------------------------- табы

    private fun setupTabs() {
        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener { selectTab(index) }
        }
    }

    private fun selectTab(index: Int) {
        currentTab = index
        prefs.tabIndex = index

        for (i in tabViews.indices) {
            val active = i == index
            tabViews[i].visibility = if (active) View.VISIBLE else View.GONE
            tabs[i].setBackgroundResource(
                if (active) R.drawable.bg_tab_active else R.color.primary
            )
            val tint = getColor(if (active) R.color.tab_text else R.color.tab_text_inactive)
            tabIcons[i].imageTintList = ColorStateList.valueOf(tint)
            tabLabels[i].setTextColor(tint)
        }

        fabSettings.visibility = if (index == TAB_RESULT) View.VISIBLE else View.GONE
        if (index != TAB_RESULT) settingsPanel.visibility = View.GONE

        when (index) {
            TAB_BROWSE -> updateBrowseInfo()
            TAB_RESULT -> updatePreview(force = false)
        }
    }

    // --------------------------------------------------------------- редактор

    private fun setupEditorActions() {
        findViewById<View>(R.id.btnNew).setOnClickListener { newFile() }
        findViewById<View>(R.id.btnSave).setOnClickListener { save() }
        findViewById<View>(R.id.btnSaveAs).setOnClickListener { saveAs() }
    }

    private fun newFile() {
        persistTabToDisk(activeFile())
        openTabs.add(FileTab(null, null, DEFAULT_HTML, DEFAULT_HTML))
        activeTabIdx = openTabs.size - 1
        editor.setText(DEFAULT_HTML)
        updateFileNameLabel()
        renderFileTabs()
        persistAll()
    }

    private fun save() {
        val uri = activeFile().uri
        if (uri == null) {
            saveAs()
            return
        }
        val text = editor.getText()
        if (FileStore.write(this, uri, text)) {
            activeFile().saved = text
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
            persistAll()
        } else {
            Toast.makeText(this, R.string.toast_save_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveAs() {
        createDocument.launch(activeFile().name ?: "index.html")
    }

    private fun writeNewFile(uri: Uri) {
        FileStore.persistPermissions(this, uri)
        val text = editor.getText()
        if (FileStore.write(this, uri, text)) {
            val tab = activeFile()
            tab.uri = uri
            tab.name = FileStore.displayName(this, uri) ?: uri.lastPathSegment
            tab.saved = text
            updateFileNameLabel()
            renderFileTabs()
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
            persistAll()
        } else {
            Toast.makeText(this, R.string.toast_save_error, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- поиск

    private fun setupSearch() {
        findViewById<View>(R.id.btnSearch).setOnClickListener {
            if (searchRow.visibility == View.VISIBLE) {
                searchRow.visibility = View.GONE
            } else {
                searchRow.visibility = View.VISIBLE
                searchInput.requestFocus()
            }
        }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                Unit

            override fun afterTextChanged(s: android.text.Editable?) {
                searchFrom(0, silent = true)
            }
        })

        findViewById<View>(R.id.btnSearchNext).setOnClickListener {
            searchFrom(codeInput.selectionEnd, silent = false)
        }
        findViewById<View>(R.id.btnSearchClose).setOnClickListener {
            searchRow.visibility = View.GONE
        }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchFrom(codeInput.selectionEnd, silent = false)
                true
            } else {
                false
            }
        }
    }

    private fun searchFrom(from: Int, silent: Boolean): Boolean {
        val query = searchInput.text.toString()
        if (query.isEmpty()) return false
        val text = editor.getText()
        var index = text.indexOf(query, from, ignoreCase = true)
        if (index < 0 && from > 0) index = text.indexOf(query, 0, ignoreCase = true)
        if (index < 0) {
            if (!silent) Toast.makeText(this, R.string.toast_not_found, Toast.LENGTH_SHORT).show()
            return false
        }
        jumpToMatch(index, query.length)
        return true
    }

    /** Прыжок к совпадению с голубой подсветкой на 1 секунду. */
    private fun jumpToMatch(index: Int, length: Int) {
        codeInput.setSelection(index, index + length)
        val editable = codeInput.text
        val span = BackgroundColorSpan(getColor(R.color.search_highlight))
        editable.setSpan(span, index, index + length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        codeInput.postDelayed({
            if (editable.getSpanStart(span) >= 0) editable.removeSpan(span)
        }, 1000L)
        editor.scrollToOffset(index)
    }

    // ---------------------------------------------------------------- обзор

    private fun setupBrowse() {
        findViewById<View>(R.id.btnBrowseOpen).setOnClickListener {
            openDocument.launch(arrayOf("*/*"))
        }
    }

    private fun openFile(uri: Uri) {
        val text = FileStore.read(this, uri)
        if (text == null) {
            Toast.makeText(this, R.string.toast_open_error, Toast.LENGTH_SHORT).show()
            return
        }
        FileStore.persistPermissions(this, uri)
        val name = FileStore.displayName(this, uri) ?: uri.lastPathSegment
        addOrActivateTab(uri, name, text)
        selectTab(TAB_EDITOR)
    }

    private fun updateBrowseInfo() {
        val tab = activeFile()
        browseFileName.text = tab.name ?: getString(R.string.no_file)
        browseStatus.setText(
            if (TextDiff.changedCharCount(tab.saved, tab.content) > 0) {
                R.string.status_modified
            } else {
                R.string.status_saved
            }
        )
    }

    private fun updateFileNameLabel() {
        editorFileName.text = activeFile().name ?: getString(R.string.editor_file_none)
    }

    // -------------------------------------------------------------- предпросмотр

    private fun updatePreview(force: Boolean) {
        val code = editor.getText()
        val changed = TextDiff.changedCharCount(lastPreviewed, code)
        if (force || lastPreviewed.isEmpty() || changed >= 2) {
            preview.load(code)
            lastPreviewed = code
            if (force && changed >= 2) {
                Toast.makeText(this, R.string.toast_preview_updated, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --------------------------------------------------------------- настройки

    private fun setupSettingsPanel() {
        val togglePanel = {
            settingsPanel.visibility =
                if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        fabSettings.setOnClickListener { togglePanel() }
        findViewById<View>(R.id.btnSettingsEditor).setOnClickListener { togglePanel() }

        findViewById<View>(R.id.btnCloseSettings).setOnClickListener {
            settingsPanel.visibility = View.GONE
        }

        btnModePhone.setOnClickListener { setMode(Prefs.MODE_PHONE) }
        btnModePc.setOnClickListener { setMode(Prefs.MODE_PC) }

        btnThemeLight.setOnClickListener { setThemeMode(Prefs.THEME_LIGHT) }
        btnThemeDark.setOnClickListener { setThemeMode(Prefs.THEME_DARK) }

        findViewById<View>(R.id.btnZoomOut).setOnClickListener {
            setZoom(preview.zoom - ZOOM_STEP)
        }
        findViewById<View>(R.id.btnZoomIn).setOnClickListener {
            setZoom(preview.zoom + ZOOM_STEP)
        }

        findViewById<View>(R.id.btnRefreshPreview).setOnClickListener {
            updatePreview(force = true)
        }
    }

    private fun setMode(mode: String) {
        if (preview.mode == mode) return
        preview.mode = mode
        prefs.mode = mode
        updateModeButtons()
        if (currentTab == TAB_RESULT) {
            preview.load(editor.getText())
            lastPreviewed = editor.getText()
        }
    }

    private fun setThemeMode(mode: String) {
        if (prefs.themeMode == mode) return
        prefs.themeMode = mode
        AppCompatDelegate.setDefaultNightMode(
            if (mode == Prefs.THEME_DARK) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        )
        // Активити пересоздастся с новой темой; вкладки в памяти сохранятся
    }

    private fun setZoom(value: Int) {
        preview.zoom = value.coerceIn(ZOOM_MIN, ZOOM_MAX)
        prefs.zoom = preview.zoom
        preview.applyZoom()
        updateZoomLabel()
    }

    private fun updateModeButtons() {
        val pc = preview.mode == Prefs.MODE_PC
        btnModePhone.setBackgroundResource(
            if (pc) R.drawable.bg_seg_normal else R.drawable.bg_seg_selected
        )
        btnModePhone.setTextColor(getColor(if (pc) R.color.primary else R.color.main))
        btnModePc.setBackgroundResource(
            if (pc) R.drawable.bg_seg_selected else R.drawable.bg_seg_normal
        )
        btnModePc.setTextColor(getColor(if (pc) R.color.main else R.color.primary))
    }

    private fun updateThemeButtons() {
        val dark = prefs.themeMode == Prefs.THEME_DARK
        btnThemeLight.setBackgroundResource(
            if (dark) R.drawable.bg_seg_normal else R.drawable.bg_seg_selected
        )
        btnThemeLight.setTextColor(getColor(if (dark) R.color.primary else R.color.main))
        btnThemeDark.setBackgroundResource(
            if (dark) R.drawable.bg_seg_selected else R.drawable.bg_seg_normal
        )
        btnThemeDark.setTextColor(getColor(if (dark) R.color.main else R.color.primary))
    }

    private fun updateZoomLabel() {
        zoomLabel.text = getString(R.string.zoom_format, preview.zoom)
    }

    // --------------------------------------------------------------- состояние

    private fun persistAll() {
        if (openTabs.isEmpty()) return
        persistTabsJson()
        val active = activeFile()
        prefs.draft = active.content
        prefs.savedCode = active.saved
        prefs.fileName = active.name
        prefs.fileUri = active.uri?.toString()
        prefs.activeTab = activeTabIdx
        if (::preview.isInitialized) {
            prefs.mode = preview.mode
            prefs.zoom = preview.zoom
        }
        prefs.tabIndex = currentTab
    }

    private fun persistTabsJson() {
        var total = 0
        for (tab in openTabs) total += tab.content.length + tab.saved.length
        val truncated = total > MAX_PERSIST_CHARS
        val list = if (truncated) listOf(activeFile()) else openTabs

        val arr = JSONArray()
        for (tab in list) {
            val o = JSONObject()
            o.put("uri", tab.uri?.toString() ?: "")
            o.put("name", tab.name ?: "")
            o.put("content", tab.content)
            o.put("saved", tab.saved)
            arr.put(o)
        }
        prefs.tabsJson = arr.toString()
    }

    companion object {
        private const val TAB_EDITOR = 0
        private const val TAB_BROWSE = 1
        private const val TAB_RESULT = 2

        private const val ZOOM_STEP = 10
        private const val ZOOM_MIN = 50
        private const val ZOOM_MAX = 300

        /** Всего символов всех вкладок, которые пишем в SharedPreferences. */
        private const val MAX_PERSIST_CHARS = 400_000

        /** Открытые файлы живут в памяти процесса — переживают пересоздание активити. */
        private val openTabs = ArrayList<FileTab>()
        private var activeTabIdx = 0

        private val DEFAULT_HTML = """<!DOCTYPE html>
<html lang="ru">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Новая страница</title>
  <style>
    body { font-family: sans-serif; margin: 16px; line-height: 1.5; }
    h1 { color: #3C6970; }
  </style>
</head>
<body>
  <h1>Привет!</h1>
  <p>Начните писать HTML, CSS и JavaScript — результат появится во вкладке Result.</p>
</body>
</html>
"""
    }
}
