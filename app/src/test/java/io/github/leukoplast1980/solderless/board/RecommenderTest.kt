package io.github.leukoplast1980.solderless.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecommenderTest {
    private val chip = ChipPins(File("../data/g431rb-pins.json").readText())
    private val rec = Recommender(chip)

    private fun new(type: PartType, id: Int = 1, f: (Part) -> Part = { it }) = f(Part(id, type, emptyList()))

    private fun place(b: Board, p: Part): Board = b.apply(rec.propose(b.parts, p)!!)

    @Test fun e12() {
        assertEquals(270, Passives.e12(280.0))
        assertEquals(56, Passives.e12(60.0))
        assertEquals(82, Passives.e12(80.0))
        assertEquals(10000, Passives.e12(9800.0))
        assertEquals(270, Passives.ledResistor(1900, 5))
    }

    @Test fun ledGoesWhereItCostsNothing() {
        val p = rec.propose(emptyList(), new(PartType.LED))!!
        assertEquals("PD2", p.part.pins[0].pin)
        assertEquals(Function.Gpio, p.part.pins[0].function)
        assertTrue(p.moves.isEmpty())
        assertTrue(p.why[0].contains("no ADC input, DAC output or timer channel"))
    }

    @Test fun reasonNamesWhatIsGivenUp() {
        val b = place(Board(), new(PartType.LED, 1))
        val why = rec.propose(b.parts, new(PartType.LED, 2))!!.why[0]
        assertTrue(why, why.contains("only ") && !why.contains("()"))
    }

    @Test fun neverReservedPins() {
        var b = Board()
        repeat(20) { b = place(b, new(PartType.LED, b.nextId())) }
        val used = b.parts.map { it.pins[0].pin }
        assertTrue(used.none { it in setOf("PA2", "PA3", "PA13", "PA14") })
        assertEquals(used.size, used.toSet().size)
        assertTrue("weak pins avoided for LEDs", used.none { it in setOf("PC13", "PC14", "PC15") })
    }

    @Test fun potGetsAnAdcInput() {
        val p = rec.propose(emptyList(), new(PartType.POT))!!
        assertTrue(p.part.pins[0].function is Function.Adc)
    }

    @Test fun rgbUsesThreeChannelsOfOneTimer() {
        val p = rec.propose(emptyList(), new(PartType.RGB_LED))!!.part
        val fs = p.pins.map { it.function as Function.Timer }
        assertEquals(1, fs.map { it.timer }.toSet().size)
        assertEquals(3, fs.map { it.channel }.toSet().size)
        assertEquals(fs.map { it.channel }.sorted(), fs.map { it.channel })
        assertEquals(3, p.pins.map { it.pin }.toSet().size)
    }

    @Test fun piezoTakesAOneChannelTimer() {
        val f = rec.propose(emptyList(), new(PartType.PIEZO))!!.part.pins[0].function as Function.Timer
        assertTrue(f.timer, f.timer in setOf("TIM16", "TIM17"))
    }

    @Test fun speakersUseTheDacsFirst() {
        var b = place(Board(), new(PartType.SPEAKER, 1))
        b = place(b, new(PartType.SPEAKER, 2))
        val dacs = b.parts.map { it.pins[0].function }.filterIsInstance<Function.Dac>().map { it.channel }.toSet()
        assertEquals(setOf(1, 2), dacs)
        b = place(b, new(PartType.SPEAKER, 3))
        assertTrue(b.parts.last().pins[0].function is Function.Timer)
    }

    @Test fun interruptButtonsGetTheirOwnExtiLine() {
        var b = Board()
        repeat(5) { b = place(b, new(PartType.BUTTON, b.nextId())) }
        val lines = b.parts.map { Recommender.bitOf(it.pins[0].pin) }
        assertEquals(lines.size, lines.toSet().size)
    }

    @Test fun tonesDoNotShareATimer() {
        var b = place(Board(), new(PartType.RGB_LED, 1))
        repeat(3) { b = place(b, new(PartType.PIEZO, b.nextId())) }
        val timers = b.parts.filter { it.type == PartType.PIEZO }.map { (it.pins[0].function as Function.Timer).timer }
        assertEquals(timers.size, timers.toSet().size)
        val rgbTimer = (b.parts.first().pins[0].function as Function.Timer).timer
        assertTrue(rgbTimer !in timers)
    }

    @Test fun pickingATakenPinMovesOnlyTheWireInTheWay() {
        val rgb = Part(1, PartType.RGB_LED, listOf(
            PinUse("PC6", Function.Timer("TIM3", 1, 2)),
            PinUse("PC7", Function.Timer("TIM3", 2, 2)),
            PinUse("PC8", Function.Timer("TIM3", 3, 2)),
        ))
        val pr = rec.proposeWithPin(listOf(rgb), new(PartType.LED, 2), 0, "PC7")
        assertNotNull(pr)
        assertEquals("PC7", pr!!.part.pins[0].pin)
        assertEquals(1, pr.moves.size)
        val m = pr.moves[0]
        assertEquals(1, m.role)
        assertEquals("PB5", m.to.pin)
        assertEquals(Function.Timer("TIM3", 2, 2), m.to.function)
    }

    @Test fun pickingALedsPinMovesTheLed() {
        val b = place(Board(), new(PartType.LED, 1))
        val pr = rec.proposeWithPin(b.parts, new(PartType.BUTTON, 2), 0, "PD2")!!
        assertEquals("PD2", pr.part.pins[0].pin)
        assertEquals(1, pr.moves.size)
        assertNotEquals("PD2", pr.moves[0].to.pin)
    }

    @Test fun optionsMarkScarceAndTaken() {
        val b = place(Board(), new(PartType.LED, 1))
        val opts = rec.options(b.parts, new(PartType.BUTTON, 2), 0)
        assertEquals(Recommender.Status.TAKEN, opts.first { it.pin == "PD2" }.status)
        assertEquals(Recommender.Status.SCARCE, opts.first { it.pin == "PA4" }.status)
        assertTrue(opts.none { it.pin == "PA2" || it.pin == "PA13" })
    }

    @Test fun boardRoundTrip() {
        var b = place(Board(), new(PartType.RGB_LED, 1))
        b = place(b, new(PartType.POT, 2) { it.copy() })
        b = place(b, new(PartType.LED, 3) { it.copy(colour = LedColour.BLUE, currentMa = 2, activeHigh = false) })
        assertEquals(b, Board.fromJson(b.toJson()))
    }

    @Test fun wiringFs() {
        val b = place(Board(), new(PartType.LED, 1))
        val fs = Wiring.wiringFs(b, chip, "My board")
        assertTrue(fs, fs.contains("\$48000C00 constant led-port  \\ GPIOD"))
        assertTrue(fs, fs.contains("2 constant led-pin"))
        assertTrue(fs, fs.contains("PD2 (pin 55) → 270 Ω → red LED → GND"))
        val b2 = place(b, new(PartType.LED, 2))
        assertTrue(Wiring.wiringFs(b2, chip, "x").contains("constant led2-pin"))
    }

    @Test fun attachmentsForTheEmulator() {
        val b = place(Board(), new(PartType.BUTTON, 1) { it.copy(externalPull = true) })
        val a = b.attachments(chip)
        assertEquals(16, a.size)                       // switch and the pull resistor
        assertEquals(Board.ATT_SWITCH, a[3])
        assertEquals(Board.ATT_PULL, a[8 + 3])
        assertEquals(1, a[8 + 4])                      // pull-up to 3V3
    }

    @Test fun headersInBothOrders() {
        val (l, r) = chip.headers(HeaderOrder.ADAPTER)
        assertEquals(32, l.size); assertEquals(32, r.size)
        assertEquals("VBAT", l[0].name); assertEquals(64, r[0].number); assertEquals(33, r.last().number)
        val (pl, pr) = chip.headers(HeaderOrder.PORT)
        assertEquals("PA0", pl[0].name); assertEquals("PB15", pl.last().name)
        assertEquals("PC0", pr[0].name); assertEquals(64, pl.size + pr.size)
    }
}
