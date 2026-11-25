/*
 * Solderless - how the chip has set up each pin (from the GPIO registers),
 * what is wired to it, and whether the two agree.
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.chip

import io.github.leukoplast1980.solderless.board.Board
import io.github.leukoplast1980.solderless.board.ChipPins
import io.github.leukoplast1980.solderless.board.Function
import io.github.leukoplast1980.solderless.board.PartType

class PinSetup(
    val pin: ChipPins.Pin,
    /** The port's clock is off in RCC: its registers cannot be read */
    val clockOff: Boolean,
    val mode: Int,          // MODER: 0 input, 1 output, 2 alternate function, 3 analog
    val openDrain: Boolean,
    val speed: Int,
    val pull: Int,          // PUPDR: 0 none, 1 up, 2 down
    val af: Int,
    val input: Boolean,     // IDR
    val output: Boolean,    // ODR
    /** MODER, PUPDR and AFR as after reset */
    val atReset: Boolean,
    /** What is wired: "LED", "Button", "OLED display SCL", "USB-serial RX" */
    val wired: List<String>,
    /** What the wiring needs, if the pin is set up otherwise: "LED needs an output (MODER5 01)" */
    val mismatch: String?,
) {
    val name: String get() = pin.name
    /** The alternate function selected, by name: "USART2_TX", or null */
    val afName: String? get() = pin.af.firstOrNull { it.af == af }?.name

    /** "output · push-pull", "AF7 USART2_TX", "input · pull-up", "analog" */
    val summary: String
        get() = when {
            clockOff -> "port clock off"
            mode == MODE_INPUT -> "input" + pullText()
            mode == MODE_OUTPUT -> "output · " + (if (openDrain) "open-drain" else "push-pull") + pullText()
            mode == MODE_AF -> "AF$af ${afName ?: "(nothing)"}" + (if (openDrain) " · open-drain" else "") + pullText()
            else -> "analog" + (pin.analog.firstOrNull()?.let { " ($it)" } ?: "")
        }

    /** "high", "low" for inputs and outputs; "" for analog */
    val level: String
        get() = when {
            clockOff || mode == MODE_ANALOG -> ""
            mode == MODE_OUTPUT -> if (output) "high" else "low"
            else -> if (input) "high" else "low"
        }

    private fun pullText() = when (pull) { 1 -> " · pull-up"; 2 -> " · pull-down"; else -> "" }

    companion object {
        const val MODE_INPUT = 0
        const val MODE_OUTPUT = 1
        const val MODE_AF = 2
        const val MODE_ANALOG = 3

        /** The GPIO registers read for each port, as offsets: MODER, OTYPER, OSPEEDR, PUPDR, IDR, ODR, AFRL, AFRH */
        val OFFSETS = intArrayOf(0x00, 0x04, 0x08, 0x0C, 0x10, 0x14, 0x20, 0x24)
        const val GPIO_BASE = 0x48000000L
        const val PORT_STRIDE = 0x400L
        /** RCC_AHB2ENR: GPIOxEN in bits 0..6 */
        const val AHB2ENR = 0x4002104CL

        /** Reset values of MODER, PUPDR (RM0440): port A and B have their debug pins set up */
        private fun resetModer(port: Int) = when (port) { 0 -> 0xABFFFFFFL; 1 -> 0xFFFFFEBFL; else -> 0xFFFFFFFFL }
        private fun resetPupdr(port: Int) = when (port) { 0 -> 0x64000000L; 1 -> 0x00000100L; else -> 0L }

        /** The addresses to read, in the order [of] wants: AHB2ENR, then each port's [OFFSETS] */
        fun addresses(ports: Int = 7): LongArray =
            LongArray(1 + ports * OFFSETS.size) { i ->
                if (i == 0) AHB2ENR else GPIO_BASE + PORT_STRIDE * ((i - 1) / OFFSETS.size) + OFFSETS[(i - 1) % OFFSETS.size]
            }

        /** Every GPIO pin of the package, from the words read at [addresses] */
        fun of(words: LongArray, chip: ChipPins, board: Board): List<PinSetup> {
            val en = words[0]
            return chip.pins.filter { it.isGpio }.sortedBy { it.number }.map { pin ->
                val port = pin.port
                val b = pin.bit
                val base = 1 + port * OFFSETS.size
                fun reg(k: Int) = words[base + k]
                val clockOff = (en shr port) and 1L == 0L
                val moder = if (clockOff) resetModer(port) else reg(0)
                val pupdr = if (clockOff) resetPupdr(port) else reg(3)
                val mode = ((moder shr (2 * b)) and 3).toInt()
                val afr = if (clockOff) 0L else if (b < 8) reg(6) else reg(7)
                val af = ((afr shr (4 * (b % 8))) and 15).toInt()
                val pull = ((pupdr shr (2 * b)) and 3).toInt()
                val atReset = mode == ((resetModer(port) shr (2 * b)) and 3).toInt() &&
                    pull == ((resetPupdr(port) shr (2 * b)) and 3).toInt() && af == 0
                val users = board.parts.flatMap { p ->
                    p.pins.withIndex().filter { it.value.pin == pin.name }.map { (role, use) -> Triple(p, role, use) }
                }
                val wired = users.map { (p, role, _) -> "${p.type.title} ${p.type.roles.getOrElse(role) { "" }.uppercase()}".trim() }.toMutableList()
                if (pin.name == "PA2") wired.add("USB-serial RX")
                if (pin.name == "PA3") wired.add("USB-serial TX")
                val mismatch = if (clockOff || atReset) null else users.firstNotNullOfOrNull { (p, role, use) ->
                    val need = need(p.type, p.type.roles.getOrElse(role) { "" }, use.function)
                    val ok = mode in need.modes && (need.af == null || af == need.af)
                    if (ok) null else "${p.type.title} needs ${need.text} (${moderField(pin)} ${need.bits}); it is ${now(mode, af)}"
                }
                PinSetup(
                    pin, clockOff, mode, !clockOff && (reg(1) shr b) and 1L == 1L,
                    if (clockOff) 0 else ((reg(2) shr (2 * b)) and 3).toInt(), pull, af,
                    !clockOff && (reg(4) shr b) and 1L == 1L, !clockOff && (reg(5) shr b) and 1L == 1L,
                    atReset, wired, mismatch,
                )
            }
        }

        private fun moderField(pin: ChipPins.Pin) = "MODER${pin.bit}"

        private fun now(mode: Int, af: Int) = when (mode) {
            MODE_INPUT -> "an input (00)"
            MODE_OUTPUT -> "an output (01)"
            MODE_AF -> "alternate function $af (10)"
            else -> "analog (11)"
        }

        class Need(val modes: Set<Int>, val af: Int?, val text: String, val bits: String)

        /** How a part's role wants its pin set up */
        fun need(type: PartType, role: String, f: Function): Need = when (f) {
            is Function.Timer -> Need(setOf(MODE_AF), f.af, "alternate function ${f.af} ($f)", "10")
            is Function.Adc -> Need(setOf(MODE_ANALOG), null, "analog for $f", "11")
            is Function.Dac -> Need(setOf(MODE_ANALOG), null, "analog for $f", "11")
            is Function.I2c -> Need(setOf(MODE_AF), f.af, "alternate function ${f.af} ($f)", "10")
            is Function.Spi -> Need(setOf(MODE_AF), f.af, "alternate function ${f.af} ($f)", "10")
            Function.Gpio -> when {
                type == PartType.BUTTON || type == PartType.ENCODER || (type == PartType.FLASH && role == "do") ->
                    Need(setOf(MODE_INPUT), null, "an input", "00")
                // I2C by bit-banging: let go (input) or pull low (output)
                type == PartType.OLED || type == PartType.BARO || type == PartType.CO2 ->
                    Need(setOf(MODE_INPUT, MODE_OUTPUT), null, "an input or an output", "00 or 01")
                else -> Need(setOf(MODE_OUTPUT), null, "an output", "01")
            }
        }
    }
}
