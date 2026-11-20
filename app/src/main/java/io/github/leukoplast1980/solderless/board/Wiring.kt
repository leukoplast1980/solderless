package io.github.leukoplast1980.solderless.board

/**
 * Text the board produces: wiring.fs (constants for every pin, so a
 * re-wire changes this one file and not your code), the wiring list and
 * the parts list for building it for real.
 */
object Wiring {

    private fun hex(v: Long) = "$" + "%08X".format(v)

    private fun describe(b: Board, p: Part, chip: ChipPins, role: Int): String {
        val u = p.pins[role]
        val pin = chip.gpio(u.pin)!!
        val r = Passives.forPart(p).firstOrNull { it.role == role && it.kind == "resistor" }
        return when (p.type) {
            PartType.LED, PartType.RGB_LED -> {
                val colour = if (p.type == PartType.RGB_LED) p.type.roles[role] else p.colour.title
                if (p.activeHigh) "${u.pin} (pin ${pin.number}) → ${r?.value} → $colour LED → GND"
                else "3V3 → $colour LED → ${r?.value} → ${u.pin} (pin ${pin.number})"
            }
            PartType.BUTTON -> "${u.pin} (pin ${pin.number}) → button → ${if (p.toVcc) "3V3" else "GND"}" +
                if (p.externalPull) ", 10 kΩ to ${if (p.toVcc) "GND" else "3V3"}" else
                    ", ${if (p.toVcc) "pull-down" else "pull-up"} inside the chip (set it in GPIO${'A' + pin.port}_PUPDR)"
            PartType.POT -> "potentiometer 10 kΩ between 3V3 and GND, wiper → ${u.pin} (pin ${pin.number})"
            PartType.SPEAKER -> "${u.pin} (pin ${pin.number}) → 1 kΩ → NPN base; speaker from 3V3 to the collector, " +
                "1N4148 across the speaker; emitter → GND"
            PartType.PIEZO -> "${u.pin} (pin ${pin.number}) → piezo → GND"
            PartType.OLED -> if (role == 0) "${u.pin} (pin ${pin.number}) → OLED SCL; the module pulls it up (4.7 kΩ)"
                else "${u.pin} (pin ${pin.number}) → OLED SDA, pulled up on the module; VCC → 3V3, GND → GND, " +
                    "address 0x%02X (SA0 to %s)".format(java.util.Locale.ROOT, p.i2cAddr, if (p.i2cAddr == 0x3D) "3V3" else "GND")
            PartType.MATRIX -> when (role) {
                0 -> "${u.pin} (pin ${pin.number}) → MAX7219 CS (LOAD); VCC → 5 V, GND → GND"
                1 -> "${u.pin} (pin ${pin.number}) → MAX7219 CLK"
                else -> "${u.pin} (pin ${pin.number}) → MAX7219 DIN, on the module's input end"
            }
            PartType.TFT -> when (role) {
                0 -> "${u.pin} (pin ${pin.number}) → display CS; VCC → 3V3, GND → GND, BLK → 3V3"
                1 -> "${u.pin} (pin ${pin.number}) → display SCL (the SPI clock, despite the name)"
                2 -> "${u.pin} (pin ${pin.number}) → display SDA (SPI data in, MOSI)"
                3 -> "${u.pin} (pin ${pin.number}) → display DC (low: command, high: data)"
                else -> "${u.pin} (pin ${pin.number}) → display RES (low: reset)"
            }
            PartType.FLASH -> when (role) {
                0 -> "${u.pin} (pin ${pin.number}) → ${p.flashName} CS; WP and HOLD → 3V3, VCC → 3V3, GND → GND, 100 nF from VCC to GND"
                1 -> "${u.pin} (pin ${pin.number}) → ${p.flashName} CLK"
                2 -> "${u.pin} (pin ${pin.number}) → ${p.flashName} DI (data into the flash)"
                else -> "${u.pin} (pin ${pin.number}) ← ${p.flashName} DO (data out of the flash)"
            }
        }
    }

    fun wiringFs(b: Board, chip: ChipPins, project: String): String {
        val sb = StringBuilder()
        sb.append("\\ wiring.fs: the board \"").append(project).append("\", written by Solderless.\n")
        sb.append("\\ It is rewritten when the board changes. Load it before your own code.\n")
        for (p in b.parts.sortedBy { it.id }) sb.append('\n').append(partBlock(b, p, chip))
        return sb.toString()
    }

