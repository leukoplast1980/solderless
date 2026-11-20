package io.github.leukoplast1980.solderless.board

/**
 * Ready-to-use Forth for a part on the board: its wiring.fs constants, a
 * word that sets the pins and peripherals up, words that use the part, and
 * a demo that runs until a key is pressed. The code follows the part's
 * settings (which level lights an LED, which pull resistor a button has) and
 * its pins (GPIO, timer channel, ADC or DAC), so it can be pasted as it is.
 * Everything runs on the 16 MHz the firmware starts with.
 */
object Samples {

    private const val AHB2ENR = "\$4002104C"
    private const val APB1ENR1 = "\$40021058"
    private const val APB2ENR = "\$40021060"
    private const val EXTI = 0x40010400L
    private const val SYSCFG = 0x40010000L

    /** RCC enable bit of each I2C */
    private val I2C_CLOCK = mapOf("I2C1" to 21, "I2C2" to 22, "I2C3" to 30)

    /** RCC enable of each SPI: register, its name, bit */
    private val SPI_CLOCK = mapOf(
        "SPI1" to Triple(APB2ENR, "APB2ENR", 12), "SPI2" to Triple(APB1ENR1, "APB1ENR1", 14),
        "SPI3" to Triple(APB1ENR1, "APB1ENR1", 15),
    )

    /** RCC enable bit of each timer: register, its name, bit */
    private val TIMER_CLOCK = mapOf(
        "TIM2" to Triple(APB1ENR1, "APB1ENR1", 0), "TIM3" to Triple(APB1ENR1, "APB1ENR1", 1),
        "TIM4" to Triple(APB1ENR1, "APB1ENR1", 2),
        "TIM1" to Triple(APB2ENR, "APB2ENR", 11), "TIM8" to Triple(APB2ENR, "APB2ENR", 13),
        "TIM15" to Triple(APB2ENR, "APB2ENR", 16), "TIM16" to Triple(APB2ENR, "APB2ENR", 17),
        "TIM17" to Triple(APB2ENR, "APB2ENR", 18),
    )
    /** These have a break unit: their outputs stay off until BDTR.MOE is set */
    private val ADVANCED = setOf("TIM1", "TIM8", "TIM15", "TIM16", "TIM17")

    private val MS = listOf(
        "\\ ms: wait n milliseconds, counted by SysTick at 16 MHz" to "",
        "\\ (lib_systick uses SysTick too: with it loaded, use its delay-ms)" to "",
        ": ms ( n -- )" to "",
        "  15999 \$E000E014 !  0 \$E000E018 !  5 \$E000E010 !" to "SysTick: wrap every 16000 cycles",
        "  0 ?do begin \$E000E010 @ \$10000 and until loop" to "count the wraps",
        "  0 \$E000E010 ! ;" to "SysTick off again",
    )

    private fun hex(v: Long) = "$" + "%X".format(v)

    /** Lines of code with their comments, aligned per paragraph */
    private class Code {
        val lines = ArrayList<Pair<String, String>>()
        fun add(code: String, comment: String = "") { lines += code to comment }
        fun gap() { if (lines.isNotEmpty() && lines.last().first.isNotEmpty()) lines += "" to "" }
        fun all(l: List<Pair<String, String>>) { lines += l }
        fun text(): String {
            val sb = StringBuilder()
            var i = 0
            while (i < lines.size) {
                var j = i
                while (j < lines.size && lines[j].first.isNotEmpty()) j++
                val para = lines.subList(i, j)
                val col = (para.filter { it.second.isNotEmpty() }.maxOfOrNull { it.first.length } ?: 0).coerceAtMost(48) + 2
                for ((c, k) in para) {
                    sb.append(c)
                    if (k.isNotEmpty()) sb.append(" ".repeat((col - c.length).coerceAtLeast(1))).append("\\ ").append(k)
                    sb.append('\n')
                }
                if (j < lines.size) sb.append('\n')
                i = j + 1
            }
            return sb.toString()
        }
    }

    fun forPart(b: Board, chip: ChipPins, p: Part): String {
        val n = b.nameOf(p)
        val c = Code()
        c.add("\\ $n: ${headline(p)}")
        c.add("\\ Paste all of it into the console, or put it in a file after wiring.fs.")
        c.gap()
        for (l in Wiring.partBlock(b, p, chip).trimEnd().lines()) c.add(l)
        c.gap()
        c.all(MS)
        c.gap()
        when (p.type) {
            PartType.LED -> if (p.pins[0].function is Function.Timer) dimmableLed(c, n, chip, p) else led(c, n, chip, p)
            PartType.RGB_LED -> if (p.pins.all { it.function is Function.Timer }) rgbPwm(c, n, chip, p) else rgbGpio(c, n, chip, p)
            PartType.BUTTON -> button(c, n, chip, p)
            PartType.POT -> pot(c, n, chip, p)
            PartType.PIEZO, PartType.SPEAKER ->
                if (p.pins[0].function is Function.Dac) dacTone(c, n, chip, p) else pwmTone(c, n, chip, p)
            PartType.OLED -> oled(c, n, chip, p)
            PartType.FLASH -> flash(c, n, chip, p)
        }
        return c.text()
    }

