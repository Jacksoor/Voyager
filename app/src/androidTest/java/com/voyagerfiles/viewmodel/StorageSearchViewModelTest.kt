package com.voyagerfiles.viewmodel

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.index.IndexedFileSource
import com.voyagerfiles.data.index.SearchTerms
import com.voyagerfiles.data.index.StorageCategoryIndex
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileTypeFilter
import com.voyagerfiles.data.model.SortOrder
import com.voyagerfiles.util.StorageVolumeInfo
import java.io.File
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StorageSearchViewModelTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private lateinit var root: File
    /** Folders the walker opened; an index-only search opens none. */
    private val walkedFolders = AtomicInteger()
    private val scanned = CopyOnWriteArrayList<String>()

    /** Stands in for MediaStore: knows [known] (absolute paths) and covers the test volume. */
    private inner class FakeIndex(var known: Set<String>, var covers: Boolean = true) : IndexedFileSource {
        val queries = AtomicInteger()
        override fun covers(volume: StorageVolumeInfo) = covers
        override fun search(query: String, volumes: List<StorageVolumeInfo>, showHidden: Boolean): List<FileItem> {
            queries.incrementAndGet()
            val terms = SearchTerms.parse(query)
            return known.map(::File).filter { it.exists() && SearchTerms.matches(it.name, terms) }
                .map { FileItem(it.name, it.path, it.isDirectory, it.length(), Date(it.lastModified())) }
        }
        override fun ofType(type: FileTypeFilter, volumes: List<StorageVolumeInfo>, showHidden: Boolean): List<FileItem> =
            known.map(::File).filter { it.exists() }
                .map { FileItem(it.name, it.path, it.isDirectory, it.length(), Date(it.lastModified())) }
                .filter(type::matches)
        override fun allPaths(volumes: List<StorageVolumeInfo>) = known
    }

    @Before
    fun setUp() {
        // Canonical, like MediaStore's paths: opening a result checks it isn't reached through a link.
        root = File(application.cacheDir.canonicalFile, "storage-search-viewmodel").apply {
            deleteRecursively()
            mkdirs()
            resolve("alpha.txt").writeText("alpha")
            resolve("beta.bin").writeText("beta")
            resolve("saves").mkdirs()
            resolve("saves/alpha-save.sav").writeText("written past FUSE")
            resolve("game").mkdirs()
            resolve("game/.nomedia").writeText("")
            resolve("game/alpha-sprite.png").writeText("png")
        }
    }

    @After
    fun tearDown() = runBlocking {
        withContext(Dispatchers.Main) { store.clear() }
        root.deleteRecursively()
        Unit
    }

    @Test
    fun typingAsksTheIndexAndWalksNothing() = runBlocking {
        val index = FakeIndex(setOf(path("alpha.txt"), path("beta.bin")))
        val model = model(index)
        withContext(Dispatchers.Main) { model.setQuery("alp") }
        await(model) { it.phase == SearchPhase.DONE && it.searchedQuery == "alp" }
        assertEquals(listOf("alpha.txt"), model.state.value.results.map { it.name })
        assertEquals(0, walkedFolders.get())
        assertFalse(model.state.value.searchedUnindexed)
    }

    @Test
    fun unindexedFilesAppearOnRequestAndTheIndexIsRepaired() = runBlocking {
        val gone = path("gone.txt")
        val index = FakeIndex(setOf(path("alpha.txt"), gone))
        val model = model(index)
        withContext(Dispatchers.Main) { model.setQuery("alpha") }
        await(model) { it.phase == SearchPhase.DONE }
        assertEquals(listOf("alpha.txt"), model.state.value.results.map { it.name })

        withContext(Dispatchers.Main) { model.searchUnindexedNow() }
        await(model) { it.phase == SearchPhase.DONE && it.searchedUnindexed }
        // Hidden files are off, but .nomedia only hides from the index, not from the user.
        assertEquals(setOf("alpha.txt", "alpha-save.sav", "alpha-sprite.png"), model.state.value.results.map { it.name }.toSet())
        // The gone row and the files the index lacks are fixed; the .nomedia folder is left alone.
        assertEquals(setOf(gone, path("beta.bin"), path("saves/alpha-save.sav")), scanned.toSet())
        assertEquals(3, model.state.value.repairedEntries)
    }

    @Test
    fun searchingUnindexedEveryTimeWalksWithEachQuery() = runBlocking {
        val model = model(FakeIndex(setOf(path("alpha.txt"))))
        withContext(Dispatchers.Main) { model.setSearchUnindexed(true) }
        withContext(Dispatchers.Main) { model.setQuery("sprite") }
        await(model) { it.phase == SearchPhase.DONE && it.searchedQuery == "sprite" }
        assertEquals(listOf("alpha-sprite.png"), model.state.value.results.map { it.name })
        assertTrue(model.state.value.searchedUnindexed)
    }

    @Test
    fun volumesTheIndexDoesNotCoverAreWalkedAutomatically() = runBlocking {
        val model = model(FakeIndex(emptySet(), covers = false))
        withContext(Dispatchers.Main) { model.setQuery("beta") }
        await(model) { it.phase == SearchPhase.DONE && it.searchedQuery == "beta" }
        assertEquals(listOf("beta.bin"), model.state.value.results.map { it.name })
        assertTrue(model.state.value.searchedUnindexed)
        assertTrue(walkedFolders.get() > 0)
    }

    @Test
    fun returningKeepsTheResultsAndOnlyDropsWhatIsGone() = runBlocking {
        val index = FakeIndex(setOf(path("alpha.txt")))
        val model = model(index)
        withContext(Dispatchers.Main) { model.setQuery("alpha") }
        await(model) { it.phase == SearchPhase.DONE }
        withContext(Dispatchers.Main) { model.searchUnindexedNow() }
        await(model) { it.phase == SearchPhase.DONE && it.searchedUnindexed }
        val walks = walkedFolders.get()

        File(path("saves/alpha-save.sav")).delete()
        root.resolve("alpha-new.txt").writeText("new")
        index.known = index.known + path("alpha-new.txt")
        withContext(Dispatchers.Main) { model.onReturn() }
        await(model) { state -> state.results.any { it.name == "alpha-new.txt" } }
        assertEquals(setOf("alpha.txt", "alpha-new.txt", "alpha-sprite.png"), model.state.value.results.map { it.name }.toSet())
        assertEquals("Nothing is walked again", walks, walkedFolders.get())
        assertTrue(model.state.value.searchedUnindexed)
    }

    @Test
    fun theTypeFilterOnlyNarrowsWhatIsShown() = runBlocking {
        root.resolve("alpha-folder").mkdirs()
        val model = model(FakeIndex(setOf(path("alpha.txt"), path("alpha-folder"))))
        withContext(Dispatchers.Main) { model.setQuery("alpha") }
        await(model) { it.phase == SearchPhase.DONE }
        withContext(Dispatchers.Main) { model.setTypeFilter(FileTypeFilter.FOLDERS) }
        // Filtering runs off the main thread.
        await(model) { it.shown.size == 1 }
        assertEquals(listOf("alpha-folder"), model.state.value.shown.map { it.name })
        assertEquals(2, model.state.value.results.size)
    }

    @Test
    fun aTypeWithoutAQueryIsListedInFullAndSortedLikeQuickAccess() = runBlocking {
        root.resolve("b-photo.jpg").writeText("b")
        root.resolve("a-photo.jpg").writeText("a")
        val model = model(FakeIndex(setOf(path("alpha.txt"), path("a-photo.jpg"), path("b-photo.jpg"))))
        withContext(Dispatchers.Main) { model.setTypeFilter(FileTypeFilter.IMAGES) }
        await(model) { it.phase == SearchPhase.DONE && it.browsedType == FileTypeFilter.IMAGES }
        assertTrue(model.state.value.browsing)
        assertEquals(listOf("a-photo.jpg", "b-photo.jpg"), model.state.value.shown.map { it.name })

        withContext(Dispatchers.Main) { model.setSortOrder(SortOrder.DESCENDING) }
        await(model) { state -> state.shown.map { it.name } == listOf("b-photo.jpg", "a-photo.jpg") }

        // Typing searches within the type; the results are ranked, not sorted.
        withContext(Dispatchers.Main) { model.setQuery("photo") }
        await(model) { it.searchedQuery == "photo" && it.phase == SearchPhase.DONE }
        assertEquals(null, model.state.value.browsedType)
        assertFalse(model.state.value.browsing)
        // Back to no query: the type is listed again.
        withContext(Dispatchers.Main) { model.setQuery("") }
        await(model) { it.browsedType == FileTypeFilter.IMAGES && it.phase == SearchPhase.DONE }
        withContext(Dispatchers.Main) { model.setTypeFilter(FileTypeFilter.ALL) }
        await(model) { it.phase == SearchPhase.IDLE }
    }

    @Test
    fun aListedTypeFindsItsUnindexedFilesOnRequest() = runBlocking {
        val model = model(FakeIndex(setOf(path("alpha.txt"))))
        withContext(Dispatchers.Main) { model.setTypeFilter(FileTypeFilter.IMAGES) }
        await(model) { it.phase == SearchPhase.DONE && it.browsedType == FileTypeFilter.IMAGES }
        assertTrue(model.state.value.shown.isEmpty())
        withContext(Dispatchers.Main) { model.searchUnindexedNow() }
        await(model) { it.phase == SearchPhase.DONE && it.searchedUnindexed }
        assertEquals(listOf("alpha-sprite.png"), model.state.value.shown.map { it.name })
    }

    @Test
    fun aGoneResultIsRemovedAndForgottenByTheIndex() = runBlocking {
        val model = model(FakeIndex(setOf(path("alpha.txt"))))
        withContext(Dispatchers.Main) { model.setQuery("alpha") }
        await(model) { it.phase == SearchPhase.DONE }
        val file = model.state.value.results.single()
        assertTrue(withContext(Dispatchers.Main) { model.validate(file) })
        File(file.path).delete()
        assertFalse(withContext(Dispatchers.Main) { model.validate(file) })
        assertTrue(model.state.value.results.isEmpty())
        assertEquals(1, model.state.value.staleEntries)
        withTimeout(5_000) { while (file.path !in scanned) delay(25) }
    }

    @Test
    fun withoutAccessNothingIsRead() = runBlocking {
        val index = FakeIndex(setOf(path("alpha.txt")))
        val model = model(index, granted = false)
        withContext(Dispatchers.Main) { model.setQuery("alpha") }
        await(model) { it.phase == SearchPhase.DENIED }
        assertEquals(0, index.queries.get())
        assertTrue(model.state.value.results.isEmpty())
    }

    private fun path(relative: String) = root.resolve(relative).path

    private suspend fun model(index: IndexedFileSource, granted: Boolean = true): StorageSearchViewModel =
        withContext(Dispatchers.Main) {
            StorageSearchViewModel(
                application,
                SavedStateHandle(),
                walker = StorageCategoryIndex(canRead = { walkedFolders.incrementAndGet(); true }),
                index = index,
                volumeProvider = { listOf(StorageVolumeInfo("Test storage", root.path, true, false, "mounted")) },
                permissionGranted = { granted },
                preferences = null,
                repairScanner = { scanned += it },
            ).also {
                store.put("search", it)
                it.configure(showHidden = false, accessGranted = granted)
            }
        }

    private suspend fun await(viewModel: StorageSearchViewModel, condition: (StorageSearchState) -> Boolean) {
        withTimeout(10_000) { while (!condition(viewModel.state.value)) delay(25) }
    }
}
