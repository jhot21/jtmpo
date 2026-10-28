package me.jhot.jtmpo.playback

import me.jhot.jtmpo.audio.ClickOutput
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.EffectiveSong
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TimeSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeOutput : ClickOutput {
    val calls = mutableListOf<String>()
    override fun start() { calls += "start" }
    override fun stop() { calls += "stop" }
    override fun setClicksPerMinute(cpm: Double) { calls += "cpm:${cpm.toInt()}" }
    fun clear() = calls.clear()
}

class PlaybackControllerTest {
    private fun rhythm(tempo: Int) = Rhythm(tempo, TimeSignature(4, 4), ClickPattern(4))

    private fun song(id: Long, tempo: Int) = EffectiveSong(
        entryId = id, songId = id * 10, title = "s$id", artist = null, rhythm = rhythm(tempo),
        notes = null, rhythmOverridden = false, notesOverridden = false,
        libraryRhythm = rhythm(tempo), libraryNotes = null,
    )

    private val a = song(1, 100)
    private val b = song(2, 120)
    private val c = song(3, 140)

    private fun controller(vararg entries: EffectiveSong, start: Int = 0): Pair<PlaybackController, FakeOutput> {
        val out = FakeOutput()
        val pc = PlaybackController(out)
        pc.load(1L, entries.toList(), start)
        out.clear()
        return pc to out
    }

    @Test
    fun playStartsAtCurrentSongCpm() {
        val (pc, out) = controller(a, b)
        pc.play()
        assertTrue(pc.state.value.isPlaying)
        assertEquals(listOf("cpm:100", "start"), out.calls)
    }

    @Test
    fun pauseStopsOutput() {
        val (pc, out) = controller(a, b)
        pc.play(); out.clear()
        pc.pause()
        assertFalse(pc.state.value.isPlaying)
        assertEquals(listOf("stop"), out.calls)
    }

    @Test
    fun nextWhilePlayingSwitchesCpmWithoutStopping() {
        val (pc, out) = controller(a, b)
        pc.play(); out.clear()
        pc.next()
        assertEquals(1, pc.state.value.currentIndex)
        assertTrue(pc.state.value.isPlaying)
        assertEquals(listOf("cpm:120"), out.calls)
    }

    @Test
    fun nextWhilePausedStaysSilent() {
        val (pc, out) = controller(a, b)
        pc.next()
        assertEquals(1, pc.state.value.currentIndex)
        assertFalse(pc.state.value.isPlaying)
        assertFalse("start" in out.calls)
        assertFalse("stop" in out.calls)
    }

    @Test
    fun selectBehavesLikeNext() {
        val (pc, out) = controller(a, b, c)
        pc.play(); out.clear()
        pc.select(2)
        assertEquals(listOf("cpm:140"), out.calls)
        assertTrue(pc.state.value.isPlaying)
        pc.pause(); out.clear()
        pc.select(0)
        assertEquals(0, pc.state.value.currentIndex)
        assertFalse(pc.state.value.isPlaying)
        assertEquals(listOf("cpm:100"), out.calls)
    }

    @Test
    fun nextOnLastAndPreviousOnFirstDoNothing() {
        val (pc, out) = controller(a, b)
        pc.previous()
        assertEquals(0, pc.state.value.currentIndex)
        pc.select(1); out.clear()
        pc.next()
        assertEquals(1, pc.state.value.currentIndex)
        assertTrue(out.calls.isEmpty())
    }

    @Test
    fun emptySetlistCannotPlay() {
        val (pc, out) = controller()
        pc.play()
        assertFalse(pc.state.value.isPlaying)
        assertNull(pc.state.value.current)
        assertFalse("start" in out.calls)
    }

    @Test
    fun startAuditionStopsRealPlaybackAndPlaysTheDraftWithoutMarkingItPlaying() {
        val (pc, out) = controller(a, b)
        pc.play(); out.clear()
        pc.startAudition(rhythm(90))
        assertEquals(listOf("stop", "cpm:90", "start"), out.calls)
        assertFalse(pc.state.value.isPlaying)
        assertTrue(pc.state.value.isAuditioning)
        assertEquals(90, pc.state.value.auditionRhythm?.tempo)
        // The player still shows the loaded song, not the sheet's draft.
        assertEquals(100, pc.state.value.effectiveRhythm?.tempo)
    }

