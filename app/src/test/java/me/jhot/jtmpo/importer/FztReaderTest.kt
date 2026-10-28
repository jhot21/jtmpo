package me.jhot.jtmpo.importer

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FztReaderTest {
    @Test
    fun readsUpToTheCap() {
        assertEquals(10, ByteArrayInputStream(ByteArray(10)).readAtMost(10).size)
    }

    @Test
    fun overTheCapFails() {
        try {
            ByteArrayInputStream(ByteArray(11)).readAtMost(10)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("too large"))
        }
    }

    @Test
    fun readsAtMostCapPlusOneByte() {
        var served = 0L
        val endless = object : java.io.InputStream() {
            override fun read(): Int { served++; return 1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { served += len; return len }
        }
        try { endless.readAtMost(100) } catch (_: IOException) {}
        assertTrue("served $served", served <= 100 + 1 + 8192)
    }
}
