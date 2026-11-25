/*
 * Solderless - the G431's clock tree as RCC has it set up: the oscillators,
 * the PLL, SYSCLK, the bus clocks, each peripheral's clock and the flash
 * wait states they need (RM0440 chapter 7).
 * Copyright (C) 2026 The Solderless authors. GPL-3.0-or-later.
 */
package io.github.leukoplast1980.solderless.chip

class ClockTree(read: (Long) -> Long, val hseHz: Long, val lseFitted: Boolean, map: RegisterMap) {

    class Source(val name: String, val hz: Long, val on: Boolean, val ready: Boolean)

    /** A peripheral on a bus: its clock enable, and the kernel clock it runs from if it has a choice */
    class Peripheral(val name: String, val on: Boolean, val kernel: String, val kernelHz: Long)

    class Bus(val name: String, val clock: String, val hz: Long, val divider: Int, val peripherals: List<Peripheral>)

    val cr = read(RCC + 0x00)
    val cfgr = read(RCC + 0x08)
    val pllcfgr = read(RCC + 0x0C)
    val ccipr = read(RCC + 0x88)
    private val bdcr = read(RCC + 0x90)
    private val csr = read(RCC + 0x94)
    private val crrcr = read(RCC + 0x98)
    private val acr = read(FLASH_ACR)
    private val pwrCr1 = read(PWR_CR1)
    private val pwrCr5 = read(PWR_CR5)
    private val stk = read(STK_CTRL)
    private val adcCcr = read(ADC12_CCR)

    private fun bit(v: Long, b: Int) = (v shr b) and 1L == 1L
    private fun bits(v: Long, b: Int, w: Int) = ((v shr b) and ((1L shl w) - 1)).toInt()

    val hsi16 = Source("HSI16", HSI_HZ, bit(cr, 8), bit(cr, 10))
    val hse = Source("HSE", hseHz, bit(cr, 16), bit(cr, 17))
    val hsi48 = Source("HSI48", 48_000_000, bit(crrcr, 0), bit(crrcr, 1))
    val lsi = Source("LSI", 32_000, bit(csr, 0), bit(csr, 1))
    val lse = Source("LSE", if (lseFitted) 32_768 else 0, bit(bdcr, 0), bit(bdcr, 1))
    val sources = listOf(hsi16, hse, hsi48, lsi, lse)

    // The PLL
    val pllOn = bit(cr, 24)
    val pllReady = bit(cr, 25)
    /** 2 HSI16, 3 HSE, else none */
    val pllSource = bits(pllcfgr, 0, 2)
    val pllSourceName = when (pllSource) { 2 -> "HSI16"; 3 -> "HSE"; else -> "none" }
    val pllM = bits(pllcfgr, 4, 4) + 1
    val pllN = bits(pllcfgr, 8, 7)
    val pllR = (bits(pllcfgr, 25, 2) + 1) * 2
    val pllREnabled = bit(pllcfgr, 24)
    val pllQ = (bits(pllcfgr, 21, 2) + 1) * 2
    val pllQEnabled = bit(pllcfgr, 20)
    val pllP = bits(pllcfgr, 27, 5).let { if (it >= 2) it else if (bit(pllcfgr, 17)) 17 else 7 }
    val pllPEnabled = bit(pllcfgr, 16)
    val pllInHz = when (pllSource) { 2 -> HSI_HZ; 3 -> hseHz; else -> 0L } / pllM
    val vcoHz = pllInHz * pllN
    val pllRHz = vcoHz / pllR
    val pllQHz = vcoHz / pllQ
    val pllPHz = vcoHz / pllP
    /** What is wrong with the PLL's settings, or null (RM0440: input 2.66 to 16 MHz, N 8 to 127, VCO 96 to 344 MHz) */
    val pllProblem: String? = when {
        pllSource < 2 -> "no input clock (PLLSRC)"
        pllInHz !in 2_660_000..16_000_000 -> "input ${mhz(pllInHz)} MHz: must be 2.66 to 16 MHz (PLLM)"
        pllN !in 8..127 -> "N = $pllN: must be 8 to 127"
        vcoHz !in 96_000_000..344_000_000 -> "VCO ${mhz(vcoHz)} MHz: must be 96 to 344 MHz"
        else -> null
    }

    /** SYSCLK's source now (SWS): 1 HSI16, 2 HSE, 3 PLL */
    val sysclkSource = bits(cfgr, 2, 2)
    val sysclkSourceName = when (sysclkSource) { 2 -> "HSE"; 3 -> "PLL R"; else -> "HSI16" }
    val sysclkHz = when (sysclkSource) { 2 -> hseHz; 3 -> pllRHz; else -> HSI_HZ }.let { if (it == 0L) HSI_HZ else it }

    val ahbDivider = bits(cfgr, 4, 4).let { if (it and 8 == 0) 1 else intArrayOf(2, 4, 8, 16, 64, 128, 256, 512)[it and 7] }
    val hclkHz = sysclkHz / ahbDivider
    val apb1Divider = bits(cfgr, 8, 3).let { if (it and 4 == 0) 1 else 2 shl (it and 3) }
    val apb2Divider = bits(cfgr, 11, 3).let { if (it and 4 == 0) 1 else 2 shl (it and 3) }
    val pclk1Hz = hclkHz / apb1Divider
    val pclk2Hz = hclkHz / apb2Divider
    /** Timers count at twice PCLK when the APB divider is not 1 */
    val timer1Hz = if (apb1Divider == 1) pclk1Hz else 2 * pclk1Hz
    val timer2Hz = if (apb2Divider == 1) pclk2Hz else 2 * pclk2Hz

