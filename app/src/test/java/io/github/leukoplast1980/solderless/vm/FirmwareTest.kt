package io.github.leukoplast1980.solderless.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class FirmwareTest {

    /** Vector table: SP in RAM, reset handler (Thumb) in flash, then some code */
    private fun vectors(sp: Int = 0x20008000, reset: Int = 0x08000101): ByteArray =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(sp).putInt(reset).putInt(0x11223344).putInt(0x55667788).array()

    @Test fun binIsLoadedAtTheStartOfFlash() {
        val img = Firmware.parse(vectors())
        assertEquals(Firmware.Format.BIN, img.format)
        assertEquals(0x00.toByte(), img.flash[0])
        assertEquals(0x80.toByte(), img.flash[1])
        assertEquals(0x20.toByte(), img.flash[3])
        assertEquals(0x44.toByte(), img.flash[8])
        assertEquals(0xFF.toByte(), img.flash[16])
        assertEquals(16, img.bytesUsed)
    }

    @Test fun binTooBigIsRejected() {
        val big = ByteArray(FlashImage.SIZE + 1)
        vectors().copyInto(big)
        assertThrows(FirmwareException::class.java) { Firmware.parse(big) }
    }

    @Test fun wrongVectorsAreRejected() {
        assertThrows(FirmwareException::class.java) { Firmware.parse(vectors(sp = 0x08000000)) }       // SP not in RAM
        assertThrows(FirmwareException::class.java) { Firmware.parse(vectors(reset = 0x08000100)) }    // ARM, not Thumb
        assertThrows(FirmwareException::class.java) { Firmware.parse(vectors(reset = 0x00200001)) }    // outside flash
    }

    private fun hexRecord(type: Int, addr: Int, data: ByteArray): String {
        val bytes = byteArrayOf(data.size.toByte(), (addr shr 8).toByte(), addr.toByte(), type.toByte()) + data
        val sum = (-bytes.sumOf { it.toInt() and 0xFF }) and 0xFF
        return ":" + (bytes + sum.toByte()).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    @Test fun intelHexWithExtendedLinearAddress() {
        val v = vectors()
        val text = listOf(
            hexRecord(4, 0, byteArrayOf(0x08, 0x00)),
            hexRecord(0, 0x0000, v.copyOfRange(0, 8)),
            hexRecord(0, 0x0100, byteArrayOf(0x70, 0x47)),          // bx lr at 0x0800_0100
            hexRecord(5, 0, byteArrayOf(0x08, 0x00, 0x01, 0x01)),
            hexRecord(1, 0, ByteArray(0)),
        ).joinToString("\n")
        val img = Firmware.parse(text.toByteArray())
        assertEquals(Firmware.Format.HEX, img.format)
        assertEquals(0x70.toByte(), img.flash[0x100])
        assertEquals(0x47.toByte(), img.flash[0x101])
        assertEquals(0x102, img.bytesUsed)
    }

    @Test fun intelHexChecksumIsChecked() {
        val bad = hexRecord(0, 0, vectors()).dropLast(2) + "00"
        val e = assertThrows(FirmwareException::class.java) { Firmware.parse("$bad\n:00000001FF".toByteArray()) }
        assertTrue(e.message!!.contains("checksum"))
    }

    @Test fun intelHexOutsideFlashIsRejected() {
        val text = hexRecord(4, 0, byteArrayOf(0x20, 0x00)) + "\n" + hexRecord(0, 0, vectors()) + "\n:00000001FF"
        assertThrows(FirmwareException::class.java) { Firmware.parse(text.toByteArray()) }
    }

    /** A minimal ELF32 ARM file with one PT_LOAD segment at the flash's physical address */
    private fun elf(segment: ByteArray, paddr: Int): ByteArray {
        val b = ByteBuffer.allocate(52 + 32 + segment.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 1, 1, 1, 0))
        b.position(16)
        b.putShort(2).putShort(40).putInt(1).putInt(0x08000101).putInt(52).putInt(0).putInt(0)
        b.putShort(52).putShort(32).putShort(1).putShort(0).putShort(0).putShort(0)
        b.putInt(1).putInt(84).putInt(paddr).putInt(paddr).putInt(segment.size).putInt(segment.size).putInt(5).putInt(4)
        b.put(segment)
        return b.array()
    }

    @Test fun elfLoadSegmentsGoToTheirPhysicalAddress() {
        val img = Firmware.parse(elf(vectors(), 0x08000000))
        assertEquals(Firmware.Format.ELF, img.format)
        assertEquals(0x55.toByte(), img.flash[15])
    }

    @Test fun elfForAnotherMachineIsRejected() {
        val e = elf(vectors(), 0x08000000)
        e[18] = 62   // x86-64
        assertThrows(FirmwareException::class.java) { Firmware.parse(e) }
    }

    @Test fun bundledStellarisMatchesItsMetadata() {
        val dir = File("../firmware/stellaris")
        val bin = File(dir, "mecrisp-stellaris-stm32g431kb.bin").readBytes()
        val json = File(dir, "stellaris.json").readText()
        val sha = MessageDigest.getInstance("SHA-256").digest(bin).joinToString("") { "%02x".format(it) }
        assertTrue("stellaris.json must hold the image's SHA-256", json.contains(sha))
        Firmware.parse(bin)   // and it is a valid image
    }
}