    @Test
    fun auditionStartsWhenNothingIsPlaying() {
        val (pc, out) = controller(a)
        out.clear()
        pc.startAudition(rhythm(90))
        assertEquals(listOf("cpm:90", "start"), out.calls)
        assertTrue(pc.state.value.isAuditioning)
        assertFalse(pc.state.value.isPlaying)
    }

    @Test
    fun updateAuditionChangesCpmLive() {
        val (pc, out) = controller(a)
        pc.startAudition(rhythm(90)); out.clear()
        pc.updateAudition(rhythm(140))
        assertEquals(listOf("cpm:140"), out.calls)
        assertEquals(140, pc.state.value.auditionRhythm?.tempo)
    }

    @Test
    fun updateAuditionWhenNoneIsActiveDoesNothing() {
        val (pc, out) = controller(a)
        out.clear()
        pc.updateAudition(rhythm(140))
        assertTrue(out.calls.isEmpty())
        assertNull(pc.state.value.auditionRhythm)
    }

    @Test
    fun stopAuditionLeavesEverythingPaused() {
        val (pc, out) = controller(a)
        pc.play()
        pc.startAudition(rhythm(90)); out.clear()
        pc.stopAudition()
        assertEquals(listOf("stop"), out.calls)
        assertNull(pc.state.value.auditionRhythm)
        assertFalse(pc.state.value.isPlaying)
        assertFalse("start" in out.calls)
    }

    @Test
    fun playingWhileAuditioningSwitchesToTheLoadedRhythm() {
        val (pc, out) = controller(a)
        pc.startAudition(rhythm(90)); out.clear()
        pc.play()
        assertEquals(listOf("stop", "cpm:100", "start"), out.calls)
        assertNull(pc.state.value.auditionRhythm)
        assertTrue(pc.state.value.isPlaying)
    }

    @Test
    fun pauseStopsAudition() {
        val (pc, out) = controller(a)
        pc.startAudition(rhythm(90)); out.clear()
        pc.pause()
        assertEquals(listOf("stop"), out.calls)
        assertNull(pc.state.value.auditionRhythm)
        assertFalse(pc.state.value.isPlaying)
    }

    @Test
    fun loadingASetlistStopsAudition() {
        val (pc, out) = controller(a)
        pc.startAudition(rhythm(90)); out.clear()
        pc.load(2L, listOf(b), 0)
        assertFalse("start" in out.calls)
        assertNull(pc.state.value.auditionRhythm)
        assertFalse(pc.state.value.isPlaying)
    }

    @Test
    fun unloadingStopsAudition() {
        val (pc, out) = controller(a)
        pc.startAudition(rhythm(90)); out.clear()
        pc.unload()
        assertEquals(listOf("stop"), out.calls)
        assertNull(pc.state.value.auditionRhythm)
        assertFalse(pc.state.value.isPlaying)
    }

    @Test
    fun previewChangesCpmLiveAndClearPreviewRestoresSaved() {
        val (pc, out) = controller(a, b)
        pc.play(); out.clear()
        pc.preview(rhythm(200))
        assertEquals(listOf("cpm:200"), out.calls)
        out.clear()
        pc.clearPreview()
        assertNull(pc.state.value.previewRhythm)
        assertEquals(listOf("cpm:100"), out.calls)
    }

    @Test
    fun updateEntriesKeepsCurrentByEntryIdWhenReordered() {
        val (pc, out) = controller(a, b, c, start = 1)
        pc.play(); out.clear()
        pc.updateEntries(1L, listOf(c, b, a))
        assertEquals(1, pc.state.value.currentIndex)
        assertEquals(2L, pc.state.value.current?.entryId)
        pc.updateEntries(1L, listOf(b, c, a))
        assertEquals(0, pc.state.value.currentIndex)
        assertTrue(pc.state.value.isPlaying)
        assertTrue(out.calls.isEmpty())
    }

