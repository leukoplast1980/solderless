package io.github.leukoplast1980.solderless.board

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The sample code for every kind of part and pin function. Each sample is
 * also in one of tests/stellaris/samples_*.fs, which the host tests run on
 * the emulator with the real firmware: so this test fails when the
 * generated code changes and those files (and their transcripts) were not
 * brought along. The samples are written to build/samples for that.
 */
class SamplesTest {
    private val chip = ChipPins(File("../data/g431rb-pins.json").readText())

    private fun tim(pin: String, timer: String): PinUse {
        val tc = chip.gpio(pin)!!.timerChannels.first { it.timer == timer }
        return PinUse(pin, Function.Timer(tc.timer, tc.channel, tc.af))
    }
    private fun adc(pin: String): PinUse {
        val (a, ch) = chip.gpio(pin)!!.adcChannels.first()
        return PinUse(pin, Function.Adc(a, ch))
    }
    private fun gpio(pin: String) = PinUse(pin, Function.Gpio)
    private fun i2c(pin: String, signal: String): PinUse {
        val f = chip.gpio(pin)!!.i2c(signal).first()
        return PinUse(pin, Function.I2c(f.periph, signal, f.af))
    }

    private fun spi(pin: String, signal: String): PinUse {
        val f = chip.gpio(pin)!!.spi(signal).first()
        return PinUse(pin, Function.Spi(f.periph, signal, f.af))
    }

    /** One board with every variant; the names are led, led2, ... in id order */
    val board = Board(listOf(
        Part(1, PartType.LED, listOf(gpio("PB5"))),
        Part(2, PartType.LED, listOf(gpio("PB6")), colour = LedColour.GREEN, activeHigh = false),
        Part(3, PartType.LED, listOf(tim("PC6", "TIM3")), dimmable = true),
        Part(4, PartType.LED, listOf(tim("PA8", "TIM1")), dimmable = true, activeHigh = false),
        Part(5, PartType.RGB_LED, listOf(tim("PA8", "TIM1"), tim("PA9", "TIM1"), tim("PA10", "TIM1"))),
        Part(6, PartType.RGB_LED, listOf(tim("PC6", "TIM3"), tim("PC7", "TIM3"), tim("PC8", "TIM3")), activeHigh = false),
        Part(7, PartType.RGB_LED, listOf(gpio("PB10"), gpio("PB11"), gpio("PB12"))),
        Part(8, PartType.BUTTON, listOf(gpio("PC13")), interrupt = false),
        Part(9, PartType.BUTTON, listOf(gpio("PB4")), toVcc = true, externalPull = true),
        Part(10, PartType.BUTTON, listOf(gpio("PA0"))),
        Part(11, PartType.POT, listOf(adc("PC0"))),
        Part(12, PartType.POT, listOf(adc("PB2"))),
        Part(13, PartType.SPEAKER, listOf(PinUse("PA4", Function.Dac(1)))),
        Part(14, PartType.SPEAKER, listOf(tim("PB6", "TIM4")), dacDrive = false),
        Part(15, PartType.PIEZO, listOf(tim("PB8", "TIM16"))),
        Part(16, PartType.OLED, listOf(i2c("PB8", "SCL"), i2c("PB9", "SDA"))),
        Part(17, PartType.OLED, listOf(i2c("PC8", "SCL"), i2c("PC9", "SDA")), i2cAddr = 0x3D),
        Part(18, PartType.FLASH, listOf(gpio("PA4"), spi("PA5", "SCK"), spi("PA7", "MOSI"), spi("PA6", "MISO"))),
        Part(19, PartType.FLASH, listOf(gpio("PB12"), spi("PB13", "SCK"), spi("PB15", "MOSI"), spi("PB14", "MISO")), flashMbit = 32),
        Part(20, PartType.MATRIX, listOf(gpio("PC9"), spi("PC10", "SCK"), spi("PC12", "MOSI"))),
        Part(21, PartType.MATRIX, listOf(gpio("PB10"), spi("PB3", "SCK"), spi("PB5", "MOSI")), matrixChips = 1),
        Part(22, PartType.TFT, listOf(gpio("PA4"), spi("PA5", "SCK"), spi("PA7", "MOSI"), gpio("PB0"), gpio("PB1"))),
        Part(23, PartType.TFT, listOf(gpio("PB12"), spi("PB13", "SCK"), spi("PB15", "MOSI"), gpio("PC6"), gpio("PC7")), tftRows = 320),
        Part(24, PartType.BARO, listOf(i2c("PB8", "SCL"), i2c("PB9", "SDA")), i2cAddr = 0x76),
        Part(25, PartType.CO2, listOf(i2c("PB8", "SCL"), i2c("PB9", "SDA")), i2cAddr = 0x62),
        Part(26, PartType.BARO, listOf(i2c("PC8", "SCL"), i2c("PC9", "SDA")), i2cAddr = 0x77),
        Part(27, PartType.ENCODER, listOf(tim("PA0", "TIM2"), tim("PA1", "TIM2"), gpio("PC3"))),
        Part(28, PartType.ENCODER, listOf(tim("PB6", "TIM4"), tim("PB7", "TIM4"), gpio("PC2"))),
    ))

