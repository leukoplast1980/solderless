package io.github.leukoplast1980.solderless.chip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaultReportTest {

    /** Little-endian memory with a few words in it */
    private fun memory(vararg words: Pair<Long, Long>): (Long, Int) -> ByteArray? {
        val m = HashMap<Long, Int>()
        for ((a, v) in words) for (k in 0..3) m[a + k] = ((v shr (8 * k)) and 0xFF).toInt()
        return { a, n -> ByteArray(n) { (m[a + it] ?: 0).toByte() } }
    }

    private val places = mapOf(0x20000530L to "show-temp +8", 0x2000055CL to "main +12", 0x20000600L to "outer +20")

    @Test
    fun aLoadFromNowhere() {
        // As in tests/stellaris/fault.fs: $60000000 @ in show-temp, called from main
        val regs = IntArray(16)
        regs[0] = 0x60000000; regs[6] = 3; regs[7] = 0x20000280; regs[13] = 0x20000380
        regs[14] = 0x2000055D; regs[15] = 0x20000530
        val f = FaultReport(0x8200, 0x40000000, 0x60000000, 0, false, 5, regs, 0x21000000,
            memory(0x20000530L to 0x6800L, 0x20000380L to 0x20000601L, 0x20000384L to 0x12345678L, 0x20000280L to 42L),
            { places[it] }, 0x20000288L)
        assertEquals("HardFault, escalated from a BusFault", f.title)
        assertEquals("Load from 6000 0000: nothing is mapped there.", f.causes[0])
        assertEquals("show-temp +8", f.where)
        assertEquals(listOf("main +12", "outer +20"), f.calledFrom)
        assertEquals("6800", f.halfwords)
        assertEquals("ldr r0, [r0, #0]", f.insn!!.text)
        assertEquals("The address came from r0 (6000 0000).", f.hints[0])
        assertTrue(f.hints[1], f.hints[1].startsWith("Nothing is at 6000 0000."))
        assertEquals(listOf(3L, 42L), f.dataStack)
    }

    @Test
    fun theDataStacksTop() {
        // @ on TOS: ldr r6, [r6, #0] with r6 = 4000 0400 + 0x10, where no peripheral is
        val regs = IntArray(16)
        regs[6] = 0x40000BF0; regs[15] = 0x00002000
        val f = FaultReport(0x8200, 0, 0x40000BF0, 0, false, 5, regs, 0,
            memory(0x2000L to 0x6836L), { null }, 0x20000288L)
        assertEquals("BusFault", f.title)
        assertEquals("The address came from r6 (4000 0BF0).", f.hints[0])
        assertTrue(f.hints[1].startsWith("r6 is TOS"))
        assertTrue(f.hints[2].startsWith("There is no peripheral at 4000 0BF0"))
    }

    @Test
    fun aDivideByZero() {
        val regs = IntArray(16)
        regs[15] = 0x1000
        val f = FaultReport(1 shl 25, 0, 0, 0, false, 6, regs, 0,
            memory(0x1000L to 0xF0F2FB91L), { null }, 0)
        assertEquals("UsageFault", f.title)
        assertEquals("sdiv r0, r1, r2", f.insn!!.text)
        assertTrue(f.hints.contains("A divide by 0: / or mod with 0 on the stack."))
        assertTrue(f.dataStack.isEmpty())
    }
}
