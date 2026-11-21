/*
 * Solderless - the Scope's bus decoder: what went over an I2C or SPI bus
 * (as the emulator logged it, a byte at a time), grouped into transfers,
 * and a line saying what the part made of each one.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.ui

import io.github.leukoplast1980.solderless.board.Part
import io.github.leukoplast1980.solderless.board.PartType

/** One thing on a bus: see sl_bus_event. Times in emulated ns; pins are slots (port * 16 + bit), -1 for none. */
class BusEv(val t: Long, val dur: Long, val kind: Int, val flags: Int, val bits: Int, val part: Int, val value: Int, val miso: Int,
            val p0: Int, val p1: Int, val p2: Int, val cs: Int) {
    val end: Long get() = t + dur
    val hw: Boolean get() = flags and HW != 0
    val i2c: Boolean get() = kind in START..STOP || kind == LOST && bits in START..STOP
    /** I2C: SCL and SDA; SPI: SCK and MOSI */
    val key: String get() = (if (i2c) "i2c:" else "spi:") + p0 + ":" + p1
    val acked: Boolean get() = miso != 0
    val read: Boolean get() = flags and READ != 0
    val driven: Boolean get() = flags and DRIVEN != 0
    val mode: Int get() = flags and 3
    val lsb: Boolean get() = flags and LSB != 0

    companion object {
        const val START = 1; const val ADDR = 2; const val BYTE = 3; const val STOP = 4
        const val SPI = 5; const val LOST = 6; const val CS = 7
        const val HW = 0x10; const val READ = 0x20; const val LSB = 0x40; const val DRIVEN = 0x80
        /** 6 ints per event, as NativeVm.busRead packs them */
        const val INTS = 6

        private fun sb(v: Int) = (v and 255).let { if (it == 255) -1 else it }

        fun unpack(r: IntArray, i: Int): BusEv {
            val o = INTS * i
            val t = (r[o + 1].toLong() shl 32) or (r[o].toLong() and 0xFFFFFFFFL)
            val k = r[o + 3]; val v = r[o + 4]; val p = r[o + 5]
            val part = (k ushr 24).let { if (it >= 128) it - 256 else it }
            return BusEv(t, r[o + 2].toLong(), k and 255, (k ushr 8) and 255, (k ushr 16) and 255, part, v and 0xFFFF, v ushr 16,
                sb(p), sb(p ushr 8), sb(p ushr 16), sb(p ushr 24))
        }

        fun hex(v: Int) = "%02X".format(java.util.Locale.ROOT, v)
    }
}

/** A transfer: START to STOP, or one part's chip select low to high */
class BusTx(val events: List<BusEv>) {
    val t0: Long get() = events.first().t
    val t1: Long get() = events.maxOf { it.end }
}

object BusDecode {

    /** The bus a part is on, as BusEv.key names it; null for parts not on a bus */
    fun keyOf(p: Part, slotOf: (String) -> Int?): String? {
        fun s(i: Int) = p.pins.getOrNull(i)?.let { slotOf(it.pin) }
        return when (p.type) {
            PartType.OLED, PartType.BARO, PartType.CO2 -> "i2c:${s(0) ?: return null}:${s(1) ?: return null}"
            PartType.FLASH, PartType.MATRIX, PartType.TFT -> "spi:${s(1) ?: return null}:${s(2) ?: return null}"
            else -> null
        }
    }

    fun isBusPart(t: PartType) = t in setOf(PartType.OLED, PartType.BARO, PartType.CO2, PartType.FLASH, PartType.MATRIX, PartType.TFT)

    /** The chip's name, as the description lines start */
    fun chipName(p: Part): String = when (p.type) {
        PartType.OLED -> "OLED"
        PartType.BARO -> "BMP280"
        PartType.CO2 -> "SCD41"
        PartType.FLASH -> p.flashName
        PartType.MATRIX -> "MAX7219"
        PartType.TFT -> "ST7789"
        else -> p.type.title
    }

