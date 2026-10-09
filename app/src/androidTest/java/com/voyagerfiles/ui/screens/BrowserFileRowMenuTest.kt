package com.voyagerfiles.ui.screens

import android.app.Application
import android.view.KeyEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.data.model.ViewMode
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class BrowserFileRowMenuTest {

    @get:Rule
    val composeTestRule = createComposeRule(effectContext = StandardTestDispatcher())

    private lateinit var root: File
    private lateinit var preferences: PreferencesManager
    private lateinit var originalViewMode: ViewMode
    private var originalUseTrash = false

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        preferences = PreferencesManager(application)
        runBlocking {
            originalViewMode = preferences.viewMode.first()
            originalUseTrash = preferences.useTrash.first()
            preferences.setViewMode(ViewMode.LIST)
        }
        root = File(application.cacheDir, "browser-file-row-menu-test").apply {
            deleteRecursively()
            mkdirs()
            resolve("notes.txt").writeText("notes")
            ZipOutputStream(FileOutputStream(resolve("archive.zip"))).use { zip ->
                zip.putNextEntry(ZipEntry("inside.txt"))
                zip.write("inside".toByteArray())
                zip.closeEntry()
            }
            resolve("legacy.rar").writeBytes(byteArrayOf())
            resolve("Folder").mkdirs()
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        runBlocking {
            preferences.setViewMode(originalViewMode)
            preferences.setUseTrash(originalUseTrash)
        }
    }

    @Test
    fun menuOffersTheFileActionsWithoutSelectingTheFile() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        openMenuFor("notes.txt")

        listOf("Open with", "Share", "Copy", "Cut", "Rename", "Compress to ZIP", "Details", "Delete")
            .forEach { composeTestRule.onNodeWithText(it).assertIsDisplayed() }
        composeTestRule.onNodeWithText("Download").assertDoesNotExist()
        composeTestRule.runOnIdle { assertTrue(viewModel.browseState.value.selectedFiles.isEmpty()) }
    }

    @Test
    fun tapNearTheRowsTopRightCornerOpensTheMenu() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        // Outside the icon-sized circle, but inside the full-height strip at the row's end.
        composeTestRule.onNode(hasText("Folder") and hasClickAction())
            .performTouchInput { click(Offset(width - 2f, 2f)) }

        composeTestRule.onNodeWithText("Details").assertIsDisplayed()
        composeTestRule.runOnIdle {
            assertEquals(root.absolutePath, viewModel.browseState.value.currentPath)
        }
    }

    @Test
    fun renameFromTheMenuRenamesThatFile() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        openMenuFor("notes.txt")
        composeTestRule.onNodeWithText("Rename").performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("renamed.txt")
        composeTestRule.onNode(hasText("Rename") and hasClickAction()).performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000) { root.resolve("renamed.txt").exists() }
        assertTrue(!root.resolve("notes.txt").exists())
    }

    @Test
    fun deleteFromTheMenuNamesAndRemovesOnlyThatFile() {
        val viewModel = launchBrowser()
        composeTestRule.runOnIdle { viewModel.setUseTrash(true) }
        waitForRoot(viewModel)
        composeTestRule.waitUntil(timeoutMillis = 10_000) { viewModel.useTrash.value }

        openMenuFor("notes.txt")
        composeTestRule.onNodeWithText("Delete").performClick()
        composeTestRule.onNodeWithText("Delete \"notes.txt\"?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete permanently").performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000) { !root.resolve("notes.txt").exists() }
        assertTrue(root.resolve("archive.zip").exists())
        assertTrue(root.resolve("legacy.rar").exists())
        assertTrue(root.resolve("Folder").isDirectory)
    }

    @Test
    fun compressFromTheMenuZipsThatFile() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        openMenuFor("notes.txt")
        composeTestRule.onNodeWithText("Compress to ZIP").performClick()
        composeTestRule.onNodeWithText("Create").performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000) { root.resolve("notes.zip").exists() }
    }

    @Test
    fun extractFromTheMenuExtractsThatArchive() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        openMenuFor("archive.zip")
        composeTestRule.onNodeWithText("Compress to ZIP").assertDoesNotExist()
        composeTestRule.onNodeWithText("Extract here").performClick()

        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            root.walkTopDown().any { it.name == "inside.txt" }
        }
    }

    @Test
    fun rarMenuExplainsThatExtractionIsUnavailable() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        openMenuFor("legacy.rar")

        composeTestRule.onNodeWithText("Extract here").assertIsDisplayed()
        composeTestRule.onNodeWithText("RAR extraction is not available in this build").assertIsDisplayed()
    }

    @Test
    fun folderMenuOffersFolderShortcuts() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        openMenuFor("Folder")

        composeTestRule.onNodeWithText("Add folder to Home screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Share").assertDoesNotExist()
    }

    @Test
    fun detailsFromTheMenuShowTheFilePath() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        openMenuFor("notes.txt")
        composeTestRule.onNodeWithText("Details").performClick()

        composeTestRule.onNodeWithText(root.resolve("notes.txt").absolutePath)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun rowMenusHideWhileSelecting() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)
        assertEquals(4, rowMenuButtonCount())

        composeTestRule.onNode(hasText("notes.txt") and hasClickAction())
            .performTouchInput { longClick() }

        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()
        assertEquals(0, rowMenuButtonCount())
    }

    @Test
    fun compactListHasRowMenusAndGridHasNone() {
        val viewModel = launchBrowser()
        waitForRoot(viewModel)

        composeTestRule.runOnIdle { viewModel.setViewMode(ViewMode.COMPACT) }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.browseState.value.viewMode == ViewMode.COMPACT
        }
        assertEquals(4, rowMenuButtonCount())

        composeTestRule.runOnIdle { viewModel.setViewMode(ViewMode.GRID) }
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.browseState.value.viewMode == ViewMode.GRID
        }
        composeTestRule.waitForIdle()
        assertEquals(0, rowMenuButtonCount())
    }

    @Test
    fun remoteRightThenCenterOpensTheMenuOnTv() {
        val viewModel = launchBrowser(isTelevision = true)
        waitForRoot(viewModel)
        val firstRow = viewModel.browseState.value.visibleFiles.first().name
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeTestRule.onNode(hasText(firstRow) and hasClickAction()).assertIsFocused()
            }.isSuccess
        }

        sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        composeTestRule.onNodeWithContentDescription("More options for $firstRow").assertIsFocused()
        sendKey(KeyEvent.KEYCODE_DPAD_CENTER)

        composeTestRule.onNodeWithText("Details").assertIsDisplayed()
        composeTestRule.runOnIdle { assertTrue(viewModel.browseState.value.selectedFiles.isEmpty()) }
    }

    private fun openMenuFor(name: String) {
        composeTestRule.onNodeWithContentDescription("More options for $name").performClick()
    }

    private fun rowMenuButtonCount(): Int =
        composeTestRule.onAllNodesWithTag(FILE_ROW_MENU_BUTTON_TEST_TAG).fetchSemanticsNodes().size

    private fun sendKey(keyCode: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = android.os.SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0))
        instrumentation.sendKeySync(KeyEvent(downTime, downTime + 50, KeyEvent.ACTION_UP, keyCode, 0))
        composeTestRule.waitForIdle()
    }

    private fun launchBrowser(isTelevision: Boolean = false): FileBrowserViewModel {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = FileBrowserViewModel(application)
        composeTestRule.setContent {
            MaterialTheme {
                BrowserScreen(
                    viewModel = viewModel,
                    onNavigateBack = {},
                    isTelevision = isTelevision,
                )
            }
        }
        composeTestRule.runOnIdle {
            viewModel.openLocalRoot(root.absolutePath)
        }
        return viewModel
    }

    private fun waitForRoot(viewModel: FileBrowserViewModel) {
        composeTestRule.waitUntil(timeoutMillis = 10_000) {
            viewModel.browseState.value.currentPath == root.absolutePath &&
                !viewModel.browseState.value.isLoading &&
                composeTestRule.onAllNodesWithText("notes.txt").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
