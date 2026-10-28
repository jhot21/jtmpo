package me.jhot.jtmpo.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WriterGenerationTest {
    @Test
    fun stopRetiresTheRunningWriter() {
        val g = WriterGeneration()
        val token = g.begin()
        assertTrue(g.isCurrent(token))
        g.end()
        assertFalse(g.isCurrent(token))
    }

    @Test
    fun aWriterThatOutlivesItsStopIsNotCurrentForTheNextStart() {
        val g = WriterGeneration()
        val stale = g.begin()
        g.end()
        val fresh = g.begin()
        assertFalse(g.isCurrent(stale))
        assertTrue(g.isCurrent(fresh))
    }

    @Test
    fun onlyTheCurrentWriterCanReportFailure() {
        val g = WriterGeneration()
        val stale = g.begin()
        g.end()
        val fresh = g.begin()
        assertEquals("stale writer may not report", WriterGeneration.NONE, g.fail(stale))

        val stamp = g.fail(fresh)
        assertFalse("failing retires the writer", g.isCurrent(fresh))
        assertTrue("report is valid until something else happens", g.isCurrent(stamp))
        assertEquals("a writer reports at most once", WriterGeneration.NONE, g.fail(fresh))
    }

    @Test
    fun aFailureReportIsVoidOnceTheOutputIsStoppedOrRestarted() {
        val g = WriterGeneration()
        val stamp = g.fail(g.begin())
        g.end()
        assertFalse(g.isCurrent(stamp))

        val stamp2 = g.fail(g.begin())
        g.begin()
        assertFalse(g.isCurrent(stamp2))
    }
}
