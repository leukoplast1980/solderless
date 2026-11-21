package io.github.leukoplast1980.solderless.board

/**
 * Picks pins for parts (design section 5). Every pin is scored by what
 * using it would take away from later parts: a DAC output most, then an
 * ADC input, timer channels and bus pins. The cheapest pin that can do
 * the job wins; ties go to the lower port and pin. When nothing fits, the
 * smallest re-wire is searched: one existing part (or only one of its
 * wires) moves.
 */
class Recommender(private val chip: ChipPins, hseFitted: Boolean = false, lseFitted: Boolean = false) {

    /** Pins the recommender never hands out */
    val reserved: Map<String, String> = buildMap {
        put("PA2", "console TX"); put("PA3", "console RX")
        put("PA13", "SWD"); put("PA14", "SWD")
        if (hseFitted) { put("PF0", "crystal"); put("PF1", "crystal") }
        if (lseFitted) { put("PC14", "32 kHz crystal"); put("PC15", "32 kHz crystal") }
    }

    data class Move(val partId: Int, val role: Int, val from: PinUse, val to: PinUse)

    /** A placement for a new part, with the moves it needs (empty if none). */
    data class Proposal(val part: Part, val moves: List<Move>, val why: List<String>)

    enum class Status { FREE, SCARCE, TAKEN, UNSUITABLE }
    data class PinOption(val pin: String, val function: Function?, val status: Status, val note: String)

    // ------------------------------------------------------------------
    // Scoring

    fun cost(pin: ChipPins.Pin, used: Function?): Double {
        var c = 0.0
        if (pin.dacChannel != 0 && used !is Function.Dac) c += 10
        if (pin.adcChannels.isNotEmpty() && used !is Function.Adc) c += 3
        val tch = pin.timerChannels.filterNot { used is Function.Timer && it.timer == used.timer && it.channel == used.channel }
        c += minOf(3, tch.size).toDouble()
        if (pin.hasBusFunction) c += 1
        c += when (pin.name) {
            "PA15", "PB3", "PB4" -> 1.0     // JTAG pins: alternate function at reset
            "PB8" -> 2.0                    // BOOT0
            "PF0", "PF1" -> 2.0             // HSE crystal
            "PC14", "PC15" -> 3.0           // LSE crystal
            "PC13" -> 1.0                   // weak output
            else -> 0.0
        }
        return c
    }

    private fun scarcityNote(pin: ChipPins.Pin): String = when {
        pin.dacChannel != 0 -> "DAC"
        pin.adcChannels.isNotEmpty() -> "ADC"
        pin.timerChannels.isNotEmpty() -> pin.timerChannels.first().toString()
        pin.name == "PB8" -> "BOOT0"
        pin.hasBusFunction -> pin.af.filter { it.periph.matches(Regex("(I2C|SPI|USART|UART|LPUART)\\d?")) }
            .map { it.periph }.distinct().joinToString(", ")
        else -> ""
    }

    // ------------------------------------------------------------------
    // What is in use

    private class Usage(board: List<Part>) {
        val pins = HashMap<String, Pair<Part, Int>>()
        val timerCh = HashMap<String, Int>()          // "TIM3/2" -> part id
        val timerOwner = HashMap<String, Part>()      // first part on each timer
        val adcCh = HashSet<String>()
        val dac = HashSet<Int>()
        val extiLines = HashMap<Int, Part>()
        val i2cPins = HashMap<String, Function>()       // pin -> the bus signal on it (I2C, SPI), shareable
        init {
            for (p in board) for ((r, u) in p.pins.withIndex()) {
                pins[u.pin] = p to r
                when (val f = u.function) {
                    is Function.Timer -> { timerCh["${f.timer}/${f.channel}"] = p.id; timerOwner.putIfAbsent(f.timer, p) }
                    is Function.Adc -> adcCh += "${f.adc}/${f.channel}"
                    is Function.Dac -> dac += f.channel
                    is Function.I2c -> i2cPins[u.pin] = f
                    is Function.Spi -> i2cPins[u.pin] = f
                    else -> {}
                }
                if (p.type == PartType.BUTTON && p.interrupt) extiLines[bitOf(u.pin)] = p
            }
        }
    }

    private fun isTone(p: Part) = p.type == PartType.PIEZO || p.type == PartType.SPEAKER

    private fun timerChannels(t: String) = when (t) { "TIM15" -> 2; "TIM16", "TIM17" -> 1; else -> 4 }