    // Flash wait states and the core voltage
    val latency = bits(acr, 0, 4)
    val range = bits(pwrCr1, 9, 2)
    val boost = !bit(pwrCr5, 8)
    val latencyNeeded: Int = run {
        val mhz = (hclkHz + 999_999) / 1_000_000
        when {
            range == 2 -> if (mhz <= 12) 0 else if (mhz <= 24) 1 else 2
            boost -> if (mhz <= 34) 0 else if (mhz <= 68) 1 else if (mhz <= 102) 2 else if (mhz <= 136) 3 else 4
            else -> if (mhz <= 30) 0 else if (mhz <= 60) 1 else if (mhz <= 90) 2 else if (mhz <= 120) 3 else 4
        }
    }
    val rangeText: String get() = if (range == 2) "range 2" else if (boost) "range 1 boost" else "range 1"

    // SysTick
    val sysTickOn = bit(stk, 0)
    val sysTickHz = if (bit(stk, 2)) hclkHz else hclkHz / 8

    val ahb: Bus
    val apb1: Bus
    val apb2: Bus

    init {
        val enables = HashMap<Int, Long>()
        fun en(off: Int) = enables.getOrPut(off) { read(RCC + off) }
        fun onBus(vararg offs: Int, timerHz: Long, busHz: Long): List<Peripheral> = map.peripherals
            .filter { it.enOffset in offs && !it.name.startsWith("FDCANRAM") }
            .distinctBy { it.name }
            .map { p ->
                val on = (en(p.enOffset) shr p.enBit) and 1L == 1L
                val (kernel, hz) = kernelOf(p.name, timerHz, busHz)
                Peripheral(p.name, on, kernel, hz)
            }
        ahb = Bus("AHB", "HCLK", hclkHz, ahbDivider, onBus(0x48, 0x4C, 0x50, timerHz = hclkHz, busHz = hclkHz))
        apb1 = Bus("APB1", "PCLK1", pclk1Hz, apb1Divider, onBus(0x58, 0x5C, timerHz = timer1Hz, busHz = pclk1Hz))
        apb2 = Bus("APB2", "PCLK2", pclk2Hz, apb2Divider, onBus(0x60, timerHz = timer2Hz, busHz = pclk2Hz))
    }

    /** The clock a peripheral runs from: its own choice in CCIPR, the timers' clock, or its bus */
    private fun kernelOf(name: String, timerHz: Long, busHz: Long): Pair<String, Long> {
        fun sel(bit: Int) = bits(ccipr, bit, 2)
        fun serial(bit: Int, bus: String): Pair<String, Long> = when (sel(bit)) {
            0 -> bus to busHz
            1 -> "SYSCLK" to sysclkHz
            2 -> "HSI16" to (if (hsi16.ready) HSI_HZ else 0)
            else -> "LSE" to (if (lse.ready) lse.hz else 0)
        }
        fun i2c(bit: Int, bus: String): Pair<String, Long> = when (sel(bit)) {
            0 -> bus to busHz
            1 -> "SYSCLK" to sysclkHz
            2 -> "HSI16" to (if (hsi16.ready) HSI_HZ else 0)
            else -> "reserved" to 0L
        }
        return when (name) {
            "USART1" -> serial(0, "PCLK2")
            "USART2" -> serial(2, "PCLK1")
            "USART3" -> serial(4, "PCLK1")
            "UART4" -> serial(6, "PCLK1")
            "LPUART1" -> serial(10, "PCLK1")
            "I2C1" -> i2c(12, "PCLK1")
            "I2C2" -> i2c(14, "PCLK1")
            "I2C3" -> i2c(16, "PCLK1")
            "ADC1", "ADC2", "ADC12_COMMON" -> {
                val ck = bits(adcCcr, 16, 2)
                if (ck != 0) "HCLK ÷ ${if (ck == 3) 4 else ck}" to hclkHz / (if (ck == 3) 4 else ck)
                else when (sel(28)) {
                    1 -> "PLL P" to (if (pllPEnabled && pllReady) pllPHz else 0)
                    2 -> "SYSCLK" to sysclkHz
                    else -> "none (ADC12SEL)" to 0L
                }
            }
            else -> if (name.startsWith("TIM")) "×${if (timerHz == busHz) 1 else 2}" to timerHz else "" to busHz
        }
    }

    companion object {
        const val HSI_HZ = 16_000_000L
        const val RCC = 0x40021000L
        const val FLASH_ACR = 0x40022000L
        const val PWR_CR1 = 0x40007000L
        const val PWR_CR5 = 0x40007080L
        const val STK_CTRL = 0xE000E010L
        const val ADC12_CCR = 0x50000308L

        /** The words a ClockTree reads besides the enable registers */
        val ADDRESSES = longArrayOf(RCC, RCC + 0x08, RCC + 0x0C, RCC + 0x88, RCC + 0x90, RCC + 0x94, RCC + 0x98,
            RCC + 0x48, RCC + 0x4C, RCC + 0x50, RCC + 0x58, RCC + 0x5C, RCC + 0x60, FLASH_ACR, PWR_CR1, PWR_CR5, STK_CTRL, ADC12_CCR)

        /** "170", "16", "32.768 k" style: MHz with up to three decimals */
        fun mhz(hz: Long): String = java.math.BigDecimal(hz).divide(java.math.BigDecimal(1_000_000))
            .setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

        /** "170 MHz", "32.768 kHz", "0" */
        fun freq(hz: Long): String = when {
            hz == 0L -> "0 Hz"
            hz >= 1_000_000 -> "${mhz(hz)} MHz"
            hz >= 1_000 -> java.math.BigDecimal(hz).divide(java.math.BigDecimal(1000)).stripTrailingZeros().toPlainString() + " kHz"
            else -> "$hz Hz"
        }
    }
}
