package me.jhot.jtmpo.importer

import java.io.IOException
import java.io.InputStream

/** A Tempo export is around 100 KB and a shared setlist far less; anything this large is neither. */
const val MAX_IMPORT_BYTES = 5 * 1024 * 1024

/** Thrown by [readAtMost] when the stream is longer than the cap. */
class FileTooLargeException : IOException("File too large")

/** Reads at most [cap] bytes; pulls only one byte more than that to notice an oversized file. */
fun InputStream.readAtMost(cap: Int = MAX_IMPORT_BYTES): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val want = minOf(buffer.size.toLong(), cap + 1L - total).toInt()
        if (want <= 0) throw FileTooLargeException()
        val n = read(buffer, 0, want)
        if (n < 0) break
        total += n
        if (total > cap) throw FileTooLargeException()
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