    /** Functions a pin offers for one role of a part, given what is in use. */
    private fun functionsFor(part: Part, role: Int, pin: ChipPins.Pin, use: Usage, groupTimer: String?): List<Function> {
        if (!pin.isGpio || pin.name in reserved) return emptyList()
        val need = part.needs()[role]
        val out = ArrayList<Function>()
        fun timerOk(tc: ChipPins.TimerChannel): Boolean {
            if (use.timerCh.containsKey("${tc.timer}/${tc.channel}")) return false
            if (groupTimer != null && tc.timer != groupTimer) return false
            val owner = use.timerOwner[tc.timer] ?: return true
            if (owner.id == part.id) return true
            return !isTone(part) && !isTone(owner)          // a tone needs its timer's frequency to itself
        }
        when (need) {
            Need.OUTPUT -> {
                val weak = pin.name in setOf("PC13", "PC14", "PC15") && part.currentMa > 3
                val boot = pin.name == "PB8" && !part.activeHigh
                if (!weak && !boot) out += Function.Gpio
            }
            Need.INPUT -> if (!(pin.name == "PB8" && part.toVcc)) out += Function.Gpio
            Need.INPUT_EXTI -> {
                val other = use.extiLines[pin.bit]
                if ((other == null || other.id == part.id) && !(pin.name == "PB8" && part.toVcc)) out += Function.Gpio
            }
            Need.PWM -> pin.timerChannels.filter(::timerOk).forEach { out += Function.Timer(it.timer, it.channel, it.af) }
            Need.ADC -> pin.adcChannels.filter { "${it.first}/${it.second}" !in use.adcCh }
                .forEach { out += Function.Adc(it.first, it.second) }
            Need.I2C_SCL, Need.I2C_SDA -> {
                val sig = if (need == Need.I2C_SCL) "SCL" else "SDA"
                pin.i2c(sig).filter { groupTimer == null || it.periph == groupTimer }.forEach { out += Function.I2c(it.periph, sig, it.af) }
            }
            Need.SPI_SCK, Need.SPI_MOSI, Need.SPI_MISO -> {
                val sig = when (need) { Need.SPI_SCK -> "SCK"; Need.SPI_MOSI -> "MOSI"; else -> "MISO" }
                pin.spi(sig).filter { groupTimer == null || it.periph == groupTimer }.forEach { out += Function.Spi(it.periph, sig, it.af) }
            }
            // A chip select idles high, and modules often pull it up: on PB8 that would start the bootloader
            Need.CS -> if (pin.name !in setOf("PB8", "PC13", "PC14", "PC15")) out += Function.Gpio
            Need.DAC_OR_PWM -> {
                if (pin.dacChannel != 0 && pin.dacChannel !in use.dac) out += Function.Dac(pin.dacChannel)
                pin.timerChannels.filter(::timerOk).forEach { out += Function.Timer(it.timer, it.channel, it.af) }
            }
        }
        return out
    }

    private fun fnCost(part: Part, pin: ChipPins.Pin, f: Function): Double {
        var c = cost(pin, f)
        if (f is Function.Timer) {
            if (f.timer in setOf("TIM1", "TIM8", "TIM15", "TIM16", "TIM17") && timerChannels(f.timer) > 1) c += 1.0   // needs MOE
            if (isTone(part)) c += (timerChannels(f.timer) - 1) * 0.5    // a tone takes a whole timer
        }
        if (part.type == PartType.SPEAKER && f is Function.Timer && part.dacDrive) c += 5.0
        return c
    }

    // ------------------------------------------------------------------
    // Placement

    private data class Placed(val part: Part, val cost: Double)

