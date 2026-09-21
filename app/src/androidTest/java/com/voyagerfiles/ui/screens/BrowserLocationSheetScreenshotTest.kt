package com.voyagerfiles.ui.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.voyagerfiles.R
import com.voyagerfiles.data.local.AppDatabase
import com.voyagerfiles.data.model.Bookmark
import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.repository.ConnectionRepository
import com.voyagerfiles.security.AndroidCredentialCipher
import com.voyagerfiles.ui.theme.VoyagerTheme
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Captures the Sessions sheet for documentation and review. Fixture connections and bookmarks are
 * stamped as just used so they fill the recent slots ahead of whatever the device already holds,
 * and they are removed afterwards. Screenshots go to Gradle's additional test output directory,
 * which the connected test task pulls into app/build/outputs, or to the app's external files
 * directory when that argument is absent.
 */
class BrowserLocationSheetScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val database = AppDatabase.getInstance(application)
    private val bookmarkDao = database.bookmarkDao()
    private val connectionRepository = ConnectionRepository(database.connectionDao(), AndroidCredentialCipher())
    private val store = ViewModelStore()
    private lateinit var root: File
    private lateinit var bookmarked: List<File>
    private val savedConnections = mutableListOf<RemoteConnection>()
    private lateinit var viewModel: FileBrowserViewModel
    private val sessionsLabel get() = application.getString(R.string.content_desc_sessions)

    @Before fun setUp() {
        root = File(application.getExternalFilesDir(null) ?: application.cacheDir, "Voyager").apply {
            deleteRecursively()
            mkdirs()
        }
        listOf("Notes.txt", "Holiday plan.md", "Packing list.txt").forEach { File(root, it).writeText(it) }
        bookmarked = listOf("Projects", "Documents", "Camera", "Downloads").map { File(root, it).apply { mkdirs() } }
        val now = System.currentTimeMillis()
        runBlocking {
            bookmarked.forEachIndexed { index, folder ->
                bookmarkDao.deleteByPath(folder.path, FileSource.LOCAL)
                bookmarkDao.insertIfAbsent(
                    Bookmark(
                        name = folder.name,
                        path = folder.path,
                        createdAt = now - 10_000L + index,
                        lastUsedAt = now - 1_000L + index,
                    ),
                )
            }
            listOf(
                RemoteConnection(name = "Home NAS", protocol = ConnectionProtocol.SFTP, host = "nas.lan", port = 22, lastConnected = now - 1_000L),
                RemoteConnection(name = "Router share", protocol = ConnectionProtocol.SMB, host = "router.lan", port = 445, shareName = "media", lastConnected = now - 2_000L),
                RemoteConnection(name = "Web server", protocol = ConnectionProtocol.FTP, host = "ftp.example.org", port = 21, lastConnected = now - 3_000L),
                RemoteConnection(name = "Backup box", protocol = ConnectionProtocol.WEBDAV, host = "backup.example.org", port = 443, lastConnected = now - 4_000L),
            ).forEach { connection ->
                val id = connectionRepository.save(connection)
                savedConnections += connection.copy(id = id)
            }
        }
        compose.runOnIdle {
            viewModel = FileBrowserViewModel(application)
            store.put("browser", viewModel)
            viewModel.openLocalRoot(root.path)
        }
    }

    @After fun tearDown() {
        compose.runOnIdle { store.clear() }
        runBlocking {
            bookmarked.forEach { bookmarkDao.deleteByPath(it.path, FileSource.LOCAL) }
            savedConnections.forEach { connectionRepository.delete(it) }
        }
        root.deleteRecursively()
    }

    @Test fun captureSessionsSheetStates() {
        compose.setContent {
            val theme by viewModel.theme.collectAsState()
            VoyagerTheme(appTheme = theme) {
                BrowserScreen(
                    viewModel = viewModel,
                    onNavigateBack = {},
                    isTelevision = false,
                    hasAllFilesAccess = true,
                )
            }
        }
        compose.waitUntil(10_000) {
            viewModel.browseState.value.currentPath == root.path && !viewModel.browseState.value.isLoading
        }
        compose.waitUntil(10_000) { savedConnections.all { saved -> viewModel.connections.value.any { it.id == saved.id } } }
        compose.waitUntil(10_000) { bookmarked.all { folder -> viewModel.bookmarks.value.any { it.path == folder.path } } }
        capture("1-browser")

        compose.onNodeWithContentDescription(sessionsLabel).performClick()
        compose.onNodeWithTag("location-home").assertExists()
        capture("2-sessions-sheet")

        compose.onNodeWithTag("location-show-all:${LocationGroup.BOOKMARKS.name}").performScrollTo().performClick()
        compose.onNodeWithTag("location-back").assertExists()
        capture("3-all-bookmarks")
        compose.onNodeWithTag("location-back").performClick()

        val camera = bookmarked.single { it.name == "Camera" }
        val cameraRow = "location-bookmark:" + viewModel.bookmarks.value.single { it.path == camera.path }.id
        compose.onNodeWithTag(cameraRow).performScrollTo().performClick()
        compose.waitUntil(10_000) {
            viewModel.activeSession.value?.rootPath == camera.path && !viewModel.browseState.value.isLoading
        }
        compose.onNodeWithContentDescription(sessionsLabel).performClick()
        compose.onNodeWithTag("location-home").assertExists()
        capture("4-two-sessions")
    }

    /** Captures the real screen after the sheet animation has settled, so the scrim and the browser behind it are included. */
    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(600)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull("screenshot $name", bitmap)
        File(outputDir(), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun outputDir(): File {
        val argument = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val dir = argument?.let(::File) ?: File(application.getExternalFilesDir(null), "screenshots")
        dir.mkdirs()
        return dir
    }
}