    /** The transfers in [ev] (one bus, in time order) between indices [from] and [to] */
    fun transfers(ev: List<BusEv>, from: Int = 0, to: Int = ev.size): List<BusTx> {
        val out = ArrayList<BusTx>()
        var cur: ArrayList<BusEv>? = null
        val spi = LinkedHashMap<Int, ArrayList<BusEv>>()    // SPI: by part, while its CS is low
        var loose: ArrayList<BusEv>? = null                 // SPI bytes with no part selected
        for (k in from until to) {
            val e = ev[k]
            when (e.kind) {
                BusEv.START, BusEv.ADDR, BusEv.BYTE -> (cur ?: ArrayList<BusEv>().also { cur = it }).add(e)
                BusEv.STOP -> { (cur ?: ArrayList()).also { it.add(e); out += BusTx(it) }; cur = null }
                BusEv.CS -> {
                    loose?.let { out += BusTx(it) }; loose = null
                    if (e.value != 0) spi.remove(e.part)?.let { if (it.size > 0) out += BusTx(it) }
                    if (e.value != 0) spi[e.part] = arrayListOf(e)
                    else spi.remove(e.part)?.let { it.add(e); out += BusTx(it) }
                }
                BusEv.SPI -> if (e.part >= 0) spi.getOrPut(e.part) { ArrayList() }.add(e)
                    else (loose ?: ArrayList<BusEv>().also { loose = it }).add(e)
                BusEv.LOST -> {
                    cur?.let { out += BusTx(it) }; cur = null
                    out += BusTx(listOf(e))
                }
            }
        }
        cur?.let { out += BusTx(it) }
        loose?.let { out += BusTx(it) }
        for (l in spi.values) if (l.isNotEmpty()) out += BusTx(l)  // still selected
        out.sortBy { it.t0 }
        return out
    }

    /** What the transfer at [at] (or the last one before it, back to [from]) did */
    fun describeAt(ev: List<BusEv>, from: Long, at: Long, parts: Map<Int, Part>): String {
        if (ev.isEmpty()) return ""
        // from a transfer's beginning a little before the window ...
        var i = ev.binarySearchBy(from) { it.t }.let { if (it >= 0) it else -it - 1 }
        var back = 0
        while (i > 0 && back < 4096) {
            i--; back++
            val e = ev[i]
            if (e.kind == BusEv.START && (i == 0 || ev[i - 1].kind == BusEv.STOP) || e.kind == BusEv.CS && e.value != 0) break
        }
        // ... to the end of the transfer going on at [at]
        var j = ev.binarySearchBy(at) { it.t }.let { if (it >= 0) it + 1 else -it - 1 }
        var fwd = 0
        while (j < ev.size && fwd < 300_000) {
            val e = ev[j]
            j++; fwd++
            if (e.kind == BusEv.STOP || e.kind == BusEv.CS && e.value == 0) break
        }
        val tx = transfers(ev, i, j).lastOrNull { it.t0 <= at && it.t1 >= from } ?: return ""
        return describe(tx, parts)
    }

    fun describe(tx: BusTx, parts: Map<Int, Part>): String {
        val e = tx.events
        if (e.size == 1 && e[0].kind == BusEv.LOST) {
            val n = e[0].miso.toLong() shl 16 or e[0].value.toLong()
            return "$n bytes too fast to decode: the scope keeps up with about a million a second"
        }
        return if (e.any { it.i2c }) i2c(e, parts) else spi(e, parts)
    }

    // ------------------------------------------------------------------
    // I2C

    private class Seg(val addr: Int, val read: Boolean, val acked: Boolean, val part: Int, val bytes: List<Int>)

    private fun segments(e: List<BusEv>): List<Seg> {
        val out = ArrayList<Seg>()
        var k = 0
        while (k < e.size) {
            val a = e[k]
            if (a.kind != BusEv.ADDR) { k++; continue }
            val bytes = ArrayList<Int>()
            k++
            while (k < e.size && e[k].kind == BusEv.BYTE) { bytes += e[k].value; k++ }
            out += Seg(a.value shr 1, a.value and 1 != 0, a.acked, a.part, bytes)
        }
        return out
    }