    @Test fun everySampleIsTestedOnTheEmulator() {
        val out = File("build/samples").apply { mkdirs() }
        val tested = File("../tests/stellaris").listFiles { f -> f.name.startsWith("samples_") && f.name.endsWith(".fs") }!!
            .joinToString("\n") { it.readText() }
        val missing = ArrayList<String>()
        for (p in board.parts) {
            val name = board.nameOf(p)
            val text = Samples.forPart(board, chip, p)
            File(out, "$name.fs").writeText(text)
            if (!tested.contains(text)) missing += name
        }
        assertTrue("not in tests/stellaris/samples_*.fs as generated (see app/build/samples): $missing", missing.isEmpty())
    }

    /** Every kind of part, in every setting that changes the code, on every pin the
     * app offers: written to build/samples/all for a run on the emulator
     * (tests/samples_all.sh). Checks only that a sample is made for each. */
    @Test fun everyPinHasASample() {
        val out = File("build/samples/all").apply { deleteRecursively(); mkdirs() }
        val rec = Recommender(chip)
        val variants = listOf(
            Part(1, PartType.LED, emptyList()), Part(1, PartType.LED, emptyList(), activeHigh = false),
            Part(1, PartType.LED, emptyList(), dimmable = true), Part(1, PartType.LED, emptyList(), dimmable = true, activeHigh = false),
            Part(1, PartType.RGB_LED, emptyList()), Part(1, PartType.RGB_LED, emptyList(), activeHigh = false),
            Part(1, PartType.BUTTON, emptyList(), interrupt = false), Part(1, PartType.BUTTON, emptyList()),
            Part(1, PartType.BUTTON, emptyList(), toVcc = true, externalPull = true),
            Part(1, PartType.POT, emptyList()),
            Part(1, PartType.SPEAKER, emptyList()), Part(1, PartType.SPEAKER, emptyList(), dacDrive = false),
            Part(1, PartType.PIEZO, emptyList()),
            Part(1, PartType.OLED, emptyList()),
            Part(1, PartType.FLASH, emptyList()),
            Part(1, PartType.MATRIX, emptyList()),
            Part(1, PartType.TFT, emptyList()),
            Part(1, PartType.BARO, emptyList(), i2cAddr = 0x76),
            Part(1, PartType.CO2, emptyList(), i2cAddr = 0x62),
            Part(1, PartType.ENCODER, emptyList()),
        )
        var n = 0
        for ((vi, v) in variants.withIndex()) for (o in rec.options(emptyList(), v, 0)) {
            val pr = rec.proposeWithPin(emptyList(), v, 0, o.pin) ?: continue
            val b = Board(listOf(pr.part))
            val text = Samples.forPart(b, chip, pr.part)
            val pins = pr.part.pins.joinToString("-") { it.pin }
            File(out, "v${vi}_${pr.part.type.prefix}_$pins.fs").writeText(text)
            n++
        }
        assertTrue("samples made: $n", n > 100)
    }

    @Test fun linesFitMecrispsInputBuffer() {
        for (p in board.parts) for (l in Samples.forPart(board, chip, p).lines())
            assertTrue("${l.length}: $l", l.toByteArray().size < 200)
    }
}
