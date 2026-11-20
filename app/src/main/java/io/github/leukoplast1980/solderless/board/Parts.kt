package io.github.leukoplast1980.solderless.board

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** The parts of milestone 2. Each terminal that goes to the chip is a role. */
enum class PartType(val prefix: String, val title: String, val roles: List<String>) {
    BUTTON("button", "Button", listOf("")),
    LED("led", "LED", listOf("")),
    RGB_LED("rgb", "RGB LED", listOf("red", "green", "blue")),
    POT("pot", "Potentiometer", listOf("")),
    SPEAKER("speaker", "Speaker", listOf("")),
    PIEZO("piezo", "Piezo buzzer", listOf("")),
}

enum class LedColour(val title: String, val vfMv: Int, val argb: Long) {
    RED("red", 1900, 0xFFFF6B6B), YELLOW("yellow", 2000, 0xFFFFD866), GREEN("green", 2100, 0xFF6FCF7F),
    BLUE("blue", 2900, 0xFF7FB8FF), WHITE("white", 2900, 0xFFF2F4F0),
}

/** What a part's pin has to be able to do */
enum class Need { OUTPUT, INPUT, INPUT_EXTI, PWM, ADC, DAC_OR_PWM }

/** A chip pin given to one role of a part, and the function it is used with. */
data class PinUse(val pin: String, val function: Function)

sealed class Function {
    object Gpio : Function() { override fun toString() = "GPIO" }
    data class Timer(val timer: String, val channel: Int, val af: Int) : Function() {
        override fun toString() = "$timer CH$channel"
    }
    data class Adc(val adc: String, val channel: Int) : Function() { override fun toString() = "${adc} IN$channel" }
    data class Dac(val channel: Int) : Function() { override fun toString() = "DAC1 OUT$channel" }
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
) {
    fun needs(): List<Need> = when (type) {
        PartType.BUTTON -> listOf(if (interrupt) Need.INPUT_EXTI else Need.INPUT)
        PartType.LED -> listOf(if (dimmable) Need.PWM else Need.OUTPUT)
        PartType.RGB_LED -> listOf(Need.PWM, Need.PWM, Need.PWM)
        PartType.POT -> listOf(Need.ADC)
        PartType.SPEAKER -> listOf(if (dacDrive) Need.DAC_OR_PWM else Need.PWM)
        PartType.PIEZO -> listOf(Need.PWM)
    }

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
