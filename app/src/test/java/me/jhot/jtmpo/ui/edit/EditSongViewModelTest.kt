package me.jhot.jtmpo.ui.edit

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.jhot.jtmpo.audio.ClickOutput
import me.jhot.jtmpo.data.JtmpoDatabase
import me.jhot.jtmpo.data.Repository
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TimeSignature
import me.jhot.jtmpo.playback.PlaybackController
import me.jhot.jtmpo.testutil.TestViewModels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class NoOutput : ClickOutput {
    override fun start() {}
    override fun stop() {}
    override fun setClicksPerMinute(cpm: Double) {}
}

private class CountingOutput : ClickOutput {
    val calls = mutableListOf<String>()
    override fun start() { calls += "start" }
    override fun stop() { calls += "stop" }
    override fun setClicksPerMinute(cpm: Double) { calls += "cpm:${cpm.toInt()}" }
    fun clear() = calls.clear()
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EditSongViewModelTest {
    private lateinit var db: JtmpoDatabase
    private lateinit var repo: Repository
    private lateinit var playback: PlaybackController

    private fun r(tempo: Int, num: Int = 4, den: Int = 4, pat: Int = 4, dotted: Boolean = false) =
        Rhythm(tempo, TimeSignature(num, den), ClickPattern(pat, dotted))

    private val vms = TestViewModels()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, JtmpoDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db)
        playback = PlaybackController(NoOutput())
    }

    @After
    fun tearDown() {
        vms.shutdown()
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun seed(rhythm: Rhythm = r(100)): Pair<Long, Long> {
        val song = repo.createSong("Song", "Artist", rhythm, "lib notes")
        val list = repo.createSetlist("Gig")
        repo.addSongs(list, listOf(song))
        val entry = repo.entries(list).first().single()
        return list to entry.entryId
    }

    private fun entryVm(entryId: Long) = EditSongViewModel(entryId, null, repo, playback)

    private suspend fun EditSongViewModel.loaded(): EditUi = withTimeout(5_000) { ui.first { it.loaded } }

    @Test
    fun tempoOutsideLimitsIsClamped() = runBlocking {
        val (_, entryId) = seed()
        val vm = entryVm(entryId).also { it.loaded() }
        vm.setTempo(999)
        assertEquals(300, vm.ui.value.rhythm.tempo)
        vm.setTempo(3)
        assertEquals(20, vm.ui.value.rhythm.tempo)
        assertTrue(vm.commitTempoInput("150"))
        assertEquals(150, vm.ui.value.rhythm.tempo)
        assertTrue(vm.commitTempoInput("999"))
        assertEquals(300, vm.ui.value.rhythm.tempo)
        assertFalse(vm.commitTempoInput("abc"))
        assertFalse(vm.commitTempoInput(""))
        assertEquals(300, vm.ui.value.rhythm.tempo)
    }

    @Test
    fun blankTitleBlocksSave() = runBlocking {
        val songId = repo.createSong("Song", null, r(100), null)
        val vm = vms.track(EditSongViewModel(null, songId, repo, playback)).also { it.loaded() }
        assertTrue(vm.ui.value.canSave)
        vm.setTitle("   ")
        assertFalse(vm.ui.value.canSave)
        vm.saveToLibrary()?.join()
        assertEquals("Song", repo.songs().first().single().title)
        vm.setTitle("Renamed")
        vm.saveToLibrary()?.join()
        assertEquals("Renamed", repo.songs().first().single().title)
    }

    @Test
    fun newSongIsCreatedOnSave() = runBlocking {
        val vm = vms.track(EditSongViewModel(null, null, repo, playback)).also { it.loaded() }
        assertFalse(vm.ui.value.canSave)
        vm.setTitle("Fresh")
        vm.setTempo(88)
        vm.saveToLibrary()?.join()
        val song = repo.songs().first().single()
        assertEquals("Fresh", song.title)
        assertEquals(88, song.rhythm.tempo)
    }

    @Test
    fun changingSignatureRemapsPatternAndKeepsTempo() = runBlocking {
        val (_, entryId) = seed(r(100, 4, 4, 16))
        val vm = entryVm(entryId).also { it.loaded() }
        vm.setTimeSignature(TimeSignature(6, 8))
        val ui = vm.ui.value
        assertEquals(100, ui.rhythm.tempo)
        assertEquals(TimeSignature(6, 8), ui.rhythm.timeSignature)
        assertEquals(ClickPattern(16), ui.rhythm.pattern)
        assertEquals(600.0, ui.clicksPerMinute, 0.001) // 100 dotted quarters x 6 sixteenths, at the cap
    }

    @Test
    fun editsPreviewLiveOnPlaybackWhenEntryIsCurrent() = runBlocking {
        val (list, entryId) = seed()
        playback.load(list, repo.entries(list).first(), 0)
        val vm = entryVm(entryId).also { it.loaded() }
        vm.setTempo(140)
        assertEquals(140, playback.state.value.previewRhythm?.tempo)
    }

    @Test
    fun freshVmForCurrentEntryStartsFromTheActivePreview() = runBlocking {
        val (list, entryId) = seed(r(120))
        playback.load(list, repo.entries(list).first(), 0)
        playback.preview(r(122))
        val vm = entryVm(entryId)
        assertEquals(122, vm.loaded().rhythm.tempo)
        vm.saveToSetlist()!!.join()
        assertEquals(122, repo.entry(entryId)!!.rhythm.tempo)
    }

    @Test
    fun cancelOnAFreshVmDoesNotDropAnActivePreview() = runBlocking {
        val (list, entryId) = seed(r(120))
        val out = CountingOutput()
        val pb = PlaybackController(out)
        pb.load(list, repo.entries(list).first(), 0)
        pb.play()
        pb.preview(r(122))
        val vm = vms.track(EditSongViewModel(entryId, null, repo, pb))
        vm.loaded()
        val before = out.calls.toList()
        vm.dismiss()
        assertEquals(122, pb.state.value.previewRhythm?.tempo)
        assertEquals(before, out.calls)
    }

    @Test
    fun cancelWithoutPreviewLeavesTheClickAlone() = runBlocking {
        val (list, entryId) = seed(r(120))
        val out = CountingOutput()
        val pb = PlaybackController(out)
        pb.load(list, repo.entries(list).first(), 0)
        pb.play()
        val vm = vms.track(EditSongViewModel(entryId, null, repo, pb))
        vm.loaded()
        val before = out.calls.toList()
        vm.dismiss()
        assertEquals(before, out.calls)
    }

    @Test
    fun revertDropsThePreviewEvenOnAFreshVm() = runBlocking {
        val (list, entryId) = seed(r(120))
        playback.load(list, repo.entries(list).first(), 0)
        playback.preview(r(122))
        val vm = entryVm(entryId).also { it.loaded() }
        vm.revert()
        assertNull(playback.state.value.previewRhythm)
    }

    @Test
    fun editsDoNotPreviewWhenEntryIsNotCurrent() = runBlocking {
        val (_, entryId) = seed()
        val vm = entryVm(entryId).also { it.loaded() }
        vm.setTempo(140)
        assertNull(playback.state.value.previewRhythm)
    }

    @Test
    fun previewTogglePlaysTheDraftRhythmWithoutMarkingThePlayerPlaying() = runBlocking {
        val (_, entryId) = seed(r(100))
        val out = CountingOutput()
        val pb = PlaybackController(out)
        val vm = vms.track(EditSongViewModel(entryId, null, repo, pb)).also { it.loaded() }
        out.clear()
        vm.togglePreview()
        assertEquals(listOf("cpm:100", "start"), out.calls)
        assertTrue(vm.ui.value.previewing)
        assertTrue(pb.state.value.isAuditioning)
        assertFalse(pb.state.value.isPlaying)
        // Merely auditioning is not an unsaved rhythm edit.
        assertNull(pb.state.value.previewRhythm)
    }

    @Test
    fun previewToggleStopsARunningClick() = runBlocking {
        val (list, entryId) = seed(r(100))
        val out = CountingOutput()
        val pb = PlaybackController(out)
        pb.load(list, repo.entries(list).first(), 0)
        pb.play(); out.clear()
        val vm = vms.track(EditSongViewModel(entryId, null, repo, pb)).also { it.loaded() }
        vm.togglePreview()
        assertEquals(listOf("stop", "cpm:100", "start"), out.calls)
        assertFalse(pb.state.value.isPlaying)
    }

    @Test
    fun editingWhilePreviewingUpdatesTheAuditionLive() = runBlocking {
        val (list, entryId) = seed(r(100))
        val out = CountingOutput()
        val pb = PlaybackController(out)
        pb.load(list, repo.entries(list).first(), 0)
        val vm = vms.track(EditSongViewModel(entryId, null, repo, pb)).also { it.loaded() }
        vm.togglePreview(); out.clear()
        vm.setTempo(140)
        assertEquals("cpm:140", out.calls.last())
        assertEquals(140, pb.state.value.auditionRhythm?.tempo)
        // The live edit preview of the current entry stays in sync too, so Play resumes the draft.
        assertEquals(140, pb.state.value.previewRhythm?.tempo)
    }

    @Test
    fun stoppingPreviewLeavesPlaybackPaused() = runBlocking {
        val (list, entryId) = seed(r(100))
        val out = CountingOutput()
        val pb = PlaybackController(out)
        pb.load(list, repo.entries(list).first(), 0)
        val vm = vms.track(EditSongViewModel(entryId, null, repo, pb)).also { it.loaded() }
        vm.togglePreview(); out.clear()
        vm.togglePreview()
        assertEquals(listOf("stop"), out.calls)
        assertNull(pb.state.value.auditionRhythm)
        assertFalse(pb.state.value.isPlaying)
        assertFalse(vm.ui.value.previewing)
    }

    @Test
    fun dismissStopsPreview() = runBlocking {
        val (_, entryId) = seed()
        val out = CountingOutput()
        val pb = PlaybackController(out)
        val vm = vms.track(EditSongViewModel(entryId, null, repo, pb)).also { it.loaded() }
        vm.togglePreview()
        vm.dismiss()
        assertNull(pb.state.value.auditionRhythm)
        assertFalse(vm.ui.value.previewing)
    }

    @Test
    fun savingStopsPreview() = runBlocking {
        val (list, entryId) = seed(r(100))
        val pb = PlaybackController(CountingOutput())
        val vm = vms.track(EditSongViewModel(entryId, null, repo, pb)).also { it.loaded() }
        vm.togglePreview()
        vm.saveToSetlist()?.join()
        assertNull(pb.state.value.auditionRhythm)
        assertEquals(100, repo.entries(list).first().single().rhythm.tempo)
    }

    @Test
    fun aNewLibrarySongCanBePreviewedWithNothingLoaded() = runBlocking {
        val out = CountingOutput()
        val pb = PlaybackController(out)
        val vm = vms.track(EditSongViewModel(null, null, repo, pb)).also { it.loaded() }
        vm.togglePreview()
        assertEquals(listOf("cpm:120", "start"), out.calls)
        assertEquals(120, pb.state.value.auditionRhythm?.tempo)
    }

    @Test
    fun dismissWithoutSaveClearsPreview() = runBlocking {
        val (list, entryId) = seed()
        playback.load(list, repo.entries(list).first(), 0)
        val vm = entryVm(entryId).also { it.loaded() }
        vm.setTempo(140)
        assertNotNull(playback.state.value.previewRhythm)
        vm.dismiss()
        assertNull(playback.state.value.previewRhythm)
    }

    @Test
    fun saveToLibraryWhileOverriddenShowsActiveOverrideText() = runBlocking {
        val (list, entryId) = seed(r(100))
        repo.saveToSetlist(entryId, r(72), "lib notes")
        val vm = entryVm(entryId).also { it.loaded() }
        assertEquals(72, vm.ui.value.rhythm.tempo)
        vm.setTempo(110)
        vm.saveToLibrary()?.join()
        assertEquals(110, repo.songs().first().single().rhythm.tempo)
        val text = vm.ui.value.activeOverrideText
        assertNotNull(text)
        assertTrue(text!!, text.contains("72"))
        assertEquals(72, repo.entries(list).first().single().rhythm.tempo)
        vm.clearOverride()?.join()
        assertNull(vm.ui.value.activeOverrideText)
        assertEquals(110, repo.entries(list).first().single().rhythm.tempo)
    }

    @Test
    fun saveToSetlistCallsRepositoryWithDraft() = runBlocking {
        val (list, entryId) = seed(r(100))
        val vm = entryVm(entryId).also { it.loaded() }
        vm.setTempo(90)
        vm.setTimeSignature(TimeSignature(3, 4))
        vm.setNotes("gig notes")
        vm.saveToSetlist()?.join()
        val e = repo.entries(list).first().single()
        assertEquals(r(90, 3, 4, 4), e.rhythm)
        assertEquals("gig notes", e.notes)
        assertTrue(e.rhythmOverridden)
        assertEquals(100, repo.songs().first().single().rhythm.tempo)
        assertTrue(vm.ui.value.finished)
    }

    @Test
    fun typedTempoReachesPreviewOnlyOnCommit() = runBlocking {
        val (list, entryId) = seed(r(100))
        playback.load(list, repo.entries(list).first(), 0)
        val vm = entryVm(entryId).also { it.loaded() }
        // Typing "250" / "300" is UI-local; nothing may reach playback before commit.
        assertNull(playback.state.value.previewRhythm)
        assertEquals(100, vm.ui.value.rhythm.tempo)
        vm.commitTempoInput("250")
        assertEquals(250, playback.state.value.previewRhythm?.tempo)
    }

    @Test
    fun committingUnchangedTempoDoesNothing() = runBlocking {
        val (list, entryId) = seed(r(100))
        playback.load(list, repo.entries(list).first(), 0)
        val vm = entryVm(entryId).also { it.loaded() }
        assertTrue(vm.commitTempoInput("100"))
        assertNull(playback.state.value.previewRhythm)
        // rhythm was never edited, so a library save with an untouched override keeps library rhythm
        repo.saveToSetlist(entryId, r(72), "lib notes")
        val vm2 = entryVm(entryId).also { it.loaded() }
        vm2.commitTempoInput("72")
        vm2.setNotes("n2")
        vm2.saveToLibrary()?.join()
        assertEquals(100, repo.songs().first().single().rhythm.tempo)
    }

    @Test
    fun notesOnlyEditThenSaveToLibraryLeavesRhythmAlone() = runBlocking {
        val (_, entryId) = seed(r(100))
        repo.saveToSetlist(entryId, r(72), "lib notes")
        val vm = entryVm(entryId).also { it.loaded() }
        vm.setNotes("other")
        vm.saveToLibrary()?.join()
        assertEquals(100, repo.songs().first().single().rhythm.tempo)
    }

    @Test
    fun saveToSetlistKeepsPreviewUntilEntriesRefresh() = runBlocking {
        val (list, entryId) = seed(r(100))
        playback.load(list, repo.entries(list).first(), 0)
        val vm = entryVm(entryId).also { it.loaded() }
        vm.setTempo(140)
        vm.saveToSetlist()?.join()
        vm.dismiss()
        assertEquals(140, playback.state.value.previewRhythm?.tempo)
        playback.updateEntries(list, repo.entries(list).first())
        assertNull(playback.state.value.previewRhythm)
        assertEquals(140, playback.state.value.effectiveRhythm?.tempo)
    }

    @Test
    fun cancelBeforeSaveStillClearsPreview() = runBlocking {
        val (list, entryId) = seed(r(100))
        playback.load(list, repo.entries(list).first(), 0)
        val vm = entryVm(entryId).also { it.loaded() }
        vm.setTempo(140)
        vm.dismiss()
        assertNull(playback.state.value.previewRhythm)
    }

    @Test
    fun notesOverrideCanBeClearedAndIsMentioned() = runBlocking {
        val (list, entryId) = seed(r(100))
        repo.saveToSetlist(entryId, r(100), "gig notes")
        val vm = entryVm(entryId).also { it.loaded() }
        assertTrue(vm.ui.value.notesOverridden)
        assertEquals("gig notes", vm.ui.value.notes)
        vm.clearNotesOverride()?.join()
        assertFalse(vm.ui.value.notesOverridden)
        assertEquals("lib notes", vm.ui.value.notes)
        assertEquals("lib notes", repo.entries(list).first().single().notes)
    }

    @Test
    fun saveToLibraryLeavesLibraryNotesAloneUnlessNotesWereEdited() = runBlocking {
        val (_, entryId) = seed(r(100))
        repo.saveToSetlist(entryId, r(100), "gig notes")
        val vm = entryVm(entryId).also { it.loaded() }
        vm.setTempo(120)
        vm.saveToLibrary()?.join()
        assertEquals("lib notes", repo.songs().first().single().notes)
        assertEquals(120, repo.songs().first().single().rhythm.tempo)
        assertTrue(vm.ui.value.activeOverrideText!!.contains("notes"))

        vm.setNotes("edited")
        vm.saveToLibrary()?.join()
        assertEquals("edited", repo.songs().first().single().notes)
    }

    @Test
    fun saveToLibraryLeavesLibraryRhythmAloneWhenOverrideUntouched() = runBlocking {
        val (_, entryId) = seed(r(100))
        repo.saveToSetlist(entryId, r(72), "lib notes")
        val vm = entryVm(entryId).also { it.loaded() }
        vm.setNotes("new lib notes")
        vm.saveToLibrary()?.join()
        assertEquals(100, repo.songs().first().single().rhythm.tempo)
        assertEquals("new lib notes", repo.songs().first().single().notes)
    }

    @Test
    fun afterLibrarySaveWithOverrideSheetShowsWhatPlaysAndSaveToSetlistKeepsIt() = runBlocking {
        val (list, entryId) = seed(r(100))
        repo.saveToSetlist(entryId, r(72), "lib notes")
        val vm = entryVm(entryId).also { it.loaded() }
        vm.setTempo(110)
        vm.saveToLibrary()?.join()
        assertEquals(72, vm.ui.value.rhythm.tempo)
        vm.setNotes("x")
        vm.saveToSetlist()?.join()
        val e = repo.entries(list).first().single()
        assertEquals(72, e.rhythm.tempo)
        assertTrue(e.rhythmOverridden)
    }

    @Test
    fun dismissingASheetWithNoChangesKeepsAnEarlierDrag() = runBlocking {
        val (list, entryId) = seed(r(120))
        val out = CountingOutput()
        val pb = PlaybackController(out)
        pb.load(list, repo.entries(list).first(), 0)
        pb.play()
        val vm = vms.track(EditSongViewModel(entryId, null, repo, pb)).also { it.loaded() }
        vm.setTempo(122) // the player's tempo drag
        assertEquals(122, pb.state.value.previewRhythm?.tempo)
        val before = out.calls.toList()
        vm.openSheet() // a chip opened the sheet just to look
        vm.dismiss() // swiped away
        assertEquals(122, pb.state.value.previewRhythm?.tempo)
        assertEquals(before, out.calls)
        assertEquals(122, vm.ui.value.rhythm.tempo)
    }

    @Test
    fun dismissingASheetUndoesOnlyTheChangesMadeInIt() = runBlocking {
        val (list, entryId) = seed(r(120))
        val out = CountingOutput()
        val pb = PlaybackController(out)
        pb.load(list, repo.entries(list).first(), 0)
        pb.play()
        val vm = vms.track(EditSongViewModel(entryId, null, repo, pb)).also { it.loaded() }
        vm.setTempo(122)
        vm.openSheet()
        vm.setPattern(ClickPattern(8))
        assertEquals(r(122, pat = 8), pb.state.value.previewRhythm)
        vm.dismiss()
        assertEquals(r(122), pb.state.value.previewRhythm)
        assertEquals(r(122), vm.ui.value.rhythm)
        assertEquals("cpm:122", out.calls.last())
    }

    @Test
    fun dismissingASheetOpenedWithoutAPreviewDropsItsChanges() = runBlocking {
        val (list, entryId) = seed(r(120))
        playback.load(list, repo.entries(list).first(), 0)
        val vm = entryVm(entryId).also { it.loaded() }
        vm.openSheet()
        vm.setTempo(140)
        vm.dismiss()
        assertNull(playback.state.value.previewRhythm)
        assertEquals(120, vm.ui.value.rhythm.tempo)
    }

    @Test
    fun cancellingASheetDoesNotUndoAClearedOverride() = runBlocking {
        val (list, entryId) = seed(r(100))
        repo.saveToSetlist(entryId, r(72), "lib notes")
        playback.load(list, repo.entries(list).first(), 0)
        val vm = entryVm(entryId).also { it.loaded() }
        vm.openSheet()
        vm.clearOverride()!!.join()
        vm.dismiss()
        assertEquals(100, vm.ui.value.rhythm.tempo)
        assertEquals(100, playback.state.value.effectiveRhythm?.tempo)
    }

    @Test
    fun savesAreNoOpsUntilTheEntryHasLoaded() = runBlocking {
        val (list, entryId) = seed(r(100))
        // Hold the view model's load back so the draft is still the default 120 4/4.
        Dispatchers.setMain(StandardTestDispatcher())
        val vm = entryVm(entryId)
        assertFalse(vm.ui.value.loaded)
        assertNull(vm.saveToSetlist())
        assertNull(vm.saveToLibrary())
        val e = repo.entries(list).first().single()
        assertEquals(100, e.rhythm.tempo)
        assertFalse(e.rhythmOverridden)
    }
}
