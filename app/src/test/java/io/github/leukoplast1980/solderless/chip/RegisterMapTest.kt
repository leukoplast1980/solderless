package io.github.leukoplast1980.solderless.chip

import io.github.leukoplast1980.solderless.board.ChipPins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RegisterMapTest {

    private val map = RegisterMap(File("../data/g431rb-registers.json").readText())
    private val chip = ChipPins(File("../data/g431rb-pins.json").readText())

    private fun field(p: String, r: String, f: String): Triple<RegisterMap.Peripheral, RegisterMap.Register, RegisterMap.Field> {
        val per = map.peripheral(p)!!
        val reg = per.registers.first { it.name == r }
        return Triple(per, reg, reg.fields.first { it.name == f })
    }

    @Test
    fun peripheralsAndAddresses() {
        val gpioa = map.peripheral("GPIOA")!!
        assertEquals(0x48000000L, gpioa.base)
        assertEquals(0x4C, gpioa.enOffset)
        assertEquals(0, gpioa.enBit)
        assertFalse(gpioa.stored)
        assertTrue(map.peripheral("CORDIC")!!.stored)
        // derived peripherals share the description, at their own address
        val usart2 = map.peripheral("USART2")!!
        assertEquals(0x4000440CL, usart2.address(usart2.registers.first { it.name == "BRR" }))
        // RCC's registers without their prefix, the core's peripherals too
        assertNotNull(map.peripheral("RCC")!!.registers.firstOrNull { it.name == "CFGR" })
        assertEquals(0xE000E010L, map.peripheral("SysTick")!!.base)
        assertNotNull(map.peripheral("SCB")!!.registers.firstOrNull { it.name == "CFSR" })
        val (p, r) = map.at(0x48000400L + 0x14)!!
        assertEquals("GPIOB", p.name)
        assertEquals("ODR", r.name)
    }

    @Test
    fun widthsFixedFromTheSvd() {
        // TIM2 counts 32 bits, TIM3 (described as TIM2 in the SVD) 16
        assertEquals(32, field("TIM2", "ARR", "ARR").third.width)
        assertEquals(16, field("TIM3", "ARR", "ARR").third.width)
        assertEquals(0xFFFFL, map.peripheral("TIM3")!!.registers.first { it.name == "ARR" }.reset)
        // the G4's USART BRR is one 16-bit field
        assertEquals(16, field("USART2", "BRR", "BRR").third.width)
    }

    @Test
    fun meanings() {
        val (gpioa, moder, moder5) = field("GPIOA", "MODER", "MODER5")
        assertEquals(10, moder5.bit)
        assertEquals("01", FieldText.value(moder5, 1))
        assertEquals("output", FieldText.meaning(gpioa, moder5, 1, chip))
        assertEquals("What pin PA5 does.", FieldText.about(gpioa, moder5))
        assertEquals("PA5", FieldText.pin(gpioa, moder5))
        assertEquals("ABFF FFFF", FieldText.word(moder.reset))

        val (_, _, afrl2) = field("GPIOA", "AFRL", "AFRL2")
        assertEquals("AF7 USART2_TX", FieldText.meaning(gpioa, afrl2, 7, chip))

        // x in a value's bits: any HPRE from 0000 to 0111 is SYSCLK ÷ 1
        val (rcc, _, hpre) = field("RCC", "CFGR", "HPRE")
        assertEquals("SYSCLK ÷ 1", FieldText.meaning(rcc, hpre, 5, chip))
        assertEquals("SYSCLK ÷ 4", FieldText.meaning(rcc, hpre, 9, chip))
        // the SVD's own list where there is no note
        val (_, _, sw) = field("RCC", "CFGR", "SW")
        assertEquals("PLL selected as system clock", FieldText.meaning(rcc, sw, 3, chip))
        // show: lines
        val (_, _, pllm) = field("RCC", "PLLCFGR", "PLLM")
        assertEquals("÷4", FieldText.meaning(rcc, pllm, 3, chip))
        assertTrue(pllm.values.isEmpty())
        val (i2c1, _, sadd) = field("I2C1", "CR2", "SADD")
        assertEquals("part at 0x76", FieldText.meaning(i2c1, sadd, 0x76L shl 1, chip))
        val (syscfg, _, exti5) = field("SYSCFG", "EXTICR2", "EXTI5")
        assertEquals("PC5", FieldText.meaning(syscfg, exti5, 2, chip))
    }

    @Test
    fun forth() {
        val (gpioa, moder, moder5) = field("GPIOA", "MODER", "MODER5")
        assertEquals("\$48000000 @ \$C00 bic \$400 or \$48000000 !", FieldText.forth(gpioa, moder, moder5, 1))
        assertEquals("\$C00 \$48000000 bis!", FieldText.forth(gpioa, moder, moder5, 3))
        assertEquals("\$C00 \$48000000 bic!", FieldText.forth(gpioa, moder, moder5, 0))
        val (_, odr, odr5) = field("GPIOA", "ODR", "ODR5")
        assertEquals("\$20 \$48000014 bis!", FieldText.forth(gpioa, odr, odr5, 1))
        val (_, bsrr, br5) = field("GPIOA", "BSRR", "BR5")
        assertEquals("\$200000 \$48000018 !", FieldText.forth(gpioa, bsrr, br5, 1))
        val (usart2, brr, f) = field("USART2", "BRR", "BRR")
        assertEquals("1476 \$4000440C !", FieldText.forth(usart2, brr, f, 1476))

        assertEquals(0xABFFF7FFL, FieldText.write(moder, moder5, 0xABFFFFFFL, 1))
        assertEquals(0x200000L, FieldText.write(bsrr, br5, 0xFFFFFFFFL, 1))
    }

    @Test
    fun numbersTypedIn() {
        assertEquals(1476L, FieldText.parse("1476"))
        assertEquals(0x5C4L, FieldText.parse("\$5C4"))
        assertEquals(0x5C4L, FieldText.parse("0x5c4"))
        assertEquals(5L, FieldText.parse("%101"))
        assertEquals(null, FieldText.parse("five"))
    }
}
