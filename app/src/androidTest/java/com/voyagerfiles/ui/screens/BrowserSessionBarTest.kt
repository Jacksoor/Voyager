package com.voyagerfiles.ui.screens

import android.app.Application
import android.os.Environment
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.R
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.data.model.SearchBarMode
import com.voyagerfiles.ui.text.UiText
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import com.voyagerfiles.viewmodel.PinnedSession
import com.voyagerfiles.viewmodel.PinnedSessionCodec
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import com.voyagerfiles.viewmodel.SnackbarAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class BrowserSessionBarTest {
    @get:Rule val compose = createComposeRule()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = PreferencesManager(application)
    private val store = ViewModelStore()
    private lateinit var first: File
    private lateinit var second: File
    private lateinit var viewModel: FileBrowserViewModel
    private lateinit var savedSearchMode: SearchBarMode
    private var savedPins: String? = null

    @Before fun setUp() {
        savedSearchMode = runBlocking { prefs.searchBarMode.first() }
        savedPins = runBlocking { prefs.pinnedSessions.first() }
        runBlocking {
            prefs.setSearchBarMode(SearchBarMode.TOP)
            prefs.setPinnedSessions("")
        }
        first = File(application.cacheDir, "session-bar-first").apply { mkdirs() }
        File(first, "inner").mkdirs()
        second = File(application.cacheDir, "session-bar-second").apply { mkdirs() }
        compose.runOnIdle {
            viewModel = FileBrowserViewModel(application)
            store.put("browser", viewModel)
            viewModel.openLocalFolder(first.path)
        }
    }

    @After fun tearDown() {
        compose.runOnIdle { store.clear() }
        runBlocking {
            prefs.setSearchBarMode(savedSearchMode)
            prefs.setPinnedSessions(savedPins.orEmpty())
        }
        first.deleteRecursively()
        second.deleteRecursively()
    }

    private fun showBrowser() {
        compose.setContent {
            MaterialTheme {
                BrowserScreen(viewModel = viewModel, onNavigateBack = {}, isTelevision = false, hasAllFilesAccess = true)
            }
        }
        compose.waitUntil(10_000) { viewModel.browseState.value.currentPath == first.path }
    }

    private fun openSecond(): String {
        compose.runOnIdle { viewModel.openLocalFolder(second.path) }
        compose.waitUntil(10_000) { viewModel.browseState.value.currentPath == second.path }
        return viewModel.activeSession.value!!.id
    }

    private fun sessionId(folder: File) = viewModel.sessions.value.single { it.startPath == folder.path }.id

    @Test fun oneTapSwitchesToAnotherSession() {
        showBrowser()
        val firstId = sessionId(first)
        openSecond()

        compose.onNodeWithTag(sessionBarItemTestTag(firstId)).performClick()

        compose.waitUntil(10_000) { viewModel.activeSession.value?.id == firstId }
        compose.onNodeWithTag(sessionBarItemTestTag(firstId)).assertIsSelected()
        assertEquals(first.path, viewModel.browseState.value.currentPath)
    }

    @Test fun longPressClosesASession() {
        showBrowser()
        val secondId = openSecond()

        compose.onNodeWithTag(sessionBarItemTestTag(secondId)).performTouchInput { longClick() }
        compose.onNodeWithText(application.getString(R.string.content_desc_close_session)).performClick()

        compose.waitUntil(10_000) { viewModel.sessions.value.none { it.id == secondId } }
        compose.onAllNodesWithTag(sessionBarItemTestTag(secondId)).assertCountEquals(0)
        compose.waitUntil(10_000) { viewModel.browseState.value.currentPath == first.path }
    }

    @Test fun itemLabelFollowsTheCurrentFolder() {
        showBrowser()
        val id = sessionId(first)
        compose.onNodeWithTag(sessionBarItemTestTag(id)).assertTextContains(first.name)

        compose.runOnIdle { viewModel.navigateTo(File(first, "inner").path) }

        compose.waitUntil(10_000) { viewModel.activeSession.value?.label == "inner" }
        compose.onNodeWithTag(sessionBarItemTestTag(id)).assertTextContains("inner")
    }

    @Test fun aPinnedTabStaysInsideItsFolderAndAboveOpensElsewhere() {
        showBrowser()
        val inner = File(first, "inner").path
        val id = sessionId(first)
        compose.runOnIdle { viewModel.navigateTo(inner) }
        compose.waitUntil(10_000) { viewModel.browseState.value.currentPath == inner }

        // Pinning pins the folder on screen.
        compose.onNodeWithTag(sessionBarItemTestTag(id)).performTouchInput { longClick() }
        compose.onNodeWithText(application.getString(R.string.action_pin_folder, "inner")).performClick()
        compose.waitUntil(10_000) { viewModel.sessions.value.single { it.id == id }.pinned }
        val pinned = viewModel.sessions.value.single { it.id == id }
        assertEquals(inner, pinned.startPath)
        assertEquals("inner", pinned.barLabel)

        // Back at the pinned folder leaves instead of going up.
        compose.runOnIdle { assertFalse(viewModel.navigateUp()) }

        // A folder above the pin opens in another tab; the pin stays where it is.
        compose.runOnIdle { viewModel.navigateTo(first.path) }
        compose.waitUntil(10_000) { viewModel.activeSession.value?.id != id && viewModel.browseState.value.currentPath == first.path }
        assertEquals(inner, viewModel.sessions.value.single { it.id == id }.currentPath)
        assertEquals(false, viewModel.activeSession.value!!.pinned)

        compose.runOnIdle { viewModel.setSessionPinned(id, false) }
        compose.waitUntil(10_000) { runBlocking { prefs.pinnedSessions.first() }.isNullOrEmpty() }
    }

    @Test fun aTallRailFitsAndPinsMoreThanFourTabs() {
        assumeTrue("Needs the rail and room for more than four cells", application.resources.configuration.let {
            it.screenWidthDp >= 600 && it.screenHeightDp >= 700
        })
        val extra = (1..5).map { File(application.cacheDir, "session-bar-extra-$it").apply { mkdirs() } }
        try {
            showBrowser()
            extra.forEach { folder ->
                compose.runOnIdle { viewModel.openLocalFolder(folder.path) }
                compose.waitUntil(10_000) { viewModel.browseState.value.currentPath == folder.path }
            }
            val ids = extra.map { folder -> viewModel.sessions.value.single { it.startPath == folder.path }.id }
            ids.forEach { id -> compose.runOnIdle { viewModel.setSessionPinned(id, true) } }

            compose.waitUntil(10_000) { viewModel.sessions.value.count { it.pinned } == 5 }
            ids.forEach { id -> compose.onNodeWithTag(sessionBarItemTestTag(id)).assertExists() }
            ids.forEach { id -> compose.runOnIdle { viewModel.setSessionPinned(id, false) } }
        } finally {
            extra.forEach { it.deleteRecursively() }
        }
    }

    @Test fun aFolderOpensInANewSessionAndTheTabStays() {
        showBrowser()
        val id = sessionId(first)
        val inner = File(first, "inner").path

        compose.runOnIdle { viewModel.openFolderElsewhere(inner) }

        compose.waitUntil(10_000) { viewModel.activeSession.value?.id != id && viewModel.browseState.value.currentPath == inner }
        assertEquals(first.path, viewModel.sessions.value.single { it.id == id }.currentPath)
        assertEquals(2, viewModel.sessions.value.size)
    }

    @Test fun aTabOnRemovableStorageIsMarkedForTheSdCardIcon() {
        val sdCard = com.voyagerfiles.util.FileUtils.getStorageVolumes(application)
            .firstOrNull { it.isRemovable && it.isAvailable }?.path
        assumeTrue("Needs a removable volume", sdCard != null)
        compose.runOnIdle { viewModel.openLocalFolder(sdCard!!) }
        compose.waitUntil(10_000) { viewModel.activeSession.value?.startPath == sdCard }
        assertTrue(viewModel.activeSession.value!!.removable)
        assertEquals(false, viewModel.sessions.value.single { it.startPath == first.path }.removable)
    }

    @Test fun theBackChipNamesWhereAHeldGestureLeads() {
        var progress by mutableFloatStateOf(0.5f)
        compose.setContent {
            MaterialTheme {
                BackDestinationChip(BackTarget.Folder("Download", "/storage/emulated/0/Download"), progress, fromLeft = true)
            }
        }
        compose.onNode(hasText("Download") and hasAnyAncestor(hasTestTag(BACK_CHIP_TEST_TAG))).assertExists()
        // Released or cancelled: the chip goes.
        compose.runOnIdle { progress = 0f }
        compose.onAllNodesWithTag(BACK_CHIP_TEST_TAG).assertCountEquals(0)
    }

    @Test fun backToStartReturnsATabToWhereItWasOpened() {
        showBrowser()
        val id = sessionId(first)
        compose.runOnIdle { viewModel.navigateTo(File(first, "inner").path) }
        compose.waitUntil(10_000) { viewModel.browseState.value.currentPath == File(first, "inner").path }
        val backToStart = application.getString(R.string.action_reset_session, first.name)

        compose.onNodeWithTag(sessionBarItemTestTag(id)).performTouchInput { longClick() }
        compose.onNodeWithText(backToStart).performClick()

        compose.waitUntil(10_000) { viewModel.browseState.value.currentPath == first.path }
        // Back at the start, the menu no longer offers it.
        compose.onNodeWithTag(sessionBarItemTestTag(id)).performTouchInput { longClick() }
        compose.onAllNodesWithText(backToStart).assertCountEquals(0)
    }

    @Test fun openingAFolderASessionShowsSwitchesInsteadOfDuplicating() {
        showBrowser()
        openSecond()

        compose.runOnIdle { viewModel.openLocalFolder(first.path + "/") }

        compose.waitUntil(10_000) { viewModel.browseState.value.currentPath == first.path }
        assertEquals(2, viewModel.sessions.value.size)
    }

    @Test fun bottomSearchIsAPermanentFieldNotABarButton() {
        runBlocking { prefs.setSearchBarMode(SearchBarMode.BOTTOM) }
        showBrowser()
        compose.waitUntil(10_000) { viewModel.searchBarMode.value == SearchBarMode.BOTTOM }

        compose.onNodeWithTag(BROWSER_SEARCH_TEST_TAG).assertExists()
        compose.onAllNodes(
            hasText(application.getString(R.string.action_search)) and hasAnyAncestor(hasTestTag(SESSION_BAR_TEST_TAG)),
        ).assertCountEquals(0)
    }

    @Test fun aPinnedTabComesBackAfterARestartAtItsLastFolder() {
        showBrowser()
        compose.waitUntil(10_000) { viewModel.pinsRestored.value }
        val id = sessionId(first)
        val item = compose.onNodeWithTag(sessionBarItemTestTag(id))

        item.performTouchInput { longClick() }
        compose.onNodeWithText(application.getString(R.string.action_pin_folder, first.name)).performClick()
        compose.waitUntil(10_000) { viewModel.sessions.value.single { it.id == id }.pinned }

        // Pinned tabs are locked: the menu offers Unpin and no Close.
        item.performTouchInput { longClick() }
        compose.onNodeWithText(application.getString(R.string.action_unpin_session)).assertExists()
        compose.onAllNodesWithText(application.getString(R.string.content_desc_close_session)).assertCountEquals(0)
        androidx.test.espresso.Espresso.pressBack()

        val inner = File(first, "inner").path
        compose.runOnIdle { viewModel.navigateTo(inner) }
        compose.waitUntil(10_000) {
            PinnedSessionCodec.decode(runBlocking { prefs.pinnedSessions.first() }) == listOf(PinnedSession.Local(first.path, inner))
        }
        // The pin keeps its name and shows where it is now as a second line.
        item.assertTextContains(first.name, substring = true)
        item.assertTextContains("› inner", substring = true)

        // A new view model stands in for the next start of the app.
        val restarted = compose.runOnIdle { FileBrowserViewModel(application).also { store.put("restarted", it) } }
        compose.waitUntil(10_000) { restarted.pinsRestored.value }
        val restored = restarted.sessions.value.single()
        assertTrue(restored.pinned)
        assertEquals(inner, restored.currentPath)
        assertEquals(first.path, restored.startPath)

        compose.runOnIdle { restarted.setSessionPinned(restored.id, false) }
        compose.waitUntil(10_000) { runBlocking { prefs.pinnedSessions.first() }.isNullOrEmpty() }
    }

    @Test fun snackbarShowButtonOpensTheFolder() {
        showBrowser()
        openSecond()
        // The download path needs a server; send the same message and action it sends.
        val showSnackbar = FileBrowserViewModel::class.java.declaredMethods
            .first { it.name == "showSnackbar" && it.parameterCount == 2 }
            .apply { isAccessible = true }
        compose.runOnIdle {
            showSnackbar.invoke(
                viewModel,
                UiText.Plural(R.plurals.items_downloaded, 1, listOf(1, UiText.Resource(R.string.downloads_folder))),
                SnackbarAction.OpenFolder(first.path),
            )
        }

        compose.onNodeWithText(application.getString(R.string.action_show_folder)).performClick()

        compose.waitUntil(10_000) { viewModel.browseState.value.currentPath == first.path }
        assertEquals(2, viewModel.sessions.value.size)
    }

    @Test fun aBookmarkOnSharedStorageCanGoUpToTheVolume() {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        val volume = Environment.getExternalStorageDirectory().absolutePath
        compose.runOnIdle { viewModel.openLocalFolder(downloads) }
        compose.waitUntil(10_000) { viewModel.activeSession.value?.startPath == downloads }
        assertEquals(volume, viewModel.activeSession.value!!.rootPath)

        // An unpinned tab is not limited to where it started: Back walks up to the volume, the way
        // the path bar reads.
        compose.runOnIdle { assertTrue(viewModel.navigateUp()) }
        compose.waitUntil(10_000) { viewModel.browseState.value.currentPath == volume }
        assertEquals(viewModel.activeSession.value!!.originLabel, viewModel.activeSession.value!!.label)
        compose.runOnIdle { assertFalse(viewModel.navigateUp()) }
    }
}