    private fun headline(p: Part): String = when (p.type) {
        PartType.LED -> "${p.colour.title} LED, lit while ${p.pins[0].pin} is ${if (p.activeHigh) "high" else "low"}" +
            if (p.pins[0].function is Function.Timer) ", dimmed by ${p.pins[0].function}" else ""
        PartType.RGB_LED -> "RGB LED, common ${if (p.activeHigh) "cathode" else "anode"}: " +
            p.pins.withIndex().joinToString(", ") { (r, u) -> "${p.type.roles[r]} on ${u.pin}" }
        PartType.BUTTON -> "push button from ${p.pins[0].pin} to ${if (p.toVcc) "3V3" else "GND"}"
        PartType.POT -> "potentiometer, wiper on ${p.pins[0].pin} (${p.pins[0].function})"
        PartType.SPEAKER -> "speaker, driven by ${p.pins[0].pin} (${p.pins[0].function})"
        PartType.PIEZO -> "piezo buzzer on ${p.pins[0].pin} (${p.pins[0].function})"
        PartType.OLED -> "OLED display, SSD1306 128 × 64, on ${p.i2c}: SCL ${p.pins[0].pin}, SDA ${p.pins[1].pin}, address " +
            "$%02X".format(java.util.Locale.ROOT, p.i2cAddr)
        PartType.FLASH -> "${p.flashName} flash, ${p.flashMbit / 8} MB, on ${p.spi}: CS ${p.pins[0].pin}, CLK ${p.pins[1].pin}, " +
            "DI ${p.pins[2].pin}, DO ${p.pins[3].pin}"
    }

    // ---- pieces --------------------------------------------------------

    private fun port(chip: ChipPins, u: PinUse) = chip.gpio(u.pin)!!.port
    private fun bit(chip: ChipPins, u: PinUse) = chip.gpio(u.pin)!!.bit

    private fun gpioClocks(c: Code, chip: ChipPins, uses: List<PinUse>) {
        for (port in uses.map { port(chip, it) }.distinct().sorted())
            c.add("  1 $port lshift $AHB2ENR bis!", "RCC AHB2ENR: clock for GPIO${'A' + port}")
    }

    /** MODER for one pin: 00 input, 01 output, 10 alternate function, 11 analog */
    private fun mode(c: Code, base: String, bits: String, what: String, pushPull: Boolean = true) {
        if (bits == "00") { c.add("  %11 $base-pin 2* lshift $base-port bic!", "MODER: 00 = $what"); return }
        if (bits != "11") c.add("  %11 $base-pin 2* lshift $base-port bic!", "MODER: clear the two bits")
        c.add("  %$bits $base-pin 2* lshift $base-port bis!", "MODER: $bits = $what")
        // An output drives both levels; the pin may have been open-drain before
        if (pushPull && (bits == "01" || bits == "10")) c.add("  1 $base-pin lshift $base-port 4 + bic!", "OTYPER: push-pull")
    }

    private fun altFunction(c: Code, base: String, pinBit: Int, f: Function) {
        val (af, what) = when (f) {
            is Function.Timer -> f.af to "${f.timer} CH${f.channel}"
            is Function.I2c -> f.af to "${f.periph} ${f.signal}"
            is Function.Spi -> f.af to "${f.periph} ${f.signal}"
            else -> return
        }
        val (shift, off, reg) = if (pinBit < 8) Triple("$base-pin 4 *", "\$20", "AFRL") else Triple("$base-pin 8 - 4 *", "\$24", "AFRH")
        c.add("  \$F $shift lshift $base-port $off + bic!", "$reg: clear the four bits")
        c.add("  $base-af $shift lshift $base-port $off + bis!", "$reg: AF$af = $what")
    }

    private fun timerClock(c: Code, timer: String) {
        val (reg, name, b) = TIMER_CLOCK.getValue(timer)
        c.add("  1 $b lshift $reg bis!", "RCC $name: clock for $timer")
    }

    /** PWM mode 1 on a channel; inverted when the part lights or sounds on a low pin */
    private fun pwmChannel(c: Code, t: String, ch: Int, inverted: Boolean, what: String = "") {
        val (off, reg) = if (ch <= 2) "\$18" to "CCMR1" else "\$1C" to "CCMR2"
        val (mask, value) = if (ch % 2 == 1) "\$FF" to "\$68" else "\$FF00" to "\$6800"
        c.add("  $mask $t $off + bic!", "$reg: clear channel $ch")
        c.add("  $value $t $off + bis!", "$reg: channel $ch in PWM mode 1, CCR$ch preloaded")
        val sh = 4 * (ch - 1)
        c.add("  ${hex(0xFL shl sh)} $t \$20 + bic!", "CCER: clear channel $ch")
        c.add("  ${hex((if (inverted) 3L else 1L) shl sh)} $t \$20 + bis!",
            "CCER: CC${ch}E, output on" + if (inverted) "; CC${ch}P, inverted$what" else "")
    }

