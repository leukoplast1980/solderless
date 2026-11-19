/*
 * Solderless - owns the one emulated board of the app, the current
 * project and its flash image.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.vm

import io.github.leukoplast1980.solderless.board.Board as Breadboard
import io.github.leukoplast1980.solderless.board.ChipPins
import io.github.leukoplast1980.solderless.board.Recommender
import io.github.leukoplast1980.solderless.board.Wiring
import java.io.File
import android.content.Context
import android.util.Log
import io.github.leukoplast1980.solderless.files.Projects
import io.github.leukoplast1980.solderless.forth.Dictionary
import io.github.leukoplast1980.solderless.forth.LineResult
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * App-wide host of the emulator. It survives activity recreation; the
 * activity pauses it when the app leaves the foreground.
 *
 * Each project keeps its own flash image. A project that runs the bundled
 * Mecrisp-Stellaris remembers which version it started from: if an app
 * update brings a different version and nothing was compiled to flash, the
 * new one is used at once; otherwise [stellarisUpdateAvailable] is set until
 * the user switches with [updateStellaris].
 */
class VmHost private constructor(context: Context) {

    /** Facts about the bundled Mecrisp-Stellaris image (assets/firmware/stellaris.json). */
    class Stellaris(val image: ByteArray, json: JSONObject) {
        val name: String = json.getString("name")
        val sha256: String = json.getString("sha256")
        val dictionaryRoot: Int = decode(json.getString("dictionaryRoot"))
        val flashDictionaryStart: Int = decode(json.getString("flashDictionaryStart"))
        val dataStackBase: Int = decode(json.getString("dataStackBase"))
        val returnStackBase: Int = decode(json.getString("returnStackBase"))

        private fun decode(s: String): Int = java.lang.Long.decode(s).toInt()
    }

    val projects = Projects(context)
    val stellaris: Stellaris
    val handle: Long

    @Volatile var project: Projects.Project
        private set

    @Volatile var stellarisUpdateAvailable = false
        private set

    /** The words Forth could find at the last [refreshDictionary]. */
    @Volatile var dictionary: Dictionary = Dictionary.EMPTY
        private set

    private var foreground = false
    private var userPaused = false

    /** The chip's pins and functions, for the Board tab and the recommender */
    val chip: ChipPins

    /** The breadboard of the current project */
    @Volatile var board: Breadboard = Breadboard()
        private set

    init {
        val assets = context.assets
        stellaris = Stellaris(
            assets.open("firmware/stellaris.bin").use { it.readBytes() },
            assets.open("firmware/stellaris.json").use { JSONObject(it.reader().readText()) },
        )
        handle = NativeVm.create(false, 0, false)
        chip = ChipPins(assets.open("chip/g431rb-pins.json").use { it.reader().readText() })

        val name = projects.current ?: projects.list().firstOrNull()
        project = name?.let { projects.load(it) } ?: projects.create(
            Projects.FIRST_PROJECT, stellarisInfo(), FlashImage.pad(stellaris.image),
        )
        projects.current = project.name
        loadProjectFlash()
        loadBoard()

        Thread({ NativeVm.run(handle) }, "chip").apply {
            isDaemon = true
            start()
        }
    }

    private fun stellarisInfo() = Projects.FirmwareInfo(Projects.KIND_STELLARIS, stellaris.name, stellaris.sha256)

    /** Loads the current project's flash into the chip, applying a Stellaris update if possible. */
    private fun loadProjectFlash() {
        var image = projects.readFlash(project.name, FlashImage.SIZE)
        stellarisUpdateAvailable = false
        if (project.firmware.isStellaris && project.firmware.sha256 != stellaris.sha256) {
            if (image == null || FlashImage.userAreaEmpty(image)) {
                image = FlashImage.pad(stellaris.image)
                project = project.copy(firmware = stellarisInfo())
                projects.save(project)
                projects.writeFlash(project.name, image)
            } else {
                stellarisUpdateAvailable = true
            }
        }
        if (image == null) {
            image = if (project.firmware.isStellaris) FlashImage.pad(stellaris.image)
            else FlashImage.pad(ByteArray(0))
        }
        NativeVm.loadFlash(handle, image)
        dictionary = Dictionary.EMPTY
    }

