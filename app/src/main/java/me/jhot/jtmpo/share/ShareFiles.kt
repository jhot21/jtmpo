package me.jhot.jtmpo.share

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val SHARE_MIME_TYPE = "application/vnd.jtmpo.setlist+json"
private const val SHARE_DIR = "share"
private const val MAX_NAME = 80

/**
 * A portable file name for a setlist: path separators, `*?"<>|:`, control characters, emoji and other
 * non-ASCII become `_`, so the name survives any chat app or file system. Leading dots and spaces are
 * dropped so the result is never hidden or relative.
 */
fun shareFileName(setlistName: String): String {
    val cleaned = setlistName
        .map { if (it.code !in 32..126 || it in "/\\*?\"<>|:") '_' else it }
        .joinToString("")
        .trim()
        .trimStart('.')
        .take(MAX_NAME)
        .trim()
    return cleaned.ifEmpty { "setlist" } + ".jtmpo"
}

/** Writes [json] to `cacheDir/share/<name>`, replacing any earlier file of that name. */
fun writeShareFile(cacheDir: File, name: String, json: String): File {
    val dir = File(cacheDir, SHARE_DIR).apply { mkdirs() }
    return File(dir, name).apply { writeText(json) }
}

/** As [writeShareFile], but a full disk or an unwritable cache is a normal outcome: it returns null. */
fun writeShareFileOrNull(cacheDir: File, name: String, json: String): File? =
    try {
        writeShareFile(cacheDir, name, json)
    } catch (_: IOException) {
        null
    }

/** Deletes every earlier share file; they only need to live until the receiving app has read them. */
fun pruneShareFiles(cacheDir: File) {
    File(cacheDir, SHARE_DIR).listFiles()?.forEach { it.deleteRecursively() }
}

fun Context.shareAuthority(): String = "$packageName.share"

/** Opens the system share sheet for [file] through the app's [FileProvider]. */
fun Context.sendShare(file: File) {
    val uri = FileProvider.getUriForFile(this, shareAuthority(), file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = SHARE_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(send, null))
}

/** Writes [json] as `<name>.jtmpo` in the cache and opens the share sheet for it. False if the file could not be written. */
suspend fun Context.shareSetlistFile(name: String, json: String): Boolean {
    val file = withContext(Dispatchers.IO) { writeShareFileOrNull(cacheDir, shareFileName(name), json) } ?: return false
    sendShare(file)
    return true
}
