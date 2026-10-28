package me.jhot.jtmpo.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WavDecoderTest {
    @Test
    fun decodesRepoSample() {
        // Gradle runs unit tests with cwd = app/
        val wav = decodeWav16Mono(File("../sounds/digital-tock-48k.wav").readBytes())
        assertEquals(48000, wav.sampleRate)
        assertEquals(11520, wav.samples.size)
        // The sample is normalised to full scale (docs/digital-sound.md)
        assertTrue(wav.samples.any { kotlin.math.abs(it.toInt()) > 32000 })
    }

    @Test
    fun bundledResourceMatchesRepoSample() {
        val repo = File("../sounds/digital-tock-48k.wav").readBytes()
        val bundled = File("src/main/res/raw/digital_tock_48k.wav").readBytes()
        assertArrayEquals(repo, bundled)
    }

    @Test
    fun skipsUnknownChunksAndReadsLittleEndianSamples() {
        val wav = decodeWav16Mono(
            wavBytes(channels = 1, bits = 16, samples = shortArrayOf(1, -2, Short.MAX_VALUE, Short.MIN_VALUE), extraChunk = true),
        )
        assertEquals(44100, wav.sampleRate)
        assertArrayEquals(shortArrayOf(1, -2, Short.MAX_VALUE, Short.MIN_VALUE), wav.samples)
    }

    @Test
    fun rejectsStereo() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            decodeWav16Mono(wavBytes(channels = 2, bits = 16, samples = shortArrayOf(0, 0)))
        }
        assertTrue(e.message!!, e.message!!.contains("mono"))
    }

    @Test
    fun rejectsNon16Bit() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            decodeWav16Mono(wavBytes(channels = 1, bits = 8, samples = shortArrayOf(0)))
        }
        assertTrue(e.message!!, e.message!!.contains("16-bit"))
    }

    @Test
    fun rejectsNonPcm() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            decodeWav16Mono(wavBytes(channels = 1, bits = 16, samples = shortArrayOf(0), format = 3))
        }
        assertTrue(e.message!!, e.message!!.contains("PCM"))
    }

    @Test
    fun rejectsNonWav() {
        assertThrows(IllegalArgumentException::class.java) {
            decodeWav16Mono("not a wav file at all, definitely".toByteArray())
        }
    }

    @Test
    fun rejectsMissingDataChunk() {
        val full = wavBytes(channels = 1, bits = 16, samples = shortArrayOf(0))
        // RIFF header (12) + fmt chunk (8 + 16) only
        val truncated = full.copyOf(36)
        assertThrows(IllegalArgumentException::class.java) { decodeWav16Mono(truncated) }
    }

    private fun wavBytes(
        channels: Int,
        bits: Int,
        samples: ShortArray,
        format: Int = 1,
        extraChunk: Boolean = false,
    ): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        // Odd-sized chunk exercises the RIFF pad byte
        val extra = if (extraChunk) byteArrayOf(1, 2, 3) else ByteArray(0)
        val extraLen = if (extraChunk) 8 + extra.size + 1 else 0
        val buf = ByteBuffer.allocate(12 + 24 + extraLen + 8 + data.capacity()).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(buf.capacity() - 8).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16)
            .putShort(format.toShort()).putShort(channels.toShort())
            .putInt(44100).putInt(44100 * channels * bits / 8)
            .putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        if (extraChunk) {
            buf.put("LIST".toByteArray()).putInt(extra.size).put(extra).put(0)
        }
        buf.put("data".toByteArray()).putInt(data.capacity()).put(data.array())
        return buf.array()
    }
}