    /** An image whose write failed; written next time unless flash changed again. */
    private var unsaved: ByteArray? = null

    /** Writes the flash image to the project if it changed. */
    @Synchronized
    fun saveFlash() {
        val image = NativeVm.copyFlashIfDirty(handle) ?: unsaved ?: return
        try {
            projects.writeFlash(project.name, image)
            unsaved = null
        } catch (e: IOException) {
            Log.e(TAG, "cannot save flash", e)
            unsaved = image
        }
    }

    /** Stops the chip, swaps what is in flash, and powers it on again. */
    @Synchronized
    private fun restart(change: () -> Unit) {
        NativeVm.pause(handle, true)
        saveFlash()
        change()
        NativeVm.powerCycle(handle)
        NativeVm.pause(handle, !foreground || userPaused)
    }

    /** Switches to the bundled Mecrisp-Stellaris version; flash words are lost. */
    fun updateStellaris() = restart {
        val image = FlashImage.pad(stellaris.image)
        project = project.copy(firmware = stellarisInfo())
        projects.save(project)
        projects.writeFlash(project.name, image)
        unsaved = null
        NativeVm.loadFlash(handle, image)
        stellarisUpdateAvailable = false
        dictionary = Dictionary.EMPTY
    }

    /** Replaces the firmware of the current project with a parsed file. */
    fun loadFirmware(image: Firmware.Image, displayName: String) = restart {
        val sha = sha256(image.flash)
        project = project.copy(firmware = Projects.FirmwareInfo(Projects.KIND_CUSTOM, displayName, sha))
        projects.save(project)
        projects.writeFlash(project.name, image.flash)
        unsaved = null
        NativeVm.loadFlash(handle, image.flash)
        stellarisUpdateAvailable = false
        dictionary = Dictionary.EMPTY
    }

    /** Puts a fresh Mecrisp-Stellaris on the current project. */
    fun restoreStellaris() = updateStellaris()

    fun createProject(name: String) = restart {
        project = projects.create(name, stellarisInfo(), FlashImage.pad(stellaris.image))
        projects.current = project.name
        unsaved = null
        loadProjectFlash()
        loadBoard()
    }

    fun openProject(name: String) = restart {
        val p = projects.load(name) ?: return@restart
        project = p
        projects.current = p.name
        unsaved = null
        loadProjectFlash()
        loadBoard()
    }

    /** Deletes another project (not the current one). */
    fun deleteProject(name: String): Boolean = name != project.name && projects.delete(name)

    /** The activity is visible: the chip may run unless the user paused it. */
    @Synchronized
    fun setForeground(visible: Boolean) {
        foreground = visible
        NativeVm.pause(handle, !foreground || userPaused)
        updateAudio()
    }

    private val audio by lazy { AudioOut(handle) }

    /** Sound plays while the app is in front and the board has a piezo or speaker. */
    private fun updateAudio() {
        val wanted = foreground && board.parts.any {
            it.type == io.github.leukoplast1980.solderless.board.PartType.PIEZO ||
                it.type == io.github.leukoplast1980.solderless.board.PartType.SPEAKER
        }
        if (wanted) audio.start() else audio.stop()
    }

    /** Pause button of the Registers view. */
    @Synchronized
    fun setUserPaused(paused: Boolean) {
        userPaused = paused
        NativeVm.pause(handle, !foreground || userPaused)
    }

    val isUserPaused: Boolean @Synchronized get() = userPaused

    // ------------------------------------------------------------------
    // The breadboard

    private fun boardFile() = File(projects.dir(project.name), "board.json")

    /** wiring.fs of the current project: rewritten whenever the board changes */
    fun wiringFile() = File(projects.dir(project.name), "wiring.fs")

