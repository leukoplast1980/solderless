package io.github.leukoplast1980.solderless.board

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** The parts. Each terminal that goes to the chip is a role. */
enum class PartType(val prefix: String, val title: String, val roles: List<String>) {
    BUTTON("button", "Button", listOf("")),
    LED("led", "LED", listOf("")),
    RGB_LED("rgb", "RGB LED", listOf("red", "green", "blue")),
    POT("pot", "Potentiometer", listOf("")),
    SPEAKER("speaker", "Speaker", listOf("")),
    PIEZO("piezo", "Piezo buzzer", listOf("")),
    OLED("oled", "OLED display", listOf("scl", "sda")),
    FLASH("flash", "SPI flash", listOf("cs", "clk", "di", "do")),
    MATRIX("matrix", "LED matrix", listOf("cs", "clk", "din")),
    TFT("tft", "Colour display", listOf("cs", "scl", "sda", "dc", "res")),
    BARO("baro", "Pressure sensor", listOf("scl", "sda")),
    CO2("co2", "CO₂ sensor", listOf("scl", "sda")),
    ENCODER("enc", "Rotary encoder", listOf("clk", "dt", "sw")),
    ;

    /** I2C addresses the part can be set to, the default first */
    val addresses: List<Int> get() = when (this) {
        OLED -> listOf(0x3C, 0x3D)
        BARO -> listOf(0x76, 0x77)
        CO2 -> listOf(0x62)
        else -> emptyList()
    }
}

enum class LedColour(val title: String, val vfMv: Int, val argb: Long) {
    RED("red", 1900, 0xFFFF6B6B), YELLOW("yellow", 2000, 0xFFFFD866), GREEN("green", 2100, 0xFF6FCF7F),
    BLUE("blue", 2900, 0xFF7FB8FF), WHITE("white", 2900, 0xFFF2F4F0),
}

/** What a part's pin has to be able to do */
enum class Need { OUTPUT, INPUT, INPUT_EXTI, PWM, ADC, DAC_OR_PWM, I2C_SCL, I2C_SDA, CS, SPI_SCK, SPI_MOSI, SPI_MISO, ENC_A, ENC_B }

/** A chip pin given to one role of a part, and the function it is used with. */
data class PinUse(val pin: String, val function: Function)

sealed class Function {
    object Gpio : Function() { override fun toString() = "GPIO" }
    data class Timer(val timer: String, val channel: Int, val af: Int) : Function() {
        override fun toString() = "$timer CH$channel"
    }
    data class Adc(val adc: String, val channel: Int) : Function() { override fun toString() = "${adc} IN$channel" }
    data class Dac(val channel: Int) : Function() { override fun toString() = "DAC1 OUT$channel" }
    /** An I2C peripheral's SCL or SDA, in alternate function [af] */
    data class I2c(val periph: String, val signal: String, val af: Int) : Function() { override fun toString() = "$periph $signal" }
    /** An SPI peripheral's SCK, MOSI or MISO, in alternate function [af] */
    data class Spi(val periph: String, val signal: String, val af: Int) : Function() { override fun toString() = "$periph $signal" }
}