    private fun ccr(ch: Int) = "\$" + "%X".format(0x34 + 4 * (ch - 1))

    private fun timerStart(c: Code, t: String, timer: String) {
        if (timer in ADVANCED) c.add("  \$8000 $t \$44 + bis!", "BDTR: MOE, outputs on ($timer needs it)")
        c.add("  1 $t \$14 + !", "EGR: UG, load PSC and ARR now")
        c.add("  \$81 $t bis!", "CR1: ARPE and CEN, the timer runs")
    }

    private fun tryIt(c: Code, s: String) { c.gap(); c.add("\\ Try it: $s") }

    // ---- parts ---------------------------------------------------------

    private fun led(c: Code, n: String, chip: ChipPins, p: Part) {
        val u = p.pins[0]
        val high = "1 $n-pin lshift"
        val low = "1 $n-pin lshift 16 lshift"
        c.add(": $n-on ( -- )")
        c.add("  ${if (p.activeHigh) high else low} $n-port \$18 + ! ;",
            if (p.activeHigh) "BSRR: set, the pin goes high" else "BSRR: reset, the pin goes low")
        c.add(": $n-off ( -- )")
        c.add("  ${if (p.activeHigh) low else high} $n-port \$18 + ! ;",
            if (p.activeHigh) "BSRR: reset, the pin goes low" else "BSRR: set, the pin goes high")
        c.add(": $n-toggle ( -- )")
        c.add("  1 $n-pin lshift $n-port \$14 + xor! ;", "ODR: flip the pin")
        c.add(": $n-init ( -- )")
        gpioClocks(c, chip, listOf(u))
        c.add("  $n-off", "dark from the start")
        mode(c, n, "01", "output")
        c.lines[c.lines.size - 1] = c.lines.last().first + " ;" to c.lines.last().second
        c.gap()
        c.add("\\ Blink twice a second until a key is pressed")
        c.add(": $n-blink ( -- )")
        c.add("  $n-init begin $n-toggle 250 ms key? until $n-off ;")
        tryIt(c, "$n-init $n-on   or   $n-blink (Enter stops it)")
    }

    private fun dimmableLed(c: Code, n: String, chip: ChipPins, p: Part) {
        val u = p.pins[0]
        val f = u.function as Function.Timer
        val t = "$n-timer"
        c.add(": $n-init ( -- )")
        gpioClocks(c, chip, listOf(u))
        mode(c, n, "10", "alternate function")
        altFunction(c, n, bit(chip, u), f)
        timerClock(c, f.timer)
        c.add("  15 $t \$28 + !", "PSC: 16 MHz / 16, a tick every microsecond")
        c.add("  999 $t \$2C + !", "ARR: 1000 ticks a period, 1 kHz")
        pwmChannel(c, t, f.channel, !p.activeHigh, ": the LED lights while the pin is low")
        c.add("  0 $t ${ccr(f.channel)} + !", "CCR${f.channel}: 0, off")
        timerStart(c, t, f.timer)
        c.lines[c.lines.size - 1] = c.lines.last().first + " ;" to c.lines.last().second
        c.add(": $n-bright ( percent -- )", "0 = off, 100 = full")
        c.add("  10 * $t ${ccr(f.channel)} + ! ;", "CCR${f.channel}: lit for this many of the 1000 ticks")
        c.add(": $n-on ( -- ) 100 $n-bright ;")
        c.add(": $n-off ( -- ) 0 $n-bright ;")
        c.gap()
        c.add("\\ Fade up and down until a key is pressed")
        c.add(": $n-fade ( -- )")
        c.add("  $n-init")
        c.add("  begin")
        c.add("    101 0 do i $n-bright 10 ms loop")
        c.add("    0 100 do i $n-bright 10 ms -1 +loop")
        c.add("  key? until $n-off ;")
        tryIt(c, "$n-init 30 $n-bright   or   $n-fade (Enter stops it)")
    }