    fun recommender() = Recommender(chip, project.board.hseHz != 0, project.board.lse)

    private fun loadBoard() {
        board = runCatching { Breadboard.fromJson(boardFile().readText()) }.getOrDefault(Breadboard())
        NativeVm.boardSet(handle, board.attachments(chip))
        if (!wiringFile().isFile && board.parts.isNotEmpty()) writeWiring()
        updateAudio()
    }

    private fun writeWiring() {
        runCatching { wiringFile().writeText(Wiring.wiringFs(board, chip, project.name)) }
            .onFailure { Log.w(TAG, "cannot write wiring.fs", it) }
    }

    /** Replaces the board: saved with the project, wiring.fs rewritten, the chip sees it at once. */
    @Synchronized fun setBoard(b: Breadboard) {
        board = b
        runCatching { boardFile().writeText(b.toJson()) }.onFailure { Log.w(TAG, "cannot save the board", it) }
        writeWiring()
        NativeVm.boardSet(handle, b.attachments(chip))
        updateAudio()
    }

    /** A button pressed (1) or released (0), or a pot's wiper in per mille */
    fun boardInput(partId: Int, value: Int) = NativeVm.boardInput(handle, partId, value)

    fun readBoard(into: BoardState) = NativeVm.boardRead(handle, into.raw)

    fun step() = NativeVm.step(handle)
    fun reset() = NativeVm.reset(handle)
    fun powerCycle() = NativeVm.powerCycle(handle)

    fun snapshot(): Snapshot = Snapshot(NativeVm.snapshot(handle))
    fun readMemory(address: Int, length: Int): ByteArray? = NativeVm.readMemory(handle, address, length)
    fun peekWords(addresses: IntArray): IntArray = NativeVm.peekWords(handle, addresses)
    fun waitingForKey(): Boolean = NativeVm.waitingForKey(handle)
    fun readLog(): String? = NativeVm.readLog(handle)

    /** Re-reads Mecrisp-Stellaris' dictionary from guest memory. Call off the UI thread. */
    fun refreshDictionary(): Dictionary {
        if (!project.firmware.isStellaris) {
            dictionary = Dictionary.EMPTY
            return dictionary
        }
        val d = Dictionary.fromNative(NativeVm.dictionary(handle, stellaris.dictionaryRoot), stellaris.flashDictionaryStart)
        dictionary = d
        return d
    }

    /** Queues text for the chip's serial port. Returns false if the input buffer is full. */
    fun send(text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var off = 0
        var tries = 0
        while (off < bytes.size && tries < 100) {
            val n = NativeVm.uartWrite(handle, bytes, off, bytes.size - off)
            if (n == 0) { Thread.sleep(2); tries++ } else off += n
        }
        return off == bytes.size
    }

    fun read(buffer: ByteArray): Int = NativeVm.uartRead(handle, buffer)

    /**
     * Lines sent to Forth, oldest first, so the console can recognise their
     * echo and colour it. Filled from any thread; the console drains it.
     */
    val sentLines = ConcurrentLinkedQueue<String>()

    /** Sends one source line and waits for Forth's answer. Blocks: not on the UI thread. */
    fun sendLineChecked(line: String, timeoutMs: Int): LineResult {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_LINE_BYTES) return LineResult.TOO_LONG
        sentLines.add(line)
        return when (NativeVm.sendLine(handle, bytes, timeoutMs)) {
            0 -> LineResult.OK
            1 -> LineResult.ERROR
            3 -> LineResult.TOO_LONG
            else -> LineResult.TIMEOUT
        }
    }

    companion object {
        private const val TAG = "VmHost"

        /** Mecrisp-Stellaris' input buffer (Maximaleeingabe): longer lines are cut off. */
        const val MAX_LINE_BYTES = 200

        fun sha256(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        @Volatile private var instance: VmHost? = null

        fun get(context: Context): VmHost =
            instance ?: synchronized(this) {
                instance ?: VmHost(context.applicationContext).also { instance = it }
            }
    }
}
