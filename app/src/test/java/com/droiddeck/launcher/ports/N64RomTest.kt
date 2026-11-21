package com.droiddeck.launcher.ports

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class N64RomTest {
    private val big = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40) + ByteArray(4092) { (it * 7).toByte() }

    private fun regroup(bytes: ByteArray, group: Int) = bytes.toList().chunked(group).flatMap { it.reversed() }.toByteArray()

    private fun normalised(bytes: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream()
        return if (N64Rom.copyBigEndian(bytes.inputStream(), out)) out.toByteArray() else null
    }

    @Test fun theHeaderTellsTheByteOrder() {
        assertEquals(N64Rom.Order.BIG, N64Rom.order(big))
        assertEquals(N64Rom.Order.PAIRS, N64Rom.order(regroup(big, 2)))
        assertEquals(N64Rom.Order.QUADS, N64Rom.order(regroup(big, 4)))
        assertNull(N64Rom.order(byteArrayOf(1, 2, 3, 4)))
        assertNull(N64Rom.order(byteArrayOf(0x80.toByte())))
    }

    @Test fun everyByteOrderComesOutBigEndian() {
        assertArrayEquals(big, normalised(big))
        assertArrayEquals(big, normalised(regroup(big, 2)))
        assertArrayEquals(big, normalised(regroup(big, 4)))
    }

    @Test fun aRomLargerThanOneBufferKeepsItsOrder() {
        val large = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40) + ByteArray((3 shl 20) + 4) { (it * 13).toByte() }
        assertTrue(normalised(regroup(large, 4)).contentEquals(large))
    }

    @Test fun aFileThatIsNotAnN64RomIsRefused() {
        assertFalse(N64Rom.copyBigEndian(ByteArray(64).inputStream(), ByteArrayOutputStream()))
        assertFalse(N64Rom.copyBigEndian(ByteArray(0).inputStream(), ByteArrayOutputStream()))
    }
}