    private fun i2c(e: List<BusEv>, parts: Map<Int, Part>): String {
        val segs = segments(e)
        if (segs.isEmpty()) return ""
        val first = segs.first()
        if (!first.acked) return "Nobody answered at address ${BusEv.hex(first.addr)}" +
            if (first.addr >= 0x78 || first.addr < 0x08) ": a reserved address" else ""
        val p = parts[first.part]
        val name = p?.let { chipName(it) } ?: "Address ${BusEv.hex(first.addr)}"
        val what = when (p?.type) {
            PartType.OLED -> oled(segs)
            PartType.BARO -> bmp280(segs)
            PartType.CO2 -> scd41(segs)
            else -> null
        } ?: segs.joinToString(" · ") { s -> (if (s.read) "read " else "write ") + s.bytes.joinToString(" ") { BusEv.hex(it) } }
        return "$name: $what"
    }

    private val SSD1306_ARGS = mapOf(0x81 to 1, 0x8D to 1, 0x20 to 1, 0x21 to 2, 0x22 to 2, 0xA8 to 1, 0xD3 to 1, 0xD5 to 1, 0xD9 to 1,
        0xDA to 1, 0xDB to 1, 0xA3 to 2, 0x26 to 6, 0x27 to 6, 0x29 to 5, 0x2A to 5)

    private fun ssd1306(c: Int, a: List<Int>): String = when (c) {
        0xAE -> "display off"
        0xAF -> "display on"
        0x8D -> if ((a.firstOrNull() ?: 0) and 4 != 0) "charge pump on" else "charge pump off"
        0x81 -> "contrast ${a.firstOrNull() ?: 0}"
        0x20 -> "addressing " + when (a.firstOrNull()) { 0 -> "horizontal"; 1 -> "vertical"; else -> "by page" }
        0x21 -> "columns ${a.getOrElse(0) { 0 }}–${a.getOrElse(1) { 0 }}"
        0x22 -> "pages ${a.getOrElse(0) { 0 }}–${a.getOrElse(1) { 0 }}"
        0xA0, 0xA1 -> if (c == 0xA1) "columns mirrored" else "columns not mirrored"
        0xC0, 0xC8 -> if (c == 0xC8) "rows mirrored" else "rows not mirrored"
        0xA4 -> "show the RAM"
        0xA5 -> "all pixels on"
        0xA6 -> "normal"
        0xA7 -> "inverted"
        0xA8 -> "multiplex ${(a.firstOrNull() ?: 0) + 1} rows"
        0xD3 -> "offset ${a.firstOrNull() ?: 0}"
        0xD5 -> "clock"
        0xD9 -> "precharge"
        0xDA -> "COM pins"
        0xDB -> "VCOMH level"
        0x2E -> "scrolling off"
        0x2F -> "scrolling on"
        0xE3 -> "no-op"
        in 0x40..0x7F -> "start line ${c - 0x40}"
        in 0xB0..0xB7 -> "page ${c - 0xB0}"
        in 0x00..0x0F -> "column low ${c}"
        in 0x10..0x1F -> "column high ${c - 0x10}"
        else -> "?"
    }

    private fun oled(segs: List<Seg>): String? {
        val s = segs.first()
        if (s.read) {
            val b = s.bytes.firstOrNull() ?: return "status read"
            return "status ${BusEv.hex(b)}, display " + if (b and 0x40 != 0) "off" else "on"
        }
        if (s.bytes.isEmpty()) return "addressed, nothing sent"
        val ctl = s.bytes[0]
        if (ctl and 0x40 != 0) return "${s.bytes.size - 1} bytes of pixels"
        val out = ArrayList<String>()
        var k = 1
        while (k < s.bytes.size) {
            val c = s.bytes[k]
            val n = SSD1306_ARGS[c] ?: 0
            val args = s.bytes.subList(k + 1, minOf(s.bytes.size, k + 1 + n))
            out += (listOf(c) + args).joinToString(" ") { BusEv.hex(it) } + ", " + ssd1306(c, args)
            k += 1 + n
        }
        return if (out.size == 1) "command " + out[0] else "commands " + out.joinToString(" · ")
    }

