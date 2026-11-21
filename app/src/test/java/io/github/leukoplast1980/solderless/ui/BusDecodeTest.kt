package io.github.leukoplast1980.solderless.ui

import io.github.leukoplast1980.solderless.board.Function
import io.github.leukoplast1980.solderless.board.Part
import io.github.leukoplast1980.solderless.board.PartType
import io.github.leukoplast1980.solderless.board.PinUse
import org.junit.Assert.assertEquals
import org.junit.Test

class BusDecodeTest {

    private var t = 0L

    private fun ev(kind: Int, value: Int = 0, miso: Int = 0, part: Int = -1, flags: Int = BusEv.HW, bits: Int = 8, dur: Long = 22_500) =
        BusEv(t, dur, kind, flags, bits, part, value, miso, 24, 25, -1, -1).also { t += dur + 1000 }

    private fun spi(value: Int, miso: Int = 0xFF, part: Int, flags: Int = BusEv.HW) =
        BusEv(t, 8000, BusEv.SPI, flags, 8, part, value, miso, 5, 7, 6, 4).also { t += 9000 }

    private fun cs(part: Int, low: Boolean) = BusEv(t, 0, BusEv.CS, 0, 0, part, if (low) 1 else 0, 0, 5, 7, 6, 4).also { t += 1000 }

    private val oled = Part(1, PartType.OLED, listOf(PinUse("PB8", Function.I2c("I2C1", "SCL", 4)), PinUse("PB9", Function.I2c("I2C1", "SDA", 4))))
    private val baro = Part(2, PartType.BARO, oled.pins, i2cAddr = 0x76)
    private val co2 = Part(3, PartType.CO2, oled.pins, i2cAddr = 0x62)
    private val flash = Part(4, PartType.FLASH, emptyList())
    private val matrix = Part(5, PartType.MATRIX, emptyList(), matrixChips = 1)
    private val chain = Part(6, PartType.MATRIX, emptyList(), matrixChips = 4)
    private val tft = Part(7, PartType.TFT, emptyList())
    private val parts = listOf(oled, baro, co2, flash, matrix, chain, tft).associateBy { it.id }

    private fun say(ev: List<BusEv>) = BusDecode.describeAt(ev, 0, Long.MAX_VALUE / 2, parts)

    @Test fun anOledCommand() {
        val ev = listOf(ev(BusEv.START), ev(BusEv.ADDR, 0x3C shl 1, 1, 1), ev(BusEv.BYTE, 0, 1, 1), ev(BusEv.BYTE, 0xAF, 1, 1), ev(BusEv.STOP))
        assertEquals("OLED: command AF, display on", say(ev))
    }

    @Test fun oledCommandsWithArguments() {
        val ev = listOf(ev(BusEv.START), ev(BusEv.ADDR, 0x78, 1, 1), ev(BusEv.BYTE, 0, 1, 1), ev(BusEv.BYTE, 0x8D, 1, 1),
            ev(BusEv.BYTE, 0x14, 1, 1), ev(BusEv.BYTE, 0xAF, 1, 1), ev(BusEv.STOP))
        assertEquals("OLED: commands 8D 14, charge pump on · AF, display on", say(ev))
    }

    @Test fun aRegisterReadWithARepeatedStart() {
        val ev = listOf(ev(BusEv.START), ev(BusEv.ADDR, 0x76 shl 1, 1, 2), ev(BusEv.BYTE, 0xD0, 1, 2),
            ev(BusEv.START), ev(BusEv.ADDR, 0x76 shl 1 or 1, 1, 2), ev(BusEv.BYTE, 0x58, 0, 2, BusEv.HW or BusEv.READ), ev(BusEv.STOP))
        assertEquals("BMP280: D0 id → 58, a BMP280", say(ev))
        assertEquals(1, BusDecode.transfers(ev).size)
    }

    @Test fun nobodyThere() {
        val ev = listOf(ev(BusEv.START), ev(BusEv.ADDR, 0x50 shl 1, 0), ev(BusEv.STOP))
        assertEquals("Nobody answered at address 50", say(ev))
    }

