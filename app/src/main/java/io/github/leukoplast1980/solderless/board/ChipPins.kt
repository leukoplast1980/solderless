package io.github.leukoplast1980.solderless.board

import org.json.JSONObject

/**
 * The STM32G431RB's 64 package pins and what each can do, from
 * data/g431rb-pins.json (generated from ST's and stm32-data's files, the
 * same source as the emulator's tables).
 */
class ChipPins(json: String) {

    class AltFunction(val af: Int, val periph: String, val signal: String) {
        val name get() = "${periph}_$signal"
    }

    class Pin(
        val number: Int,
        val name: String,
        val port: Int,              // -1 for supply and reset pins
        val bit: Int,
        val af: List<AltFunction>,
        val analog: List<String>,   // "ADC1_IN6", "DAC1_OUT1", ...
    ) {
        val isGpio get() = port >= 0
        /** port * 16 + bit, as the emulator numbers pins */
        val slot get() = port * 16 + bit

        /** Timer channels (not the complementary outputs): "TIM3" to channel 1..4 */
        val timerChannels: List<TimerChannel>
            get() = af.filter { it.periph.startsWith("TIM") && it.signal.matches(Regex("CH[1-4]")) }
                .map { TimerChannel(it.periph, it.signal[2] - '0', it.af) }

        /** Regular ADC inputs: (ADC1, 6) */
        val adcChannels: List<Pair<String, Int>>
            get() = analog.mapNotNull { a ->
                Regex("(ADC\\d)_IN(\\d+)").matchEntire(a)?.let { it.groupValues[1] to it.groupValues[2].toInt() }
            }

        /** DAC1 output channel (1 or 2), or 0 */
        val dacChannel: Int
            get() = analog.firstNotNullOfOrNull { Regex("DAC1_OUT(\\d)").matchEntire(it)?.groupValues?.get(1)?.toInt() } ?: 0

        /** I2C functions: (I2C1, SCL, af 4) */
        fun i2c(signal: String): List<AltFunction> = af.filter { it.periph.startsWith("I2C") && it.signal == signal }

        val hasBusFunction get() = af.any { it.periph.matches(Regex("(I2C|SPI|USART|UART|LPUART)\\d?")) }
    }

    class TimerChannel(val timer: String, val channel: Int, val af: Int) {
        override fun toString() = "$timer CH$channel"
    }

    val pins: List<Pin>

    init {
        val list = JSONObject(json).getJSONArray("pins")
        pins = (0 until list.length()).map { i ->
            val p = list.getJSONObject(i)
            val afs = p.optJSONArray("af")
            val an = p.optJSONArray("analog")
            Pin(
                number = p.getInt("number"),
                name = p.getString("name"),
                port = p.optInt("port", -1),
                bit = p.optInt("bit", -1),
                af = if (afs == null) emptyList() else (0 until afs.length()).map {
                    val a = afs.getJSONObject(it)
                    AltFunction(a.getInt("af"), a.getString("periph"), a.getString("signal"))
                },
                analog = if (an == null) emptyList() else (0 until an.length()).map { an.getString(it) },
            )
        }
    }

    private val byName = pins.filter { it.isGpio }.associateBy { it.name }

    fun gpio(name: String): Pin? = byName[name]
    val gpios get() = pins.filter { it.isGpio }

    /** Pins listed as two headers: (left, right), in the order the setting asks for. */
    fun headers(order: HeaderOrder): Pair<List<Pin>, List<Pin>> = when (order) {
        HeaderOrder.ADAPTER -> pins.filter { it.number <= 32 }.sortedBy { it.number } to
            pins.filter { it.number > 32 }.sortedByDescending { it.number }
        HeaderOrder.PORT -> {
            val gp = gpios.sortedWith(compareBy({ it.port }, { it.bit }))
            val left = gp.filter { it.port <= 1 }
            val right = gp.filter { it.port > 1 } + pins.filter { !it.isGpio }.sortedWith(compareBy({ supplyRank(it.name) }, { it.number }))
            left to right
        }
    }

    private fun supplyRank(name: String) = listOf("NRST", "VBAT", "VREF+", "VDDA", "VSSA", "VDD", "VSS").indexOf(name).let { if (it < 0) 99 else it }

    companion object {
        /** Peripheral base addresses for wiring.fs */
        val BASE = mapOf(
            "TIM1" to 0x40012C00L, "TIM2" to 0x40000000L, "TIM3" to 0x40000400L, "TIM4" to 0x40000800L,
            "TIM8" to 0x40013400L, "TIM15" to 0x40014000L, "TIM16" to 0x40014400L, "TIM17" to 0x40014800L,
            "ADC1" to 0x50000000L, "ADC2" to 0x50000100L, "DAC1" to 0x50000800L,
            "I2C1" to 0x40005400L, "I2C2" to 0x40005800L, "I2C3" to 0x40007800L,
        )

        fun gpioBase(port: Int) = 0x48000000L + 0x400L * port
    }
}

enum class HeaderOrder { ADAPTER, PORT }