    /** Cheapest placement of [part] with the board fixed; [fixed] pins some roles. */
    private fun place(part: Part, board: List<Part>, fixed: Map<Int, String> = emptyMap()): Placed? {
        val use = Usage(board.filter { it.id != part.id })
        val roles = part.type.roles.indices
        val busPart = part.needs().any { it == Need.I2C_SCL || it == Need.I2C_SDA }
        val spiPart = part.needs().any { it == Need.SPI_SCK }
        val timers: List<String?> = when {
            part.type == PartType.RGB_LED -> chip.gpios.flatMap { p -> p.timerChannels.map { it.timer } }.distinct().sorted()
            busPart -> chip.gpios.flatMap { p -> p.af.filter { it.periph.startsWith("I2C") }.map { it.periph } }.distinct().sorted()
            spiPart -> chip.gpios.flatMap { p -> p.af.filter { it.periph.startsWith("SPI") }.map { it.periph } }.distinct().sorted()
            else -> listOf(null)
        }
        var best: Placed? = null
        for (t in timers) {
            val taken = HashSet<String>()
            val takenCh = HashSet<String>()
            val chosen = ArrayList<PinUse>()
            var total = 0.0
            var ok = true
            for (r in roles) {
                val cands = chip.gpios.asSequence()
                    .filter { (fixed[r] == null || it.name == fixed[r]) && it.name !in taken }
                    .flatMap { p -> functionsFor(part, r, p, use, t).map { f -> Triple(p, f, fnCost(part, p, f)) } }
                    // a pin in use only as the same bus signal: the part joins the bus there, for free
                    .filter { (p, f, _) -> p.name !in use.pins || ((f is Function.I2c || f is Function.Spi) && use.i2cPins[p.name] == f) }
                    .map { (p, f, c) -> if (p.name in use.pins) Triple(p, f, 0.0) else Triple(p, f, c) }
                    .filter { (_, f, _) -> f !is Function.Timer || "${f.timer}/${f.channel}" !in takenCh }
                    .sortedWith(compareBy({ it.third }, { it.first.port }, { it.first.bit }))
                    .firstOrNull()
                if (cands == null) { ok = false; break }
                val (p, f, c) = cands
                taken += p.name
                if (f is Function.Timer) takenCh += "${f.timer}/${f.channel}"
                chosen += PinUse(p.name, f)
                total += c
            }
            if (!ok) continue
            // Keep a multi-pin part on one side of the board (pins 1-32 or 33-64)
            if (chosen.size > 1 && chosen.map { (chip.gpio(it.pin)?.number ?: 0) <= 32 }.toSet().size > 1) total += 2.0
            if (part.type == PartType.RGB_LED) {
                // red, green, blue on rising channel numbers reads best
                val byCh = chosen.sortedBy { (it.function as Function.Timer).channel }
                if (fixed.isEmpty()) { chosen.clear(); chosen += byCh }
            }
            var placed = part.copy(pins = chosen.toList())
            if (busPart) placed = placed.copy(i2cAddr = freeAddress(placed, board.filter { it.id != part.id }))
            if (best == null || total < best.cost) best = Placed(placed, total)
        }
        return best
    }

    /** An address no other part on the same bus has: the OLED's SA0 strap gives 0x3C or 0x3D */
    /** An address of the part's own that no other part on the same bus has */
    private fun freeAddress(p: Part, others: List<Part>): Int {
        val onBus = others.filter { it.pins == p.pins }.map { it.i2cAddr }.toSet()
        val own = p.type.addresses
        val first = if (p.i2cAddr in own) listOf(p.i2cAddr) else emptyList()
        return (first + own).firstOrNull { it !in onBus } ?: own.firstOrNull() ?: p.i2cAddr
    }

    fun propose(board: List<Part>, newPart: Part): Proposal? {
        place(newPart, board)?.let { return Proposal(it.part, emptyList(), reasons(it.part)) }
        // Smallest re-wire: move one existing part.
        var best: Pair<Proposal, Double>? = null
        for (q in board) {
            val rest = board.filter { it.id != q.id }
            val n = place(newPart, rest) ?: continue
            val q2 = place(q, rest + n.part) ?: continue
            val moves = q.pins.indices.filter { q.pins[it] != q2.part.pins[it] }
                .map { Move(q.id, it, q.pins[it], q2.part.pins[it]) }
            val score = moves.size * 100 + n.cost + q2.cost
            if (best == null || score < best.second) best = Proposal(n.part, moves, reasons(n.part)) to score
        }
        return best?.first
    }

    /** The user picked [pin] for [role] of [newPart]. Moves whatever is in the way. */
    fun proposeWithPin(board: List<Part>, newPart: Part, role: Int, pin: String): Proposal? {
        val use = Usage(board)
        val occ = use.pins[pin]
        if (occ == null) {
            val p = place(newPart, board, mapOf(role to pin)) ?: return null
            return Proposal(p.part, emptyList(), reasons(p.part))
        }
        val (q, qRole) = occ
        val rest = board.filter { it.id != q.id }
        // First try: only the wire in the way moves, with the same kind of function.
        val qFixed = q.pins.indices.filter { it != qRole }.associateWith { q.pins[it].pin }
        val oneWire = place(q, rest, qFixed)?.takeIf { cand ->
            val f0 = q.pins[qRole].function; val f1 = cand.part.pins[qRole].function
            cand.part.pins[qRole].pin != pin && (f0 !is Function.Timer || (f1 is Function.Timer && f1.timer == f0.timer))
        }
        // Otherwise the whole part moves.
        val qPlaced = oneWire ?: run {
            val first = place(newPart, rest, mapOf(role to pin)) ?: return null
            place(q, rest + first.part)
        } ?: return null
        val n = place(newPart, rest + qPlaced.part, mapOf(role to pin)) ?: return null
        val moves = q.pins.indices.filter { q.pins[it] != qPlaced.part.pins[it] }
            .map { Move(q.id, it, q.pins[it], qPlaced.part.pins[it]) }
        return Proposal(n.part, moves, reasons(n.part))
    }

