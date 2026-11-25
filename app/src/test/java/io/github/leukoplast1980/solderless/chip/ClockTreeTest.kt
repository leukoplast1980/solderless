package io.github.leukoplast1980.solderless.chip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClockTreeTest {

    private val map = RegisterMap(File("../data/g431rb-registers.json").readText())

    private fun tree(vararg words: Pair<Long, Long>, hse: Long = 0): ClockTree {
        val m = words.toMap()
        return ClockTree({ m[it] ?: 0L }, hse, false, map)
    }

    private val rcc = ClockTree.RCC

    @Test
    fun afterStellarisStarts() {
        // HSI16 on and ready, SYSCLK from it; GPIOA and USART2 clocked
        val t = tree(rcc to 0x500L, rcc + 0x08 to 0x5L, rcc + 0x0C to 0x1000L, rcc + 0x4C to 0x2FL, rcc + 0x58 to 0x20000L,
            ClockTree.PWR_CR1 to 0x200L, ClockTree.PWR_CR5 to 0x100L)
        assertEquals(16_000_000L, t.sysclkHz)
        assertEquals("HSI16", t.sysclkSourceName)
        assertEquals(16_000_000L, t.pclk1Hz)
        assertFalse(t.pllOn)
        assertEquals(0, t.latencyNeeded)
        val usart2 = t.apb1.peripherals.first { it.name == "USART2" }
        assertTrue(usart2.on)
        assertEquals("PCLK1", usart2.kernel)
        assertEquals(16_000_000L, usart2.kernelHz)
        assertFalse(t.apb2.peripherals.first { it.name == "USART1" }.on)
        assertTrue(t.ahb.peripherals.first { it.name == "GPIOA" }.on)
        assertEquals("range 1", t.rangeText)
    }

    @Test
    fun at170MHz() {
        // lib_pll: HSI16 ÷ 4 × 85 ÷ 2 = 170 MHz, boost mode, 4 wait states; APB1 ÷ 2
        val t = tree(rcc to 0x03000500L, rcc + 0x08 to (0xFL or (4L shl 8)), rcc + 0x0C to 0x01005532L,
            ClockTree.FLASH_ACR to 4L, ClockTree.PWR_CR1 to 0x200L, ClockTree.PWR_CR5 to 0L, ClockTree.STK_CTRL to 0x5L)
        assertEquals(4_000_000L, t.pllInHz)
        assertEquals(340_000_000L, t.vcoHz)
        assertEquals(170_000_000L, t.sysclkHz)
        assertEquals("PLL R", t.sysclkSourceName)
        assertNull(t.pllProblem)
        assertEquals(85_000_000L, t.pclk1Hz)
        assertEquals(170_000_000L, t.timer1Hz)
        assertEquals(4, t.latencyNeeded)
        assertEquals("range 1 boost", t.rangeText)
        assertEquals(170_000_000L, t.sysTickHz)
        assertEquals("170 MHz", ClockTree.freq(t.sysclkHz))
        assertEquals("32.768 kHz", ClockTree.freq(32_768))
    }

    @Test
    fun aPllOutOfRange() {
        // M = 1: 16 MHz in, × 30 = 480 MHz VCO
        val t = tree(rcc to 0x01000500L, rcc + 0x0C to (2L or (30L shl 8)))
        assertEquals("VCO 480 MHz: must be 96 to 344 MHz", t.pllProblem)
    }
}
