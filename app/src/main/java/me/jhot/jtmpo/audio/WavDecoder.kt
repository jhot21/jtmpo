package me.jhot.jtmpo.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WavData(val sampleRate: Int, val samples: ShortArray) {
    // Arrays compare by identity by default; compare contents instead
    override fun equals(other: Any?) =
        other is WavData && sampleRate == other.sampleRate && samples.contentEquals(other.samples)

    override fun hashCode() = 31 * sampleRate + samples.contentHashCode()
}

/** Decodes a RIFF WAV that must be uncompressed PCM, 16-bit, mono; anything else is rejected. */
fun decodeWav16Mono(bytes: ByteArray): WavData {
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(bytes.size >= 12 && tag(buf, 0) == "RIFF" && tag(buf, 8) == "WAVE") { "Not a RIFF/WAVE file" }

    var sampleRate: Int? = null
    var pos = 12
    while (pos + 8 <= bytes.size) {
        val id = tag(buf, pos)
        val size = buf.getInt(pos + 4)
        val body = pos + 8
        require(size >= 0 && body.toLong() + size <= bytes.size) { "Truncated WAV chunk '$id'" }
        when (id) {
            "fmt " -> {
                require(size >= 16) { "WAV fmt chunk too short" }
                val format = buf.getShort(body).toInt() and 0xFFFF
                val channels = buf.getShort(body + 2).toInt()
                val bits = buf.getShort(body + 14).toInt()
                require(format == 1) { "WAV must be uncompressed PCM (format 1), got format $format" }
                require(channels == 1) { "WAV must be mono, got $channels channels" }
                require(bits == 16) { "WAV must be 16-bit, got $bits-bit" }
                sampleRate = buf.getInt(body + 4)
            }
            "data" -> {
                val rate = requireNotNull(sampleRate) { "WAV data chunk before fmt chunk" }
                val samples = ShortArray(size / 2)
                buf.position(body)
                buf.asShortBuffer().get(samples)
                return WavData(rate, samples)
            }
        }
        // Chunks are word-aligned: odd sizes carry a pad byte
        pos = body + size + (size and 1)
    }
    throw IllegalArgumentException("WAV has no data chunk")
}

private fun tag(buf: ByteBuffer, at: Int) = String(ByteArray(4) { buf.get(at + it) }, Charsets.US_ASCII)
