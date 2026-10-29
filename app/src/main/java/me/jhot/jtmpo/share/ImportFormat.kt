package me.jhot.jtmpo.share

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.jhot.jtmpo.importer.MAX_IMPORT_BYTES

/** What an imported file is, judged by its content; names and MIME types from other apps are not reliable. */
enum class ImportFormat { TEMPO, JTMPO, BACKUP, UNKNOWN }

private const val HEAD_BYTES = 4096
private val SETLIST_TYPE = Regex("\"type\"\\s*:\\s*\"${Regex.escape(SetlistFileCodec.TYPE)}\"")

/**
 * An XML plist is a Tempo export, JSON with `type == "jtmpo-setlist"` a `.jtmpo`, and JSON with a
 * `setlists` array and no `type` a backup. Anything else, including input over the import cap, is
 * [ImportFormat.UNKNOWN]. Only the first byte decides whether the JSON is parsed at all.
 */
fun detectFormat(bytes: ByteArray): ImportFormat {
    if (bytes.size > MAX_IMPORT_BYTES) return ImportFormat.UNKNOWN
    var start = 0
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) start = 3
    while (start < bytes.size && bytes[start].toInt().toChar().isWhitespace()) start++
    if (start >= bytes.size) return ImportFormat.UNKNOWN
    return when (bytes[start].toInt().toChar()) {
        '<' -> {
            val head = String(bytes, start, minOf(bytes.size - start, HEAD_BYTES), Charsets.ISO_8859_1).lowercase()
            if ("<plist" in head) ImportFormat.TEMPO else ImportFormat.UNKNOWN
        }
        '{' -> json(String(bytes, start, bytes.size - start, Charsets.UTF_8))
        else -> ImportFormat.UNKNOWN
    }
}

private fun json(text: String): ImportFormat {
    val root = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        // A damaged .jtmpo still names its type near the top; it then gets the setlist's own error.
        ?: return if (SETLIST_TYPE.containsMatchIn(text.take(HEAD_BYTES))) ImportFormat.JTMPO else ImportFormat.UNKNOWN
    if (root !is JsonObject) return ImportFormat.UNKNOWN
    val type = root["type"]
    return when {
        type is JsonPrimitive && type.isString && type.content == SetlistFileCodec.TYPE -> ImportFormat.JTMPO
        type == null && root["setlists"] is JsonArray -> ImportFormat.BACKUP
        else -> ImportFormat.UNKNOWN
    }
}
