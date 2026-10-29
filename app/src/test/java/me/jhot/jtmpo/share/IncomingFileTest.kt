package me.jhot.jtmpo.share

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import me.jhot.jtmpo.importer.FileTooLargeException
import me.jhot.jtmpo.importer.MAX_IMPORT_BYTES
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class IncomingFileTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val uri: Uri = Uri.parse("content://example.files/attachment/1")

    private fun serve(stream: InputStream) {
        shadowOf(context.contentResolver).registerInputStream(uri, stream)
    }

    @Test
    fun readIncomingReturnsTheBytesAndHandsThemOver() = runTest {
        val bytes = """{"type":"jtmpo-setlist"}""".toByteArray()
        serve(ByteArrayInputStream(bytes))
        val incoming = MutableStateFlow<ByteArray?>(null)

        assertEquals(IncomingResult.Accepted, receiveIncoming(context, uri, incoming))

        assertArrayEquals(bytes, incoming.value)
    }

    @Test
    fun readIncomingRefusesFilesOverTheCap() = runTest {
        serve(ByteArrayInputStream(ByteArray(MAX_IMPORT_BYTES + 1)))
        assertThrows(FileTooLargeException::class.java) {
            kotlinx.coroutines.runBlocking { readIncoming(context, uri) }
        }

        serve(ByteArrayInputStream(ByteArray(MAX_IMPORT_BYTES + 1)))
        val incoming = MutableStateFlow<ByteArray?>(null)
        assertEquals(IncomingResult.TooLarge, receiveIncoming(context, uri, incoming))
        assertNull(incoming.value)
    }

    @Test
    fun anUnreadableStreamSetsNothing() = runTest {
        serve(object : InputStream() {
            override fun read(): Int = throw IOException("grant lapsed")
        })
        val incoming = MutableStateFlow<ByteArray?>(null)

        assertEquals(IncomingResult.Unreadable, receiveIncoming(context, uri, incoming))
        assertNull(incoming.value)
    }

    @Test
    fun nonSetlistBytesAreStillHandedOverForTheImportScreenToJudge() = runTest {
        val bytes = "%PDF-1.4 not a setlist".toByteArray()
        serve(ByteArrayInputStream(bytes))
        val incoming = MutableStateFlow<ByteArray?>(null)

        assertEquals(IncomingResult.Accepted, receiveIncoming(context, uri, incoming))
        assertArrayEquals(bytes, incoming.value)
    }
}