    @Test fun anScd41CommandAndItsCrc() {
        val ev = listOf(ev(BusEv.START), ev(BusEv.ADDR, 0x62 shl 1, 1, 3), ev(BusEv.BYTE, 0x21, 1, 3), ev(BusEv.BYTE, 0xB1, 1, 3), ev(BusEv.STOP))
        assertEquals("SCD41: command 21B1, start periodic measurement", say(ev))
        // 0xBEEF has the CRC 0x92 (the datasheet's example)
        val rd = listOf(ev(BusEv.START), ev(BusEv.ADDR, 0x62 shl 1 or 1, 1, 3), ev(BusEv.BYTE, 0xBE, 1, 3, BusEv.HW or BusEv.READ),
            ev(BusEv.BYTE, 0xEF, 1, 3, BusEv.HW or BusEv.READ), ev(BusEv.BYTE, 0x92, 0, 3, BusEv.HW or BusEv.READ), ev(BusEv.STOP))
        assertEquals("SCD41: read 1 word, CRCs ✓", say(rd))
    }

    @Test fun max7219() {
        val ev = listOf(cs(5, true), spi(0x0C, part = 5), spi(0x01, part = 5), cs(5, false))
        assertEquals("MAX7219: register 0C shutdown ← 01, normal operation", say(ev))
        val four = listOf(cs(6, true)) + (0 until 4).flatMap { listOf(spi(0x01, part = 6), spi(0x10 * it, part = 6)) } + cs(6, false)
        assertEquals("MAX7219: digit 0 ← 00 10 20 30 (chips 3…0)", say(four))
    }

    @Test fun flashId() {
        val ev = listOf(cs(4, true), spi(0x9F, part = 4), spi(0, 0xEF, 4, BusEv.HW or BusEv.DRIVEN),
            spi(0, 0x40, 4, BusEv.HW or BusEv.DRIVEN), spi(0, 0x17, 4, BusEv.HW or BusEv.DRIVEN), cs(4, false))
        assertEquals("W25Q64: 9F JEDEC ID → EF 40 17", say(ev))
    }

    @Test fun displayCommandsAndData() {
        val d = BusEv.HW or BusEv.READ
        val ev = listOf(cs(7, true), spi(0x2A, part = 7), spi(0, part = 7, flags = d), spi(0, part = 7, flags = d), spi(0, part = 7, flags = d),
            spi(239, part = 7, flags = d), spi(0x2C, part = 7), spi(0xF8, part = 7, flags = d), spi(0x00, part = 7, flags = d), cs(7, false))
        assertEquals("ST7789: 2A columns 0–239 · 2C RAM write, 2 bytes (1 pixels)", say(ev))
    }

    @Test fun theTransferUnderTheCursor() {
        val a = listOf(cs(5, true), spi(0x0C, part = 5), spi(0x01, part = 5), cs(5, false))
        val mid = t
        val b = listOf(cs(5, true), spi(0x0A, part = 5), spi(0x07, part = 5), cs(5, false))
        val ev = a + b
        assertEquals("MAX7219: register 0C shutdown ← 01, normal operation", BusDecode.describeAt(ev, 0, mid - 1, parts))
        assertEquals("MAX7219: register 0A intensity ← 07, 15 of 32", BusDecode.describeAt(ev, 0, t, parts))
    }

    @Test fun unpackingWhatJniPacks() {
        val r = intArrayOf(5, 1, 4000, BusEv.SPI or (0x91 shl 8) or (8 shl 16) or (0xFF shl 24), 0x9F or (0xEF shl 16), 5 or (7 shl 8) or (0xFF shl 16) or (4 shl 24))
        val e = BusEv.unpack(r, 0)
        assertEquals((1L shl 32) + 5, e.t)
        assertEquals(-1, e.part); assertEquals(0x9F, e.value); assertEquals(0xEF, e.miso)
        assertEquals(-1, e.p2); assertEquals(4, e.cs); assertEquals(1, e.mode)
        assertEquals(true, e.driven); assertEquals("spi:5:7", e.key)
    }
}