    private fun bmpReg(r: Int): String = when (r) {
        0xD0 -> "id"; 0xE0 -> "reset"; 0xF3 -> "status"; 0xF4 -> "ctrl_meas"; 0xF5 -> "config"
        0xF7 -> "press_msb"; 0xF8 -> "press_lsb"; 0xF9 -> "press_xlsb"; 0xFA -> "temp_msb"; 0xFB -> "temp_lsb"; 0xFC -> "temp_xlsb"
        in 0x88..0xA1 -> "calibration"
        else -> "register"
    }

    private fun bmp280(segs: List<Seg>): String? {
        val w = segs.firstOrNull { !it.read }
        val r = segs.firstOrNull { it.read }
        if (r != null && w != null && w.bytes.isNotEmpty()) {
            val reg = w.bytes[0]
            val vals = r.bytes.joinToString(" ") { BusEv.hex(it) }
            return when {
                reg == 0xF7 && r.bytes.size >= 6 -> "read F7…FC, pressure and temperature"
                reg in 0x88..0xA1 && r.bytes.size > 2 -> "read ${r.bytes.size} bytes of calibration from ${BusEv.hex(reg)}"
                r.bytes.size == 1 -> "${BusEv.hex(reg)} ${bmpReg(reg)} → $vals" + if (reg == 0xD0 && r.bytes[0] == 0x58) ", a BMP280" else ""
                else -> "read ${r.bytes.size} bytes from ${BusEv.hex(reg)} ${bmpReg(reg)}"
            }
        }
        if (w == null) return null
        if (w.bytes.size == 1) return "register pointer ← ${BusEv.hex(w.bytes[0])} ${bmpReg(w.bytes[0])}"
        return w.bytes.chunked(2).joinToString(" · ") { pr ->
            val reg = pr[0]; val v = pr.getOrNull(1) ?: return@joinToString BusEv.hex(reg)
            "${BusEv.hex(reg)} ${bmpReg(reg)} ← ${BusEv.hex(v)}" + when (reg) {
                0xE0 -> if (v == 0xB6) ", reset" else ""
                0xF4 -> ", " + when (v and 3) { 0 -> "sleep"; 3 -> "normal mode"; else -> "one measurement" }
                else -> ""
            }
        }
    }

    private val SCD41_CMDS = mapOf(0x21B1 to "start periodic measurement", 0xEC05 to "read measurement", 0x3F86 to "stop periodic measurement",
        0x3682 to "get serial number", 0xE4B8 to "get data ready status", 0x219D to "measure once", 0x2196 to "measure humidity and temperature once",
        0x36F6 to "reinit", 0x3639 to "self test", 0x241D to "set temperature offset", 0x2318 to "get temperature offset",
        0x2427 to "set altitude", 0x2322 to "get altitude", 0xE000 to "set ambient pressure", 0x362F to "forced recalibration",
        0x2416 to "set automatic self calibration", 0x2313 to "get automatic self calibration", 0x21AC to "start low power periodic measurement",
        0x3615 to "persist settings", 0x3632 to "factory reset", 0x36E0 to "power down", 0x36F6 to "wake up")

    private fun crc8(a: Int, b: Int): Int {
        var c = 0xFF
        for (x in intArrayOf(a, b)) { c = c xor x; repeat(8) { c = if (c and 0x80 != 0) (c shl 1) xor 0x31 else c shl 1; c = c and 0xFF } }
        return c
    }

