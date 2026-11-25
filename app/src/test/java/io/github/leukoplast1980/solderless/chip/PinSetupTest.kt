package io.github.leukoplast1980.solderless.chip

import io.github.leukoplast1980.solderless.board.Board
import io.github.leukoplast1980.solderless.board.ChipPins
import io.github.leukoplast1980.solderless.board.Function
import io.github.leukoplast1980.solderless.board.Part
import io.github.leukoplast1980.solderless.board.PartType
import io.github.leukoplast1980.solderless.board.PinUse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PinSetupTest {

    private val chip = ChipPins(File("../data/g431rb-pins.json").readText())

    /** The words [PinSetup.addresses] reads: ports A and B clocked, the rest off */
    private fun words(moderA: Long, afrlA: Long = 0x7700, odrA: Long = 0, idrA: Long = 0x0C): LongArray {
        val a = PinSetup.addresses()
        val w = LongArray(a.size)
        w[0] = 0x3
        fun set(port: Int, k: Int, v: Long) { w[1 + port * PinSetup.OFFSETS.size + k] = v }
        set(0, 0, moderA); set(0, 3, 0x64000000); set(0, 4, idrA); set(0, 5, odrA); set(0, 6, afrlA)
        set(1, 0, 0xFFFFFEBF); set(1, 3, 0x100)
        return w
    }

    private fun Board.with(vararg p: Part) = Board(parts + p)

    @Test
    fun setUpAndWired() {
        // Stellaris: PA2, PA3 USART2; PA5 an output driven high, an LED on it
        val board = Board().with(Part(1, PartType.LED, listOf(PinUse("PA5", Function.Gpio))))
        val pins = PinSetup.of(words(0xEBFFF7AF, odrA = 0x20, idrA = 0x2C), chip, board).associateBy { it.name }
        val pa2 = pins["PA2"]!!
        assertEquals("AF7 USART2_TX", pa2.summary)
        assertEquals("high", pa2.level)
        assertEquals(listOf("USB-serial RX"), pa2.wired)
        val pa5 = pins["PA5"]!!
        assertEquals("output · push-pull", pa5.summary)
        assertEquals("high", pa5.level)
        assertEquals(listOf("LED"), pa5.wired)
        assertNull(pa5.mismatch)
        // Untouched pins are as after reset; port C's clock is off
        assertTrue(pins["PA0"]!!.atReset)
        assertTrue(pins["PA13"]!!.atReset)
        assertTrue(pins["PB4"]!!.atReset)
        assertTrue(pins["PC13"]!!.clockOff)
        assertEquals("port clock off", pins["PC13"]!!.summary)
    }

    @Test
    fun setUpOtherwiseThanWired() {
        val board = Board().with(
            Part(1, PartType.POT, listOf(PinUse("PA1", Function.Adc("ADC1", 2)))),
            Part(2, PartType.BUTTON, listOf(PinUse("PA0", Function.Gpio))),
            Part(3, PartType.RGB_LED, listOf(PinUse("PA6", Function.Timer("TIM3", 1, 2)),
                PinUse("PA7", Function.Timer("TIM3", 2, 2)), PinUse("PA8", Function.Gpio))),
        )
        // PA1 an input, PA0 untouched, PA6 AF1 instead of AF2, PA7 AF2, PA8 still at reset
        var moder = 0xEBFFFFAFL and (3L shl 2).inv()
        moder = (moder and (0xFL shl 12).inv()) or (0xAL shl 12)
        val afrl = 0x7700L or (1L shl 24) or (2L shl 28)
        val pins = PinSetup.of(words(moder, afrl), chip, board).associateBy { it.name }
        assertEquals("Potentiometer needs analog for ADC1 IN2 (MODER1 11); it is an input (00)", pins["PA1"]!!.mismatch)
        assertNull("not set up yet is no mismatch", pins["PA0"]!!.mismatch)
        assertEquals("RGB LED needs alternate function 2 (TIM3 CH1) (MODER6 10); it is alternate function 1 (10)", pins["PA6"]!!.mismatch)
        assertNull(pins["PA7"]!!.mismatch)
        assertEquals(listOf("RGB LED GREEN"), pins["PA7"]!!.wired)
        assertFalse(pins["PA7"]!!.atReset)
    }
}