    private fun rgbPwm(c: Code, n: String, chip: ChipPins, p: Part) {
        val fs = p.pins.map { it.function as Function.Timer }
        val first = fs[0].timer
        fun t(r: Int) = if (fs[r].timer == first) "$n-timer" else hex(ChipPins.BASE[fs[r].timer]!!)
        val timers = fs.indices.distinctBy { fs[it].timer }
        val inv = !p.activeHigh
        c.add(": $n-init ( -- )")
        gpioClocks(c, chip, p.pins)
        for ((r, u) in p.pins.withIndex()) {
            val base = "$n-${p.type.roles[r]}"
            mode(c, base, "10", "alternate function")
            altFunction(c, base, bit(chip, u), fs[r])
        }
        for (r in timers) {
            timerClock(c, fs[r].timer)
            c.add("  15 ${t(r)} \$28 + !", "PSC: 16 MHz / 16, a tick every microsecond")
            c.add("  999 ${t(r)} \$2C + !", "ARR: 1000 ticks a period, 1 kHz")
        }
        for (r in p.pins.indices) {
            pwmChannel(c, t(r), fs[r].channel, inv, if (r == 0 && inv) ": common anode, a colour lights while its pin is low" else "")
            c.add("  0 ${t(r)} ${ccr(fs[r].channel)} + !", "CCR${fs[r].channel}: ${p.type.roles[r]} off")
        }
        for (r in timers) timerStart(c, t(r), fs[r].timer)
        c.lines[c.lines.size - 1] = c.lines.last().first + " ;" to c.lines.last().second
        c.add(": $n-colour ( red green blue -- )", "each 0..100 %")
        for (r in 2 downTo 0)
            c.add("  10 * ${t(r)} ${ccr(fs[r].channel)} +" + (if (r == 0) " ! ;" else " !"), "CCR${fs[r].channel}: ${p.type.roles[r]}")
        c.add(": $n-off ( -- ) 0 0 0 $n-colour ;")
        c.gap()
        c.add("\\ Fade red, green, blue, red until a key is pressed")
        c.add(": $n-rainbow ( -- )")
        c.add("  $n-init")
        c.add("  begin")
        c.add("    100 0 do 100 i -  i  0  $n-colour 20 ms loop", "red to green")
        c.add("    100 0 do 0  100 i -  i  $n-colour 20 ms loop", "green to blue")
        c.add("    100 0 do i  0  100 i -  $n-colour 20 ms loop", "blue to red")
        c.add("  key? until $n-off ;")
        tryIt(c, "$n-init 100 40 0 $n-colour (orange)   or   $n-rainbow (Enter stops it)")
    }

    private fun rgbGpio(c: Code, n: String, chip: ChipPins, p: Part) {
        val names = p.type.roles.map { "$n-$it" }
        c.add(": $n-colour ( red green blue -- )", "each: 0 = off, else on")
        for (r in 2 downTo 0) {
            val hi = "1 ${names[r]}-pin lshift"
            val lo = "1 ${names[r]}-pin lshift 16 lshift"
            c.add("  0<> if ${if (p.activeHigh) hi else lo} else ${if (p.activeHigh) lo else hi} then", "BSRR: ${p.type.roles[r]} on or off")
            c.add("  ${names[r]}-port \$18 + !" + if (r == 0) " ;" else "")
        }
        c.add(": $n-off ( -- ) 0 0 0 $n-colour ;")
        c.add(": $n-init ( -- )")
        gpioClocks(c, chip, p.pins)
        for (b in names) mode(c, b, "01", "output")
        c.add("  $n-off ;")
        c.gap()
        c.add("\\ Red, green, blue in turn until a key is pressed")
        c.add(": $n-cycle ( -- )")
        c.add("  $n-init")
        c.add("  begin")
        c.add("    1 0 0 $n-colour 300 ms  0 1 0 $n-colour 300 ms  0 0 1 $n-colour 300 ms")
        c.add("  key? until $n-off ;")
        tryIt(c, "$n-init 1 1 0 $n-colour (yellow)   or   $n-cycle (Enter stops it)")
    }

