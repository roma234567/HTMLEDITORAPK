package com.htmleditor.app

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.htmleditor.app.py.PyConsoleHost
import com.htmleditor.app.py.TurtleBridge
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
    private lateinit var pal: Palette

    // Result / Python
    private lateinit var previewWeb: View
    private lateinit var pythonPane: View
    private lateinit var turtleView: TurtleView
    private lateinit var consoleScroll: ScrollView
    private lateinit var consoleText: TextView
    private lateinit var inputRow: View
    private lateinit var consoleInput: EditText
    private lateinit var btnStopRun: View
    private lateinit var resultTitle: TextView
    private lateinit var rowMode: View
    private lateinit var rowZoom: View

    private var isPythonPane = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pyHost: PyConsoleHost? = null
    private var pyThread: Thread? = null
    private var inputPending = false

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
        // Палитра первой: остальные setup/restore используют цвета схемы
        applyPalette()
        setupTabs()
        setupEditorActions()
        setupSearch()
        setupBrowse()
        setupSettingsPanel()
        restoreState()

        preview.mode = prefs.mode
        preview.zoom = prefs.zoom
        updateZoomLabel()

        selectTab(prefs.tabIndex.coerceIn(0, 2))
    }

    override fun onPause() {
        persistAll()
        super.onPause()
    }

    override fun onDestroy() {
        pyHost?.requestStop()
        super.onDestroy()
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

        previewWeb = findViewById(R.id.previewWeb)
        pythonPane = findViewById(R.id.pythonPane)
        turtleView = findViewById(R.id.turtleView)
        consoleScroll = findViewById(R.id.consoleScroll)
        consoleText = findViewById(R.id.consoleText)
        inputRow = findViewById(R.id.inputRow)
        consoleInput = findViewById(R.id.consoleInput)
        btnStopRun = findViewById(R.id.btnStopRun)
        resultTitle = findViewById(R.id.resultTitle)
        rowMode = findViewById(R.id.rowMode)
        rowZoom = findViewById(R.id.rowZoom)

        editor = CodeEditor(codeInput, findViewById(R.id.lineNumbers)) { text ->
            if (openTabs.isNotEmpty()) activeFile().content = text
        }
        preview = PreviewController(previewWeb as android.webkit.WebView)
        preview.setup(this)
    }

    // --------------------------------------------------------- вкладки файлов

    private fun activeFile(): FileTab = openTabs[activeTabIdx]

    private fun restoreState() {
        if (openTabs.isEmpty()) restoreTabsFromPrefs()
        editor.setText(activeFile().content)
        lastPreviewed = ""
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
        lastPreviewed = ""
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
        lastPreviewed = ""
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
        lastPreviewed = ""
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
            tintShape(chip, if (active) pal.accent else pal.soft)
            val color = if (active) pal.onAccent else pal.ink
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
            tabViews[i].visibility = if (i == index) View.VISIBLE else View.GONE
        }
        refreshTabsVisual()

        fabSettings.visibility = if (index == TAB_RESULT) View.VISIBLE else View.GONE
        if (index != TAB_RESULT) settingsPanel.visibility = View.GONE

        when (index) {
            TAB_BROWSE -> updateBrowseInfo()
            TAB_RESULT -> {
                updateResultPane()
                updatePreview(force = false)
            }
        }
    }

    private fun refreshTabsVisual() {
        for (i in tabs.indices) {
            val active = i == currentTab
            if (active) tintShape(tabs[i], pal.accent) else tabs[i].setBackgroundColor(pal.primary)
            val tint = if (active) pal.onAccent else pal.tabInactive
            tabIcons[i].imageTintList = ColorStateList.valueOf(tint)
            tabLabels[i].setTextColor(tint)
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
        lastPreviewed = ""
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

    /** Прыжок к совпадению с подсветкой на 1 секунду. */
    private fun jumpToMatch(index: Int, length: Int) {
        codeInput.setSelection(index, index + length)
        val editable = codeInput.text
        val span = BackgroundColorSpan(pal.searchHl)
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

    /**
     * Правило запуска: только на вкладке Result, если текст изменился на ≥2 символов
     * или нажата ручное обновление. HTML — перезагрузка WebView, Python — запуск.
     */
    private fun updatePreview(force: Boolean) {
        val code = editor.getText()
        val changed = TextDiff.changedCharCount(lastPreviewed, code)
        if (force || lastPreviewed.isEmpty() || changed >= 2) {
            lastPreviewed = code
            updateResultPane()
            if (isPythonPane) {
                runPython(code)
            } else {
                preview.load(code)
                if (force && changed >= 2) {
                    Toast.makeText(this, R.string.toast_preview_updated, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ------------------------------------------------------------- Python/Result

    private fun resolvePython(): Boolean {
        when (prefs.langOverride) {
            Prefs.LANG_PYTHON -> return true
            Prefs.LANG_HTML -> return false
        }
        val name = if (openTabs.isNotEmpty()) activeFile().name else null
        return LanguageDetector.isPython(name, editor.getText())
    }

    private fun updateResultPane() {
        isPythonPane = resolvePython()
        previewWeb.visibility = if (isPythonPane) View.GONE else View.VISIBLE
        pythonPane.visibility = if (isPythonPane) View.VISIBLE else View.GONE
        rowMode.visibility = if (isPythonPane) View.GONE else View.VISIBLE
        rowZoom.visibility = if (isPythonPane) View.GONE else View.VISIBLE
        resultTitle.setText(
            if (isPythonPane) R.string.result_title_py else R.string.result_title_html
        )
    }

    private val turtleBridge = object : TurtleBridge {
        override fun segment(x1: Double, y1: Double, x2: Double, y2: Double, color: Int, width: Double) {
            postUi { turtleView.addSegment(x1, y1, x2, y2, color, width) }
        }

        override fun marker(x: Double, y: Double, heading: Double, visible: Boolean, color: Int) {
            postUi { turtleView.setMarker(x, y, heading, visible, color) }
        }

        override fun bgcolor(color: Int) {
            postUi { turtleView.setCanvasColor(color) }
        }

        override fun clear() {
            postUi { turtleView.clearDrawing() }
        }
    }

    private fun postUi(f: () -> Unit) {
        mainHandler.post {
            if (!isFinishing && !isDestroyed) f()
        }
    }

    private fun runPython(code: String) {
        // Остановка предыдущего запуска (в т.ч. ожидающего ввода)
        val prev = pyThread
        if (prev != null && prev.isAlive) {
            pyHost?.requestStop()
            try {
                prev.join(700)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        val host = pyHost ?: PyConsoleHost({ event -> postUi { handleHostEvent(event) } }, turtleBridge)
            .also { pyHost = it }
        host.resetForNewRun()

        consoleText.text = ""
        inputPending = false
        inputRow.visibility = View.GONE
        consoleInput.setText("")
        appendConsole(getString(R.string.py_started) + "\n", pal.muted)
        btnStopRun.visibility = View.VISIBLE

        pyThread = Thread({ host.execute(code) }, "py-interpreter").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleHostEvent(e: PyConsoleHost.HostEvent) {
        when (e) {
            is PyConsoleHost.HostEvent.Output -> appendConsole(e.text, null)

            is PyConsoleHost.HostEvent.Prompt -> {
                if (inputPending) appendConsole("\n", null)
                appendConsole(e.text, null)
                inputPending = true
                inputRow.visibility = View.VISIBLE
                consoleInput.requestFocus()
            }

            is PyConsoleHost.HostEvent.Error -> {
                if (inputPending) appendConsole("\n", null)
                inputPending = false
                inputRow.visibility = View.GONE
                btnStopRun.visibility = View.GONE
                val msg = getString(R.string.py_error_line, e.line, e.message)
                appendConsole(msg + "\n", ERROR_COLOR)
            }

            is PyConsoleHost.HostEvent.Done -> finishRun(getString(R.string.py_done))

            is PyConsoleHost.HostEvent.Stopped -> finishRun(getString(R.string.py_stopped))

            is PyConsoleHost.HostEvent.Finished -> {
                btnStopRun.visibility = View.GONE
                inputRow.visibility = View.GONE
            }
        }
    }

    private fun finishRun(marker: String) {
        if (inputPending) appendConsole("\n", null)
        inputPending = false
        inputRow.visibility = View.GONE
        btnStopRun.visibility = View.GONE
        appendConsole(marker + "\n", pal.muted)
    }

    private fun appendConsole(text: String, color: Int?) {
        if (text.isEmpty()) return
        val sb = SpannableStringBuilder(consoleText.text)
        val start = sb.length
        sb.append(text)
        if (color != null) {
            sb.setSpan(
                ForegroundColorSpan(color),
                start,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (sb.length > CONSOLE_MAX_CHARS) {
            sb.delete(0, sb.length - CONSOLE_KEEP_CHARS)
        }
        consoleText.text = sb
        consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendConsoleInput() {
        if (!inputPending) return
        val line = consoleInput.text.toString()
        appendConsole(line + "\n", null)
        inputPending = false
        inputRow.visibility = View.GONE
        consoleInput.setText("")
        pyHost?.feedInput(line)
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

        btnThemeLight.setOnClickListener { setThemeMode(Prefs.THEME_LIGHT) }
        btnThemeDark.setOnClickListener { setThemeMode(Prefs.THEME_DARK) }

        btnModePhone.setOnClickListener { setMode(Prefs.MODE_PHONE) }
        btnModePc.setOnClickListener { setMode(Prefs.MODE_PC) }

        findViewById<TextView>(R.id.swClassic).setOnClickListener { setScheme(PaletteNs.CLASSIC) }
        findViewById<TextView>(R.id.swBlue).setOnClickListener { setScheme(PaletteNs.BLUE) }
        findViewById<TextView>(R.id.swGreen).setOnClickListener { setScheme(PaletteNs.GREEN) }
        findViewById<TextView>(R.id.swWarm).setOnClickListener { setScheme(PaletteNs.WARM) }

        findViewById<TextView>(R.id.btnLangAuto).setOnClickListener { setLangOverride(Prefs.LANG_AUTO) }
        findViewById<TextView>(R.id.btnLangHtml).setOnClickListener { setLangOverride(Prefs.LANG_HTML) }
        findViewById<TextView>(R.id.btnLangPython).setOnClickListener { setLangOverride(Prefs.LANG_PYTHON) }

        findViewById<View>(R.id.btnZoomOut).setOnClickListener {
            setZoom(preview.zoom - ZOOM_STEP)
        }
        findViewById<View>(R.id.btnZoomIn).setOnClickListener {
            setZoom(preview.zoom + ZOOM_STEP)
        }

        findViewById<View>(R.id.btnRefreshPreview).setOnClickListener {
            updatePreview(force = true)
        }
        findViewById<View>(R.id.btnStopRun).setOnClickListener {
            pyHost?.requestStop()
        }
        findViewById<View>(R.id.btnSendInput).setOnClickListener { sendConsoleInput() }
        consoleInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT) {
                sendConsoleInput()
                true
            } else {
                false
            }
        }
    }

    private fun setScheme(id: String) {
        if (prefs.scheme == id) return
        prefs.scheme = id
        applyPalette()
    }

    private fun setLangOverride(value: String) {
        if (prefs.langOverride == value) return
        prefs.langOverride = value
        updateLangButtons()
        lastPreviewed = ""
        if (currentTab == TAB_RESULT) {
            updateResultPane()
            updatePreview(force = false)
        }
    }

    private fun setMode(mode: String) {
        if (preview.mode == mode) return
        preview.mode = mode
        prefs.mode = mode
        updateModeButtons()
        if (currentTab == TAB_RESULT && !isPythonPane) {
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

    private fun setSeg(button: TextView, selected: Boolean) {
        tintShape(button, if (selected) pal.accent else pal.soft)
        button.setTextColor(if (selected) pal.onAccent else pal.ink)
    }

    private fun updateModeButtons() {
        setSeg(btnModePhone, preview.mode == Prefs.MODE_PHONE)
        setSeg(btnModePc, preview.mode == Prefs.MODE_PC)
    }

    private fun updateThemeButtons() {
        setSeg(btnThemeLight, prefs.themeMode == Prefs.THEME_LIGHT)
        setSeg(btnThemeDark, prefs.themeMode == Prefs.THEME_DARK)
    }

    private fun updateLangButtons() {
        val cur = prefs.langOverride
        setSeg(findViewById(R.id.btnLangAuto), cur == Prefs.LANG_AUTO)
        setSeg(findViewById(R.id.btnLangHtml), cur == Prefs.LANG_HTML)
        setSeg(findViewById(R.id.btnLangPython), cur == Prefs.LANG_PYTHON)
    }

    private fun updateSwatches() {
        val density = resources.displayMetrics.density
        val radius = 8 * density
        val strokeW = (2 * density).toInt().coerceAtLeast(1)
        val entries = listOf(
            findViewById<TextView>(R.id.swClassic) to PaletteNs.CLASSIC,
            findViewById<TextView>(R.id.swBlue) to PaletteNs.BLUE,
            findViewById<TextView>(R.id.swGreen) to PaletteNs.GREEN,
            findViewById<TextView>(R.id.swWarm) to PaletteNs.WARM
        )
        for ((view, id) in entries) {
            val schemePal = PaletteNs.get(id, pal.isLight)
            val selected = prefs.scheme == id
            val gd = GradientDrawable()
            gd.cornerRadius = radius
            gd.setColor(schemePal.accent)
            if (selected) gd.setStroke(strokeW, pal.ink)
            view.background = gd
            view.setTextColor(schemePal.onAccent)
        }
    }

    private fun updateZoomLabel() {
        zoomLabel.text = getString(R.string.zoom_format, preview.zoom)
    }

    // ------------------------------------------------------------------ палитра

    /**
     * Программная перекраска без пересоздания активити: все цвета берутся из
     * выбранной схемы и текущего day/night. Вызывается при старте и смене свотча.
     */
    private fun applyPalette() {
        val night =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        pal = PaletteNs.get(prefs.scheme, !night)

        window.statusBarColor = pal.main
        window.navigationBarColor = pal.main
        val insetsController = WindowCompat.getInsetsController(window, root)
        insetsController.isAppearanceLightStatusBars = pal.isLight
        insetsController.isAppearanceLightNavigationBars = pal.isLight
        root.setBackgroundColor(pal.main)

        // контейнеры
        settingsPanel.setBackgroundColor(pal.main)
        findViewById<View>(R.id.fileStrip).setBackgroundColor(pal.main)
        findViewById<View>(R.id.editorToolbar).setBackgroundColor(pal.soft)
        searchRow.setBackgroundColor(pal.soft)
        findViewById<View>(R.id.resultToolbar).setBackgroundColor(pal.soft)
        pythonPane.setBackgroundColor(pal.main)
        consoleScroll.setBackgroundColor(pal.main)
        inputRow.setBackgroundColor(pal.soft)

        // редактор
        tintShape(codeInput, pal.main)
        codeInput.setTextColor(pal.codeText)
        codeInput.setHighlightColor(pal.soft)
        tintShape(searchInput, pal.main)
        searchInput.setTextColor(pal.codeText)
        searchInput.setTextColorHint(pal.muted)
        val lineNumbers = findViewById<TextView>(R.id.lineNumbers)
        lineNumbers.setBackgroundColor(pal.soft)
        lineNumbers.setTextColor(ensureContrast(pal.accent, pal.soft, 3.2))
        editorFileName.setTextColor(pal.ink)
        editor.updateColors(
            HtmlHighlighter.Colors(
                tag = pal.hlTag,
                attr = pal.hlAttr,
                string = pal.hlString,
                comment = pal.hlComment
            )
        )

        // обзор
        tintShape(findViewById(R.id.browseCard), pal.soft)
        findViewById<TextView>(R.id.browseTitle).setTextColor(pal.ink)
        findViewById<TextView>(R.id.browseFileName).setTextColor(pal.ink)
        findViewById<TextView>(R.id.browseFileCaption)
            .setTextColor(ensureContrast(pal.accent, pal.soft, 3.2))
        browseStatus.setTextColor(pal.muted)
        val browseOpen = findViewById<android.widget.Button>(R.id.btnBrowseOpen)
        tintShape(browseOpen, pal.accent)
        browseOpen.setTextColor(pal.onAccent)

        // Result / консоль
        resultTitle.setTextColor(pal.ink)
        consoleText.setTextColor(pal.codeText)
        tintShape(consoleInput, pal.main)
        consoleInput.setTextColor(pal.codeText)
        consoleInput.setTextColorHint(pal.muted)
        findViewById<ImageView>(R.id.btnRefreshPreview)
            .imageTintList = ColorStateList.valueOf(pal.ink)
        findViewById<ImageView>(R.id.btnStopRun)
            .imageTintList = ColorStateList.valueOf(pal.ink)
        findViewById<ImageView>(R.id.btnSendInput)
            .imageTintList = ColorStateList.valueOf(pal.ink)

        // FAB
        tintShape(fabSettings, pal.accent)
        (fabSettings as? ImageView)?.imageTintList = ColorStateList.valueOf(pal.onAccent)

        // настройки: сегменты, свотчи, подписи
        updateThemeButtons()
        updateModeButtons()
        updateLangButtons()
        updateSwatches()
        zoomLabel.setTextColor(ensureContrast(pal.accent, pal.main, 3.2))
        recolorSettingsLabels(settingsPanel)

        // табы и чипы файлов
        refreshTabsVisual()
        renderFileTabs()
    }

    /** Подписи без id в настройках — контрастный ink; разделители — приглушённый. */
    private fun recolorSettingsLabels(v: View) {
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) recolorSettingsLabels(v.getChildAt(i))
        }
        if (v.id != View.NO_ID) return
        when (v) {
            is TextView -> v.setTextColor(pal.ink)
            !is ViewGroup -> {
                if (v.background != null) v.setBackgroundColor(mixColor(pal.ink, pal.main, 0.55))
            }
        }
    }

    /** Заливка фигуры (shape-drawable) новым цветом без потери скруглений. */
    private fun tintShape(v: View, color: Int) {
        val d = v.background
        if (d == null) {
            v.setBackgroundColor(color)
            return
        }
        val m = d.mutate()
        when (m) {
            is GradientDrawable -> m.setColor(color)
            is ColorDrawable -> m.setColor(color)
            else -> m.setTint(color)
        }
        v.background = m
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

        /** Ограничение консоли Python. */
        private const val CONSOLE_MAX_CHARS = 120_000
        private const val CONSOLE_KEEP_CHARS = 100_000

        /** Приглушённый красный ошибок (без неона). */
        private val ERROR_COLOR = 0xFFC0392B.toInt()

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
