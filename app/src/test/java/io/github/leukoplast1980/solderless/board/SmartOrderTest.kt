package io.github.leukoplast1980.solderless.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SmartOrderTest {
    private val chip = ChipPins(File("../data/g431rb-pins.json").readText())
    private fun u(key: Int, vararg pins: String, span: Int = 0, gap: Int = 0, reach: Float = 0f) =
        SmartOrder.Unit(key, pins.toList(), span, gap, reach)

    /** Every pin once, 32 a side */
    private fun checkComplete(l: SmartOrder.Layout) {
        assertEquals(32, l.left.size)
        assertEquals(32, l.right.size)
        assertEquals(chip.pins.map { it.name }.sorted(), (l.left + l.right).map { it.name }.sorted())
    }

    private fun rowOf(l: SmartOrder.Layout, pin: String): Pair<Int, Int> {
        val i = l.left.indexOfFirst { it.name == pin }
        return if (i >= 0) 1 to i else -1 to l.right.indexOfFirst { it.name == pin }
    }

    @Test fun eachPartsPinsTogetherInOrderAndCentred() {
        val l = SmartOrder.layout(chip, listOf(
            listOf(u(-1, "PA2", "PA3", span = 6, gap = 2)),
            listOf(u(1, "PA8", "PA9", "PA10", span = 6)),
            listOf(u(2, "PB0", span = 5)),
            listOf(u(3, "PD2", span = 3)),
        ))!!
        checkComplete(l)
        // USB-serial on the left, RX and TX three rows apart
        assertEquals(1 to 1, rowOf(l, "PA2")); assertEquals(1 to 4, rowOf(l, "PA3"))
        // RGB LED on the right, its pins adjacent and in order; 7 rows, not 6, for even margins
        val (s, r) = rowOf(l, "PA8")
        assertEquals(-1, s)
        assertEquals(s to r + 1, rowOf(l, "PA9")); assertEquals(s to r + 2, rowOf(l, "PA10"))
        assertEquals(r + 1.5f, l.centre[1]!!, 0.001f)
        // the sides take turns: pot on the left under the strip, button on the right
        assertEquals(1, l.side[2]); assertEquals(-1, l.side[3])
        assertEquals(rowOf(l, "PB0").second + 0.5f, l.centre[2]!!, 0.001f)
    }

    @Test fun freePinsFillInPinOrderSupplyLast() {
        val l = SmartOrder.layout(chip, listOf(listOf(u(1, "PA8", span = 3))))!!
        checkComplete(l)
        val free = (l.left + l.right).filter { it.name != "PA8" }
        val gpio = free.filter { it.isGpio }
        assertEquals(gpio.sortedBy { it.number }, gpio)
        val firstSupply = free.indexOfFirst { !it.isGpio }
        assertTrue(free.drop(firstSupply).none { it.isGpio })
    }

    @Test fun aBusStaysOnOneSide() {
        // OLED with SCL/SDA, then a BMP280 on the same pins below it
        val l = SmartOrder.layout(chip, listOf(listOf(u(1, "PB8", "PB9", span = 6), u(2, span = 5))))!!
        checkComplete(l)
        assertEquals(l.side[1], l.side[2])
        assertTrue(l.centre[2]!! >= l.centre[1]!! + 5.5f)
    }

    @Test fun facingWidePartsGetRowsOfTheirOwn() {
        val l = SmartOrder.layout(chip, listOf(
            listOf(u(1, "PB8", "PB9", span = 6, reach = 180f)),
            listOf(u(2, "PB0", span = 4, reach = 100f)),
        ), width = 220f)!!
        assertEquals(1, l.side[1]); assertEquals(-1, l.side[2])
        // together they reach further than the 220 between the headers: the pot goes below the OLED
        assertTrue(l.centre[2]!! - 2f >= l.centre[1]!! + 3f)
        val narrow = SmartOrder.layout(chip, listOf(
            listOf(u(1, "PB8", "PB9", span = 6, reach = 100f)),
            listOf(u(2, "PB0", span = 4, reach = 100f)),
        ), width = 220f)!!
        assertEquals(2.5f, narrow.centre[2]!!, 0.001f)
    }

    @Test fun pinsShiftedToTheirLeads() {
        // leads 1.4 rows below the part's centre: its pins one row lower than centred
        val l = SmartOrder.layout(chip, listOf(listOf(u(1, "PA5", "PA7", span = 7))))!!
        val m = SmartOrder.layout(chip, listOf(listOf(SmartOrder.Unit(1, listOf("PA5", "PA7"), 7, shift = 1.4f))))!!
        assertEquals(rowOf(l, "PA5").second + 1, rowOf(m, "PA5").second)
        assertEquals(l.centre[1], m.centre[1])
        // never out of the part's own rows (8: 7, and one more for even margins)
        val far = SmartOrder.layout(chip, listOf(listOf(SmartOrder.Unit(1, listOf("PA5", "PA7"), 7, shift = 9f))))!!
        assertEquals(7, rowOf(far, "PA7").second)
    }

    @Test fun tooManyRowsDoesNotFit() {
        val groups = (1..12).map { listOf(u(it, span = 8)) }
        assertNull(SmartOrder.layout(chip, groups))
        assertNotNull(SmartOrder.layout(chip, groups.take(8)))
    }

    @Test fun aPinNamedTwiceIsPlacedOnce() {
        val l = SmartOrder.layout(chip, listOf(listOf(u(1, "PA8", "PA8", "PA9"))))!!
        checkComplete(l)
    }
}
