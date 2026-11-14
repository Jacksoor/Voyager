package com.voyagerfiles.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.R
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.repository.FileProvider
import com.voyagerfiles.data.repository.LocalFileProvider
import com.voyagerfiles.data.local.AppDatabase
import com.voyagerfiles.data.repository.ConnectionRepository
import com.voyagerfiles.security.AndroidCredentialCipher
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import com.voyagerfiles.viewmodel.PinnedSession
import com.voyagerfiles.viewmodel.PinnedSessionCodec
import com.voyagerfiles.viewmodel.RemoteFileProviderFactory
import com.voyagerfiles.viewmodel.StartTabPlan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Date

class BrowserSessionConnectionTest {
    @get:Rule val compose = createComposeRule()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = PreferencesManager(application)
    private val store = ViewModelStore()
    private val server = FakeServer()
    private lateinit var viewModel: FileBrowserViewModel
    private var savedPins: String? = null

    @Before fun setUp() {
        savedPins = runBlocking { prefs.pinnedSessions.first() }
        runBlocking { prefs.setPinnedSessions("") }
        compose.runOnIdle {
            viewModel = FileBrowserViewModel(application, remoteProviderFactory = RemoteFileProviderFactory { _, _ -> server })
            store.put("browser", viewModel)
        }
    }

    @After fun tearDown() {
        compose.runOnIdle { store.clear() }
        runBlocking { prefs.setPinnedSessions(savedPins.orEmpty()) }
    }

    @Test fun disconnectKeepsTheTabAndBrowsingReconnects() {
        compose.setContent {
            MaterialTheme { BrowserScreen(viewModel = viewModel, onNavigateBack = {}, isTelevision = false, hasAllFilesAccess = true) }
        }
        compose.runOnIdle { viewModel.connectToRemote(RemoteConnection(id = 41, name = "NAS", protocol = ConnectionProtocol.FTP, host = "nas.test", port = 21)) }
        compose.waitUntil(10_000) { viewModel.activeSession.value?.connected == true }
        val id = viewModel.activeSession.value!!.id
        val disconnect = application.getString(R.string.action_disconnect)

        compose.onNodeWithTag(sessionBarItemTestTag(id)).performTouchInput { longClick() }
        compose.onNodeWithText(disconnect).performClick()

        compose.waitUntil(10_000) { viewModel.sessions.value.single().connected == false }
        assertEquals(1, server.disconnects)
        assertEquals(id, viewModel.activeSession.value?.id)

        // Not connected: the menu has no Disconnect.
        compose.onNodeWithTag(sessionBarItemTestTag(id)).performTouchInput { longClick() }
        compose.onAllNodesWithText(disconnect).assertCountEquals(0)
        androidx.test.espresso.Espresso.pressBack()

        compose.runOnIdle { viewModel.refresh() }
        compose.waitUntil(10_000) { viewModel.activeSession.value?.connected == true }
    }

    @Test fun aServerHasOneTabWhichPinsAtItsRoot() {
        compose.setContent {
            MaterialTheme { BrowserScreen(viewModel = viewModel, onNavigateBack = {}, isTelevision = false, hasAllFilesAccess = true) }
        }
        val nas = RemoteConnection(id = 42, name = "NAS", protocol = ConnectionProtocol.FTP, host = "nas.test", port = 21)
        compose.runOnIdle { viewModel.connectToRemote(nas) }
        compose.waitUntil(10_000) { viewModel.activeSession.value?.connected == true }
        val tabId = viewModel.activeSession.value!!.id
        compose.runOnIdle { viewModel.navigateTo("/music") }
        compose.waitUntil(10_000) { viewModel.browseState.value.currentPath == "/music" }
        compose.runOnIdle { viewModel.setSessionPinned(tabId, true) }
        compose.waitUntil(10_000) { viewModel.sessions.value.single().pinned }

        // Pinned at the server's root, the tab still reaches every folder, and stays where it was.
        val pin = viewModel.sessions.value.single()
        assertEquals("/", pin.startPath)
        assertEquals("/music", pin.currentPath)
        compose.runOnIdle { viewModel.navigateTo("/") }
        compose.waitUntil(10_000) { viewModel.browseState.value.currentPath == "/" }
        assertEquals(tabId, viewModel.activeSession.value?.id)

        // Connecting again or showing another folder of the server uses the same tab.
        compose.runOnIdle { viewModel.connectToRemote(nas) }
        compose.runOnIdle { viewModel.openRemoteFolder(42, "/music") }
        compose.waitUntil(10_000) { viewModel.browseState.value.currentPath == "/music" }
        assertEquals(listOf(tabId), viewModel.sessions.value.map { it.id })
        compose.runOnIdle { viewModel.setSessionPinned(tabId, false) }
    }