    /** Every pin for one role, marked free, scarce, taken or unsuitable. */
    fun options(board: List<Part>, part: Part, role: Int): List<PinOption> {
        val use = Usage(board.filter { it.id != part.id })
        val group = (part.pins.getOrNull(0)?.function as? Function.Timer)?.timer.takeIf { part.type == PartType.RGB_LED && role > 0 }
        return chip.gpios.mapNotNull { p ->
            if (p.name in reserved) return@mapNotNull null
            val occ = use.pins[p.name]
            val free = Usage(board.filter { it.id != part.id && it.id != occ?.first?.id })
            val fs = functionsFor(part, role, p, if (occ == null) use else free, group)
            when {
                fs.isEmpty() -> null
                occ != null -> PinOption(p.name, fs.first(), Status.TAKEN, occ.first.type.title.let { t ->
                    val rn = occ.first.type.roles[occ.second]; if (rn.isEmpty()) t else "$t $rn" })
                else -> {
                    val f = fs.minByOrNull { fnCost(part, p, it) }!!
                    val c = fnCost(part, p, f)
                    PinOption(p.name, f, if (c >= 3) Status.SCARCE else Status.FREE,
                        if (f is Function.Timer) f.toString() else if (c >= 3) scarcityNote(p) else "")
                }
            }
        }.sortedWith(compareBy({ it.status.ordinal }, { fnCostByName(part, it) }, { chip.gpio(it.pin)!!.port }, { chip.gpio(it.pin)!!.bit }))
    }

    private fun fnCostByName(part: Part, o: PinOption) = o.function?.let { fnCost(part, chip.gpio(o.pin)!!, it) } ?: 99.0

    /** One line per role saying why that pin. */
    fun reasons(p: Part): List<String> = p.pins.mapIndexed { r, u ->
        val pin = chip.gpio(u.pin)!!
        val lost = cost(pin, u.function)
        when (val f = u.function) {
            is Function.Gpio -> if (p.needs()[r] == Need.CS && p.type == PartType.TFT && r == 4) "${u.pin} drives RES: low resets " +
                "the display. Not PB8: a pull-up on RES would hold BOOT0 high at reset."
                else if (p.needs()[r] == Need.CS) "${u.pin} is the chip select: any output pin works, the " +
                "code drives it low around each command. Not PB8: a pull-up on CS would hold BOOT0 high at reset."
                else if (p.type == PartType.TFT) "${u.pin} drives DC: low while a command byte goes out, high for its " +
                "parameters and the pixels."
                else if (lost == 0.0) "Any ${if (p.needs()[r] == Need.OUTPUT) "output" else "input"} pin works. " +
                "${u.pin} has no ADC input, DAC output or timer channel that a later part might need."
                else "${u.pin}: the free pin that gives up the least: only ${scarcityNote(pin)}, which a later part might want."
            is Function.Timer -> "${u.pin} carries $f (AF${f.af}), so the timer drives it as PWM."
            is Function.Adc -> "${u.pin} is ${f.adc} input ${f.channel}."
            is Function.Dac -> "${u.pin} is DAC1 output ${f.channel}; the DAC can play samples."
            is Function.I2c -> "${u.pin} is ${f.periph} ${f.signal} (AF${f.af}). I2C lines are open-drain; the module's " +
                "pull-ups hold them high." + if (r == 0) " Address 0x%02X.".format(java.util.Locale.ROOT, p.i2cAddr) else ""
            is Function.Spi -> "${u.pin} is ${f.periph} ${f.signal} (AF${f.af})." +
                if (f.signal == "SCK") " Other SPI parts can share SCK, MOSI and MISO, each with its own chip select." else ""
        }
    }

    companion object {
        fun bitOf(pin: String) = pin.substring(2).toInt()
    }
}
