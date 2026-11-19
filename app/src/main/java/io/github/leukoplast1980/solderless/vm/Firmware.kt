/*
 * Solderless - reads firmware files (.bin, Intel .hex, .elf) into a flash
 * image for the G431's 128 KiB of flash at 0x0800_0000.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.vm

import java.nio.ByteBuffer
import java.nio.ByteOrder

class FirmwareException(message: String) : Exception(message)

object Firmware {
    const val FLASH_BASE = 0x08000000L
    const val FLASH_SIZE = FlashImage.SIZE
    private const val SRAM_BASE = 0x20000000L
    private const val SRAM_END = 0x20008000L
    private const val CCM_BASE = 0x10000000L
    private const val CCM_END = 0x10002800L

    enum class Format { BIN, HEX, ELF }

    class Image(val flash: ByteArray, val format: Format, val bytesUsed: Int)

    fun detect(data: ByteArray): Format = when {
        data.size >= 4 && data[0] == 0x7F.toByte() && data[1] == 'E'.code.toByte() &&
            data[2] == 'L'.code.toByte() && data[3] == 'F'.code.toByte() -> Format.ELF
        data.isNotEmpty() && data[0] == ':'.code.toByte() -> Format.HEX
        else -> Format.BIN
    }

    /** Parses a firmware file and checks that it can start on the chip. */
    fun parse(data: ByteArray): Image {
        val format = detect(data)
        val flash = ByteArray(FLASH_SIZE) { 0xFF.toByte() }
        val used = when (format) {
            Format.BIN -> parseBin(data, flash)
            Format.HEX -> parseHex(data, flash)
            Format.ELF -> parseElf(data, flash)
        }
        checkVectors(flash)
        return Image(flash, format, used)
    }

    private fun parseBin(data: ByteArray, flash: ByteArray): Int {
        if (data.size > FLASH_SIZE)
            throw FirmwareException("The file has ${data.size} bytes; the G431's flash holds $FLASH_SIZE.")
        data.copyInto(flash)
        return data.size
    }

    /** Flash offset of a physical address, or throws. */
    private fun flashOffset(addr: Long, len: Int): Int {
        val a = if (addr < FLASH_SIZE) addr else addr - FLASH_BASE      // 0x0000_0000 aliases flash
        if (a < 0 || a + len > FLASH_SIZE)
            throw FirmwareException(
                "Data at 0x%08X is outside the flash (0x08000000 to 0x0801FFFF).".format(addr))
        return a.toInt()
    }

    private fun parseHex(data: ByteArray, flash: ByteArray): Int {
        var upper = 0L
        var used = 0
        var lineNo = 0
        for (raw in String(data, Charsets.US_ASCII).lineSequence()) {
            lineNo++
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line[0] != ':' || line.length < 11 || line.length % 2 == 0)
                throw FirmwareException("Line $lineNo is not an Intel HEX record.")
            val bytes = try {
                ByteArray((line.length - 1) / 2) { line.substring(1 + 2 * it, 3 + 2 * it).toInt(16).toByte() }
            } catch (e: NumberFormatException) {
                throw FirmwareException("Line $lineNo has characters that are not hex digits.")
            }
            val len = bytes[0].toInt() and 0xFF
            if (bytes.size != len + 5) throw FirmwareException("Line $lineNo has the wrong length.")
            if (bytes.fold(0) { s, b -> s + (b.toInt() and 0xFF) } and 0xFF != 0)
                throw FirmwareException("Line $lineNo has a wrong checksum.")
            val offset = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
            when (bytes[3].toInt() and 0xFF) {
                0x00 -> {
                    val at = flashOffset(upper + offset, len)
                    bytes.copyInto(flash, at, 4, 4 + len)
                    used = maxOf(used, at + len)
                }
                0x01 -> return used
                0x02 -> upper = (((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)).toLong() shl 4
                0x04 -> upper = (((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)).toLong() shl 16
                0x03, 0x05 -> {}                            // start address: the chip uses its vector table
                else -> throw FirmwareException("Line $lineNo has an unknown record type.")
            }
        }
        return used
    }

    private fun parseElf(data: ByteArray, flash: ByteArray): Int {
        if (data.size < 52) throw FirmwareException("The ELF file is too short.")
        if (data[4].toInt() != 1) throw FirmwareException("Only 32-bit ELF files fit this chip.")
        if (data[5].toInt() != 1) throw FirmwareException("The ELF file is big-endian; the G431 is little-endian.")
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val machine = b.getShort(18).toInt() and 0xFFFF
        if (machine != 40) throw FirmwareException("The ELF file is not for ARM (machine $machine).")
        val phoff = b.getInt(28).toLong() and 0xFFFFFFFFL
        val phentsize = b.getShort(42).toInt() and 0xFFFF
        val phnum = b.getShort(44).toInt() and 0xFFFF
        var used = 0
        var loaded = 0
        for (i in 0 until phnum) {
            val ph = (phoff + i.toLong() * phentsize).toInt()
            if (ph < 0 || ph + 32 > data.size) throw FirmwareException("The ELF program headers are damaged.")
            val type = b.getInt(ph)
            val offset = b.getInt(ph + 4).toLong() and 0xFFFFFFFFL
            val paddr = b.getInt(ph + 12).toLong() and 0xFFFFFFFFL
            val filesz = b.getInt(ph + 16).toLong() and 0xFFFFFFFFL
            if (type != 1 || filesz == 0L) continue                  // PT_LOAD with data only
            if (offset + filesz > data.size) throw FirmwareException("The ELF file is cut short.")
            val at = flashOffset(paddr, filesz.toInt())
            data.copyInto(flash, at, offset.toInt(), (offset + filesz).toInt())
            used = maxOf(used, at + filesz.toInt())
            loaded++
        }
        if (loaded == 0) throw FirmwareException("The ELF file has nothing to load into flash.")
        return used
    }

    /** The first two words are the initial stack pointer and the reset handler. */
    private fun checkVectors(flash: ByteArray) {
        val b = ByteBuffer.wrap(flash).order(ByteOrder.LITTLE_ENDIAN)
        val sp = b.getInt(0).toLong() and 0xFFFFFFFFL
        val reset = b.getInt(4).toLong() and 0xFFFFFFFFL
        if (sp == 0xFFFFFFFFL && reset == 0xFFFFFFFFL)
            throw FirmwareException("The image does not start at the beginning of flash (0x08000000): " +
                "there is no vector table there.")
        if (!(sp in (SRAM_BASE + 1)..SRAM_END || sp in (CCM_BASE + 1)..CCM_END))
            throw FirmwareException("The first word (initial stack pointer, 0x%08X) does not point into RAM. ".format(sp) +
                "Is this firmware for another chip?")
        val target = reset and 1L.inv()
        if (reset and 1L == 0L || !(target < FLASH_SIZE || target in FLASH_BASE until FLASH_BASE + FLASH_SIZE))
            throw FirmwareException("The second word (reset handler, 0x%08X) is not a Thumb address in flash. ".format(reset) +
                "Is this firmware for another chip?")
    }
}
