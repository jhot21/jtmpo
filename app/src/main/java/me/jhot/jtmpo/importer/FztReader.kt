package me.jhot.jtmpo.importer

import java.io.IOException
import java.io.InputStream

/** An export is around 100 KB; anything this large is not one. */
const val MAX_FZT_BYTES = 5 * 1024 * 1024

/** Reads at most [cap] bytes; pulls only one byte more than that to notice an oversized file. */
fun InputStream.readAtMost(cap: Int = MAX_FZT_BYTES): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val want = minOf(buffer.size.toLong(), cap + 1L - total).toInt()
        if (want <= 0) throw IOException("File too large")
        val n = read(buffer, 0, want)
        if (n < 0) break
        total += n
        if (total > cap) throw IOException("File too large")
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
