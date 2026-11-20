/*
 * Solderless - main screen. Phones: Console, Editor and Registers as tabs.
 * Wide screens: the editor on the left, Console and Registers as tabs on the right.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import io.github.leukoplast1980.solderless.files.ProjectFolder
import io.github.leukoplast1980.solderless.files.SessionStore
import io.github.leukoplast1980.solderless.settings.Settings
import io.github.leukoplast1980.solderless.settings.SettingsActivity
import io.github.leukoplast1980.solderless.ui.Completion
import io.github.leukoplast1980.solderless.ui.BoardController
import io.github.leukoplast1980.solderless.ui.ScopeController
import io.github.leukoplast1980.solderless.ui.ConsoleController
import io.github.leukoplast1980.solderless.ui.EditorController
import io.github.leukoplast1980.solderless.ui.ExtraKeys
import io.github.leukoplast1980.solderless.ui.InputTarget
import io.github.leukoplast1980.solderless.ui.RegistersController
import io.github.leukoplast1980.solderless.ui.SuggestionStrip
import io.github.leukoplast1980.solderless.vm.Firmware
import io.github.leukoplast1980.solderless.vm.FirmwareException
import io.github.leukoplast1980.solderless.vm.VmHost

class MainActivity : AppCompatActivity() {

    private enum class Screen { REPL, EDITOR, BOARD, SCOPE, REGISTERS }

    private lateinit var vm: VmHost
    private lateinit var console: ConsoleController
    private lateinit var editor: EditorController
    private lateinit var registers: RegistersController
    private lateinit var boardCtl: BoardController
    private lateinit var boardPane: View
    private lateinit var scopeCtl: ScopeController
    private lateinit var scopePane: View
    private lateinit var strip: SuggestionStrip
    private lateinit var folder: ProjectFolder
    private lateinit var sessionStore: SessionStore
    private lateinit var settings: Settings

    private lateinit var tabs: TabLayout
    private lateinit var editorPane: View
    private lateinit var rightPane: View
    private lateinit var replPane: View
    private lateinit var registersPane: View
    private lateinit var divider: View
    private lateinit var extraKeysScroll: View
    private lateinit var title: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var screen = Screen.REPL
    private var tabsWide: Boolean? = null
    private var rebuildingTabs = false

    /** The input that last had focus, for the strip and extra keys on wide screens. */
    private var lastInput: InputTarget? = null

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            folder.setTree(uri)
            editor.showFiles()
        }
    }

    private val pickFirmware = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadFirmware(uri)
    }

    private val poll = object : Runnable {
        override fun run() {
            console.poll()
            vm.readLog()?.let { console.appendChipMessages(it) }
            updateTitle()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val registerRefresh = object : Runnable {
        override fun run() {
            if (screen == Screen.REGISTERS) registers.refresh()
            handler.postDelayed(this, REGISTERS_MS)
        }
    }

    private val scopeRefresh = object : Runnable {
        override fun run() {
            scopeCtl.poll(screen == Screen.SCOPE)
            handler.postDelayed(this, if (screen == Screen.SCOPE) SCOPE_MS else SCOPE_IDLE_MS)
        }
    }

    /** The tallest bar along the bottom of the shown panes (the Console's input line, the Editor's buttons) */
    private fun bottomBars(): Int = listOf(
        findViewById<View>(R.id.input).parent as View,
        findViewById<View>(R.id.editorSend).parent.parent as View,
    ).filter { it.isShown }.maxOfOrNull { it.height } ?: 0

    private val boardRefresh = object : Runnable {
        override fun run() {
            boardCtl.refresh(screen == Screen.BOARD, screen != Screen.BOARD, bottomBars())
            handler.postDelayed(this, BOARD_MS)
        }
    }

    private val autosave = object : Runnable {
        override fun run() {
            Thread { vm.saveFlash() }.start()
            handler.postDelayed(this, AUTOSAVE_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        // The input line is not a login form: keep password managers away.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS

        vm = VmHost.get(this)
        folder = ProjectFolder(this)
        sessionStore = SessionStore(this)
        settings = Settings(this)

        strip = SuggestionStrip(this, findViewById<HorizontalScrollView>(R.id.suggestionScroll), findViewById(R.id.suggestions))
        console = ConsoleController(
            this, vm,
            onInputChanged = { updateStrip() },
            onDictionaryChanged = { editor.scheduleHighlight(); updateStrip() },
        )
        editor = EditorController(
            this, vm, folder,
            onInputChanged = { updateStrip() },
            onSent = { console.markDictionaryStale() },
            pickFolder = { pickFolder.launch(folder.treeUri) },
        )
        registers = RegistersController(this, vm)
        boardCtl = BoardController(this, vm, settings) { file -> openInEditor(file) }
        scopeCtl = ScopeController(this, vm)
        registers.onPausedChanged = { console.markDictionaryStale() }
        ExtraKeys(findViewById<LinearLayout>(R.id.extraKeys)) { activeInput() }

        tabs = findViewById(R.id.tabs)
        editorPane = findViewById(R.id.editorPane)
        rightPane = findViewById(R.id.rightPane)
        replPane = findViewById(R.id.replPane)
        registersPane = findViewById(R.id.registersPane)
        boardPane = findViewById(R.id.boardPane)
        scopePane = findViewById(R.id.scopePane)
        divider = findViewById(R.id.paneDivider)
        extraKeysScroll = findViewById(R.id.extraKeysScroll)
        title = findViewById(R.id.title)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        findViewById<Button>(R.id.reset).apply {
            setOnClickListener {
                vm.reset()
                console.markDictionaryStale()
            }
            setOnLongClickListener {
                powerCycle()
                true
            }
        }
        findViewById<ImageButton>(R.id.menu).setOnClickListener { showMenu(it) }

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (rebuildingTabs) return
                screen = tab.tag as Screen
                applyLayout()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // Track which input was used last (both are visible on wide screens).
        val trackFocus = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                lastInput = if (v === editor.editor) editor else console
                updateStrip()
            }
        }
        editor.editor.onFocusChangeListener = trackFocus
        console.input.onFocusChangeListener = trackFocus
        console.rawInput.onFocusChangeListener = trackFocus

        // Restore the last session.
        val session = sessionStore.load()
        if (savedInstanceState == null) {
            val text = sessionStore.loadConsole()
            if (text.isNotEmpty()) console.appendText(text)
        } else {
            savedInstanceState.getCharSequence(KEY_OUTPUT)?.let { console.appendText(it.toString()) }
        }
        if (session.rawMode) console.setRawMode(true)
        screen = runCatching { Screen.valueOf(session.screen) }.getOrDefault(Screen.REPL)
        editor.restore(session.files, session.activeFile, sessionStore.firstStart)
        console.scrollToEnd()
        if (vm.stellarisUpdateAvailable && savedInstanceState == null) askToUpdateStellaris()

        applyLayout()
    }

    private fun isWide(): Boolean = resources.configuration.screenWidthDp >= WIDE_DP

    /** Tabs: Console, Editor, Registers on phones; without Editor on wide screens. */
    private fun rebuildTabs(wide: Boolean) {
        if (tabsWide == wide) return
        tabsWide = wide
        rebuildingTabs = true
        tabs.removeAllTabs()
        val order = if (wide) listOf(Screen.REPL, Screen.BOARD, Screen.SCOPE, Screen.REGISTERS)
        else listOf(Screen.REPL, Screen.EDITOR, Screen.BOARD, Screen.SCOPE, Screen.REGISTERS)
        for (s in order) {
            val label = when (s) {
                Screen.REPL -> R.string.tab_repl
                Screen.EDITOR -> R.string.tab_editor
                Screen.BOARD -> R.string.tab_board
                Screen.SCOPE -> R.string.tab_scope
                Screen.REGISTERS -> R.string.tab_registers
            }
            tabs.addTab(tabs.newTab().setText(label).setTag(s), false)
        }
        rebuildingTabs = false
    }

    private fun applyLayout() {
        val wide = isWide()
        rebuildTabs(wide)
        if (wide && screen == Screen.EDITOR) screen = Screen.REPL
        rebuildingTabs = true
        for (i in 0 until tabs.tabCount) {
            val t = tabs.getTabAt(i) ?: continue
            if (t.tag == screen && !t.isSelected) t.select()
        }
        rebuildingTabs = false

        editorPane.visibility = if (wide || screen == Screen.EDITOR) View.VISIBLE else View.GONE
        divider.visibility = if (wide) View.VISIBLE else View.GONE
        rightPane.visibility = if (wide || screen != Screen.EDITOR) View.VISIBLE else View.GONE
        replPane.visibility = if (screen == Screen.REGISTERS || screen == Screen.BOARD || screen == Screen.SCOPE) View.GONE else View.VISIBLE
        scopePane.visibility = if (screen == Screen.SCOPE) View.VISIBLE else View.GONE
        registersPane.visibility = if (screen == Screen.REGISTERS) View.VISIBLE else View.GONE
        boardPane.visibility = if (screen == Screen.BOARD) View.VISIBLE else View.GONE

        if (!wide) {
            when (screen) {
                Screen.REPL -> console.focusInput()
                Screen.EDITOR -> editor.editor.requestFocus()
                Screen.REGISTERS, Screen.BOARD, Screen.SCOPE -> getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(rightPane.windowToken, 0)
            }
        }
        if (screen == Screen.REGISTERS && started) registers.refresh()
        updateStrip()
    }

    /** Where the suggestion strip and the extra keys type into. */
    private fun activeInput(): InputTarget? {
        if (!isWide()) {
            return when (screen) {
                Screen.REPL -> console
                Screen.EDITOR -> editor
                Screen.REGISTERS, Screen.BOARD, Screen.SCOPE -> null
            }
        }
        if (editor.editor.hasFocus()) return editor
        if (screen == Screen.REGISTERS || screen == Screen.BOARD || screen == Screen.SCOPE) return editor
        return lastInput ?: console
    }

    private fun updateStrip() {
        val target = activeInput()
        strip.update(target)
        extraKeysScroll.visibility = if (target == null) View.GONE else View.VISIBLE
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
    }

    private fun powerCycle() {
        vm.powerCycle()
        console.markDictionaryStale()
        Toast.makeText(this, R.string.power_cycled, Toast.LENGTH_SHORT).show()
    }

    /** Opens a file of the app's own (wiring.fs) in the editor. */
    private fun openInEditor(file: java.io.File) {
        editor.openUri(android.net.Uri.fromFile(file))
        if (!isWide()) { screen = Screen.EDITOR; applyLayout() }
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menuInflater.inflate(R.menu.console, menu.menu)
        menu.menu.findItem(R.id.raw_mode).isChecked = console.rawMode
        menu.menu.findItem(R.id.update_stellaris).isVisible = vm.stellarisUpdateAvailable
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.projects -> showProjects()
                R.id.load_firmware -> pickFirmware.launch(arrayOf("*/*"))
                R.id.restore_stellaris -> askToRestoreStellaris()
                R.id.power_cycle -> powerCycle()
                R.id.raw_mode -> console.setRawMode(!console.rawMode)
                R.id.copy_all -> console.copyAll()
                R.id.clear_screen -> console.clear()
                R.id.project_folder -> pickFolder.launch(folder.treeUri)
                R.id.update_stellaris -> askToUpdateStellaris()
                R.id.settings -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        menu.show()
    }

    private fun updateTitle() {
        val name = getString(R.string.title_format, vm.project.name)
        title.text = if (vm.isUserPaused) getString(R.string.paused_suffix, name) else name
    }

    // ------------------------------------------------------------------
    // Projects and firmware

    private fun note(text: String) {
        console.appendChipMessages(text + "\n")
        console.scrollToEnd()
    }

    private fun showProjects() {
        val names = vm.projects.list()
        val current = names.indexOf(vm.project.name)
        var chosen = current
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.projects)
            .setSingleChoiceItems(names.toTypedArray(), current) { _, which -> chosen = which }
            .setPositiveButton(R.string.open) { _, _ ->
                val name = names.getOrNull(chosen) ?: return@setPositiveButton
                if (name != vm.project.name) Thread {
                    vm.openProject(name)
                    runOnUiThread { console.markDictionaryStale(); boardCtl.reload(); note(getString(R.string.project_opened, name)) }
                }.start()
            }
            .setNeutralButton(R.string.new_project) { _, _ -> askNewProject() }
            .setNegativeButton(R.string.delete) { _, _ ->
                val name = names.getOrNull(chosen) ?: return@setNegativeButton
                askDeleteProject(name)
            }
            .create()
        dialog.show()
    }

    private fun askNewProject() {
        val input = EditText(this).apply { setSingleLine(); hint = getString(R.string.project_name) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_project)
            .setMessage(R.string.new_project_message)
            .setView(input)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = input.text.toString().trim()
                val error = vm.projects.checkNewName(name)
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                Thread {
                    vm.createProject(name)
                    runOnUiThread { console.markDictionaryStale(); boardCtl.reload(); note(getString(R.string.project_created, name)) }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askDeleteProject(name: String) {
        if (name == vm.project.name) {
            Toast.makeText(this, R.string.cannot_delete_open_project, Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_question, name))
            .setMessage(R.string.delete_project_message)
            .setPositiveButton(R.string.delete) { _, _ -> vm.deleteProject(name) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "firmware"

    private fun loadFirmware(uri: Uri) {
        Thread {
            val name = displayName(uri)
            val result = try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw FirmwareException(getString(R.string.cannot_open, name))
                Result.success(Firmware.parse(bytes))
            } catch (e: FirmwareException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(FirmwareException(getString(R.string.cannot_open, name)))
            }
            runOnUiThread {
                result.onFailure {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.firmware_rejected, name))
                        .setMessage(it.message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                result.onSuccess { image ->
                    MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.firmware_load_question, name))
                        .setMessage(getString(R.string.firmware_load_message, vm.project.name, image.format.name, image.bytesUsed))
                        .setPositiveButton(R.string.load) { _, _ ->
                            Thread {
                                vm.loadFirmware(image, name)
                                runOnUiThread { console.markDictionaryStale(); note(getString(R.string.firmware_loaded, name)) }
                            }.start()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }.start()
    }

    private fun askToRestoreStellaris() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_stellaris)
            .setMessage(getString(R.string.restore_stellaris_message, vm.stellaris.name, vm.project.name))
            .setPositiveButton(R.string.restore) { _, _ ->
                Thread {
                    vm.restoreStellaris()
                    runOnUiThread { console.markDictionaryStale(); note(getString(R.string.stellaris_restored, vm.stellaris.name)) }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * An app update brought a new Mecrisp-Stellaris, but words compiled to
     * flash belong to the old one. Switching erases them, so ask.
     */
    private fun askToUpdateStellaris() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_stellaris_title)
            .setMessage(getString(R.string.update_stellaris_message, vm.stellaris.name))
            .setPositiveButton(R.string.switch_core_now) { _, _ ->
                Thread {
                    vm.updateStellaris()
                    runOnUiThread { console.markDictionaryStale() }
                }.start()
            }
            .setNegativeButton(R.string.switch_core_later) { _, _ ->
                note(getString(R.string.core_update_notice))
            }
            .show()
    }

    // ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val inEditor = editor.editor.hasFocus()
            if (inEditor && editor.onKeyShortcut(event)) return true
            if (event.isCtrlPressed) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_L -> { console.clear(); return true }
                    KeyEvent.KEYCODE_SPACE -> { activeInput()?.let { Completion.tab(it) }; return true }
                }
            }
            if (event.keyCode == KeyEvent.KEYCODE_TAB && !event.isCtrlPressed) {
                val target = activeInput()
                if (target != null) {
                    if (inEditor && target.currentToken().isEmpty()) target.insertText("  ")
                    else Completion.tab(target)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        applySettings()
        started = true
        vm.setForeground(true)
        handler.post(poll)
        handler.post(registerRefresh)
        handler.post(boardRefresh)
        handler.post(scopeRefresh)
        boardCtl.reload()
        handler.postDelayed(autosave, AUTOSAVE_MS)
    }

    /** Settings may have changed on the Settings screen. */
    private fun applySettings() {
        val sp = settings.textSizeSp.toFloat()
        editor.editor.setTextSizeSp(sp)
        editor.editor.setLineWrap(settings.lineWrap)
        console.setTextSizeSp(sp)
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() {
        started = false
        handler.removeCallbacks(poll)
        handler.removeCallbacks(registerRefresh)
        handler.removeCallbacks(boardRefresh)
        handler.removeCallbacks(scopeRefresh)
        handler.removeCallbacks(autosave)
        vm.setForeground(false)
        console.history.save()
        editor.saveAll()
        sessionStore.save(
            SessionStore.Session(
                files = editor.sessionFiles(),
                activeFile = editor.activeIndex().coerceAtLeast(0),
                screen = screen.name,
                rawMode = console.rawMode,
            )
        )
        sessionStore.saveConsole(console.savedText())
        Thread { vm.saveFlash() }.start()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putCharSequence(KEY_OUTPUT, console.savedText())
    }

    companion object {
        private const val POLL_MS = 30L
        private const val BOARD_MS = 50L
        private const val SCOPE_MS = 40L
        private const val SCOPE_IDLE_MS = 200L
        private const val REGISTERS_MS = 100L
        private const val AUTOSAVE_MS = 5000L
        private const val WIDE_DP = 600
        private const val KEY_OUTPUT = "output"
    }
}