    @Test
    fun removingPlayingEntryMovesToNextAndPauses() {
        val (pc, out) = controller(a, b, c, start = 1)
        pc.play(); out.clear()
        pc.updateEntries(1L, listOf(a, c))
        assertEquals(1, pc.state.value.currentIndex)
        assertEquals(3L, pc.state.value.current?.entryId)
        assertFalse(pc.state.value.isPlaying)
        assertEquals(listOf("stop", "cpm:140"), out.calls)
    }

    @Test
    fun removingPlayingLastEntryMovesToNewLast() {
        val (pc, _) = controller(a, b, c, start = 2)
        pc.play()
        pc.updateEntries(1L, listOf(a, b))
        assertEquals(1, pc.state.value.currentIndex)
        assertFalse(pc.state.value.isPlaying)
    }

    @Test
    fun removingOnlyEntryLeavesNothingPaused() {
        val (pc, out) = controller(a)
        pc.play(); out.clear()
        pc.updateEntries(1L, emptyList())
        assertNull(pc.state.value.current)
        assertFalse(pc.state.value.isPlaying)
        assertEquals("stop", out.calls.last())
        assertFalse("start" in out.calls)
    }

    @Test
    fun savedRhythmChangeOnCurrentEntryUpdatesCpmWhilePlaying() {
        val (pc, out) = controller(a, b)
        pc.play(); out.clear()
        pc.updateEntries(1L, listOf(a.copy(rhythm = rhythm(150)), b))
        assertTrue(pc.state.value.isPlaying)
        assertEquals(listOf("cpm:150"), out.calls)
    }

    @Test
    fun updateEntriesDropsPreviewOnceEntryMatchesItWithoutCpmGap() {
        val (pc, out) = controller(a, b)
        pc.play()
        pc.preview(rhythm(140))
        out.clear()
        pc.updateEntries(1L, listOf(song(1, 140), b))
        assertNull(pc.state.value.previewRhythm)
        assertEquals(emptyList<String>(), out.calls)
        assertEquals(140, pc.state.value.effectiveRhythm?.tempo)
    }

    @Test
    fun updateEntriesKeepsPreviewWhileEntryStillDiffers() {
        val (pc, _) = controller(a, b)
        pc.preview(rhythm(140))
        pc.updateEntries(1L, listOf(a, b))
        assertEquals(140, pc.state.value.previewRhythm?.tempo)
    }

    @Test
    fun loadingAnotherSetlistWhilePlayingKeepsTheClickGoing() {
        val (pc, out) = controller(a, b)
        pc.play(); out.clear()
        pc.load(2L, listOf(b, c), 1)
        val s = pc.state.value
        assertEquals(2L, s.setlistId)
        assertEquals(c.entryId, s.current?.entryId)
        assertTrue(s.isPlaying)
        assertEquals(listOf("cpm:140"), out.calls)
    }

    @Test
    fun loadingAnotherSetlistWhilePausedStaysSilent() {
        val (pc, out) = controller(a, b)
        pc.load(2L, listOf(c), 0)
        assertFalse(pc.state.value.isPlaying)
        assertFalse("start" in out.calls || "stop" in out.calls)
    }

    @Test
    fun loadingAnEmptySetlistWhilePlayingStops() {
        val (pc, out) = controller(a)
        pc.play(); out.clear()
        pc.load(2L, emptyList(), 0)
        assertFalse(pc.state.value.isPlaying)
        assertEquals(listOf("stop"), out.calls)
    }

    @Test
    fun aBufferedEmissionForThePreviousSetlistIsIgnored() {
        val (pc, out) = controller(a, b)
        pc.play()
        pc.load(2L, listOf(c), 0)
        out.clear()
        // A's entries flow emitted before load(B) and is delivered after it.
        pc.updateEntries(1L, listOf(a, b))
        val s = pc.state.value
        assertEquals(2L, s.setlistId)
        assertEquals(listOf(c), s.entries)
        assertTrue(s.isPlaying)
        assertTrue(out.calls.isEmpty())
    }
}
