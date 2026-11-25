/*
 * Solderless - owns the one emulated board of the app, the current
 * project and its flash image.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.vm

import io.github.leukoplast1980.solderless.board.Board as Breadboard
import io.github.leukoplast1980.solderless.board.ChipPins
import io.github.leukoplast1980.solderless.chip.AccessLog
import io.github.leukoplast1980.solderless.chip.RegisterMap
import io.github.leukoplast1980.solderless.board.Recommender
import io.github.leukoplast1980.solderless.board.Wiring
import io.github.leukoplast1980.solderless.board.PartType
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

    /** The memory parts' content counts (NativeVm.deviceGen) as last saved (declared before init, which loads the board) */
    private val memorySums = HashMap<Int, Int>()

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

    private val appContext = context.applicationContext

    /** Every register and field, for the Chip tab: null until [loadRegisterMap] has read it */
    @Volatile var registerMap: RegisterMap? = null
        private set

    /** Register writes and chip messages, for the Chip tab's Log (UI thread only) */
    val accessLog = AccessLog()
    private val accessBuf = IntArray(1 + 12 * 1024)

    /** Takes the register writes since the last call into [accessLog] */
    fun pollAccessLog() {
        val n = NativeVm.accessRead(handle, accessBuf)
        accessLog.addWrites(accessBuf, n)
    }

    /** Reads the register descriptions (half a megabyte of JSON): call off the UI thread */
    fun loadRegisterMap(): RegisterMap = registerMap ?: synchronized(this) {
        registerMap ?: RegisterMap(appContext.assets.open("chip/g431rb-registers.json").use { it.reader().readText() })
            .also { registerMap = it }
    }

    /** Plays the board's piezo and speaker (created when first needed) */
    private var audioOut: AudioOut? = null

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
        NativeVm.setFaultBreak(handle, true)
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

    /** Writes the flash image to the project if it changed, and the contents of memory parts. */
    @Synchronized
    fun saveFlash() {
        saveDeviceMemories()
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

    /** Sound plays while the app is in front and the board has a piezo or speaker. */
    private fun updateAudio() {
        val wanted = foreground && board.parts.any {
            it.type == io.github.leukoplast1980.solderless.board.PartType.PIEZO ||
                it.type == io.github.leukoplast1980.solderless.board.PartType.SPEAKER
        }
        if (wanted) (audioOut ?: AudioOut(handle).also { audioOut = it }).start() else audioOut?.stop()
    }

    /** Pause button of the Registers view. */
    @Synchronized
    fun setUserPaused(paused: Boolean) {
        userPaused = paused
        faultPaused = false
        NativeVm.pause(handle, !foreground || userPaused)
    }

    /** Paused because of a fault, not by Pause */
    var faultPaused = false
        @Synchronized get
        private set

    val isUserPaused: Boolean @Synchronized get() = userPaused

    /**
     * True once after the chip stopped itself at a fault (as a debugger
     * would, before Stellaris' handler prints and retries without end): it
     * stays paused like after Pause, until Resume, Step or Reset.
     */
    @Synchronized
    fun stoppedAtFault(): Boolean {
        if (!NativeVm.faultStopped(handle)) return false
        userPaused = true
        faultPaused = true
        return true
    }

    // ------------------------------------------------------------------
    // The breadboard

    private fun boardFile() = File(projects.dir(project.name), "board.json")

    /** wiring.fs of the current project: rewritten whenever the board changes */
    fun wiringFile() = File(projects.dir(project.name), "wiring.fs")

    fun recommender() = Recommender(chip, project.board.hseHz != 0, project.board.lse)

    private fun loadBoard() {
        board = runCatching { Breadboard.fromJson(boardFile().readText()) }.getOrDefault(Breadboard())
        NativeVm.boardSet(handle, board.attachments(chip))
        memorySums.clear()
        for (p in board.parts) if (p.type == PartType.FLASH) loadDeviceMemory(p)
        for (p in board.parts) sendSensor(p)
        if (!wiringFile().isFile && board.parts.isNotEmpty()) writeWiring()
        updateAudio()
    }

    private fun writeWiring() {
        runCatching { wiringFile().writeText(Wiring.wiringFs(board, chip, project.name)) }
            .onFailure { Log.w(TAG, "cannot write wiring.fs", it) }
    }

    /** Replaces the board: saved with the project, wiring.fs rewritten, the chip sees it at once. */
    @Synchronized fun setBoard(b: Breadboard) {
        val before = board
        board = b
        runCatching { boardFile().writeText(b.toJson()) }.onFailure { Log.w(TAG, "cannot save the board", it) }
        writeWiring()
        NativeVm.boardSet(handle, b.attachments(chip))
        // A new flash part starts from its saved contents; a removed one's file goes with it.
        // One whose size changed is a new chip: the emulator starts it erased.
        for (p in b.parts) if (p.type == PartType.FLASH) {
            val old = before.parts.firstOrNull { it.id == p.id && it.type == PartType.FLASH }
            if (old == null) loadDeviceMemory(p)
            else if (old.flashMbit != p.flashMbit) { memoryFile(p.id).delete(); memorySums.remove(p.id) }
        }
        for (p in before.parts) if (p.type == PartType.FLASH && b.parts.none { it.id == p.id && it.type == PartType.FLASH }) {
            memoryFile(p.id).delete(); memorySums.remove(p.id)
        }
        for (p in b.parts) sendSensor(p)
        updateAudio()
    }

    /** A sensor's settings to the emulated part */
    fun sendSensor(p: io.github.leukoplast1980.solderless.board.Part) {
        when (p.type) {
            PartType.BARO -> {
                NativeVm.deviceInput(handle, p.id, "pressure_pa", p.pressureHpa * 100.0)
                NativeVm.deviceInput(handle, p.id, "temp_c", p.tempC)
            }
            PartType.CO2 -> {
                NativeVm.deviceInput(handle, p.id, "co2_ppm", p.co2Ppm.toDouble())
                NativeVm.deviceInput(handle, p.id, "temp_c", p.tempC)
                NativeVm.deviceInput(handle, p.id, "rh", p.rhPct.toDouble())
            }
            else -> {}
        }
    }

    /** A breath on the CO2 sensor: it jumps to about 3000 ppm, then settles back */
    fun breathe(partId: Int) = NativeVm.deviceInput(handle, partId, "breath", 1.0)

    /** The phone's barometer, for a pressure sensor that follows it */
    fun setPressure(partId: Int, hpa: Double) = NativeVm.deviceInput(handle, partId, "pressure_pa", hpa * 100.0)

    // ------------------------------------------------------------------
    // Memory parts (W25Q): their contents are kept with the project, as
    // the 4 KB sectors that are not all FF

    private fun memoryFile(partId: Int) = File(projects.dir(project.name), "flash-part$partId.bin")

    /** Size in bytes of a memory part, 0 if it is none */
    fun memorySize(p: io.github.leukoplast1980.solderless.board.Part): Int =
        if (p.type == PartType.FLASH) p.flashMbit * 131072 else 0

    fun readMemory(partId: Int, addr: Int, len: Int): ByteArray {
        val b = ByteArray(len)
        val n = NativeVm.deviceMem(handle, partId, addr, b, len, false)
        return if (n == len) b else b.copyOf(maxOf(0, n))
    }

    fun writeMemory(partId: Int, addr: Int, data: ByteArray): Int = NativeVm.deviceMem(handle, partId, addr, data, data.size, true)

    private fun loadDeviceMemory(p: io.github.leukoplast1980.solderless.board.Part) {
        val f = memoryFile(p.id)
        if (!f.isFile) return
        runCatching {
            java.io.DataInputStream(f.inputStream().buffered()).use { inp ->
                if (inp.readInt() != MEM_MAGIC) return
                val sector = ByteArray(4096)
                while (inp.available() > 0) {
                    val at = inp.readInt()
                    inp.readFully(sector)
                    if (at in 0 until memorySize(p)) writeMemory(p.id, at, sector)
                }
            }
            memorySums[p.id] = NativeVm.deviceGen(handle, p.id)
        }.onFailure { Log.w(TAG, "cannot read ${f.name}", it) }
    }

    /** Every few seconds: only the memory parts whose contents changed (a counter, not a scan of megabytes) */
    private fun saveDeviceMemories() {
        for (p in board.parts) {
            if (p.type != PartType.FLASH) continue
            val sum = NativeVm.deviceGen(handle, p.id)
            if (memorySums[p.id] == sum) continue
            val f = memoryFile(p.id)
            runCatching {
                val tmp = File(f.path + ".tmp")
                java.io.DataOutputStream(tmp.outputStream().buffered()).use { out ->
                    out.writeInt(MEM_MAGIC)
                    val chunk = 1 shl 16
                    var a = 0
                    while (a < memorySize(p)) {
                        val b = readMemory(p.id, a, chunk)
                        for (s in 0 until b.size / 4096) {
                            if ((0 until 4096).all { b[s * 4096 + it] == 0xFF.toByte() }) continue
                            out.writeInt(a + s * 4096)
                            out.write(b, s * 4096, 4096)
                        }
                        a += chunk
                    }
                }
                if (!tmp.renameTo(f)) throw IOException("rename")
                memorySums[p.id] = sum
            }.onFailure { Log.w(TAG, "cannot save ${f.name}", it) }
        }
    }

    /** A button pressed (1) or released (0), or a pot's wiper in per mille */
    fun boardInput(partId: Int, value: Int) = NativeVm.boardInput(handle, partId, value)

    fun readBoard(into: BoardState) = NativeVm.boardRead(handle, into.raw)

    /** A bus part's pixels and state text (null if it is not on the board) */
    fun readDevice(partId: Int, pixels: ByteArray): String? = NativeVm.deviceRead(handle, partId, pixels)

    fun step() = NativeVm.step(handle)
    /** NRST; a chip stopped at a fault runs again */
    fun reset() {
        if (faultPaused) setUserPaused(false)
        NativeVm.reset(handle)
    }

    fun powerCycle() {
        if (faultPaused) setUserPaused(false)
        NativeVm.powerCycle(handle)
    }

    fun snapshot(): Snapshot = Snapshot(NativeVm.snapshot(handle))
    fun readMemory(address: Int, length: Int): ByteArray? = NativeVm.readMemory(handle, address, length)
    fun peekWords(addresses: IntArray): IntArray = NativeVm.peekWords(handle, addresses)
    /** For the Registers view: a write as the CPU would make it. True if it went through. */
    fun pokeWord(address: Int, value: Int): Boolean = NativeVm.pokeWord(handle, address, value) == 0

    /** An encoder turned by some detents, clockwise when positive */
    fun boardTurn(partId: Int, steps: Int) = NativeVm.boardTurn(handle, partId, steps)
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
        private const val MEM_MAGIC = 0x534C464C   // "SLFL": a memory part's sectors

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
