package me.jhot.jtmpo.share

import android.content.Context
import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import me.jhot.jtmpo.importer.FileTooLargeException
import me.jhot.jtmpo.importer.readAtMost

/**
 * Reads a file another app opened in jtmpo, under the import size cap. Called as soon as the intent arrives:
 * the read grant on [uri] may lapse, so only the bytes travel on.
 *
 * @throws FileTooLargeException if the file is over the cap
 * @throws IOException if it cannot be opened or read
 */
suspend fun readIncoming(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.use { it.readAtMost() } ?: throw IOException("Cannot open $uri")
}

enum class IncomingResult { Accepted, TooLarge, Unreadable }

/**
 * Reads [uri] and hands the bytes to [into] for the import screen. Whether they are a setlist is that
 * screen's call; on failure [into] is left alone.
 */
suspend fun receiveIncoming(context: Context, uri: Uri, into: MutableStateFlow<ByteArray?>): IncomingResult =
    try {
        into.value = readIncoming(context, uri)
        IncomingResult.Accepted
    } catch (_: FileTooLargeException) {
        IncomingResult.TooLarge
    } catch (_: IOException) {
        IncomingResult.Unreadable
    } catch (_: SecurityException) {
        // A grant that was never given or has already been revoked.
        IncomingResult.Unreadable
    }