    private fun scd41(segs: List<Seg>): String? {
        val s = segs.first()
        if (s.read) {
            val words = s.bytes.chunked(3)
            val ok = words.all { it.size == 3 && crc8(it[0], it[1]) == it[2] }
            return "read ${words.size} word${if (words.size == 1) "" else "s"}" + (if (words.isEmpty()) "" else if (ok) ", CRCs ✓" else ", a CRC is wrong")
        }
        if (s.bytes.size < 2) return "addressed, nothing sent"
        val cmd = s.bytes[0] shl 8 or s.bytes[1]
        val name = SCD41_CMDS[cmd] ?: "unknown"
        val args = s.bytes.drop(2).chunked(3)
        val arg = if (args.isEmpty()) "" else " ← " + args.joinToString(" ") { w ->
            "%04X".format(java.util.Locale.ROOT, (w[0] shl 8) or w.getOrElse(1) { 0 }) + if (w.size == 3 && crc8(w[0], w[1]) == w[2]) "" else " (CRC wrong)"
        }
        return "command %04X, %s%s".format(java.util.Locale.ROOT, cmd, name, arg)
    }

    // ------------------------------------------------------------------
    // SPI

    private fun spi(e: List<BusEv>, parts: Map<Int, Part>): String {
        val bytes = e.filter { it.kind == BusEv.SPI }
        val part = e.firstOrNull { it.part >= 0 }?.part ?: -1
        val p = parts[part]
        if (p == null || part < 0) return if (bytes.isEmpty()) "" else "SPI: ${bytes.size} bytes, no chip select low"
        if (bytes.isEmpty()) return "${chipName(p)}: selected, nothing sent"
        val what = when (p.type) {
            PartType.FLASH -> w25q(bytes)
            PartType.MATRIX -> max7219(bytes, p.matrixChips)
            PartType.TFT -> st7789(bytes)
            else -> bytes.joinToString(" ") { BusEv.hex(it.value) }
        }
        return "${chipName(p)}: $what"
    }

    private fun addr24(b: List<BusEv>, k: Int) =
        if (b.size >= k + 3) "%06X".format(java.util.Locale.ROOT, b[k].value shl 16 or (b[k + 1].value shl 8) or b[k + 2].value) else "?"

    private fun w25q(b: List<BusEv>): String {
        val c = b[0].value
        fun back(from: Int) = b.drop(from).filter { it.driven }.joinToString(" ") { BusEv.hex(it.miso) }
        fun data(from: Int) = (b.size - from).coerceAtLeast(0)
        return when (c) {
            0x9F -> "9F JEDEC ID → " + back(1)
            0x90 -> "90 manufacturer and device → " + back(4)
            0x4B -> "4B unique ID → " + back(5)
            0xAB -> "AB release from power-down" + if (b.size > 4) " → " + back(4) else ""
            0x03 -> "03 read at ${addr24(b, 1)}, ${data(4)} bytes"
            0x0B -> "0B fast read at ${addr24(b, 1)}, ${data(5)} bytes"
            0x02 -> "02 page program at ${addr24(b, 1)}, ${data(4)} bytes"
            0x06 -> "06 write enable"
            0x04 -> "04 write disable"
            0x05, 0x35, 0x15 -> "%02X status %d → %s".format(java.util.Locale.ROOT, c, if (c == 5) 1 else if (c == 0x35) 2 else 3, back(1)) +
                if (c == 5 && b.size > 1 && b[1].driven) (if (b[1].miso and 1 != 0) ", busy" else ", ready") + (if (b[1].miso and 2 != 0) ", write enabled" else "") else ""
            0x01, 0x31, 0x11 -> "%02X write status ← %s".format(java.util.Locale.ROOT, c, b.drop(1).joinToString(" ") { BusEv.hex(it.value) })
            0x20 -> "20 erase the 4 KB sector at ${addr24(b, 1)}"
            0x52 -> "52 erase the 32 KB block at ${addr24(b, 1)}"
            0xD8 -> "D8 erase the 64 KB block at ${addr24(b, 1)}"
            0xC7, 0x60 -> "%02X erase the whole chip".format(java.util.Locale.ROOT, c)
            0xB9 -> "B9 power down"
            0x66 -> "66 enable reset"
            0x99 -> "99 reset"
            else -> "${BusEv.hex(c)}, not a command it knows"
        }
    }