data class Part(
    val id: Int,
    val type: PartType,
    val pins: List<PinUse>,                 // one per role
    val colour: LedColour = LedColour.RED,  // LED
    val sizeMm: Int = 5,
    val currentMa: Int = 5,                 // LED, RGB LED
    val activeHigh: Boolean = true,         // LED lights when the pin is high; RGB: common cathode
    val dimmable: Boolean = false,          // LED on a timer channel
    val toVcc: Boolean = false,             // button between pin and 3V3 instead of GND
    val externalPull: Boolean = false,      // button: 10 kohm instead of the chip's pull resistor
    val bounceUs: Int = 2000,
    val interrupt: Boolean = true,          // button wants its own EXTI line
    val dacDrive: Boolean = true,           // speaker on a DAC output, else PWM
    val pinnedOverTabs: Boolean = false,
    val i2cAddr: Int = 0x3C,                // OLED: 0x3C, or 0x3D with its SA0 strap to 3V3
    val flashMbit: Int = 64,                // W25Q32, W25Q64, W25Q128
    val matrixChips: Int = 4,               // MAX7219 modules: one 8 x 8, or a 4-in-1 board
    val tftRows: Int = 240,                 // ST7789: 240 x 240 (1.3"), or 240 x 320 (2.0")
    // What a sensor senses, as the user sets it
    val pressureHpa: Double = 1013.2,
    val tempC: Double = 21.5,
    val co2Ppm: Int = 820,
    val rhPct: Int = 45,
    val followBarometer: Boolean = false,   // BMP280: the phone's own pressure sensor sets it
) {
    fun needs(): List<Need> = when (type) {
        PartType.BUTTON -> listOf(if (interrupt) Need.INPUT_EXTI else Need.INPUT)
        PartType.LED -> listOf(if (dimmable) Need.PWM else Need.OUTPUT)
        PartType.RGB_LED -> listOf(Need.PWM, Need.PWM, Need.PWM)
        PartType.POT -> listOf(Need.ADC)
        PartType.SPEAKER -> listOf(if (dacDrive) Need.DAC_OR_PWM else Need.PWM)
        PartType.PIEZO -> listOf(Need.PWM)
        PartType.OLED, PartType.BARO, PartType.CO2 -> listOf(Need.I2C_SCL, Need.I2C_SDA)
        PartType.ENCODER -> listOf(Need.ENC_A, Need.ENC_B, Need.INPUT)
        PartType.FLASH -> listOf(Need.CS, Need.SPI_SCK, Need.SPI_MOSI, Need.SPI_MISO)
        PartType.MATRIX -> listOf(Need.CS, Need.SPI_SCK, Need.SPI_MOSI)
        // RES idles high like a chip select, so it stays off PB8 too
        PartType.TFT -> listOf(Need.CS, Need.SPI_SCK, Need.SPI_MOSI, Need.OUTPUT, Need.CS)
    }

    /** The I2C peripheral a bus part is on, if it is */
    val i2c: String? get() = (pins.firstOrNull()?.function as? Function.I2c)?.periph

    /** The SPI peripheral an SPI part is on */
    val spi: String? get() = pins.firstNotNullOfOrNull { (it.function as? Function.Spi)?.periph }

    /** W25Q: the JEDEC capacity byte (0x16 32 Mbit, 0x17 64, 0x18 128) and the name */
    val flashCap: Int get() = when (flashMbit) { 32 -> 0x16; 128 -> 0x18; else -> 0x17 }
    val flashName: String get() = "W25Q$flashMbit"

    fun vfMv(role: Int): Int = when (type) {
        PartType.RGB_LED -> if (role == 0) 1900 else 3000
        else -> colour.vfMv
    }
}

/** A resistor or other passive part the app places, with the reason. */
data class Passive(val partId: Int, val role: Int, val kind: String, val value: String, val ohms: Int, val reason: String)

object Passives {
    private val E12 = listOf(10, 12, 15, 18, 22, 27, 33, 39, 47, 56, 68, 82)

    /** Nearest E12 value (by ratio) */
    fun e12(ohms: Double): Int {
        if (ohms <= 0) return 0
        val decade = 10.0.pow(kotlin.math.floor(log10(ohms)) - 1)
        val cands = (E12.map { it * decade } + E12.map { it * decade * 10 })
        return cands.minByOrNull { abs(log10(it / ohms)) }!!.roundToInt()
    }

    fun format(ohms: Int): String = when {
        ohms >= 1_000_000 -> trim(ohms / 1e6) + " MΩ"
        ohms >= 1000 -> trim(ohms / 1e3) + " kΩ"
        else -> "$ohms Ω"
    }

    private fun trim(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, v)

    /** Series resistor of an LED on a 3.3 V pin. */
    fun ledResistor(vfMv: Int, currentMa: Int): Int = e12((3300 - vfMv).toDouble() / currentMa)

    fun ledReason(vfMv: Int, currentMa: Int): String {
        val exact = (3300 - vfMv).toDouble() / currentMa
        val r = ledResistor(vfMv, currentMa)
        val i = (3300 - vfMv).toDouble() / r
        return "(3.3 V − %.1f V) ÷ %d mA = %d Ω, nearest E12 value %s: %.1f mA"
            .format(vfMv / 1000.0, currentMa, exact.roundToInt(), format(r), i)
    }

