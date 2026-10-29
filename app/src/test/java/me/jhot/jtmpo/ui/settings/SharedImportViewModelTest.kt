package me.jhot.jtmpo.ui.settings

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.jhot.jtmpo.data.JtmpoDatabase
import me.jhot.jtmpo.data.Repository
import me.jhot.jtmpo.domain.ClickPattern
import me.jhot.jtmpo.domain.Rhythm
import me.jhot.jtmpo.domain.TimeSignature
import me.jhot.jtmpo.importer.sampleBytes
import me.jhot.jtmpo.share.Choice
import me.jhot.jtmpo.share.Match
import me.jhot.jtmpo.share.SetlistFileCodec
import me.jhot.jtmpo.share.SharedEntry
import me.jhot.jtmpo.share.SharedSetlist
import me.jhot.jtmpo.share.SharedSong
import me.jhot.jtmpo.testutil.TestViewModels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SharedImportViewModelTest {
    private lateinit var db: JtmpoDatabase
    private lateinit var repo: Repository
    private lateinit var vm: SharedImportViewModel
    private val vms = TestViewModels()

    private fun r(tempo: Int) = Rhythm(tempo, TimeSignature(4, 4), ClickPattern(4))

    private fun file(name: String, songs: List<SharedSong>, entries: List<Long> = songs.map { it.localId }): ByteArray =
        SetlistFileCodec.encode(SharedSetlist(name, songs, entries.map { id -> SharedEntry(id, songs.first { it.localId == id }.rhythm, null) }))
            .toByteArray()

    /** Blue Harbor gets dialog A, Echo dialog B, Paper Lanterns is new. */
    private fun conflictFile() = file(
        "Friday",
        listOf(
            SharedSong(1, "Blue Harbor", "Ann Example", r(120), "Count in twice"),
            SharedSong(2, "Echo", null, r(100), null),
            SharedSong(3, "Paper Lanterns", "Cal Example", r(80), null),
        ),
        listOf(1, 2, 3, 1),
    )

    private suspend fun await(predicate: (SharedImportState) -> Boolean): SharedImportState =
        withTimeout(5_000) { vm.state.first(predicate) }

    private suspend inline fun <reified T : SharedImportState> awaitState(): T = await { it is T } as T

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), JtmpoDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = Repository(db)
        vm = vms.track(SharedImportViewModel(repo))
    }

    @After
    fun tearDown() {
        vms.shutdown()
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun seedConflicts() {
        repo.createSong("Blue Harbor", null, r(90), null)
        repo.createSong("Echo", "Ben Example", r(100), null)
    }

    @Test
    fun loadWithNoConflictsGoesToConfirm() = runBlocking {
        vm.load(file("Friday", listOf(SharedSong(1, "Paper Lanterns", null, r(80), null), SharedSong(2, "Echo", null, r(100), null)), listOf(1, 2, 1)))
        val confirm = awaitState<SharedImportState.Confirm>()
        assertEquals(false, confirm.nameCollision)
        assertEquals("Friday", confirm.suggestedName)
        assertEquals(3, confirm.plan.entryCount)
        assertEquals(2, confirm.plan.newCount)
        assertTrue(confirm.plan.conflicts.isEmpty())
        // Nothing is written before the user confirms.
        assertTrue(repo.snapshot().songs.isEmpty())

        vm.confirm(addWithSuffix = false)
        val done = awaitState<SharedImportState.Done>()
        assertEquals(SharedImportState.Done("Friday", newSongs = 2, entries = 3), done)
        val snap = repo.snapshot()
        assertEquals(listOf("Friday"), snap.setlists.map { it.name })
        assertEquals(3, snap.entries.size)
    }

    @Test
    fun confirmWithConflictsShowsConflictOneOfN() = runBlocking {
        seedConflicts()
        vm.load(conflictFile())
        val confirm = awaitState<SharedImportState.Confirm>()
        assertEquals(4, confirm.plan.entryCount)
        assertEquals(0, confirm.plan.exactCount)
        assertEquals(1, confirm.plan.newCount)
        assertEquals(2, confirm.plan.conflicts.size)

        vm.confirm(addWithSuffix = false)
        val conflict = awaitState<SharedImportState.Conflict>()
        assertEquals(0, conflict.index)
        assertEquals(1, conflict.number)
        assertEquals(2, conflict.total)
        assertEquals(Match.CONFLICT_A, conflict.current.match)
        assertEquals("Blue Harbor", conflict.current.candidate?.title)
    }

    @Test
    fun answeringTheLastConflictCommits() = runBlocking {
        seedConflicts()
        vm.load(conflictFile())
        awaitState<SharedImportState.Confirm>()
        vm.confirm(addWithSuffix = false)
        awaitState<SharedImportState.Conflict>()
        vm.answer(Choice.UPDATE)
        val second = await { it is SharedImportState.Conflict && it.index == 1 } as SharedImportState.Conflict
        assertEquals(Match.CONFLICT_B, second.current.match)
        assertEquals(2, second.number)
        // Answering the first conflict writes nothing yet.
        assertNull(repo.snapshot().songs.single { it.title == "Blue Harbor" }.artist)

        vm.answer(Choice.KEEP_MINE)
        val done = awaitState<SharedImportState.Done>()
        assertEquals(SharedImportState.Done("Friday", newSongs = 1, entries = 4), done)
        val snap = repo.snapshot()
        val harbor = snap.songs.single { it.title == "Blue Harbor" }
        assertEquals("Ann Example", harbor.artist)
        assertEquals(120, harbor.tempo)
        assertEquals(3, snap.songs.size)
        assertEquals(4, snap.entries.size)
    }

    @Test
    fun updateIsNotAChoiceForDialogB() = runBlocking {
        seedConflicts()
        vm.load(conflictFile())
        awaitState<SharedImportState.Confirm>()
        vm.confirm(addWithSuffix = false)
        awaitState<SharedImportState.Conflict>()
        vm.answer(Choice.NEW)
        await { it is SharedImportState.Conflict && it.index == 1 }
        vm.answer(Choice.UPDATE)
        val still = vm.state.value as SharedImportState.Conflict
        assertEquals(1, still.index)
        assertTrue(repo.snapshot().setlists.isEmpty())
    }

    @Test
    fun cancelAtConflictTwoWritesNothing() = runBlocking {
        seedConflicts()
        val before = repo.snapshot()
        vm.load(conflictFile())
        awaitState<SharedImportState.Confirm>()
        vm.confirm(addWithSuffix = false)
        awaitState<SharedImportState.Conflict>()
        vm.answer(Choice.UPDATE)
        await { it is SharedImportState.Conflict && it.index == 1 }
        vm.cancel()
        assertEquals(SharedImportState.Idle, vm.state.value)
        assertEquals(before, repo.snapshot())
        // A late answer after cancelling must not resurrect the import.
        vm.answer(Choice.KEEP_MINE)
        assertEquals(SharedImportState.Idle, vm.state.value)
        assertEquals(before, repo.snapshot())
    }

    @Test
    fun nameCollisionOffersSuffixOrCancel() = runBlocking {
        repo.createSetlist(" friday ")
        repo.createSetlist("Friday (2)")
        val bytes = file("Friday", listOf(SharedSong(1, "Paper Lanterns", null, r(80), null)))
        vm.load(bytes)
        val confirm = awaitState<SharedImportState.Confirm>()
        assertTrue(confirm.nameCollision)
        assertEquals("Friday (3)", confirm.suggestedName)

        // Without the suffix the only way on is cancel.
        vm.confirm(addWithSuffix = false)
        assertEquals(SharedImportState.Idle, vm.state.value)
        assertEquals(2, repo.snapshot().setlists.size)
        assertTrue(repo.snapshot().songs.isEmpty())

        vm.load(bytes)
        awaitState<SharedImportState.Confirm>()
        vm.confirm(addWithSuffix = true)
        assertEquals("Friday (3)", (awaitState<SharedImportState.Done>()).name)
        assertTrue(repo.snapshot().setlists.any { it.name == "Friday (3)" })
    }

    @Test
    fun wrongFileKindsGiveTheRightMessage() = runBlocking {
        fun errorFor(bytes: ByteArray): SharedImportError = runBlocking {
            vm.cancel()
            vm.load(bytes)
            (awaitState<SharedImportState.Error>()).kind
        }
        assertEquals(SharedImportError.BACKUP, errorFor("""{"version":1,"songs":[],"setlists":[],"entries":[]}""".toByteArray()))
        assertEquals(SharedImportError.NOT_A_SETLIST, errorFor("hello".toByteArray()))
        assertEquals(SharedImportError.NOT_A_SETLIST, errorFor("""{"type":"other"}""".toByteArray()))
        assertEquals(SharedImportError.NOT_A_SETLIST, errorFor(sampleBytes()))
        assertEquals(
            SharedImportError.NEWER_VERSION,
            errorFor("""{"type":"jtmpo-setlist","version":2,"songs":[],"entries":[]}""".toByteArray()),
        )
        assertEquals(
            SharedImportError.INVALID,
            errorFor("""{"type":"jtmpo-setlist","version":1,"name":"X","songs":[],"entries":[]}""".toByteArray()),
        )
        val good = conflictFile()
        assertEquals(SharedImportError.INVALID, errorFor(good.copyOf(good.size / 2)))
        assertTrue(repo.snapshot().songs.isEmpty())
    }

    @Test
    fun garbageBytesNeverCrash() = runBlocking {
        for (bytes in listOf(Random(7).nextBytes(1024), ByteArray(0), ByteArray(6 * 1024 * 1024))) {
            vm.cancel()
            vm.load(bytes)
            assertEquals(SharedImportError.NOT_A_SETLIST, (awaitState<SharedImportState.Error>()).kind)
        }
        assertTrue(repo.snapshot().songs.isEmpty())
        vm.cancel()
        assertEquals(SharedImportState.Idle, vm.state.value)
    }

    private suspend fun loadSimple() {
        vm.load(file("Friday", listOf(SharedSong(1, "Paper Lanterns", null, r(80), null))))
        awaitState<SharedImportState.Confirm>()
    }

    @Test
    fun loadWhileCommittingIsIgnored() = runBlocking {
        loadSimple()
        vm.confirm(addWithSuffix = false)
        assertEquals(SharedImportState.Committing, vm.state.value)
        vm.load(file("Saturday", listOf(SharedSong(1, "Night Ferry", null, r(90), null))))
        assertEquals(SharedImportState.Committing, vm.state.value)
        assertEquals(SharedImportState.Done("Friday", newSongs = 1, entries = 1), awaitState<SharedImportState.Done>())
        val snap = repo.snapshot()
        assertEquals(listOf("Paper Lanterns"), snap.songs.map { it.title })
        assertEquals(listOf("Friday"), snap.setlists.map { it.name })
    }

    @Test
    fun secondAnswerWhileCommittingIsIgnored() = runBlocking {
        seedConflicts()
        vm.load(conflictFile())
        awaitState<SharedImportState.Confirm>()
        vm.confirm(addWithSuffix = false)
        awaitState<SharedImportState.Conflict>()
        vm.answer(Choice.NEW)
        await { it is SharedImportState.Conflict && it.index == 1 }
        vm.answer(Choice.NEW)
        assertEquals(SharedImportState.Committing, vm.state.value)
        vm.answer(Choice.KEEP_MINE)
        assertEquals(SharedImportState.Committing, vm.state.value)
        awaitState<SharedImportState.Done>()
        assertEquals(1, repo.snapshot().setlists.size)
    }

    @Test
    fun secondConfirmWhileCommittingIsIgnored() = runBlocking {
        loadSimple()
        vm.confirm(addWithSuffix = false)
        assertEquals(SharedImportState.Committing, vm.state.value)
        vm.confirm(addWithSuffix = false)
        assertEquals(SharedImportState.Committing, vm.state.value)
        awaitState<SharedImportState.Done>()
        assertEquals(1, repo.snapshot().setlists.size)
    }

    @Test
    fun utf8BomFileGoesToConfirm() = runBlocking {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        vm.load(bom + file("Friday", listOf(SharedSong(1, "Paper Lanterns", null, r(80), null))))
        val confirm = awaitState<SharedImportState.Confirm>()
        assertEquals("Friday", confirm.plan.name)
    }

    @Test
    fun successfulCommitEndsInDone() = runBlocking {
        loadSimple()
        vm.confirm(addWithSuffix = false)
        assertEquals(SharedImportState.Done("Friday", newSongs = 1, entries = 1), awaitState<SharedImportState.Done>())
    }

    @Test
    fun commitFailureEndsInErrorAndWritesNothing() = runBlocking {
        vm.load(file("Friday", listOf(SharedSong(1, "Paper Lanterns", null, r(80), null))))
        awaitState<SharedImportState.Confirm>()
        // The song appears after planning, so the commit's own duplicate check trips.
        repo.createSong("Paper Lanterns", null, r(90), null)
        val before = repo.snapshot()
        vm.confirm(addWithSuffix = false)
        assertEquals(SharedImportState.Error(SharedImportError.COMMIT_FAILED), awaitState<SharedImportState.Error>())
        assertEquals(before, repo.snapshot())
    }
}