    private fun button(c: Code, n: String, chip: ChipPins, p: Part) {
        val u = p.pins[0]
        val line = bit(chip, u)
        val port = port(chip, u)
        c.add(": $n-init ( -- )")
        gpioClocks(c, chip, listOf(u))
        mode(c, n, "00", "input")
        c.add("  %11 $n-pin 2* lshift $n-port \$0C + bic!",
            if (p.externalPull) "PUPDR: 00, no pull: the 10 kΩ resistor holds the pin ${if (p.toVcc) "low" else "high"}" else "PUPDR: clear the two bits")
        if (!p.externalPull)
            c.add("  %${if (p.toVcc) "10" else "01"} $n-pin 2* lshift $n-port \$0C + bis!",
                if (p.toVcc) "PUPDR: 10 = pull-down, low until pressed" else "PUPDR: 01 = pull-up, high until pressed")
        if (p.interrupt) {
            val cr = SYSCFG + 8 + 4 * (line / 4)
            val edge = if (p.toVcc) "RTSR1" to 0x08L else "FTSR1" to 0x0CL
            c.add("  1 0 lshift $APB2ENR bis!", "RCC APB2ENR: clock for SYSCFG")
            val crName = "SYSCFG EXTICR${line / 4 + 1}"
            if (port == 0) c.add("  \$F ${4 * (line % 4)} lshift ${hex(cr)} bic!", "$crName: 0, EXTI$line watches GPIOA")
            else {
                c.add("  \$F ${4 * (line % 4)} lshift ${hex(cr)} bic!", "$crName: clear EXTI$line's port")
                c.add("  $port ${4 * (line % 4)} lshift ${hex(cr)} bis!", "$crName: $port, EXTI$line watches GPIO${'A' + port}")
            }
            c.add("  1 $line lshift ${hex(EXTI + edge.second)} bis!",
                "EXTI ${edge.first}: a ${if (p.toVcc) "rising" else "falling"} edge (the press) sets the flag")
            c.add("  1 $line lshift ${hex(EXTI)} bis!", "EXTI IMR1: line $line on (its interrupt stays off in the NVIC)")
            c.add("  1 $line lshift ${hex(EXTI + 0x14)} ! ;", "EXTI PR1: clear the flag")
        } else c.lines[c.lines.size - 1] = c.lines.last().first + " ;" to c.lines.last().second
        c.add(": $n? ( -- flag )", "true while pressed")
        c.add("  $n-port \$10 + @  1 $n-pin lshift and ${if (p.toVcc) "0<>" else "0="} ;",
            "IDR: ${if (p.toVcc) "high" else "low"} = pressed")
        if (p.interrupt) {
            c.add(": $n-pressed? ( -- flag )", "true once for each press")
            c.add("  ${hex(EXTI + 0x14)} @ 1 $line lshift and 0<>", "EXTI PR1: a press since last time?")
            c.add("  dup if 5 ms then", "let the contacts settle: they bounce for about 2 ms")
            c.add("  1 $line lshift ${hex(EXTI + 0x14)} !", "PR1: clear it and the bounces, by writing 1")
            c.add("  $n? and ;", "and still pressed: not a bounce on release")
            c.gap()
            c.add("\\ Count presses until a key is pressed. The EXTI flag marks each")
            c.add("\\ press, so it counts once however long the button is held.")
            c.add(": $n-count ( -- )")
            c.add("  $n-init 0")
            c.add("  begin")
            c.add("    $n-pressed? if 1+ dup . cr then")
            c.add("    10 ms")
            c.add("  key? until drop ;")
            tryIt(c, "$n-init $n? .   or   $n-count (Enter stops it)")
        } else {
            c.gap()
            c.add("\\ Report each press and release until a key is pressed")
            c.add(": $n-watch ( -- )")
            c.add("  $n-init $n?")
            c.add("  begin")
            c.add("    $n? tuck <> if dup if .\" pressed\" else .\" released\" then cr then")
            c.add("    10 ms", "reading every 10 ms rides over the bounce")
            c.add("  key? until drop ;")
            tryIt(c, "$n-init $n? .   or   $n-watch (Enter stops it)")
        }
    }

    private fun pot(c: Code, n: String, chip: ChipPins, p: Part) {
        val u = p.pins[0]
        val f = u.function as? Function.Adc ?: return
        val a = "$n-adc"
        val (smpr, smprOff) = if (f.channel < 10) "SMPR1" to "\$14" else "SMPR2" to "\$18"
        val sh = 3 * (f.channel % 10)
        c.add(": $n-init ( -- )")
        gpioClocks(c, chip, listOf(u))
        mode(c, n, "11", "analog")
        c.add("  1 13 lshift $AHB2ENR bis!", "RCC AHB2ENR: clock for ADC1 and ADC2")
        c.add("  1 16 lshift \$50000308 bis!", "ADC12 CCR: CKMODE 01, the ADC runs on the 16 MHz HCLK")
        c.add("  $a 8 + @ 1 and 0= if", "CR: ADEN clear? (another part may have set it up)")
        c.add("    1 29 lshift $a 8 + bic!", "CR: DEEPPWD off, out of deep power-down")
        c.add("    1 28 lshift $a 8 + bis!", "CR: ADVREGEN, voltage regulator on")
        c.add("    1 ms", "the regulator needs 20 µs")
        c.add("    1 31 lshift $a 8 + bis!", "CR: ADCAL, calibrate")
        c.add("    begin $a 8 + @ 1 31 lshift and 0= until", "until ADCAL clears")
        c.add("    1 $a 8 + bis!", "CR: ADEN, ADC on")
        c.add("    begin $a @ 1 and until", "ISR: until ADRDY")
        c.add("  then")
        c.add("  %111 $sh lshift $a $smprOff + bic!", "$smpr: clear IN${f.channel}'s sample time")
        c.add("  %100 $sh lshift $a $smprOff + bis! ;", "$smpr: 47.5 cycles, time to charge through the pot")
        c.add(": $n@ ( -- u )", "0 at GND .. 4095 at 3.3 V")
        c.add("  $n-ch 6 lshift $a \$30 + !", "SQR1: one conversion, of IN${f.channel}")
        c.add("  %100 $a 8 + bis!", "CR: ADSTART")
        c.add("  begin $a @ %100 and until", "ISR: until EOC")
        c.add("  $a \$40 + @ ;", "DR: the result; reading it clears EOC")
        c.add(": $n-mv ( -- mV ) $n@ 3300 4095 */ ;")
        c.gap()
        c.add("\\ Print the voltage when it changes, until a key is pressed")
        c.add(": $n-watch ( -- )")
        c.add("  $n-init -100")
        c.add("  begin")
        c.add("    $n-mv 2dup - abs 10 > if nip dup . .\" mV\" cr else drop then")
        c.add("    100 ms")
        c.add("  key? until drop ;")
        tryIt(c, "$n-init $n@ .   or   $n-watch (Enter stops it)")
    }

