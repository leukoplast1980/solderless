package io.github.leukoplast1980.solderless.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashImageTest {

    /** A Mecrisp-Stellaris core below 0x5000, nothing compiled to flash */
    private fun image() = ByteArray(FlashImage.SIZE) { 0xFF.toByte() }.also { it[0] = 0x00; it[0x4fff] = 0 }

    @Test fun coreOnlyIsEmpty() {
        assertTrue(FlashImage.userAreaEmpty(image()))
    }

    @Test fun aCompiledWordIsNotEmpty() {
        val img = image()
        img[0x5000 + 40] = 0x12
        assertFalse(FlashImage.userAreaEmpty(img))
    }

    @Test fun lastByteCounts() {
        val img = image()
        img[img.size - 1] = 0
        assertFalse(FlashImage.userAreaEmpty(img))
    }

    @Test fun padFillsWithErasedFlash() {
        val p = FlashImage.pad(byteArrayOf(1, 2, 3))
        assertEquals(FlashImage.SIZE, p.size)
        assertEquals(3, p[2].toInt())
        assertEquals(0xFF.toByte(), p[3])
        assertTrue(FlashImage.startsWith(p, byteArrayOf(1, 2, 3)))
        assertFalse(FlashImage.startsWith(p, byteArrayOf(1, 2, 4)))
    }
}
