package io.github.leukoplast1980.solderless.chip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The disassembler against GNU objdump (arm-none-eabi-objdump -d of the
 * same instructions), in its own syntax where it differs on purpose
 * (push.w and pop.w for stmdb and ldmia on sp, no .n suffixes).
 */
class ThumbTest {

    private fun check(pc: Long, h1: Int, h2: Int, text: String) {
        val i = Thumb.decode(pc, h1, h2)
        assertEquals("at 0x%X".format(pc), text, i.text)
        assertEquals("size at 0x%X".format(pc), if (Thumb.is32(h1)) 4 else 2, i.size)
    }

    @Test
    fun likeObjdump() {
        check(0x0, 0x6836, 0x0000, "ldr r6, [r6, #0]")
        check(0x2, 0x6071, 0x0000, "str r1, [r6, #4]")
        check(0x4, 0x5C70, 0x0000, "ldrb r0, [r6, r1]")
        check(0x6, 0x88DA, 0x0000, "ldrh r2, [r3, #6]")
        check(0x8, 0x9802, 0x0000, "ldr r0, [sp, #8]")
        check(0xA, 0x4903, 0x0000, "ldr r1, [pc, #12]")
        check(0xC, 0x3601, 0x0000, "adds r6, #1")
        check(0xE, 0x3F04, 0x0000, "subs r7, #4")
        check(0x10, 0x20C8, 0x0000, "movs r0, #200")
        check(0x12, 0x280A, 0x0000, "cmp r0, #10")
        check(0x14, 0x1888, 0x0000, "adds r0, r1, r2")
        check(0x16, 0x1EC8, 0x0000, "subs r0, r1, #3")
        check(0x18, 0x0088, 0x0000, "lsls r0, r1, #2")
        check(0x1A, 0x0FDA, 0x0000, "lsrs r2, r3, #31")
        check(0x1C, 0x106C, 0x0000, "asrs r4, r5, #1")
        check(0x1E, 0x4008, 0x0000, "ands r0, r1")
        check(0x20, 0x4306, 0x0000, "orrs r6, r0")
        check(0x22, 0x404E, 0x0000, "eors r6, r1")
        check(0x24, 0x4370, 0x0000, "muls r0, r6")
        check(0x26, 0x4386, 0x0000, "bics r6, r0")
        check(0x28, 0x43C8, 0x0000, "mvns r0, r1")
        check(0x2A, 0x4248, 0x0000, "negs r0, r1")
        check(0x2C, 0x4208, 0x0000, "tst r0, r1")
        check(0x2E, 0x44B0, 0x0000, "add r8, r6")
        check(0x30, 0x4640, 0x0000, "mov r0, r8")
        check(0x32, 0x4589, 0x0000, "cmp r9, r1")
        check(0x34, 0x4770, 0x0000, "bx lr")
        check(0x36, 0x4798, 0x0000, "blx r3")
        check(0x38, 0xB530, 0x0000, "push {r4, r5, lr}")
        check(0x3A, 0xBD30, 0x0000, "pop {r4, r5, pc}")
        check(0x3C, 0xB004, 0x0000, "add sp, #16")
        check(0x3E, 0xB082, 0x0000, "sub sp, #8")
        check(0x40, 0xA801, 0x0000, "add r0, sp, #4")
        check(0x42, 0xB100, 0x0000, "cbz r0, 0x00000046")
        check(0x44, 0xBF00, 0x0000, "nop")
        check(0x46, 0xB2C8, 0x0000, "uxtb r0, r1")
        check(0x48, 0xB21A, 0x0000, "sxth r2, r3")
        check(0x4A, 0xBA08, 0x0000, "rev r0, r1")
        check(0x4C, 0xB672, 0x0000, "cpsid i")
        check(0x4E, 0xB662, 0x0000, "cpsie i")
        check(0x50, 0xBE01, 0x0000, "bkpt 0x0001")
        check(0x52, 0xBF00, 0x0000, "nop")
        check(0x54, 0xBF30, 0x0000, "wfi")
        check(0x56, 0xC006, 0x0000, "stmia r0!, {r1, r2}")
        check(0x58, 0xC90C, 0x0000, "ldmia r1!, {r2, r3}")
        check(0x5A, 0xD0F4, 0x0000, "beq 0x00000046")
        check(0x5C, 0xE7F3, 0x0000, "b 0x00000046")
        check(0x5E, 0xDE00, 0x0000, "udf #0")
        check(0x60, 0xDF05, 0x0000, "svc 5")
        check(0x62, 0xF8D6, 0x6800, "ldr.w r6, [r6, #2048]")
        check(0x66, 0xF841, 0x0C04, "str.w r0, [r1, #-4]")
        check(0x6A, 0xF851, 0x0F04, "ldr.w r0, [r1, #4]!")
        check(0x6E, 0xF851, 0x0022, "ldr.w r0, [r1, r2, lsl #2]")
        check(0x72, 0xF891, 0x0FFF, "ldrb.w r0, [r1, #4095]")
        check(0x76, 0xF9B1, 0x0002, "ldrsh.w r0, [r1, #2]")
        check(0x7A, 0xE9D2, 0x0102, "ldrd r0, r1, [r2, #8]")
        check(0x7E, 0xE96D, 0x0102, "strd r0, r1, [sp, #-8]!")
        check(0x82, 0xE92D, 0x41F0, "push.w {r4, r5, r6, r7, r8, lr}")
        check(0x86, 0xE8BD, 0x81F0, "pop.w {r4, r5, r6, r7, r8, pc}")
        check(0x8A, 0xE8B0, 0x0106, "ldmia.w r0!, {r1, r2, r8}")
        check(0x8E, 0xF501, 0x5080, "add.w r0, r1, #4096")
        check(0x92, 0xF1A7, 0x0604, "sub.w r6, r7, #4")
        check(0x96, 0xF04F, 0x20FF, "mov.w r0, #4278255360")
        check(0x9A, 0xF241, 0x2034, "movw r0, #4660")
        check(0x9E, 0xF6C4, 0x0000, "movt r0, #18432")
        check(0xA2, 0xEA01, 0x1002, "and.w r0, r1, r2, lsl #4")
        check(0xA6, 0xF046, 0x0620, "orr.w r6, r6, #32")
        check(0xAA, 0xF420, 0x6040, "bic.w r0, r0, #3072")
        check(0xAE, 0xEB01, 0x0002, "add.w r0, r1, r2")
        check(0xB2, 0xFB01, 0xF002, "mul.w r0, r1, r2")
        check(0xB6, 0xFB01, 0x3002, "mla r0, r1, r2, r3")
        check(0xBA, 0xFB91, 0xF0F2, "sdiv r0, r1, r2")
        check(0xBE, 0xFBB6, 0xF6F0, "udiv r6, r6, r0")
        check(0xC2, 0xFBA2, 0x0103, "umull r0, r1, r2, r3")
        check(0xC6, 0xFA01, 0xF002, "lsl.w r0, r1, r2")
        check(0xCA, 0xFAB1, 0xF081, "clz r0, r1")
        check(0xCE, 0xF3C1, 0x1002, "ubfx r0, r1, #4, #3")
        check(0xD2, 0xF361, 0x200B, "bfi r0, r1, #8, #4")
        check(0xD6, 0xF7FF, 0xFFB6, "bl 0x00000046")
        check(0xDA, 0xF7FF, 0xBFB4, "b.w 0x00000046")
        check(0xDE, 0xF43F, 0xAFB2, "beq.w 0x00000046")
        check(0xE2, 0xF3EF, 0x8010, "mrs r0, PRIMASK")
        check(0xE6, 0xF381, 0x8811, "msr BASEPRI, r1")
        check(0xEA, 0xF3BF, 0x8F4F, "dsb sy")
        check(0xEE, 0xF3BF, 0x8F6F, "isb sy")
        check(0xF2, 0xED96, 0x0A01, "vldr s0, [r6, #4]")
        check(0xF6, 0xEDC7, 0x0A00, "vstr s1, [r7]")
        check(0xFA, 0xEE30, 0x0A81, "FPU instruction (VADD, VMUL, VCVT, …)")
        check(0xFE, 0xEE10, 0x0A90, "vmov r0, s1")
        check(0x102, 0xE8DF, 0xF000, "tbb [pc, r0]")
        check(0x106, 0xE851, 0x0F00, "ldrex r0, [r1]")
    }

    @Test
    fun loadsAndStoresTellTheirBase() {
        // @ in Stellaris: ldr r6, [r6, #0]
        val at = Thumb.decode(0, 0x6836, 0)
        assertTrue(at.load)
        assertEquals(6, at.base)
        assertEquals(0L, at.offset)
        val st = Thumb.decode(0, 0xF841, 0x0C04)
        assertTrue(st.store)
        assertEquals(1, st.base)
        assertEquals(-4L, st.offset)
        assertFalse(Thumb.decode(0, 0x4770, 0).load)
    }
}