    private fun oled(c: Code, n: String, chip: ChipPins, p: Part) {
        val f = p.pins[0].function as? Function.I2c ?: return
        val i = "$n-i2c"
        c.add(": $n-isr ( -- u ) $i \$18 + @ ;", "I2C ISR")
        c.add(": $n-go ( n -- )", "write n bytes to the OLED, STOP after them")
        c.add("  16 lshift  $n-addr 1 lshift or", "CR2: NBYTES, the address")
        c.add("  1 25 lshift or  1 13 lshift or  $i 4 + ! ;", "AUTOEND, START")
        c.add(": >$n ( b -- )", "one byte")
        c.add("  begin $n-isr 2 and until  $i \$28 + ! ;", "wait for TXIS, then TXDR")
        c.add(": $n-end ( -- )", "the transfer's STOP")
        c.add("  begin $n-isr \$20 and until  \$20 $i \$1C + ! ;", "wait for STOPF, clear it in ICR")
        c.add(": $n? ( -- flag )", "does the OLED answer at its address?")
        c.add("  0 $n-go  begin $n-isr \$20 and until", "the address alone")
        c.add("  $n-isr \$10 and 0=  \$30 $i \$1C + ! ;", "no NACKF: it answered")
        c.add(": $n-cmd ( c -- ) 2 $n-go  0 >$n  >$n  $n-end ;", "control byte 00: a command follows")
        c.gap()
        c.add("1024 buffer: $n-buf", "the picture: 8 pages of 128 columns")
        c.add(": $n-clear ( -- ) $n-buf 1024 0 fill ;")
        c.add(": $n-pixel ( x y -- )", "x 0..127 from the left, y 0..63 from the top")
        c.add("  over 128 u< over 64 u< and 0= if 2drop exit then")
        c.add("  dup 7 and 1 swap lshift -rot", "the bit in its byte")
        c.add("  3 rshift 7 lshift + $n-buf + cbis! ;", "the byte: page y/8, column x")
        c.add(": $n-show ( -- )", "the picture to the display")
        c.add("  \$21 $n-cmd 0 $n-cmd 127 $n-cmd", "columns 0 to 127")
        c.add("  \$22 $n-cmd 0 $n-cmd 7 $n-cmd", "pages 0 to 7")
        c.add("  8 0 do")
        c.add("    129 $n-go  \$40 >$n", "control byte 40: data follows")
        c.add("    128 0 do  j 7 lshift i + $n-buf + c@ >$n  loop")
        c.add("    $n-end")
        c.add("  loop ;")
        c.gap()
        c.add(": $n-init ( -- )")
        gpioClocks(c, chip, p.pins)
        c.add("  1 ${I2C_CLOCK[f.periph]} lshift $APB1ENR1 bis!", "RCC APB1ENR1: clock for ${f.periph}")
        for ((r, u) in p.pins.withIndex()) {
            val base = "$n-${p.type.roles[r]}"
            mode(c, base, "10", "alternate function", pushPull = false)
            c.add("  1 $base-pin lshift $base-port 4 + bis!", "OTYPER: open-drain, as I2C needs")
            altFunction(c, base, bit(chip, u), u.function)
        }
        c.add("  0 $i !", "CR1: PE off while setting the timing")
        c.add("  \$00310F13 $i \$10 + !", "TIMINGR: SCL about 380 kHz from 16 MHz")
        c.add("  1 $i !", "CR1: PE, on")
        c.add("  $n? 0= if .\" The OLED does not answer at \" $n-addr hex. cr exit then")
        c.add("  \$AE $n-cmd", "display off while setting it up")
        c.add("  \$D5 $n-cmd \$80 $n-cmd", "clock: the default")
        c.add("  \$A8 $n-cmd 63 $n-cmd", "multiplex: 64 lines")
        c.add("  \$D3 $n-cmd 0 $n-cmd  \$40 $n-cmd", "no offset, start at line 0")
        c.add("  \$8D $n-cmd \$14 $n-cmd", "charge pump on: without it the panel stays dark")
        c.add("  \$20 $n-cmd 0 $n-cmd", "horizontal addressing")
        c.add("  \$A1 $n-cmd  \$C8 $n-cmd", "column 0 on the left, row 0 at the top")
        c.add("  \$DA $n-cmd \$12 $n-cmd", "COM pins as 128 × 64 panels are wired")
        c.add("  \$81 $n-cmd \$CF $n-cmd", "contrast")
        c.add("  \$A4 $n-cmd  \$A6 $n-cmd", "show the RAM, not inverted")
        c.add("  $n-clear $n-show", "the RAM holds noise at power-on")
        c.add("  \$AF $n-cmd ;", "display on")
        c.gap()
        c.add("\\ A ball bouncing inside a frame until a key is pressed")
        c.add("10 variable $n-x  10 variable $n-y  2 variable $n-dx  1 variable $n-dy")
        c.add(": $n-frame ( -- )")
        c.add("  128 0 do i 0 $n-pixel  i 63 $n-pixel loop")
        c.add("  64 0 do 0 i $n-pixel  127 i $n-pixel loop ;")
        c.add(": $n-ball ( -- )")
        c.add("  4 0 do 4 0 do  $n-x @ i +  $n-y @ j +  $n-pixel  loop loop ;")
        c.add(": $n-move ( -- )")
        c.add("  $n-dx @ $n-x +!  $n-x @ 2 < $n-x @ 122 > or if $n-dx @ negate $n-dx ! then")
        c.add("  $n-dy @ $n-y +!  $n-y @ 2 < $n-y @ 58 > or if $n-dy @ negate $n-dy ! then ;")
        c.add(": $n-demo ( -- )")
        c.add("  $n-init  $n? 0= if exit then")
        c.add("  begin $n-clear $n-frame $n-ball $n-show $n-move key? until ;")
        tryIt(c, "$n-init 10 20 $n-pixel $n-show   or   $n-demo (Enter stops it)")
    }

