package com.voyagerfiles.ui.screens

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.os.Environment
import android.os.Build
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.index.IndexedFileSource
import com.voyagerfiles.data.index.SearchTerms
import com.voyagerfiles.data.model.FileItem
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.isSelected
import com.voyagerfiles.data.model.FileTypeFilter
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.util.StorageVolumeInfo
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import com.voyagerfiles.viewmodel.StorageSearchViewModel
import com.voyagerfiles.viewmodel.SearchPhase
import java.util.Date
import kotlinx.coroutines.flow.first
import androidx.compose.ui.test.hasTestTag
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class StorageSearchScreenTest {
    @get:Rule val compose = createComposeRule()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(application.cacheDir, "storage-search-screen").apply {
            deleteRecursively()
            resolve("one").mkdirs()
            resolve("two").mkdirs()
            resolve("one/report.txt").writeText("first")
            resolve("two/report.txt").writeText("second")
        }
    }

    @After
    fun tearDown() {
        compose.runOnIdle { store.clear() }
        root.deleteRecursively()
    }

    @Test
    fun typingShowsResultsWithTheirFolderAndTheFolderButtonOpensIt() {
        val model = searchModel()
        var opened: String? = null
        showSearch(model) { opened = it }
        type("report")
        awaitResults(model)
        val first = root.resolve("one/report.txt").canonicalFile
        val second = root.resolve("two/report.txt").canonicalFile
        // Date and size, then the folder each result is in, from the volume down.
        compose.onNodeWithText(" · 5 B · ", substring = true).assertIsDisplayed()
        compose.onNodeWithText(" · 6 B · ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("one").assertIsDisplayed()
        compose.onNodeWithText("two").assertIsDisplayed()
        // A volume that isn't mounted is named in the footer.
        compose.onNodeWithText("Removed card is not available and was not searched.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open folder for ${first.path}").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open folder for ${second.path}").performClick()
        compose.waitUntil(5_000) { opened != null }
        assertEquals(second.parent, opened)
    }

    @Test
    fun searchAgainFindsFilesAddedSinceAndKeepsTheUnindexedSearch() {
        val model = searchModel()
        showSearch(model) {}
        type("report")
        awaitResults(model)
        compose.onNodeWithTag(STORAGE_SEARCH_RESULTS_TAG).performScrollToNode(hasTestTag(STORAGE_SEARCH_UNINDEXED_TAG))
        compose.onNodeWithTag(STORAGE_SEARCH_UNINDEXED_TAG).performClick()
        compose.waitUntil(10_000) { model.state.value.searchedUnindexed && model.state.value.phase == SearchPhase.DONE }
        root.resolve("one/report-new.txt").writeText("new")
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Search again").performClick()
        compose.waitUntil(10_000) { model.state.value.results.size == 3 && model.state.value.phase == SearchPhase.DONE }
        assertTrue(model.state.value.searchedUnindexed)
    }

    @Test
    fun tappingAFolderResultOpensTheFolderItself() {
        val folder = root.resolve("report-folder").apply { mkdirs() }
        val model = searchModel()
        var opened: String? = null
        showSearch(model) { opened = it }
        type("report")
        compose.waitUntil(10_000) { model.state.value.results.size == 3 }
        compose.onNodeWithTag(STORAGE_SEARCH_RESULTS_TAG).performScrollToNode(hasText("report-folder"))
        compose.onNodeWithText("report-folder").performClick()
        compose.waitUntil(5_000) { opened != null }
        assertEquals(folder.canonicalPath, opened)
    }

    @Test
    fun removedResultCannotOpenItsFormerLocation() {
        val model = searchModel()
        var opened: String? = null
        showSearch(model) { opened = it }
        type("report")
        awaitResults(model)
        val removed = root.resolve("one/report.txt").canonicalFile
        assertTrue(removed.delete())

        compose.onNodeWithContentDescription("Open folder for ${removed.path}").performClick()
        compose.waitUntil(5_000) { model.state.value.staleEntries == 1 }
        compose.runOnIdle { assertNull(opened) }
        compose.onNodeWithContentDescription("Open folder for ${removed.path}").assertDoesNotExist()
        compose.onNodeWithText("This item changed or is no longer accessible. Search again.").assertIsDisplayed()
    }

    @Test
    fun aTypeChipWithoutAQueryListsTheTypeWithASortMenu() {
        root.resolve("one/photo.jpg").writeText("jpg")
        val model = searchModel()
        showSearch(model) {}
        compose.onNodeWithContentDescription("Sort").assertDoesNotExist()
        compose.onNodeWithText("Images").performClick()
        compose.waitUntil(10_000) { model.state.value.browsedType == FileTypeFilter.IMAGES && model.state.value.phase == SearchPhase.DONE }
        compose.onNodeWithText("photo.jpg").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sort").assertIsDisplayed()
        // Tapping the field already sorted by flips the direction.
        val order = model.state.value.sort.order
        compose.onNodeWithContentDescription("Sort").performClick()
        compose.onNodeWithText(stringFor(model.state.value.sort.by)).performClick()
        compose.waitUntil(5_000) { model.state.value.sort.order != order }
        compose.onNodeWithContentDescription("Sort").performClick()
        compose.onNodeWithText(stringFor(model.state.value.sort.by)).performClick()
        compose.waitUntil(5_000) { model.state.value.sort.order == order }
        type("photo")
        compose.waitUntil(10_000) { model.state.value.searchedQuery == "photo" }
        compose.onNodeWithContentDescription("Sort").assertDoesNotExist()
    }

    private fun stringFor(by: com.voyagerfiles.data.model.SortBy) = application.getString(by.labelRes)

    @Test
    fun aQuickAccessTileOpensTheSearchWithItsTypeAndOffersPermissionRecovery() {
        val preferences = com.voyagerfiles.data.local.PreferencesManager(application)
        val previous = kotlinx.coroutines.runBlocking { preferences.homeLayout.first() }
        try {
            kotlinx.coroutines.runBlocking { preferences.setHomeLayout(com.voyagerfiles.data.model.HomeLayout.DEFAULT) }
            val browser = browserModel()
            compose.setContent { MaterialTheme { AppNavigation(browser, false, onRequestAllFilesAccess = {}) } }
            compose.waitUntil(10_000) { browser.homeLayout.value != null }
            compose.onNodeWithText("Apps").performScrollTo().performClick()
            compose.onNodeWithText("Full storage access is required to search shared storage.").assertIsDisplayed()
            compose.onNode(hasText("Apps") and isSelected()).assertExists()
            // Browsing a type: the keyboard stays down.
            compose.onNodeWithTag(STORAGE_SEARCH_QUERY_TAG).assertIsNotFocused()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Quick Access").assertExists()
        } finally {
            kotlinx.coroutines.runBlocking { preferences.setHomeLayout(previous) }
        }
    }

    @Test
    fun typeChipsNarrowTheResultsAndTheFooterOffersTheUnindexedFolders() {
        root.resolve("report-folder").mkdirs()
        val model = searchModel()
        showSearch(model) {}
        type("report")
        compose.waitUntil(10_000) { model.state.value.results.size == 3 && model.state.value.phase == SearchPhase.DONE }
        compose.onNodeWithTag(STORAGE_SEARCH_RESULTS_TAG).performScrollToNode(hasText("3 results"))
        compose.onNodeWithText("Folders").performClick()
        compose.onNodeWithTag(STORAGE_SEARCH_RESULTS_TAG).performScrollToNode(hasText("1 result"))
        compose.onNodeWithTag(STORAGE_SEARCH_RESULTS_TAG).performScrollToNode(hasTestTag(STORAGE_SEARCH_UNINDEXED_TAG))
        compose.onNodeWithTag(STORAGE_SEARCH_UNINDEXED_TAG).performClick()
        compose.waitUntil(10_000) { model.state.value.searchedUnindexed && model.state.value.phase == SearchPhase.DONE }
        compose.onNodeWithTag(STORAGE_SEARCH_UNINDEXED_TAG).assertDoesNotExist()
    }

    @Test
    fun fileOpenFailureReturnsToTheMainThreadAndShowsItsMessage() {
        root.resolve("unhandled.dat").writeText("fixture")
        val model = searchModel()
        val openedOnMain = AtomicBoolean()
        val context = object : ContextWrapper(application) {
            override fun startActivity(intent: Intent) {
                openedOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                throw ActivityNotFoundException("No handler for the fixture")
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme { StorageSearchScreen(false, true, {}, {}, {}, model) }
            }
        }
        type("unhandled")
        compose.waitUntil(10_000) { model.state.value.results.isNotEmpty() }
        compose.onNodeWithText("unhandled.dat").performClick()
        compose.onNodeWithText("No app can open this file type").assertIsDisplayed()
        assertTrue(openedOnMain.get())
    }

    @Test
    fun homeSearchOffersPermissionRecoveryAndReturnsHome() {
        val browser = browserModel()
        var requested = false
        compose.setContent {
            MaterialTheme { AppNavigation(browser, false, onRequestAllFilesAccess = { requested = true }) }
        }
        compose.onNodeWithContentDescription("Search shared storage").performClick()
        compose.onNodeWithText("Full storage access is required to search shared storage.").assertIsDisplayed()
        compose.onNodeWithText("Grant full access").performClick()
        assertTrue(requested)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Search shared storage").assertIsDisplayed()
    }

    @Test
    fun realSharedStorageResultOpensANewLocalSessionForItsFolder() {
        assumeTrue("Full storage access is required for the shared-storage navigation fixture", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
        val fixture = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "voyager-search-${UUID.randomUUID()}")
        val needle = "voyager-search-${UUID.randomUUID()}"
        val firstFolder = fixture.resolve("one").apply { mkdirs() }
        val chosenFolder = fixture.resolve("two").apply { mkdirs() }
        val first = firstFolder.resolve("$needle.txt").apply { writeText("first") }
        val chosen = chosenFolder.resolve("$needle.txt").apply { writeText("chosen") }
        try {
            val browser = browserModel()
            compose.runOnIdle { browser.openLocalRoot(firstFolder.canonicalPath) }
            compose.setContent { MaterialTheme { AppNavigation(browser, true, onRequestAllFilesAccess = {}) } }
            compose.waitUntil(10_000) { browser.browseState.value.currentPath == firstFolder.canonicalPath }
            val originalSession = browser.activeSession.value?.id
            compose.onNodeWithContentDescription("Search shared storage").performClick()
            compose.onNodeWithTag("storage-search-query").performTextInput(needle)
            compose.onNodeWithTag("storage-search-query").performImeAction()
            val chosenPath = chosen.canonicalPath
            compose.waitUntil(45_000) { compose.onAllNodesWithText("2 results").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("storage-search-results").performScrollToNode(hasContentDescription("Open folder for $chosenPath"))
            compose.onNodeWithContentDescription("Open folder for $chosenPath").performClick()
            compose.waitUntil(10_000) {
                browser.browseState.value.currentPath == chosenFolder.canonicalPath && !browser.browseState.value.isLoading
            }
            compose.onNodeWithText("$needle.txt").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(FileSource.LOCAL, browser.browseState.value.source)
                assertEquals(chosenFolder.canonicalPath, browser.activeSession.value?.rootPath)
                assertNotEquals(originalSession, browser.activeSession.value?.id)
            }
            assertEquals("first", first.readText())
            assertEquals("chosen", chosen.readText())
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithTag("storage-search-query").assertIsDisplayed()
        } finally {
            fixture.deleteRecursively()
        }
    }

    /** The test volume counts as indexed: the fake index knows every file below [root], like MediaStore would. */
    private fun searchModel(reads: AtomicInteger = AtomicInteger()): StorageSearchViewModel {
        lateinit var model: StorageSearchViewModel
        val index = object : IndexedFileSource {
            override fun covers(volume: StorageVolumeInfo) = true
            override fun search(query: String, volumes: List<StorageVolumeInfo>, showHidden: Boolean): List<FileItem> {
                val terms = SearchTerms.parse(query)
                return root.walkTopDown().drop(1).filter { SearchTerms.matches(it.name, terms) }
                    .map { FileItem(it.name, it.canonicalPath, it.isDirectory, it.length(), Date(it.lastModified())) }.toList()
            }
            override fun ofType(type: FileTypeFilter, volumes: List<StorageVolumeInfo>, showHidden: Boolean): List<FileItem> =
                root.walkTopDown().drop(1)
                    .map { FileItem(it.name, it.canonicalPath, it.isDirectory, it.length(), Date(it.lastModified())) }
                    .filter(type::matches).toList()
            override fun allPaths(volumes: List<StorageVolumeInfo>) =
                root.walkTopDown().drop(1).mapTo(HashSet()) { it.canonicalPath }
        }
        compose.runOnIdle {
            model = StorageSearchViewModel(application,
                index = index,
                volumeProvider = {
                    reads.incrementAndGet()
                    listOf(
                        StorageVolumeInfo("Test storage", root.canonicalPath, true, false, "mounted"),
                        StorageVolumeInfo("Removed card", null, false, true, "removed"),
                    )
                },
                permissionGranted = { true },
                preferences = null,
                repairScanner = {},
            )
            store.put("search", model)
        }
        return model
    }

    private fun browserModel(): FileBrowserViewModel {
        lateinit var model: FileBrowserViewModel
        compose.runOnIdle { model = FileBrowserViewModel(application); store.put("browser", model) }
        return model
    }

    private fun showSearch(model: StorageSearchViewModel, onOpenFolder: (String) -> Unit) {
        compose.setContent {
            MaterialTheme { StorageSearchScreen(false, true, {}, {}, onOpenFolder, model) }
        }
    }

    private fun awaitResults(model: StorageSearchViewModel) {
        compose.waitUntil(10_000) { model.state.value.phase == SearchPhase.DONE && model.state.value.results.size == 2 }
        compose.onNodeWithTag(STORAGE_SEARCH_RESULTS_TAG).performScrollToNode(hasText("2 results"))
        compose.onNodeWithTag(STORAGE_SEARCH_RESULTS_TAG).performScrollToIndex(0)
    }

    private fun type(text: String) {
        compose.onNodeWithTag(STORAGE_SEARCH_QUERY_TAG).performTextInput(text)
        compose.onNodeWithTag(STORAGE_SEARCH_QUERY_TAG).performImeAction()
    }
}
