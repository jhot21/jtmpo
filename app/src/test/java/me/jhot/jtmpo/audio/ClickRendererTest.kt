package me.jhot.jtmpo.audio

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickRendererTest {
    private val rate = 48000

    /** Renders [total] frames in buffers whose sizes cycle through [sizes]; returns global click-start frames. */
    private fun ClickRenderer.clickStarts(
        total: Long,
        sizes: IntArray = intArrayOf(192, 4096, 1, 960, 23999, 333),
        beforeBuffer: (Long) -> Unit = {},
    ): List<Long> {
        val out = ShortArray(sizes.max())
        val starts = mutableListOf<Long>()
        var pos = 0L
        var i = 0
        while (pos < total) {
            val n = minOf(sizes[i++ % sizes.size].toLong(), total - pos).toInt()
            beforeBuffer(pos)
            val base = pos
            render(out, n) { starts += base + it }
            pos += n
        }
        return starts
    }

    private fun shortClick() = ShortArray(100) { 1000 }

    @Test
    fun clicksLandAtExactSampleIntervals() {
        val r = ClickRenderer(shortClick(), rate)
        r.setClicksPerMinute(120.0)
        val starts = r.clickStarts(total = 24000L * 500 + 1)
        assertEquals(501, starts.size)
        starts.forEachIndexed { k, f -> assertEquals("click $k", 24000L * k, f) }
    }

    @Test
    fun fractionalIntervalsNeverDrift() {
        // 113 cpm at 48 kHz = 25486.7256... frames: positions must track k * interval, not k * rounded interval
        val r = ClickRenderer(shortClick(), rate)
        r.setClicksPerMinute(113.0)
        val interval = rate * 60.0 / 113.0
        val clicks = 3000
        val starts = r.clickStarts(total = (interval * clicks).toLong() - 1)
        assertEquals(clicks, starts.size)
        starts.forEachIndexed { k, f ->
            val ideal = k * interval
            assertTrue("click $k at $f, ideal $ideal", f >= ideal - 1e-6 && f - ideal < 1.0)
        }
    }

    @Test
    fun tempoChangeAppliesAtNextClickWithoutGapOrDouble() {
        // Slow down mid-interval: the pending click keeps its time, the next interval is the new length.
        val r = ClickRenderer(shortClick(), rate)
        r.setClicksPerMinute(120.0) // 24000 frames
        val starts = r.clickStarts(total = 200_000, sizes = intArrayOf(1000)) { pos ->
            if (pos == 30_000L) r.setClicksPerMinute(60.0) // 48000 frames
        }
        assertEquals(listOf(0L, 24_000L, 48_000L, 96_000L, 144_000L, 192_000L), starts)
    }

    @Test
    fun speedUpNeverWaitsLongerThanOneNewInterval() {
        val r = ClickRenderer(shortClick(), rate)
        r.setClicksPerMinute(20.0) // 144000 frames
        val starts = r.clickStarts(total = 100_000, sizes = intArrayOf(1000)) { pos ->
            if (pos == 10_000L) r.setClicksPerMinute(480.0) // 6000 frames
        }
        // Click at 0, change at 10000: next click no later than 10000 + 6000, then every 6000.
        assertEquals(0L, starts[0])
        assertTrue("first after change at ${starts[1]}", starts[1] in 10_001L..16_000L)
        starts.drop(1).zipWithNext { a, b -> assertEquals(6000L, b - a) }
    }

    @Test
    fun repeatedChangesNeverProduceZeroGapOrDoubleClick() {
        val r = ClickRenderer(shortClick(), rate)
        val cpms = doubleArrayOf(120.0, 600.0, 33.3, 250.0, 7.0, 599.9, 480.0)
        var c = 0
        r.setClicksPerMinute(cpms[0])
        val starts = r.clickStarts(total = 2_000_000, sizes = intArrayOf(777, 3001, 1)) { pos ->
            if (pos % 7 == 0L) r.setClicksPerMinute(cpms[c++ % cpms.size])
        }
        assertTrue(starts.size > 10)
        // 600 cpm is the fastest possible: 4800 frames
        starts.zipWithNext { a, b -> assertTrue("gap ${b - a} at $a", b - a >= 4800) }
        assertEquals(starts.distinct(), starts)
    }

    @Test
    fun overlappingClicksAreMixedWithoutOverflow() {
        val loud = ShortArray(11520) { Short.MAX_VALUE }
        val r = ClickRenderer(loud, rate)
        r.setClicksPerMinute(600.0) // 4800 frames: up to three voices overlap
        val out = ShortArray(20_000)
        r.render(out, out.size)
        assertTrue(out.all { it == Short.MAX_VALUE })

        val quiet = ShortArray(11520) { 10_000 }
        val q = ClickRenderer(quiet, rate)
        q.setClicksPerMinute(600.0)
        val qo = ShortArray(20_000)
        q.render(qo, qo.size)
        assertEquals(10_000, qo[0].toInt()) // one voice
        assertEquals(20_000, qo[4800].toInt()) // two voices mixed
        assertEquals(30_000, qo[9600].toInt()) // three voices mixed
        assertEquals(30_000, qo[11519].toInt())
        assertEquals(20_000, qo[11520].toInt()) // first voice ended

        val neg = ClickRenderer(ShortArray(11520) { Short.MIN_VALUE }, rate)
        neg.setClicksPerMinute(600.0)
        val no = ShortArray(20_000)
        neg.render(no, no.size)
        assertTrue(no.all { it == Short.MIN_VALUE })
    }

    @Test
    fun firstClickSoundsImmediatelyOnStart() {
        val click = ShortArray(50) { (it + 1).toShort() }
        val r = ClickRenderer(click, rate)
        r.setClicksPerMinute(120.0)
        val out = ShortArray(100)
        val starts = mutableListOf<Int>()
        r.render(out, out.size) { starts += it }
        assertEquals(listOf(0), starts)
        assertArrayEquals(click, out.copyOf(50))
        assertTrue(out.drop(50).all { it.toInt() == 0 })
    }

    @Test
    fun resetRestartsPhaseAndSilencesVoices() {
        val click = ShortArray(5000) { 100 }
        val r = ClickRenderer(click, rate)
        r.setClicksPerMinute(120.0)
        val out = ShortArray(3000)
        r.render(out, out.size) // mid-click, mid-interval
        r.reset()
        val starts = mutableListOf<Int>()
        val after = ShortArray(6000)
        r.render(after, after.size) { starts += it }
        assertEquals(listOf(0), starts)
        // Only the fresh voice: the interrupted one must not linger and mix in
        assertTrue(after.copyOf(5000).all { it.toInt() == 100 })
        assertTrue(after.drop(5000).all { it.toInt() == 0 })
    }

    @Test
    fun cpmIsClampedTo600() {
        val r = ClickRenderer(shortClick(), rate)
        r.setClicksPerMinute(1000.0)
        val starts = r.clickStarts(total = 48_001)
        assertEquals((0..10).map { it * 4800L }, starts)
    }

    @Test
    fun cpmIsClampedToSaneMinimum() {
        val r = ClickRenderer(shortClick(), rate)
        r.setClicksPerMinute(0.0)
        r.setClicksPerMinute(-5.0)
        r.setClicksPerMinute(Double.NaN)
        // Clamped to MIN_CPM rather than stalling forever or dividing by zero
        val interval = rate * 60.0 / ClickRenderer.MIN_CPM
        val starts = r.clickStarts(total = interval.toLong() + 1, sizes = intArrayOf(48_000))
        assertEquals(2, starts.size)
        assertTrue(abs(starts[1] - interval) < 1.0)
    }

    @Test
    fun rendersOnlyRequestedFrames() {
        val r = ClickRenderer(ShortArray(10) { 5 }, rate)
        r.setClicksPerMinute(120.0)
        val out = ShortArray(20) { 7 }
        r.render(out, 4)
        assertArrayEquals(shortArrayOf(5, 5, 5, 5), out.copyOf(4))
        assertTrue(out.drop(4).all { it.toInt() == 7 })
    }

    @Test
    fun aClickWhoseIdealTimeIsAWholeFrameIsNeverOneFrameLate() {
        // 560 cpm: 5142.857... frames apart, so every 7th click ideally lands exactly on a whole frame
        // (36000 * m). Rounding error in the running sum must not push it to the next frame.
        val r = ClickRenderer(shortClick(), rate)
        r.setClicksPerMinute(560.0)
        val starts = r.clickStarts(total = 36000L * 300 + 1)
        for (m in 0..300) assertEquals("click ${7 * m}", 36000L * m, starts[7 * m])
    }
}