    private fun flash(c: Code, n: String, chip: ChipPins, p: Part) {
        val f = p.pins[1].function as? Function.Spi ?: return
        val sp = "$n-spi"
        val (reg, regName, clockBit) = SPI_CLOCK.getValue(f.periph)
        c.add(": $n-sr ( -- u ) $sp 8 + @ ;", "${f.periph} SR")
        c.add(": >$n> ( b -- b )", "one byte out, one byte in")
        c.add("  begin $n-sr 2 and until  $sp \$C + c!", "wait for TXE; c! writes one frame")
        c.add("  begin $n-sr 1 and until  $sp \$C + c@ ;", "wait for RXNE: the byte that came back")
        c.add(": $n-on ( c -- )", "CS low, then the command byte")
        c.add("  1 $n-cs-pin lshift 16 lshift $n-cs-port \$18 + !  >$n> drop ;")
        c.add(": $n-off ( -- ) 1 $n-cs-pin lshift $n-cs-port \$18 + ! ;", "CS high: the command ends here")
        c.add(": >$n-addr ( a -- )", "24-bit address, high byte first")
        c.add("  dup 16 rshift >$n> drop  dup 8 rshift >$n> drop  >$n> drop ;")
        c.add(": $n-status ( -- u ) 5 $n-on 0 >$n> $n-off ;", "status register 1: BUSY bit 0, WEL bit 1")
        c.add(": $n-wait ( -- ) begin $n-status 1 and 0= until ;", "until a program or erase is done")
        c.add(": $n-wren ( -- ) 6 $n-on $n-off ;", "write enable, before every program and erase")
        c.add(": $n-id ( -- )", "JEDEC ID: EF Winbond, 40, then the size")
        c.add("  \$9F $n-on  0 >$n> hex. 0 >$n> hex. 0 >$n> hex.  $n-off ;")
        c.add(": $n-read ( a addr u -- )", "u bytes from the flash at a to memory at addr")
        c.add("  rot 3 $n-on >$n-addr")
        c.add("  0 ?do 0 >$n> over i + c! loop drop $n-off ;")
        c.add(": $n-write ( addr u a -- )", "u bytes to the flash at a, within one 256-byte page")
        c.add("  $n-wren 2 $n-on >$n-addr")
        c.add("  0 ?do dup i + c@ >$n> drop loop drop $n-off $n-wait ;", "it programs when CS goes high")
        c.add(": $n-erase ( a -- )", "the 4 KB sector around a, back to FF")
        c.add("  $n-wren \$20 $n-on >$n-addr $n-off $n-wait ;", "takes about 45 ms")
        c.gap()
        c.add(": $n-init ( -- )")
        gpioClocks(c, chip, p.pins)
        c.add("  1 $clockBit lshift $reg bis!", "RCC $regName: clock for ${f.periph}")
        c.add("  $n-off", "CS high before it becomes an output")
        for ((r, u) in p.pins.withIndex()) {
            val base = "$n-${p.type.roles[r]}"
            if (r == 0) mode(c, base, "01", "output")
            else { mode(c, base, "10", "alternate function"); altFunction(c, base, bit(chip, u), u.function) }
        }
        c.add("  \$1700 $sp 4 + !", "CR2: 8-bit frames, RXNE after one byte (FRXTH)")
        c.add("  \$354 $sp ! ;", "CR1: master, SCK = 16 MHz / 8, SSM and SSI (CS is a GPIO), on")
        c.gap()
        c.add("\\ Erase a sector, write a line, read it back")
        c.add(": $n-hello s\" Hello from Forth\" ;")
        c.add("64 buffer: $n-buf")
        c.add(": $n-demo ( -- )")
        c.add("  $n-init $n-id cr")
        c.add("  0 $n-erase  $n-hello 0 $n-write")
        c.add("  0 $n-buf 16 $n-read  $n-buf 16 type ;")
        tryIt(c, "$n-init $n-id   or   $n-demo")
    }

