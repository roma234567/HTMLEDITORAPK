package com.htmleditor.app

import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

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
    private lateinit var zoomLabel: TextView

    private lateinit var editor: CodeEditor
    private lateinit var preview: PreviewController
    private lateinit var prefs: Prefs

    private var currentTab = 0
    private var fileUri: Uri? = null
    private var fileName: String? = null
    private var savedCode = ""
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

        prefs = Prefs(this)
        bindViews()
        setupTabs()
        setupEditorActions()
        setupBrowse()
        setupSettingsPanel()
        restoreState()

        preview.mode = prefs.mode
        preview.zoom = prefs.zoom
        updateModeButtons()
        updateZoomLabel()

        selectTab(prefs.tabIndex.coerceIn(0, 2))
    }

    override fun onPause() {
        persistState()
        super.onPause()
    }

    // ---------------------------------------------------------------- привязка

    private fun bindViews() {
        settingsPanel = findViewById(R.id.settingsPanel)
        fabSettings = findViewById(R.id.fabSettings)

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
        zoomLabel = findViewById(R.id.zoomLabel)

        editor = CodeEditor(codeInput, findViewById(R.id.lineNumbers))
        preview = PreviewController(findViewById(R.id.previewWeb))
        preview.setup(this)
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
        findViewById<View>(R.id.btnNew).setOnClickListener {
            withUnsavedGuard { newFile() }
        }
        findViewById<View>(R.id.btnSave).setOnClickListener { save() }
        findViewById<View>(R.id.btnSaveAs).setOnClickListener { saveAs() }
    }

    private fun restoreState() {
        val draft = prefs.draft
        if (draft != null) {
            editor.setText(draft)
            savedCode = prefs.savedCode ?: draft
            fileName = prefs.fileName
            fileUri = prefs.fileUri?.let(Uri::parse)
        } else {
            editor.setText(DEFAULT_HTML)
            savedCode = DEFAULT_HTML
        }
        updateFileNameLabel()
    }

    private fun newFile() {
        editor.setText(DEFAULT_HTML)
        fileUri = null
        fileName = null
        savedCode = DEFAULT_HTML
        lastPreviewed = ""
        updateFileNameLabel()
        persistState()
    }

    private fun save() {
        val uri = fileUri
        if (uri == null) {
            saveAs()
            return
        }
        val text = editor.getText()
        if (FileStore.write(this, uri, text)) {
            savedCode = text
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
            persistState()
        } else {
            Toast.makeText(this, R.string.toast_save_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveAs() {
        createDocument.launch(fileName ?: "index.html")
    }

    private fun writeNewFile(uri: Uri) {
        FileStore.persistPermissions(this, uri)
        val text = editor.getText()
        if (FileStore.write(this, uri, text)) {
            fileUri = uri
            fileName = FileStore.displayName(this, uri) ?: uri.lastPathSegment
            savedCode = text
            updateFileNameLabel()
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
            persistState()
        } else {
            Toast.makeText(this, R.string.toast_save_error, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- обзор

    private fun setupBrowse() {
        findViewById<View>(R.id.btnBrowseOpen).setOnClickListener {
            withUnsavedGuard { openDocument.launch(arrayOf("*/*")) }
        }
    }

    private fun openFile(uri: Uri) {
        val text = FileStore.read(this, uri)
        if (text == null) {
            Toast.makeText(this, R.string.toast_open_error, Toast.LENGTH_SHORT).show()
            return
        }
        FileStore.persistPermissions(this, uri)
        fileUri = uri
        fileName = FileStore.displayName(this, uri) ?: uri.lastPathSegment
        editor.setText(text)
        savedCode = text
        updateFileNameLabel()
        persistState()
        selectTab(TAB_EDITOR)
    }

    private fun updateBrowseInfo() {
        browseFileName.text = fileName ?: getString(R.string.no_file)
        browseStatus.setText(
            if (hasUnsavedChanges()) R.string.status_modified else R.string.status_saved
        )
    }

    private fun updateFileNameLabel() {
        editorFileName.text = fileName ?: getString(R.string.editor_file_none)
    }

    private fun hasUnsavedChanges(): Boolean =
        TextDiff.changedCharCount(savedCode, editor.getText()) > 0

    private fun withUnsavedGuard(action: () -> Unit) {
        if (hasUnsavedChanges()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_unsaved_title)
                .setMessage(R.string.dialog_unsaved_message)
                .setPositiveButton(R.string.dialog_continue) { _, _ -> action() }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        } else {
            action()
        }
    }

    // -------------------------------------------------------------- предпросмотр

    /**
     * Обновление предпросмотра по условию: изменения кода >= 2 символов
     * и нажатие на вкладку Result (force = ручное обновление кнопкой).
     */
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
        fabSettings.setOnClickListener {
            settingsPanel.visibility =
                if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.btnCloseSettings).setOnClickListener {
            settingsPanel.visibility = View.GONE
        }

        btnModePhone.setOnClickListener { setMode(Prefs.MODE_PHONE) }
        btnModePc.setOnClickListener { setMode(Prefs.MODE_PC) }

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
            // Режим влияет на отображение — перезагружаем с текущим кодом
            preview.load(editor.getText())
            lastPreviewed = editor.getText()
        }
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

    private fun updateZoomLabel() {
        zoomLabel.text = getString(R.string.zoom_format, preview.zoom)
    }

    // --------------------------------------------------------------- состояние

    private fun persistState() {
        val text = editor.getText()
        prefs.draft = text
        prefs.savedCode = savedCode
        prefs.fileName = fileName
        prefs.fileUri = fileUri?.toString()
        prefs.mode = preview.mode
        prefs.zoom = preview.zoom
        prefs.tabIndex = currentTab
    }

    companion object {
        private const val TAB_EDITOR = 0
        private const val TAB_BROWSE = 1
        private const val TAB_RESULT = 2

        private const val ZOOM_STEP = 10
        private const val ZOOM_MIN = 50
        private const val ZOOM_MAX = 300

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