    private fun max7219Reg(r: Int, v: Int): String = when (r) {
        0x0 -> "no-op"
        in 0x1..0x8 -> "digit ${r - 1} ← ${BusEv.hex(v)}"
        0x9 -> "decode mode ← ${BusEv.hex(v)}, " + if (v == 0) "no decoding" else "Code B"
        0xA -> "intensity ← ${BusEv.hex(v)}, ${(v and 15) * 2 + 1} of 32"
        0xB -> "scan limit ← ${BusEv.hex(v)}, digits 0–${v and 7}"
        0xC -> "shutdown ← ${BusEv.hex(v)}, " + if (v and 1 != 0) "normal operation" else "shut down"
        0xF -> "display test ← ${BusEv.hex(v)}, " + if (v and 1 != 0) "all on" else "off"
        else -> "register ${BusEv.hex(r)} ← ${BusEv.hex(v)}"
    }

    private fun max7219(b: List<BusEv>, chips: Int): String {
        if (b.size % 2 != 0) return "${b.size} bytes, an odd count: each chip takes two"
        val pairs = b.takeLast(2 * chips.coerceAtLeast(1)).chunked(2).map { (it[0].value and 15) to it[1].value }
        if (pairs.size == 1) return "register ${BusEv.hex(pairs[0].first)} ${max7219Reg(pairs[0].first, pairs[0].second)}"
        // the first pair shifted ends up in the chip farthest along the chain
        val n = pairs.size
        val regs = pairs.map { it.first }.distinct()
        if (regs.size == 1 && regs[0] in 1..8)
            return "digit ${regs[0] - 1} ← " + pairs.joinToString(" ") { BusEv.hex(it.second) } + " (chips ${n - 1}…0)"
        if (regs.size == 1) return "all $n: register ${BusEv.hex(regs[0])} " + max7219Reg(regs[0], pairs[0].second)
        return pairs.withIndex().joinToString(" · ") { (k, pr) -> "chip ${n - 1 - k} " + max7219Reg(pr.first, pr.second) }
    }

    private val ST7789_CMDS = mapOf(0x01 to "software reset", 0x11 to "sleep out", 0x10 to "sleep in", 0x29 to "display on", 0x28 to "display off",
        0x2A to "columns", 0x2B to "rows", 0x2C to "RAM write", 0x3C to "RAM write on", 0x36 to "memory access", 0x3A to "colour mode",
        0x21 to "inversion on", 0x20 to "inversion off", 0x13 to "normal mode", 0x12 to "partial mode", 0x39 to "idle on", 0x38 to "idle off",
        0x04 to "read ID", 0x09 to "read status", 0x00 to "no-op", 0x51 to "brightness", 0x37 to "scroll start", 0x33 to "scroll area")

    private fun st7789(b: List<BusEv>): String {
        val out = ArrayList<String>()
        var k = 0
        while (k < b.size) {
            if (b[k].read) {                                    // data with no command before it
                var n = 0; while (k < b.size && b[k].read) { n++; k++ }
                out += "$n bytes of data"; continue
            }
            val c = b[k].value; k++
            val data = ArrayList<Int>()
            while (k < b.size && b[k].read) { data += b[k].value; k++ }
            val name = ST7789_CMDS[c] ?: "command"
            fun w(i: Int) = (data.getOrElse(i) { 0 } shl 8) or data.getOrElse(i + 1) { 0 }
            out += "${BusEv.hex(c)} $name" + when (c) {
                0x2A, 0x2B -> if (data.size >= 4) " ${w(0)}–${w(2)}" else ""
                0x2C, 0x3C -> ", ${data.size} bytes" + if (data.size >= 2) " (${data.size / 2} pixels)" else ""
                0x3A -> if (data.isNotEmpty()) " ← ${BusEv.hex(data[0])}" + when (data[0] and 7) { 5 -> ", 16-bit"; 6 -> ", 18-bit"; 3 -> ", 12-bit"; else -> "" } else ""
                0x36 -> if (data.isNotEmpty()) " ← ${BusEv.hex(data[0])}" else ""
                else -> if (data.isNotEmpty()) " ← " + data.take(4).joinToString(" ") { BusEv.hex(it) } + if (data.size > 4) "…" else "" else ""
            }
        }
        return out.joinToString(" · ")
    }
}