    private val SCALE = "523 494 440 392 349 330 294 262"

    private fun pwmTone(c: Code, n: String, chip: ChipPins, p: Part) {
        val u = p.pins[0]
        val f = u.function as? Function.Timer ?: return
        val t = "$n-timer"
        c.add(": $n-init ( -- )")
        gpioClocks(c, chip, listOf(u))
        mode(c, n, "10", "alternate function")
        altFunction(c, n, bit(chip, u), f)
        timerClock(c, f.timer)
        c.add("  15 $t \$28 + !", "PSC: 16 MHz / 16, a tick every microsecond")
        pwmChannel(c, t, f.channel, false)
        c.add("  0 $t ${ccr(f.channel)} + !", "CCR${f.channel}: 0, quiet")
        timerStart(c, t, f.timer)
        c.lines[c.lines.size - 1] = c.lines.last().first + " ;" to c.lines.last().second
        c.add(": $n-tone ( hz -- )", "20 .. 20000 Hz, until $n-quiet")
        c.add("  1000000 swap / 1-  dup $t \$2C + !", "ARR: microseconds a period, less one")
        c.add("  1+ 2/ $t ${ccr(f.channel)} + !", "CCR${f.channel}: high for half of it")
        c.add("  1 $t \$14 + ! ;", "EGR: UG, the new period starts now")
        c.add(": $n-quiet ( -- ) 0 $t ${ccr(f.channel)} + ! ;", "CCR${f.channel}: 0, the pin stays low")
        c.gap()
        c.add("\\ Play a scale, C to C")
        c.add(": $n-scale ( -- )")
        c.add("  $n-init  $SCALE")
        c.add("  8 0 do $n-tone 250 ms loop  $n-quiet ;")
        tryIt(c, "$n-init 440 $n-tone 500 ms $n-quiet   or   $n-scale")
    }

    private fun dacTone(c: Code, n: String, chip: ChipPins, p: Part) {
        val u = p.pins[0]
        val f = u.function as Function.Dac
        val dhr = if (f.channel == 1) "\$50000808" to "DHR12R1" else "\$50000814" to "DHR12R2"
        c.add(": $n-out ( u -- ) ${dhr.first} ! ;", "DAC1 ${dhr.second}: 0 .. 4095 is 0 .. 3.3 V")
        c.add(": $n-init ( -- )")
        gpioClocks(c, chip, listOf(u))
        mode(c, n, "11", "analog")
        c.add("  1 16 lshift $AHB2ENR bis!", "RCC AHB2ENR: clock for DAC1")
        c.add("  1 ${if (f.channel == 1) 0 else 16} lshift \$50000800 bis!", "DAC1 CR: EN${f.channel}, OUT${f.channel} on")
        c.add("  0 $n-out ;", "low: no current through the speaker")
        c.add(": $n-tone ( ms hz -- )")
        c.add("  dup 8000000 swap / 1- \$E000E014 !", "SysTick: wrap twice a period")
        c.add("  0 \$E000E018 !  5 \$E000E010 !")
        c.add("  * 500 /  0 ?do", "ms × hz / 500 half periods")
        c.add("    i 1 and if 0 else 4095 then $n-out", "a square wave: high, low, ...")
        c.add("    begin \$E000E010 @ \$10000 and until", "until SysTick wraps")
        c.add("  loop  0 $n-out  0 \$E000E010 ! ;", "end low; SysTick off again")
        c.gap()
        c.add("\\ Play a scale, C to C")
        c.add(": $n-scale ( -- )")
        c.add("  $n-init  $SCALE")
        c.add("  8 0 do 250 swap $n-tone loop ;")
        tryIt(c, "$n-init 500 440 $n-tone   or   $n-scale")
    }
}