    /** A part's block of wiring.fs: a comment and the constants for each of its pins */
    fun partBlock(b: Board, p: Part, chip: ChipPins): String {
        val n = b.nameOf(p)
        val sb = StringBuilder()
        for ((role, u) in p.pins.withIndex()) {
            val pin = chip.gpio(u.pin) ?: continue
            val rn = p.type.roles[role]
            val base = if (rn.isEmpty()) n else "$n-$rn"
            sb.append("\\ ").append(describe(b, p, chip, role)).append('\n')
            sb.append(hex(ChipPins.gpioBase(pin.port))).append(" constant ").append(base).append("-port  \\ GPIO")
                .append('A' + pin.port).append('\n')
            sb.append(pin.bit).append(" constant ").append(base).append("-pin\n")
            when (val f = u.function) {
                is Function.Timer -> {
                    if (role == 0 || p.type != PartType.RGB_LED)
                        sb.append(hex(ChipPins.BASE[f.timer]!!)).append(" constant ").append(n).append("-timer  \\ ").append(f.timer).append('\n')
                    sb.append(f.channel).append(" constant ").append(base).append("-ch\n")
                    sb.append(f.af).append(" constant ").append(base).append("-af\n")
                }
                is Function.Adc -> {
                    sb.append(hex(ChipPins.BASE[f.adc]!!)).append(" constant ").append(base).append("-adc  \\ ").append(f.adc).append('\n')
                    sb.append(f.channel).append(" constant ").append(base).append("-ch\n")
                }
                is Function.Dac -> sb.append(f.channel).append(" constant ").append(base).append("-dac-ch  \\ DAC1 OUT")
                    .append(f.channel).append('\n')
                is Function.I2c -> {
                    if (role == 0) {
                        sb.append(hex(ChipPins.BASE[f.periph]!!)).append(" constant ").append(n).append("-i2c  \\ ").append(f.periph).append('\n')
                        sb.append("$%02X".format(java.util.Locale.ROOT, p.i2cAddr)).append(" constant ").append(n).append("-addr\n")
                    }
                    sb.append(f.af).append(" constant ").append(base).append("-af\n")
                }
                is Function.Spi -> {
                    if (f.signal == "SCK") sb.append(hex(ChipPins.BASE[f.periph]!!)).append(" constant ").append(n).append("-spi  \\ ").append(f.periph).append('\n')
                    sb.append(f.af).append(" constant ").append(base).append("-af\n")
                }
                is Function.Gpio -> {}
            }
        }
        return sb.toString()
    }

    fun wiringList(b: Board, chip: ChipPins, project: String): String {
        val sb = StringBuilder("Wiring list: ").append(project).append("\nSTM32G431RB (LQFP64)\n\n")
        sb.append("PA2 (pin 14) → USB-serial RX; PA3 (pin 17) ← USB-serial TX; GND to GND\n")
        for (p in b.parts.sortedBy { it.id }) for (role in p.pins.indices) sb.append(describe(b, p, chip, role)).append('\n')
        sb.append("\nSupport circuit\n")
        for ((v, w) in Passives.support) sb.append(v).append(": ").append(w).append('\n')
        return sb.toString()
    }

    fun partsList(b: Board, project: String): String {
        val items = LinkedHashMap<String, Int>()
        fun add(s: String, n: Int = 1) { items[s] = (items[s] ?: 0) + n }
        add("STM32G431RBT6 on an LQFP64 adapter")
        add("USB-serial adapter, 3.3 V")
        for (p in b.parts.sortedBy { it.id }) {
            when (p.type) {
                PartType.LED -> add("LED ${p.colour.title}, ${p.sizeMm} mm")
                PartType.RGB_LED -> add("RGB LED, 5 mm, common ${if (p.activeHigh) "cathode" else "anode"}")
                PartType.BUTTON -> add("push button, 6 mm")
                PartType.POT -> add("potentiometer 10 kΩ, linear")
                PartType.SPEAKER -> add("speaker 8 Ω, small")
                PartType.PIEZO -> add("piezo buzzer, passive")
                PartType.OLED -> add("OLED module, SSD1306, 128 × 64, 0.96\", I2C (4 pins)")
                PartType.FLASH -> add("SPI flash module, ${p.flashName} (8 pins)")
                PartType.MATRIX -> add(if (p.matrixChips == 1) "LED matrix module, MAX7219, 8 × 8" else "LED matrix module, MAX7219, ${p.matrixChips} in 1, ${8 * p.matrixChips} × 8")
                PartType.TFT -> add(if (p.tftRows == 320) "colour display module, ST7789, 240 × 320, 2.0\" IPS" else "colour display module, ST7789, 240 × 240, 1.3\" IPS, with CS")
            }
            for (x in Passives.forPart(p).filter { it.kind != "on the module" && it.kind != "wire" }) add(if (x.kind == "resistor") "resistor ${x.value}" else "${x.kind} ${x.value}")
        }
        add("capacitor 100 nF", 6); add("capacitor 1 µF"); add("capacitor 4.7 µF"); add("resistor 10 kΩ (BOOT0)")
        val sb = StringBuilder("Parts list: ").append(project).append("\n\n")
        for ((k, v) in items) sb.append(v).append(" × ").append(k).append('\n')
        return sb.toString()
    }
}