    fun forPart(p: Part): List<Passive> = when (p.type) {
        PartType.LED -> listOf(Passive(p.id, 0, "resistor", format(ledResistor(p.vfMv(0), p.currentMa)),
            ledResistor(p.vfMv(0), p.currentMa), ledReason(p.vfMv(0), p.currentMa)))
        PartType.RGB_LED -> (0..2).map { r ->
            Passive(p.id, r, "resistor", format(ledResistor(p.vfMv(r), p.currentMa)), ledResistor(p.vfMv(r), p.currentMa),
                ledReason(p.vfMv(r), p.currentMa))
        }
        PartType.BUTTON -> if (p.externalPull) listOf(Passive(p.id, 0, "resistor", "10 kΩ", 10000,
            if (p.toVcc) "pull-down: the pin reads low until the button connects it to 3V3"
            else "pull-up: the pin reads high until the button connects it to GND")) else emptyList()
        PartType.SPEAKER -> listOf(
            Passive(p.id, 0, "resistor", "1 kΩ", 1000, "base resistor: about 2.5 mA into the transistor's base"),
            Passive(p.id, 0, "transistor", "NPN (BC337)", 0, "switches the speaker current, which a pin cannot supply"),
            Passive(p.id, 0, "diode", "1N4148", 0, "flyback: the speaker coil kicks back when switched off"),
        )
        PartType.OLED -> listOf(Passive(p.id, 0, "on the module", "4.7 kΩ × 2", 4700,
            "pull-ups on SCL and SDA: I2C lines are open-drain, something has to pull them high"))
        PartType.ENCODER -> listOf(Passive(p.id, 0, "on the module", "10 kΩ × 2", 10000,
            "pull-ups on CLK and DT; SW has none fitted: turn on the pin's own pull-up"))
        PartType.BARO -> listOf(Passive(p.id, 0, "on the module", "10 kΩ × 2", 10000,
            "pull-ups on SCL and SDA; CSB is pulled high for I2C, SDO sets the address"))
        PartType.CO2 -> listOf(Passive(p.id, 0, "on the module", "10 kΩ × 2", 10000, "pull-ups on SCL and SDA"))
        PartType.MATRIX -> listOf(
            Passive(p.id, 0, "wire", "VCC to 5 V", 0, "the LEDs and the MAX7219 run from 5 V; DIN, CLK and CS take 3.3 V logic, just"),
            Passive(p.id, 0, "on the module", "10 µF + 100 nF", 0, "decoupling, and RSET setting the LED current"),
        )
        PartType.TFT -> listOf(
            Passive(p.id, 0, "wire", "BLK to 3V3", 0, "the backlight: on; a PWM pin there would dim it"),
            Passive(p.id, 0, "on the module", "regulator", 0, "the module runs from 3.3 V or 5 V; its logic is 3.3 V"),
        )
        PartType.FLASH -> listOf(
            Passive(p.id, 0, "wire", "WP to 3V3", 0, "write protect: low would lock the status registers"),
            Passive(p.id, 0, "wire", "HOLD to 3V3", 0, "hold: low would pause the chip in the middle of a transfer"),
            Passive(p.id, 0, "capacitor", "100 nF", 0, "decoupling, from VCC to GND, close to the chip"),
        )
        else -> emptyList()
    }

    /** What the chip needs around it on a real board (shown once). */
    val support = listOf(
        "4 × 100 nF" to "VDD 16, 32, 48, 64: one per supply pin, close to it",
        "4.7 µF" to "3V3 rail: one bulk capacitor for the board",
        "1 µF + 100 nF" to "VDDA 29: clean supply for the ADC and DAC",
        "wire" to "VREF+ 28 to VDDA: the ADC and DAC reference is 3.3 V",
        "100 nF" to "NRST 7: filters reset; the reset button sits here",
        "10 kΩ to GND" to "PB8-BOOT0 61: starts the program in flash",
        "wire" to "VBAT 1 to 3V3: no backup battery fitted",
    )
}