    /** Saves pins as if left by the last run, starts a new view model and runs [check] with it. */
    private fun withLaunch(
        pins: (connectionId: Long, folder: java.io.File) -> List<PinnedSession>,
        provider: FileProvider = server,
        check: (FileBrowserViewModel, java.io.File) -> Unit,
    ) {
        val repository = ConnectionRepository(AppDatabase.getInstance(application).connectionDao(), AndroidCredentialCipher())
        val folder = java.io.File(application.cacheDir, "launch-pin").apply { mkdirs() }
        val connection = RemoteConnection(name = "Launch NAS", protocol = ConnectionProtocol.FTP, host = "nas.test", port = 21)
        val connectionId = runBlocking { repository.save(connection) }
        try {
            runBlocking { prefs.setPinnedSessions(PinnedSessionCodec.encode(pins(connectionId, folder))) }
            // A new view model stands in for the next start of the app.
            val started = compose.runOnIdle {
                FileBrowserViewModel(application, remoteProviderFactory = RemoteFileProviderFactory { _, _ -> provider })
                    .also { store.put("started", it) }
            }
            check(started, folder)
        } finally {
            runBlocking { repository.delete(connection.copy(id = connectionId)) }
            folder.deleteRecursively()
        }
    }

    @Test fun noStartTabMeansHome() = withLaunch({ id, folder ->
        listOf(PinnedSession.Remote(id, "/", "/"), PinnedSession.Local(folder.path, folder.path))
    }) { started, _ ->
        assertEquals(StartTabPlan.None, runBlocking { started.startTabPlan(hasAllFilesAccess = true) })
        assertEquals(0, server.listings)
    }

    @Test fun aDeviceStartTabOpensAtOnceWithoutContactingAServer() = withLaunch({ id, folder ->
        listOf(PinnedSession.Remote(id, "/", "/music"), PinnedSession.Local(folder.path, folder.path, isStart = true))
    }) { started, folder ->
        assertEquals(StartTabPlan.Opened, runBlocking { started.startTabPlan(hasAllFilesAccess = true) })
        compose.waitUntil(10_000) { started.browseState.value.currentPath == folder.path && !started.browseState.value.isLoading }
        assertEquals(0, server.listings)
    }

    @Test fun aDeviceStartTabWithoutFullAccessStaysOnHome() = withLaunch({ _, folder ->
        listOf(PinnedSession.Local(folder.path, folder.path, isStart = true))
    }) { started, folder ->
        assertEquals(StartTabPlan.NeedsAccess(folder.name), runBlocking { started.startTabPlan(hasAllFilesAccess = false) })
    }

    @Test fun aServerStartTabConnectsAndOpens() = withLaunch({ id, _ ->
        listOf(PinnedSession.Remote(id, "/", "/music", isStart = true))
    }) { started, _ ->
        val plan = runBlocking { started.startTabPlan(hasAllFilesAccess = true) }
        assertTrue(plan is StartTabPlan.Server)
        assertEquals(null, runBlocking { started.openServerStartTab((plan as StartTabPlan.Server).sessionId) })
        assertEquals("/music", started.browseState.value.currentPath)
        assertEquals(1, server.listings)
    }

    @Test fun anUnreachableServerStartTabReportsWhy() = withLaunch(
        { id, _ -> listOf(PinnedSession.Remote(id, "/", "/music", isStart = true)) },
        provider = UnreachableServer(),
    ) { started, _ ->
        val plan = runBlocking { started.startTabPlan(hasAllFilesAccess = true) } as StartTabPlan.Server
        val failure = runBlocking { started.openServerStartTab(plan.sessionId) }
        assertTrue(failure != null)
    }

    @Test fun aStartTabWhoseConnectionIsGoneIsReportedUnavailable() = withLaunch({ id, _ ->
        listOf(PinnedSession.Remote(id + 1000, "/music", "/music", isStart = true))
    }) { started, _ ->
        assertEquals(StartTabPlan.Unavailable("music"), runBlocking { started.startTabPlan(hasAllFilesAccess = true) })
    }

    @Test fun thereIsOneStartTabAndUnpinningClearsIt() = withLaunch({ _, folder ->
        val second = java.io.File(folder, "second").apply { mkdirs() }
        listOf(PinnedSession.Local(folder.path, folder.path, isStart = true), PinnedSession.Local(second.path, second.path))
    }) { started, _ ->
        compose.waitUntil(10_000) { started.pinsRestored.value }
        val (first, second) = started.sessions.value
        compose.runOnIdle { started.setStartTab(second.id, true) }
        assertEquals(listOf(false, true), started.sessions.value.map { it.isStartTab })
        compose.runOnIdle { started.setSessionPinned(second.id, false) }
        assertEquals(listOf(false, false), started.sessions.value.map { it.isStartTab })
        assertEquals(first.id, started.sessions.value.first().id)
    }

    /** A server that cannot be reached. */
    private class UnreachableServer : FileProvider by LocalFileProvider() {
        override suspend fun listFiles(path: String): Result<List<FileItem>> =
            Result.failure(java.net.UnknownHostException("nas.test"))
        override fun getParentPath(path: String): String? = null
    }

    /** Opens its "connection" on the first listing and drops it on disconnect. */
    private class FakeServer : FileProvider by LocalFileProvider() {
        @Volatile private var open = false
        var disconnects = 0
        @Volatile var listings = 0
        override val hasOpenConnection: Boolean get() = open
        override suspend fun listFiles(path: String): Result<List<FileItem>> {
            listings++
            open = true
            return Result.success(listOf(FileItem("song.mp3", "/song.mp3", false, 1, Date(0), source = FileSource.FTP)))
        }
        override suspend fun disconnect() {
            open = false
            disconnects++
        }
        override fun getParentPath(path: String): String? = null
    }
}
