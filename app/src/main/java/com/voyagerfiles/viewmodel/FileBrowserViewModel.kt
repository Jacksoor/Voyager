package com.voyagerfiles.viewmodel

import com.voyagerfiles.app.VoyagerApp
import kotlinx.coroutines.CancellationException

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voyagerfiles.audio.AudioTone
import com.voyagerfiles.audio.AudioToneInstaller
import com.voyagerfiles.R
import com.voyagerfiles.data.archive.ArchiveFormat
import com.voyagerfiles.data.archive.ArchivePhase
import com.voyagerfiles.data.archive.PartialExtractionException
import com.voyagerfiles.data.archive.ArchiveExtractionReport
import com.voyagerfiles.data.archive.ArchiveProgress
import com.voyagerfiles.data.archive.ArchiveService
import com.voyagerfiles.data.local.AppDatabase
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.data.model.Bookmark
import com.voyagerfiles.data.model.BrowseState
import com.voyagerfiles.data.model.ConnectionProtocol
import com.voyagerfiles.data.model.LocalFileVisibility
import com.voyagerfiles.data.repository.LocalFileProvider
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.FileTypeFilter
import com.voyagerfiles.data.model.HomeLayout
import com.voyagerfiles.data.model.HomeSection
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.data.model.SessionAutoCloseTimeout
import com.voyagerfiles.data.model.SortBy
import com.voyagerfiles.data.model.SortOrder
import com.voyagerfiles.data.model.TrashEntry
import com.voyagerfiles.data.model.ViewMode
import com.voyagerfiles.data.model.isNetwork
import com.voyagerfiles.data.remote.saf.SafFileProvider
import com.voyagerfiles.data.remote.webdav.WebDavFileProvider
import com.voyagerfiles.data.repository.ConnectionRepository
import com.voyagerfiles.data.repository.DownloadProgress
import com.voyagerfiles.data.repository.FileDownloader
import com.voyagerfiles.data.repository.FileProvider
import com.voyagerfiles.data.repository.FileProviderFactory
import com.voyagerfiles.data.repository.LocalTrashManager
import com.voyagerfiles.data.repository.StreamTransferProgress
import com.voyagerfiles.security.AndroidCredentialCipher
import com.voyagerfiles.playback.PlaybackEntry
import com.voyagerfiles.playback.PreparedWebDavPlayback
import com.voyagerfiles.playback.WebDavPlaybackProvider
import com.voyagerfiles.ui.theme.AppTheme
import com.voyagerfiles.ui.screens.SessionBarModel
import com.voyagerfiles.ui.text.UiText
import com.voyagerfiles.ui.text.resolve
import com.voyagerfiles.util.FileNameValidationResult
import com.voyagerfiles.util.FileNameValidator
import com.voyagerfiles.util.FileUtils
import com.voyagerfiles.util.UploadSourceFactory
import kotlinx.coroutines.Dispatchers
import com.voyagerfiles.ui.components.RemoteImageThumbnailLoader
import com.voyagerfiles.ui.components.RemoteImageThumbnailSession
import com.voyagerfiles.data.model.SearchBarMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

fun interface RemoteFileProviderFactory {
    fun create(context: Application, connection: RemoteConnection): FileProvider
}

fun interface WebDavPlaybackPreparer {
    suspend fun prepare(context: Application, provider: FileProvider, file: FileItem): Result<PreparedWebDavPlayback>
}

private val defaultRemoteFileProviderFactory = RemoteFileProviderFactory { context, connection ->
    FileProviderFactory.createRemote(context, connection)
}

private val defaultWebDavPlaybackPreparer = WebDavPlaybackPreparer { context, provider, file ->
    withContext(Dispatchers.IO) {
        runCatching {
            val webDavProvider = provider as? WebDavFileProvider
                ?: error("The active provider is not WebDAV")
            val prepared = webDavProvider.createPlaybackSource(file.path).getOrThrow()
            WebDavPlaybackProvider.prepare(
                context,
                PlaybackEntry(file.name, file.mimeType, prepared.metadata.size, prepared.source),
                prepared.identity,
            )
        }
    }
}

class FileBrowserViewModel @JvmOverloads constructor(
    application: Application,
    private val remoteProviderFactory: RemoteFileProviderFactory = defaultRemoteFileProviderFactory,
    private val playbackPreparer: WebDavPlaybackPreparer = defaultWebDavPlaybackPreparer,
    private val rootProviderFactory: () -> com.voyagerfiles.data.repository.RootFileProvider = { com.voyagerfiles.data.repository.RootFileProvider() },
    private val operationController: TransferOperationController = (application as VoyagerApp).transfers,
) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    private val db = AppDatabase.getInstance(application)
    private val connectionDao = db.connectionDao()
    private val connectionRepository = ConnectionRepository(connectionDao, AndroidCredentialCipher())
    private val bookmarkDao = db.bookmarkDao()
    private val trashManager = LocalTrashManager(
        FileUtils.getStorageVolumes(application).mapNotNull { it.path?.let(::File) },
    )

    private var fileProvider: FileProvider = FileProviderFactory.createLocal()
    private var browserSessionRootPath: String? = null
    private var initialNavigationJob: Job? = null
    private val sessionProviders = mutableMapOf<String, FileProvider>()
    private val uploadingProviders = mutableSetOf<FileProvider>()
    private val retiredUploadProviders = mutableSetOf<FileProvider>()
    private val remoteImageThumbnailSessions = mutableMapOf<String, RemoteImageThumbnailSession>()
    private val loadGuard = DirectoryLoadGuard()
    private val scrollMemory = DirectoryScrollMemory()
    private val sessionAutoCloseTracker = SessionAutoCloseTracker()
    private var backgroundedSessionIds = emptySet<String>()
    private var deferredAutoCloseSessionIds = emptySet<String>()

    private val _browseState = MutableStateFlow(BrowseState())
    val browseState: StateFlow<BrowseState> = _browseState.asStateFlow()

    /** Start position for the directory on screen; the UI keeps it current through [onDirectoryScrolled]. */
    val directoryScrollPosition: ScrollPosition
        get() = scrollMemory.current

    private val _rootEditor = MutableStateFlow<RootTextEditorState?>(null)
    val rootEditor: StateFlow<RootTextEditorState?> = _rootEditor.asStateFlow()

    private val _sessions = MutableStateFlow<List<BrowserSession>>(emptyList())
    val sessions: StateFlow<List<BrowserSession>> = _sessions.asStateFlow()

    private val _activeSession = MutableStateFlow<BrowserSession?>(null)
    val activeSession: StateFlow<BrowserSession?> = _activeSession.asStateFlow()

    private val _clipboardPaths = MutableStateFlow<List<String>>(emptyList())
    val clipboardPaths: StateFlow<List<String>> = _clipboardPaths.asStateFlow()

    private val _clipboardOperation = MutableStateFlow(ClipboardOperation.NONE)
    val clipboardOperation: StateFlow<ClipboardOperation> = _clipboardOperation.asStateFlow()
    private var clipboardProvider: FileProvider? = null

    private val _snackbarMessage = MutableStateFlow<UiText?>(null)
    val snackbarMessage: StateFlow<UiText?> = _snackbarMessage.asStateFlow()

    /** The button offered with [snackbarMessage], if any. Read it when the message is shown. */
    private val _snackbarAction = MutableStateFlow<SnackbarAction?>(null)
    val snackbarAction: StateFlow<SnackbarAction?> = _snackbarAction.asStateFlow()

    val operationState: StateFlow<OperationState> = operationController.state
    val lastOperationResult = operationController.lastResult
    val transferConflict = operationController.conflicts.pending
    fun resolveTransferConflict(request: TransferConflictDecisions.Request, response: ConflictResponse) =
        operationController.conflicts.respond(request, response)
    /** A kept extraction the user asked to remove, waiting until the listing shows its folder. */
    private val _pendingExtractionRemoval = MutableStateFlow<String?>(null)
    val pendingExtractionRemoval: StateFlow<String?> = _pendingExtractionRemoval.asStateFlow()

    private val _sessionClosureGeneration = MutableStateFlow(0L)
    val sessionClosureGeneration: StateFlow<Long> = _sessionClosureGeneration.asStateFlow()

    private val _trashState = MutableStateFlow(TrashState())
    val trashState: StateFlow<TrashState> = _trashState.asStateFlow()

    val theme = prefs.theme.stateIn(viewModelScope, SharingStarted.Eagerly, AppTheme.SYSTEM)
    val searchBarMode = prefs.searchBarMode.stateIn(viewModelScope, SharingStarted.Eagerly, SearchBarMode.TOP)
    val confirmRemoteDownloads = prefs.confirmRemoteDownloads.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setConfirmRemoteDownloads(confirm: Boolean) {
        viewModelScope.launch { prefs.setConfirmRemoteDownloads(confirm) }
    }

    fun setSearchBarMode(mode: SearchBarMode) {
        viewModelScope.launch { prefs.setSearchBarMode(mode) }
    }
    val useTrash = prefs.useTrash.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val autoCloseSessions = prefs.autoCloseSessions.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val sessionAutoCloseTimeout = prefs.sessionAutoCloseTimeout.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SessionAutoCloseTimeout.FIFTEEN_MINUTES,
    )
    val homeLayout = prefs.homeLayout.stateInWithLoading(viewModelScope)
    val dismissedTranslationNotices: StateFlow<Set<String>?> = prefs.dismissedTranslationNotices.stateIn(
        viewModelScope, SharingStarted.Eagerly, null,
    )

    fun dismissTranslationNotice(languageUrl: String) {
        viewModelScope.launch { prefs.dismissTranslationNotice(languageUrl) }
    }
    val limitedAccessAccepted = prefs.limitedAccessAccepted.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val connections = connectionRepository.connections.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val bookmarks = bookmarkDao.getAllBookmarks().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            operationState.collect { state ->
                if (state == OperationState.Idle && autoCloseSessions.value && deferredAutoCloseSessionIds.isNotEmpty()) {
                    val sessionIds = deferredAutoCloseSessionIds
                    deferredAutoCloseSessionIds = emptySet()
                    closeSessionsAfterInactivity(sessionIds)
                }
            }
        }
        viewModelScope.launch {
            runCatching { connectionRepository.migratePlaintextCredentials() }
                .onFailure {
                    showSnackbar(UiText.Resource(R.string.credential_migration_failed))
                }
        }
        viewModelScope.launch {
            prefs.showHidden.distinctUntilChanged().collect { show ->
                val changed = _browseState.value.showHidden != show
                _browseState.update { it.copy(showHidden = show) }
                if (changed && _browseState.value.currentPath != "/") refreshFiles()
            }
        }
        viewModelScope.launch {
            prefs.sortBy.distinctUntilChanged().collect { sort ->
                _browseState.update { it.copy(sortBy = sort) }
                if (_browseState.value.files.isNotEmpty()) resortFiles()
            }
        }
        viewModelScope.launch {
            prefs.sortOrder.distinctUntilChanged().collect { order ->
                _browseState.update { it.copy(sortOrder = order) }
                if (_browseState.value.files.isNotEmpty()) resortFiles()
            }
        }
        viewModelScope.launch {
            prefs.viewMode.distinctUntilChanged().collect { mode ->
                _browseState.update { it.copy(viewMode = mode) }
            }
        }
        // Load initial path
        initialNavigationJob = viewModelScope.launch {
            val defaultPath = prefs.defaultPath.first()
            navigateToPath(defaultPath)
        }
    }

    fun navigateTo(path: String) {
        cancelInitialNavigation()
        viewModelScope.launch {
            val normalizedPath = BrowserNavigationBounds.normalizePath(path)
            val rootPath = browserSessionRootPath
            val active = _activeSession.value
            if (
                active != null && active.pinned && active.source != FileSource.SAF &&
                !BrowserNavigationBounds.isPathAtOrInsideRoot(normalizedPath, active.limitPath)
            ) {
                // A pin keeps meaning its folder: anything above it opens in another tab.
                openFolderElsewhere(normalizedPath)
                return@launch
            }
            if (
                rootPath != null &&
                _browseState.value.source != FileSource.SAF &&
                !BrowserNavigationBounds.isPathAtOrInsideRoot(normalizedPath, rootPath)
            ) {
                showSnackbar(UiText.Resource(R.string.location_outside_session))
                return@launch
            }
            navigateToPath(normalizedPath)
        }
    }

    /**
     * Shows [path] of the active session's place in another tab, leaving the active (pinned) tab
     * where it is: a tab already showing it, or a new unpinned one.
     */
    fun openFolderElsewhere(path: String) {
        val active = _activeSession.value ?: return
        val normalizedPath = BrowserNavigationBounds.normalizePath(path)
        when {
            active.source == FileSource.LOCAL -> openLocalFolder(normalizedPath)
            active.connectionId != null -> openRemoteFolder(active.connectionId, normalizedPath)
        }
    }

    /** Shows [path] on a server: a tab of that server already showing it, or a new unpinned one. */
    fun openRemoteFolder(connectionId: Long, path: String) {
        cancelInitialNavigation()
        viewModelScope.launch {
            val normalizedPath = BrowserNavigationBounds.normalizePath(path)
            val existing = _sessions.value.firstOrNull {
                it.connectionId == connectionId && BrowserNavigationBounds.normalizePath(it.currentPath) == normalizedPath
            }
            if (existing != null) {
                activateSessionInternal(existing.id)
                return@launch
            }
            val connection = sessionConnections.values.firstOrNull { it.id == connectionId }
                ?: connectionRepository.connections.first().firstOrNull { it.id == connectionId }
                ?: return@launch
            activateSessionInternal(addRemoteSession(connection, normalizedPath))
        }
    }

    /**
     * Shows a local folder: switches to a session already showing exactly [path], or opens a new
     * one there. The new session can go up to the storage volume holding [path]; a folder on no
     * known volume (an app's private folder) stays the session's limit.
     */
    fun openLocalFolder(path: String) {
        cancelInitialNavigation()
        viewModelScope.launch {
            val normalizedPath = BrowserNavigationBounds.normalizePath(path)
            bookmarkDao.markUsed(normalizedPath, FileSource.LOCAL, System.currentTimeMillis())
            val existing = _sessions.value.firstOrNull { session ->
                session.source == FileSource.LOCAL &&
                    BrowserNavigationBounds.normalizePath(session.currentPath) == normalizedPath
            }
            if (existing != null) {
                activateSessionInternal(existing.id)
                return@launch
            }
            activateSessionInternal(addLocalSession(normalizedPath, normalizedPath))
        }
    }

    /** Adds a local session without showing it. Its limit is the storage volume holding [startPath]. */
    private suspend fun addLocalSession(startPath: String, currentPath: String, pinned: Boolean = false): String {
        val volumes = withContext(Dispatchers.IO) {
            FileUtils.getStorageVolumes(getApplication()).filter { it.path != null }
        }
        val volumeRoot = BrowserNavigationBounds.containingRoot(startPath, volumes.mapNotNull { it.path })
        val volume = volumes.firstOrNull { volumeRoot != null && BrowserNavigationBounds.normalizePath(it.path!!) == volumeRoot }
        val rootPath = volumeRoot ?: startPath
        val sessionId = newSessionId("local")
        sessionProviders[sessionId] = FileProviderFactory.createLocal()
        _sessions.update { sessions ->
            sessions + BrowserSession(
                id = sessionId,
                originLabel = when {
                    volume == null -> titleForLocalPath(startPath)
                    // Shorter than the system's "Internal shared storage"; SD cards keep their own names.
                    volume.isPrimary -> getApplication<Application>().getString(R.string.session_primary_storage)
                    else -> volume.description
                },
                source = FileSource.LOCAL,
                rootPath = rootPath,
                startPath = startPath,
                currentPath = currentPath.takeIf { BrowserNavigationBounds.isPathAtOrInsideRoot(it, rootPath) } ?: startPath,
                removable = volume?.isRemovable == true,
                pinned = pinned,
                pinOrder = if (pinned) ++pinCounter else 0L,
            )
        }
        return sessionId
    }

    /** Adds a SAF session at the tree's root without showing it, unless one is already open. */
    private fun addSafSession(treeUri: Uri, pinned: Boolean = false): String {
        val rootPath = SafFileProvider.rootDocumentUri(treeUri).toString()
        val sessionId = safSessionId(rootPath)
        if (_sessions.value.none { it.id == sessionId }) {
            sessionProviders[sessionId] = FileProviderFactory.createSaf(getApplication(), treeUri)
            _sessions.update { sessions ->
                sessions + BrowserSession(
                    id = sessionId,
                    originLabel = SafFileProvider.titleForTreeUri(treeUri),
                    source = FileSource.SAF,
                    rootPath = rootPath,
                    currentPath = rootPath,
                    treeUri = treeUri.toString(),
                    pinned = pinned,
                    pinOrder = if (pinned) ++pinCounter else 0L,
                )
            }
        }
        return sessionId
    }

    fun openSafRoot(treeUri: Uri) {
        cancelInitialNavigation()
        viewModelScope.launch {
            val rootPath = SafFileProvider.rootDocumentUri(treeUri).toString()
            val sessionId = safSessionId(rootPath)
            if (_sessions.value.none { it.id == sessionId }) {
                addSafSession(treeUri)
            } else {
                _sessions.update { sessions ->
                    sessions.map { session ->
                        if (session.id == sessionId) session.copy(currentPath = rootPath) else session
                    }
                }
            }
            activateSessionInternal(sessionId)
        }
    }

    /** Called only after the user confirms the root-access dialog. */
    fun openRootSession() {
        cancelInitialNavigation()
        viewModelScope.launch {
            val sessionId = "root:/"
            if (_sessions.value.none { it.id == sessionId }) {
                sessionProviders[sessionId] = rootProviderFactory()
                _sessions.update { sessions ->
                    sessions + BrowserSession(
                        id = sessionId,
                        originLabel = getApplication<Application>().getString(R.string.root_title),
                        source = FileSource.ROOT,
                        rootPath = "/",
                        currentPath = "/",
                    )
                }
            }
            activateSessionInternal(sessionId)
        }
    }

    /** Connects the Shizuku tab, a pinned one at the folder it was left in, else a new one at Android. */
    fun openShizukuSession(provider: com.voyagerfiles.data.shizuku.ShizukuFileProvider) {
        cancelInitialNavigation()
        viewModelScope.launch {
            val sessionId = addShizukuSession(SHIZUKU_START_PATH, SHIZUKU_START_PATH)
            val previous = sessionProviders.put(sessionId, provider)
            if (previous !== provider) previous?.disconnect()
            refreshConnectionState(sessionId)
            activateSessionInternal(sessionId)
        }
    }

    /** There is one Shizuku tab; this adds it when missing, without a connection. */
    private fun addShizukuSession(startPath: String, currentPath: String, pinned: Boolean = false): String {
        if (_sessions.value.none { it.id == SHIZUKU_SESSION_ID }) {
            _sessions.update { sessions ->
                sessions + BrowserSession(
                    id = SHIZUKU_SESSION_ID,
                    originLabel = getApplication<Application>().getString(R.string.shizuku_title),
                    source = FileSource.SHIZUKU,
                    rootPath = "/",
                    startPath = startPath,
                    currentPath = currentPath,
                    pinned = pinned,
                    pinOrder = if (pinned) ++pinCounter else 0L,
                )
            }
        }
        return SHIZUKU_SESSION_ID
    }

    fun openRootTextEditor(file: FileItem) {
        val provider = fileProvider as? com.voyagerfiles.data.repository.RootFileProvider
            ?: return
        _rootEditor.value = RootTextEditorState(file.path)
        viewModelScope.launch {
            provider.readText(file.path).fold(
                onSuccess = { document ->
                    _rootEditor.value = RootTextEditorState(file.path, document, document.text, busy = false)
                },
                onFailure = { error ->
                    _rootEditor.value = RootTextEditorState(file.path, busy = false, error = error.message)
                },
            )
        }
    }

    fun updateRootText(text: String) {
        _rootEditor.update { state -> if (state?.busy == false) state.copy(text = text) else state }
    }

    fun closeRootTextEditor() { if (_rootEditor.value?.busy == false) _rootEditor.value = null }

    fun saveRootText() {
        val state = _rootEditor.value ?: return
        val document = state.document ?: return
        if (state.busy) return
        val provider = fileProvider as? com.voyagerfiles.data.repository.RootFileProvider
        if (provider == null) {
            _rootEditor.value = state.copy(error = getApplication<Application>().getString(R.string.root_session_closed))
            return
        }
        _rootEditor.value = state.copy(busy = true, error = null)
        val label = UiText.Resource(R.string.root_editor_save)
        val started = operationController.launch(
            label = label,
            cancellable = false,
            onFailure = { error ->
                _rootEditor.value = state.copy(busy = false,
                    error = OperationMessages.failure(R.string.root_editor_save, error).resolve(getApplication<Application>().resources))
            },
            onFinished = { _rootEditor.update { it?.copy(busy = false) } },
        ) {
            updateOperationProgress(TransferProgress(label, totalItems = 1, currentItemName = File(document.path).name))
            provider.saveText(document, state.text).getOrThrow()
            updateOperationProgress(TransferProgress(label, completedItems = 1, totalItems = 1))
            _rootEditor.value = null
            refreshFiles()
        }
        if (!started) {
            val active = operationState.value as OperationState.Running
            _rootEditor.value = state.copy(busy = false,
                error = UiText.Resource(R.string.operation_already_running, listOf(active.label)).resolve(getApplication<Application>().resources))
        }
    }

    /**
     * Goes up one folder, within the tab's limit (see [upTarget]). False at the limit, where Back
     * leaves the browser.
     */
    fun navigateUp(): Boolean {
        val parent = upTarget() ?: return false
        navigateTo(parent)
        return true
    }

    /**
     * Where [navigateUp] would go, or null at the tab's limit (Back then leaves the browser). A
     * pinned tab stops at its pinned folder; any other tab walks up to its volume or server root,
     * the way the path bar reads, even when it was opened deeper down.
     */
    fun upTarget(): String? {
        val currentPath = BrowserNavigationBounds.normalizePath(_browseState.value.currentPath)
        val parent = fileProvider.getParentPath(currentPath)
        val startPath = _activeSession.value?.takeIf { it.pinned }?.startPath
        val bound = if (startPath != null && BrowserNavigationBounds.isPathAtOrInsideRoot(currentPath, startPath)) {
            startPath
        } else {
            browserSessionRootPath
        }
        return parent?.takeIf { BrowserNavigationBounds.canNavigateToParent(currentPath, it, bound) }
    }

    fun onDirectoryScrolled(sessionId: String?, path: String, position: ScrollPosition) {
        if (sessionId == _activeSession.value?.id && path == _browseState.value.currentPath) {
            scrollMemory.update(position)
        }
    }

    private suspend fun navigateToPath(path: String) {
        val normalizedPath = BrowserNavigationBounds.normalizePath(path)
        val sessionId = _activeSession.value?.id
        if (sessionId != null) {
            updateSession(sessionId) { it.copy(currentPath = normalizedPath) }
            if (_activeSession.value?.pinned == true) savePins()
        }
        val previousPath = _browseState.value.currentPath
        scrollMemory.leave(sessionId, previousPath)
        scrollMemory.enter(
            sessionId,
            normalizedPath,
            returning = BrowserNavigationBounds.isSameOrAncestor(normalizedPath, previousPath, fileProvider::getParentPath),
        )
        _browseState.update {
            it.copy(
                currentPath = normalizedPath,
                isLoading = true,
                error = null,
                selectedFiles = emptySet(),
                searchQuery = "",
                fileTypeFilter = FileTypeFilter.ALL,
            )
        }
        loadFiles(normalizedPath, sessionId, fileProvider)
    }

    fun refresh() {
        refreshFiles()
    }

    private fun refreshFiles() {
        viewModelScope.launch {
            loadFiles(_browseState.value.currentPath, _activeSession.value?.id, fileProvider)
        }
    }

    private suspend fun loadFiles(
        path: String,
        sessionId: String? = _activeSession.value?.id,
        provider: FileProvider = fileProvider,
    ) {
        val requestId = loadGuard.nextRequest(sessionId)
        _browseState.update { it.copy(isLoading = true, error = null) }
        val listing = provider.listFiles(path)
        if (sessionId != null) refreshConnectionState(sessionId)
        listing.fold(
            onSuccess = { files ->
                if (!loadGuard.isCurrent(requestId, sessionId) || !isCurrentLoad(sessionId)) return@fold
                val filtered = if (_browseState.value.showHidden) files
                else files.filter { !it.isHidden }
                val sorted = sortFiles(filtered)
                _browseState.update { state ->
                    val next = state.copy(files = sorted, isLoading = false)
                    next.copy(selectedFiles = next.reconciledSelection)
                }
            },
            onFailure = { error ->
                if (!loadGuard.isCurrent(requestId, sessionId) || !isCurrentLoad(sessionId)) return@fold
                _browseState.update {
                    it.copy(
                        error = OperationMessages.reason(error),
                        isLoading = false,
                        files = emptyList(),
                    )
                }
            },
        )
    }

    private fun resortFiles() {
        _browseState.update { state ->
            state.copy(files = sortFiles(state.files))
        }
    }

    private fun sortFiles(files: List<FileItem>): List<FileItem> {
        val state = _browseState.value
        val comparator = when (state.sortBy) {
            SortBy.NAME -> compareBy<FileItem, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortBy.SIZE -> compareBy { it.size }
            SortBy.DATE -> compareBy { it.lastModified }
            SortBy.TYPE -> compareBy<FileItem, String>(String.CASE_INSENSITIVE_ORDER) { it.extension }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        val ordered = if (state.sortOrder == SortOrder.DESCENDING) comparator.reversed() else comparator
        return files.sortedWith(compareByDescending<FileItem> { it.isDirectory }.then(ordered))
    }

    fun toggleSelection(path: String) {
        _browseState.update { state ->
            val newSelection = state.selectedFiles.toMutableSet()
            if (path in newSelection) newSelection.remove(path) else newSelection.add(path)
            state.copy(selectedFiles = newSelection)
        }
    }

    fun setDragSelection(paths: Set<String>) {
        _browseState.update { state -> state.copy(selectedFiles = paths.intersect(state.visibleFiles.mapTo(mutableSetOf()) { it.path })) }
    }

    fun selectAll() {
        _browseState.update { state ->
            state.copy(selectedFiles = state.visibleFiles.map { it.path }.toSet())
        }
    }

    fun setSearchQuery(query: String) {
        _browseState.update { state ->
            val next = state.copy(searchQuery = query)
            next.copy(selectedFiles = next.reconciledSelection)
        }
    }

    fun setFileTypeFilter(filter: FileTypeFilter) {
        _browseState.update { state ->
            val next = state.copy(fileTypeFilter = filter)
            next.copy(selectedFiles = next.reconciledSelection)
        }
    }

    fun clearFilters() {
        _browseState.update { state ->
            state.copy(
                searchQuery = "",
                fileTypeFilter = FileTypeFilter.ALL,
                selectedFiles = emptySet(),
            )
        }
    }

    fun clearSelection() {
        _browseState.update { it.copy(selectedFiles = emptySet()) }
    }

    fun createDirectory(name: String) {
        val validatedName = validFileNameOrNotify(name) ?: return
        val provider = fileProvider
        val path = _browseState.value.currentPath
        launchOperation(R.string.progress_creating_folder, R.string.operation_create_folder) {
            provider.createDirectory(path, validatedName).fold(
                onSuccess = {
                    showSnackbar(UiText.Resource(R.string.folder_created))
                    refreshFiles()
                },
                onFailure = {
                    operationController.recordFailure(it)
                    showSnackbar(OperationMessages.failure(R.string.operation_create_folder, it))
                },
            )
        }
    }

    fun createFile(name: String) {
        val validatedName = validFileNameOrNotify(name) ?: return
        val provider = fileProvider
        val path = _browseState.value.currentPath
        launchOperation(R.string.progress_creating_file, R.string.operation_create_file) {
            provider.createFile(path, validatedName).fold(
                onSuccess = {
                    showSnackbar(UiText.Resource(R.string.file_created))
                    refreshFiles()
                },
                onFailure = {
                    operationController.recordFailure(it)
                    showSnackbar(OperationMessages.failure(R.string.operation_create_file, it))
                },
            )
        }
    }

    fun uploadDocuments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val destinationProvider = fileProvider
        val destinationPath = _browseState.value.currentPath
        val contentResolver = getApplication<Application>().contentResolver
        val progressLabel = UiText.Resource(R.string.progress_uploading)
        launchOperation(R.string.progress_uploading, R.string.operation_upload, readUris = uris.map(Uri::toString)) {
            uploadingProviders.add(destinationProvider)
            try {
                val sources = withContext(Dispatchers.IO) {
                    uris.map { uri -> UploadSourceFactory.fromUri(contentResolver, uri) }
                }
                val validatedSources = sources.map { source ->
                    when (val result = FileNameValidator.validate(source.name)) {
                        is FileNameValidationResult.Valid -> source.copy(name = result.name)
                        is FileNameValidationResult.Invalid -> {
                            throw UiTextException(UiText.Resource(result.messageRes))
                        }
                    }
                }
                var failed = 0
                var firstError: Throwable? = null
                var completed = 0
                var skipped = 0
                for (source in validatedSources) {
                    updateOperationProgress(
                        TransferProgress(
                            label = progressLabel,
                            completedItems = completed,
                            skippedItems = skipped,
                            totalItems = validatedSources.size,
                            currentItemName = source.name,
                            totalBytes = source.size,
                        ),
                    )
                    val progressThrottle = StreamProgressThrottle()
                    var latestStreamProgress: StreamTransferProgress? = null
                    val publishStreamProgress: (StreamTransferProgress) -> Unit = { streamProgress ->
                        latestStreamProgress = streamProgress
                        if (progressThrottle.shouldPublish(streamProgress)) {
                            updateOperationProgress(
                                TransferProgress(
                                    label = progressLabel,
                                    completedItems = completed,
                                    skippedItems = skipped,
                                    totalItems = validatedSources.size,
                                    currentItemName = source.name,
                                    copiedBytes = streamProgress.bytesTransferred,
                                    totalBytes = streamProgress.totalBytes,
                                    elapsedNanos = streamProgress.elapsedNanos,
                                ),
                            )
                        }
                    }
                    FileOperationCoordinator.uploadFile(
                        source = source,
                        destinationProvider = destinationProvider,
                        destinationDirectoryPath = destinationPath,
                        resolveConflict = operationController.conflicts::resolve,
                        onProgress = publishStreamProgress,
                    ).onSuccess { disposition ->
                        if (disposition == TransferDisposition.SKIPPED) skipped++ else completed++
                        val streamProgress = latestStreamProgress
                        updateOperationProgress(
                            TransferProgress(
                                label = progressLabel,
                                completedItems = completed,
                                skippedItems = skipped,
                                totalItems = validatedSources.size,
                                currentItemName = source.name,
                                copiedBytes = streamProgress?.bytesTransferred ?: 0,
                                totalBytes = streamProgress?.totalBytes ?: source.size,
                                elapsedNanos = streamProgress?.elapsedNanos ?: 0,
                            ),
                        )
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        failed++
                        operationController.recordFailure(error)
                        if (firstError == null) firstError = error
                    }
                }
                refreshFiles()
                if (failed > 0) {
                    showSnackbar(
                        OperationMessages.partial(
                            failed = failed,
                            total = validatedSources.size,
                            action = R.string.action_uploaded,
                            error = checkNotNull(firstError),
                        )
                    )
                } else {
                    showSnackbar(if (skipped > 0) UiText.Resource(R.string.upload_completed_skipped, listOf(completed, skipped))
                        else UiText.Plural(R.plurals.files_uploaded, completed, listOf(completed)))
                }
            } finally {
                uploadingProviders.remove(destinationProvider)
                if (retiredUploadProviders.remove(destinationProvider)) destinationProvider.disconnect()
            }
        }
    }

    fun deleteSelected(mode: DeleteMode? = null) {
        deletePaths(_browseState.value.selectedFiles.toList(), mode, clearSelectionAfter = true)
    }

    fun deletePaths(
        paths: List<String>,
        mode: DeleteMode? = null,
        clearSelectionAfter: Boolean = false,
    ) {
        val state = _browseState.value
        val selectedPaths = paths.toList()
        if (selectedPaths.isEmpty()) return
        val resolvedMode = mode ?: if (state.source == FileSource.LOCAL && useTrash.value) {
            DeleteMode.TRASH
        } else {
            DeleteMode.PERMANENT
        }
        if (resolvedMode == DeleteMode.TRASH && state.source != FileSource.LOCAL) {
            showSnackbar(UiText.Resource(R.string.trash_direct_local_only))
            return
        }
        val moveToTrash = resolvedMode == DeleteMode.TRASH
        val provider = fileProvider
        launchOperation(
            if (moveToTrash) R.string.progress_moving_to_trash else R.string.progress_deleting,
            if (moveToTrash) R.string.operation_trash else R.string.operation_delete,
        ) {
            val count = selectedPaths.size
            var failed = 0
            var firstError: Throwable? = null
            for (path in selectedPaths) {
                val result = if (moveToTrash) trashManager.moveToTrash(path).map { Unit } else provider.delete(path)
                result.onFailure { error ->
                    failed++
                    operationController.recordFailure(error)
                    if (firstError == null) firstError = error
                }
            }
            if (clearSelectionAfter) clearSelection()
            refreshFiles()
            if (failed > 0) {
                showSnackbar(
                    OperationMessages.partial(
                        failed = failed,
                        total = count,
                        action = if (moveToTrash) R.string.action_moved_to_trash else R.string.action_deleted,
                        error = checkNotNull(firstError),
                    )
                )
            } else {
                showSnackbar(
                    UiText.Plural(
                        if (moveToTrash) R.plurals.items_trashed else R.plurals.items_deleted,
                        count,
                        listOf(count),
                    ),
                )
            }
        }
    }

    fun rename(oldPath: String, newName: String) {
        val validatedName = validFileNameOrNotify(newName) ?: return
        renameFile(oldPath, validatedName)
    }

    fun toggleHidden(path: String) {
        if (fileProvider !is LocalFileProvider) return
        val file = _browseState.value.files.firstOrNull { it.path == path } ?: return
        val target = LocalFileVisibility.targetName(file) ?: return
        renameFile(path, target)
    }

    private fun renameFile(oldPath: String, validatedName: String) {
        val provider = fileProvider
        launchOperation(R.string.progress_renaming, R.string.operation_rename) {
            provider.rename(oldPath, validatedName).fold(
                onSuccess = {
                    showSnackbar(UiText.Resource(R.string.renamed_to, listOf(UiText.Dynamic(validatedName))))
                    refreshFiles()
                },
                onFailure = {
                    operationController.recordFailure(it)
                    showSnackbar(OperationMessages.failure(R.string.operation_rename, it))
                },
            )
        }
    }

    fun createZipFromSelection(archiveName: String) {
        createZip(_browseState.value.selectedFiles.toList(), archiveName, clearSelectionAfter = true)
    }

    fun createZip(
        paths: List<String>,
        archiveName: String,
        clearSelectionAfter: Boolean = false,
    ) {
        val validatedName = validFileNameOrNotify(archiveName) ?: return
        if ('\\' in validatedName) {
            showSnackbar(UiText.Resource(R.string.name_no_backslashes))
            return
        }
        if (ArchiveFormat.detect(validatedName) != ArchiveFormat.ZIP) {
            showSnackbar(UiText.Resource(R.string.name_must_end_zip))
            return
        }
        val state = _browseState.value
        val targetPaths = paths.toSet()
        val selectedItems = state.files.filter { it.path in targetPaths }
        if (selectedItems.isEmpty()) return
        val provider = fileProvider
        val destinationDirectory = state.currentPath
        val publishProgress = archiveProgressPublisher(R.string.progress_compressing)

        launchOperation(
            R.string.progress_compressing,
            R.string.operation_compress,
            cancelledMessage = R.string.archive_compression_cancelled,
        ) {
            ArchiveService.createZip(
                provider = provider,
                selectedItems = selectedItems,
                destinationDirectory = destinationDirectory,
                archiveName = validatedName,
                onProgress = publishProgress,
            ).fold(
                onSuccess = { archive ->
                    if (clearSelectionAfter) clearSelection()
                    refreshFiles()
                    showSnackbar(
                        UiText.Resource(R.string.archive_created, listOf(UiText.Dynamic(archive.name))),
                    )
                },
                onFailure = { error ->
                    operationController.recordFailure(error)
                    showSnackbar(OperationMessages.failure(R.string.operation_compress, error))
                },
            )
        }
    }

    fun extractSelectedArchive() {
        val state = _browseState.value
        if (state.selectedFiles.size != 1) {
            showSnackbar(UiText.Resource(R.string.archive_select_one))
            return
        }
        val archive = state.files.firstOrNull { it.path in state.selectedFiles }
        if (archive == null) {
            showSnackbar(UiText.Resource(R.string.selected_archive_unavailable))
            return
        }
        launchArchiveExtraction(
            archive = archive,
            destinationDirectory = state.currentPath,
            clearSelectionAfter = true,
        )
    }

    fun extractArchive(path: String) {
        val state = _browseState.value
        val archive = state.files.firstOrNull { it.path == path }
        if (archive == null) {
            showSnackbar(UiText.Resource(R.string.archive_unavailable))
            return
        }
        launchArchiveExtraction(
            archive = archive,
            destinationDirectory = state.currentPath,
            clearSelectionAfter = false,
        )
    }

    private fun launchArchiveExtraction(
        archive: FileItem,
        destinationDirectory: String,
        clearSelectionAfter: Boolean,
    ) {
        val provider = fileProvider
        val publishProgress = archiveProgressPublisher(R.string.progress_extracting)
        var report: ArchiveExtractionReport? = null

        launchOperation(
            R.string.progress_extracting,
            R.string.operation_extract,
            cancelledMessage = R.string.archive_extraction_cancelled,
        ) {
            ArchiveService.extract(
                provider = provider,
                archive = archive,
                destinationDirectory = destinationDirectory,
                onProgress = publishProgress,
                onReport = { extracted ->
                    report = extracted
                    operationController.attachArchiveReport(extracted)
                },
            ).fold(
                onSuccess = { extractionRoot ->
                    if (clearSelectionAfter) clearSelection()
                    refreshFiles()
                    report?.notExtracted?.firstOrNull()?.let { operationController.recordFailure(it.error) }
                    showSnackbar(
                        OperationMessages.archiveExtracted(
                            extractionRoot.name,
                            renamedEntries = report?.renamedCount ?: 0,
                            notExtractedEntries = report?.notExtractedCount ?: 0,
                        ),
                    )
                },
                onFailure = { error ->
                    operationController.recordFailure(error)
                    if (error is PartialExtractionException) {
                        refreshFiles()
                        showSnackbar(OperationMessages.archivePartiallyExtracted(error))
                    } else {
                        showSnackbar(OperationMessages.failure(R.string.operation_extract, error))
                    }
                },
            )
        }
    }

    /**
     * Removes a kept extraction through the browser's own delete confirmation, Trash included, so the
     * user sees exactly which folder goes. Opens the folder that contains it first when needed.
     */
    fun requestExtractionRemoval(report: ArchiveExtractionReport) {
        val root = report.root.path
        _pendingExtractionRemoval.value = root
        val parent = fileProvider.getParentPath(root) ?: return
        val state = _browseState.value
        if (state.files.none { it.path == root } && state.currentPath != parent) navigateTo(parent)
    }

    /**
     * Selects the pending extraction once the listing shows it and returns true, so the delete
     * confirmation can open. A folder that is gone by then is forgotten.
     */
    fun takeExtractionRemoval(): Boolean {
        val root = _pendingExtractionRemoval.value ?: return false
        val state = _browseState.value
        if (state.isLoading) return false
        if (state.files.none { it.path == root }) {
            if (state.currentPath == fileProvider.getParentPath(root)) _pendingExtractionRemoval.value = null
            return false
        }
        _pendingExtractionRemoval.value = null
        _browseState.update { it.copy(selectedFiles = setOf(root)) }
        return true
    }

    fun copyToClipboard(paths: List<String>, clearSelectionAfter: Boolean = true) {
        _clipboardPaths.value = paths
        _clipboardOperation.value = ClipboardOperation.COPY
        clipboardProvider = fileProvider
        if (clearSelectionAfter) clearSelection()
        showSnackbar(UiText.Plural(R.plurals.items_copied, paths.size, listOf(paths.size)))
    }

    fun cutToClipboard(paths: List<String>, clearSelectionAfter: Boolean = true) {
        _clipboardPaths.value = paths
        _clipboardOperation.value = ClipboardOperation.CUT
        clipboardProvider = fileProvider
        if (clearSelectionAfter) clearSelection()
        showSnackbar(UiText.Plural(R.plurals.items_cut, paths.size, listOf(paths.size)))
    }

    fun clearClipboard() {
        _clipboardPaths.value = emptyList()
        _clipboardOperation.value = ClipboardOperation.NONE
        clipboardProvider = null
    }

    fun onAppBackgrounded(nowMillis: Long) {
        sessionAutoCloseTracker.onBackgrounded(nowMillis)
        // Pinned sessions stay open; keeping them is what pinning is for.
        backgroundedSessionIds = _sessions.value.filterNot { it.pinned }.mapTo(mutableSetOf()) { it.id }
    }

    fun onAppForegrounded(nowMillis: Long) {
        refreshConnectionStates()
        val sessionIds = backgroundedSessionIds
        backgroundedSessionIds = emptySet()
        val decision = sessionAutoCloseTracker.onForegrounded(
            nowMillis = nowMillis,
            enabled = autoCloseSessions.value,
            timeoutMillis = sessionAutoCloseTimeout.value.durationMillis,
            operationRunning = operationState.value is OperationState.Running,
        )
        when (decision) {
            SessionAutoCloseDecision.KEEP_OPEN -> Unit
            SessionAutoCloseDecision.CLOSE_NOW -> closeSessionsAfterInactivity(sessionIds)
            SessionAutoCloseDecision.DEFER_UNTIL_OPERATION_FINISHES -> {
                deferredAutoCloseSessionIds = deferredAutoCloseSessionIds + sessionIds
            }
        }
    }

    fun paste() {
        val paths = _clipboardPaths.value.toList()
        val operation = _clipboardOperation.value
        if (paths.isEmpty() || operation == ClipboardOperation.NONE) return
        val destPath = _browseState.value.currentPath
        val sourceProvider = clipboardProvider ?: fileProvider
        val destinationProvider = fileProvider
        val progressLabelRes = when (operation) {
            ClipboardOperation.COPY -> R.string.progress_copying
            ClipboardOperation.CUT -> R.string.progress_moving
            ClipboardOperation.NONE -> R.string.progress_pasting
        }
        val progressLabel = UiText.Resource(progressLabelRes)
        launchOperation(progressLabelRes, R.string.operation_paste) {
            var failed = 0
            var firstError: Throwable? = null
            var completed = 0
            var skipped = 0
            val itemResults = buildMap {
                for (sourcePath in paths) {
                    val visibleItem = _browseState.value.files.firstOrNull { it.path == sourcePath }
                    put(
                        sourcePath,
                        visibleItem?.let { Result.success(it) }
                            ?: sourceProvider.getFileInfo(sourcePath),
                    )
                }
            }
            for (sourcePath in paths) {
                val itemResult = itemResults.getValue(sourcePath)
                val item = itemResult.getOrNull()
                updateOperationProgress(
                    TransferProgress(
                        label = progressLabel,
                        completedItems = completed,
                        skippedItems = skipped,
                        totalItems = paths.size,
                        currentItemName = item?.name ?: sourcePath.substringAfterLast('/'),
                    )
                )
                if (item == null) {
                    failed++
                    operationController.recordFailure(itemResult.exceptionOrNull() ?: IllegalStateException("The source item is unavailable"))
                    if (firstError == null) {
                        firstError = itemResult.exceptionOrNull()
                            ?: IllegalStateException("The source item is unavailable")
                    }
                    continue
                }

                val progressThrottle = StreamProgressThrottle()
                var latestStreamProgress: StreamTransferProgress? = null
                val publishStreamProgress: (StreamTransferProgress) -> Unit = { streamProgress ->
                    latestStreamProgress = streamProgress
                    if (progressThrottle.shouldPublish(streamProgress)) {
                        updateOperationProgress(
                            TransferProgress(
                                label = progressLabel,
                                completedItems = completed,
                                skippedItems = skipped,
                                totalItems = paths.size,
                                currentItemName = streamProgress.path.substringAfterLast('/'),
                                copiedBytes = streamProgress.bytesTransferred,
                                totalBytes = streamProgress.totalBytes,
                                elapsedNanos = streamProgress.elapsedNanos,
                            )
                        )
                    }
                }
                val result = when (operation) {
                    ClipboardOperation.COPY -> FileOperationCoordinator.copyPath(
                        sourceProvider, destinationProvider, sourcePath, destPath,
                        operationController.conflicts::resolve, publishStreamProgress,
                    )
                    ClipboardOperation.CUT -> FileOperationCoordinator.movePath(
                        sourceProvider, destinationProvider, sourcePath, destPath,
                        operationController.conflicts::resolve, publishStreamProgress,
                    )
                    ClipboardOperation.NONE -> Result.success(TransferDisposition.SKIPPED)
                }
                result.onSuccess { disposition ->
                    if (disposition == TransferDisposition.SKIPPED) skipped++ else completed++
                    val streamProgress = latestStreamProgress
                    updateOperationProgress(
                        TransferProgress(
                            label = progressLabel,
                            completedItems = completed,
                            skippedItems = skipped,
                            totalItems = paths.size,
                            currentItemName = item.name,
                            copiedBytes = streamProgress?.bytesTransferred ?: 0,
                            totalBytes = streamProgress?.totalBytes,
                            elapsedNanos = streamProgress?.elapsedNanos ?: 0,
                        )
                    )
                }
                result.onFailure { error ->
                    if (error is CancellationException) throw error
                    failed++
                    operationController.recordFailure(error)
                    if (firstError == null) firstError = error
                }
            }
            if (operation == ClipboardOperation.CUT && failed == 0 && skipped == 0) {
                clearClipboard()
            }
            refreshFiles()
            if (failed > 0) {
                showSnackbar(
                    OperationMessages.partial(
                        failed = failed,
                        total = paths.size,
                        action = R.string.action_pasted,
                        error = checkNotNull(firstError),
                    )
                )
            } else {
                showSnackbar(UiText.Resource(R.string.paste_success))
            }
        }
    }

    fun setAudioTone(file: FileItem, tone: AudioTone) {
        val label = UiText.Resource(R.string.audio_setting_tone)
        launchOperation(R.string.audio_setting_tone, R.string.audio_setting_tone) {
            updateOperationProgress(TransferProgress(label, totalItems = 1, currentItemName = file.name))
            val throttle = StreamProgressThrottle()
            AudioToneInstaller(getApplication()).install(file, tone, operationController::beginCommit) { stream ->
                if (throttle.shouldPublish(stream)) {
                    updateOperationProgress(TransferProgress(label, totalItems = 1, currentItemName = file.name,
                        copiedBytes = stream.bytesTransferred, totalBytes = stream.totalBytes, elapsedNanos = stream.elapsedNanos))
                }
            }
            updateOperationProgress(TransferProgress(label, completedItems = 1, totalItems = 1))
            showSnackbar(UiText.Resource(if (tone == AudioTone.RINGTONE) R.string.audio_ringtone_set else R.string.audio_notification_set))
        }
    }

    fun downloadFile(path: String) {
        downloadPaths(listOf(path), clearSelection = false)
    }

    suspend fun prepareWebDavPlayback(file: FileItem): Result<PreparedWebDavPlayback> = runCatching {
        val state = _browseState.value
        require(state.source == FileSource.WEBDAV && file.source == FileSource.WEBDAV) {
            "Direct playback requires the active WebDAV session"
        }
        require(!file.isDirectory) {
            "Direct opening requires a file"
        }
        require(state.files.any { it.path == file.path && it.source == file.source }) {
            "The WebDAV file is no longer active"
        }
        playbackPreparer.prepare(getApplication(), fileProvider, file).getOrThrow()
    }

    fun downloadSelected() {
        downloadPaths(_browseState.value.selectedFiles.toList(), clearSelection = true)
    }

    private fun downloadPaths(paths: List<String>, clearSelection: Boolean) {
        if (paths.isEmpty()) return
        val state = _browseState.value
        val provider = fileProvider
        val progressLabel = UiText.Resource(R.string.progress_downloading)
        launchOperation(R.string.progress_downloading, R.string.operation_download) {
            if (!state.source.isNetwork) {
                showSnackbar(UiText.Resource(R.string.file_already_on_device))
                return@launchOperation
            }

            val items = runCatching {
                paths.map { path ->
                    state.files.firstOrNull { it.path == path }
                        ?: provider.getFileInfo(path).getOrThrow()
                }
            }.getOrElse { error ->
                operationController.recordFailure(error)
                showSnackbar(OperationMessages.failure(R.string.operation_download, error))
                return@launchOperation
            }
            if (items.any { it.path == state.currentPath }) {
                showSnackbar(UiText.Resource(R.string.choose_inside_location))
                return@launchOperation
            }

            if (clearSelection) clearSelection()
            showSnackbar(UiText.Plural(R.plurals.items_downloading, items.size, listOf(items.size)))

            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val progressThrottle = StreamProgressThrottle()
            var lastCompletedRequestedItems = -1
            val publishProgress: (DownloadProgress) -> Unit = { progress ->
                val stream = progress.stream
                if (stream == null) {
                    progressThrottle.reset()
                    lastCompletedRequestedItems = progress.completedRequestedItems
                    updateOperationProgress(
                        TransferProgress(
                            label = progressLabel,
                            completedItems = progress.completedRequestedItems,
                            totalItems = progress.totalRequestedItems,
                            currentItemName = items
                                .getOrNull(progress.completedRequestedItems)
                                ?.name,
                        ),
                    )
                } else {
                    val completedItemsChanged =
                        progress.completedRequestedItems != lastCompletedRequestedItems
                    if (progressThrottle.shouldPublish(stream, force = completedItemsChanged)) {
                        updateOperationProgress(
                            TransferProgress(
                                label = progressLabel,
                                completedItems = progress.completedRequestedItems,
                                totalItems = progress.totalRequestedItems,
                                currentItemName = stream.path.substringAfterLast('/'),
                                copiedBytes = stream.bytesTransferred,
                                totalBytes = stream.totalBytes,
                                elapsedNanos = stream.elapsedNanos,
                            ),
                        )
                    }
                    lastCompletedRequestedItems = progress.completedRequestedItems
                }
            }
            FileDownloader.download(
                provider = provider,
                items = items,
                destinationDirectory = downloads,
                onProgress = publishProgress,
            ).fold(
                onSuccess = { result ->
                    val count = result.downloadedFiles + result.downloadedDirectories
                    showSnackbar(
                        UiText.Plural(
                            R.plurals.items_downloaded,
                            count,
                            listOf(count, UiText.Resource(R.string.downloads_folder)),
                        ),
                        action = SnackbarAction.OpenFolder(downloads.absolutePath),
                    )
                },
                onFailure = { error ->
                    operationController.recordFailure(error)
                    showSnackbar(OperationMessages.failure(R.string.operation_download, error))
                },
            )
        }
    }

    // Connection management
    fun connectToRemote(connection: RemoteConnection) {
        cancelInitialNavigation()
        viewModelScope.launch {
            // Reuse an unpinned tab of this server; pinned tabs keep their place and are never re-pointed.
            val sessionId = _sessions.value.firstOrNull { it.connectionId == connection.id && !it.pinned }?.id
                ?: addRemoteSession(connection)
            connectionRepository.updateLastConnected(connection.id, System.currentTimeMillis())
            activateSessionInternal(sessionId)
        }
    }

    /**
     * Adds a session for [connection] without showing it, starting at [startPath] (the connection's
     * remote folder by default); nothing connects until it is shown. Returns its id.
     */
    private fun addRemoteSession(
        connection: RemoteConnection,
        startPath: String = connection.remotePath,
        currentPath: String? = null,
        pinned: Boolean = false,
    ): String {
        val sessionId = newSessionId("remote:${connection.id}")
        val startPath = BrowserNavigationBounds.normalizePath(startPath)
        sessionProviders[sessionId] = remoteProviderFactory.create(getApplication(), connection)
        sessionConnections[sessionId] = connection
        remoteImageThumbnailSessions[sessionId] = RemoteImageThumbnailSession {
            remoteProviderFactory.create(getApplication(), connection)
        }
        _sessions.update { sessions ->
            sessions + BrowserSession(
                id = sessionId,
                originLabel = connection.name,
                source = sourceForProtocol(connection.protocol),
                rootPath = "/",
                startPath = startPath,
                currentPath = currentPath?.let(BrowserNavigationBounds::normalizePath) ?: startPath,
                connectionId = connection.id,
                host = connection.host,
                pinned = pinned,
                pinOrder = if (pinned) ++pinCounter else 0L,
            )
        }
        return sessionId
    }

    // Pinned sessions

    private val _pinsRestored = MutableStateFlow(false)
    /** True once the sessions pinned in the last run are back in [sessions]. */
    val pinsRestored: StateFlow<Boolean> = _pinsRestored.asStateFlow()

    fun setSessionPinned(sessionId: String, pinned: Boolean) {
        val session = _sessions.value.firstOrNull { it.id == sessionId } ?: return
        if (pinned && !session.canPin) return
        if (pinned && !session.pinned &&
            !SessionBarModel.canPinMore(_sessions.value, pinCapacity)
        ) return
        if (pinned == session.pinned) return
        updateSession(sessionId) { current ->
            current.copy(
                pinned = pinned,
                pinOrder = if (pinned) ++pinCounter else 0L,
                startPath = if (pinned && current.source != FileSource.SAF) current.currentPath else current.startPath,
                // Only a pin can be the start tab.
                isStartTab = pinned && current.isStartTab,
            )
        }
        savePins()
    }

    /**
     * Marks a pinned tab as the one the app opens on launch, or clears the mark. There is at most
     * one start tab: marking one unmarks the previous one.
     */
    fun setStartTab(sessionId: String, start: Boolean) {
        val session = _sessions.value.firstOrNull { it.id == sessionId } ?: return
        if (start && !session.canBeStartTab) return
        _sessions.update { sessions ->
            sessions.map { it.copy(isStartTab = if (it.id == sessionId) start else it.isStartTab && !start) }
        }
        refreshActiveSessionSnapshot()
        missingStartTab = null
        savePins()
    }

    /** How many tabs the current layout can pin: the bar's four, or what fits in the rail. */
    private var pinCapacity = SessionBarModel.SIDE_CELLS

    fun setPinCapacity(cells: Int) {
        pinCapacity = cells.coerceAtLeast(1)
    }

    /** Hands out [BrowserSession.pinOrder]; restored pins take theirs first, in their saved order. */
    private var pinCounter = 0L

    private fun pinFor(session: BrowserSession): PinnedSession? {
        if (!session.pinned) return null
        val connectionId = session.connectionId
        val start = session.isStartTab
        return when {
            session.source == FileSource.LOCAL -> PinnedSession.Local(session.startPath, session.currentPath, start)
            session.source == FileSource.SAF ->
                session.treeUri?.let { PinnedSession.Saf(it, session.startPath, session.currentPath, start) }
            session.source == FileSource.SHIZUKU -> PinnedSession.Shizuku(session.startPath, session.currentPath, start)
            connectionId != null -> PinnedSession.Remote(connectionId, session.startPath, session.currentPath, start)
            else -> null
        }
    }

    private var savedPins: String? = null

    /** Stores the pinned sessions and the folder each one shows, so they come back after a restart. */
    private fun savePins() {
        if (!_pinsRestored.value) return
        val encoded = PinnedSessionCodec.encode(_sessions.value.sortedBy { it.pinOrder }.mapNotNull(::pinFor))
        if (encoded == savedPins) return
        savedPins = encoded
        viewModelScope.launch { prefs.setPinnedSessions(encoded) }
    }

    /**
     * The name of a start tab that could not come back after a restart (its folder-picker access
     * was withdrawn or its connection deleted), so launching can say so instead of failing silently.
     */
    private var missingStartTab: String? = null

    /** Brings back the sessions pinned in the last run, in their order, without showing or connecting any. */
    private suspend fun restorePinnedSessions() {
        try {
            val stored = prefs.pinnedSessions.first()
            savedPins = stored.orEmpty()
            val pins = PinnedSessionCodec.decode(stored)
            if (pins.isEmpty()) return
            val connections = if (pins.any { it is PinnedSession.Remote }) connectionRepository.connections.first() else emptyList()
            val grantedTrees = getApplication<Application>().contentResolver.persistedUriPermissions
                .filter { it.isReadPermission }
                .mapTo(mutableSetOf()) { it.uri.toString() }
            for (pin in pins) {
                val restoredId = runCatching {
                    when (pin) {
                        is PinnedSession.Local -> addLocalSession(pin.startPath, pin.currentPath, pinned = true)
                        is PinnedSession.Remote -> connections.firstOrNull { it.id == pin.connectionId }?.let { connection ->
                            addRemoteSession(connection, pin.startPath, pin.currentPath, pinned = true)
                        }
                        is PinnedSession.Saf -> if (pin.treeUri in grantedTrees) {
                            addSafSession(Uri.parse(pin.treeUri), pinned = true)
                        } else {
                            null
                        }
                        is PinnedSession.Shizuku -> addShizukuSession(pin.startPath, pin.currentPath, pinned = true)
                    }
                }.getOrNull()
                if (!pin.isStart) continue
                if (restoredId != null) {
                    updateSession(restoredId) { it.copy(isStartTab = true) }
                } else {
                    missingStartTab = when (pin) {
                        is PinnedSession.Saf -> SafFileProvider.titleForTreeUri(Uri.parse(pin.treeUri))
                        else -> pin.startPath.trimEnd('/').substringAfterLast('/').ifEmpty { pin.startPath }
                    }
                }
            }
        } finally {
            _pinsRestored.value = true
        }
    }

    /**
     * What launching should do with the start tab. Device and folder-picker tabs open at once;
     * a server tab connects first ([openServerStartTab]) while Home stays usable.
     */
    suspend fun startTabPlan(hasAllFilesAccess: Boolean): StartTabPlan {
        pinsRestored.first { it }
        missingStartTab?.let { return StartTabPlan.Unavailable(it) }
        val tab = _sessions.value.firstOrNull { it.isStartTab } ?: return StartTabPlan.None
        return when {
            tab.source == FileSource.LOCAL && !hasAllFilesAccess -> StartTabPlan.NeedsAccess(tab.barLabel)
            tab.source.isNetwork -> StartTabPlan.Server(tab.id, tab.barLabel)
            else -> {
                cancelInitialNavigation()
                viewModelScope.launch { activateSessionInternal(tab.id) }
                StartTabPlan.Opened
            }
        }
    }

    /**
     * Connects the server start tab and lists its folder. Returns null on success, else why it
     * failed. Cancelling the caller (the user tapped Cancel or left Home) stops the attempt.
     */
    suspend fun openServerStartTab(sessionId: String): UiText? {
        cancelInitialNavigation()
        if (!activateSessionInternal(sessionId)) return UiText.Resource(R.string.launch_tab_gone)
        return _browseState.value.error
    }

    fun disconnectRemote() {
        _activeSession.value?.let { disconnectSession(it.id) }
    }

    private val sessionConnections = mutableMapOf<String, RemoteConnection>()

    /**
     * Drops a server session's connection but keeps the tab and what it shows. Browsing in it
     * again connects anew; pinned tabs use this instead of closing.
     */
    fun disconnectSession(sessionId: String) {
        val provider = sessionProviders[sessionId] ?: return
        val connection = sessionConnections[sessionId]
        viewModelScope.launch {
            remoteImageThumbnailSessions.remove(sessionId)?.close()
            runCatching { provider.disconnect() }
            if (connection != null && sessionId in sessionProviders) {
                remoteImageThumbnailSessions[sessionId] = RemoteImageThumbnailSession {
                    remoteProviderFactory.create(getApplication(), connection)
                }
            }
            refreshConnectionState(sessionId)
        }
    }

    private fun refreshConnectionState(sessionId: String) {
        val connected = sessionProviders[sessionId]?.hasOpenConnection == true
        if (_sessions.value.firstOrNull { it.id == sessionId }?.connected == connected) return
        updateSession(sessionId) { it.copy(connected = connected) }
    }

    /** Re-reads every session's connection state, e.g. after the app was in the background. */
    fun refreshConnectionStates() {
        _sessions.value.forEach { refreshConnectionState(it.id) }
    }

    fun remoteImageThumbnailLoader(sessionId: String?): RemoteImageThumbnailLoader? =
        remoteImageThumbnailSessions[sessionId]

    fun activateSession(sessionId: String) {
        cancelInitialNavigation()
        viewModelScope.launch {
            activateSessionInternal(sessionId)
        }
    }

    /** Shows the session again where it was opened (a pinned "Downloads" tab back at Downloads). */
    fun resetSession(sessionId: String) {
        cancelInitialNavigation()
        viewModelScope.launch {
            val session = _sessions.value.firstOrNull { it.id == sessionId } ?: return@launch
            if (session.id == _activeSession.value?.id) {
                navigateToPath(session.startPath)
            } else {
                updateSession(sessionId) { it.copy(currentPath = it.startPath) }
                activateSessionInternal(sessionId)
            }
            if (session.pinned) savePins()
        }
    }

    fun closeActiveSession() {
        _activeSession.value?.let { closeSession(it.id) }
    }

    fun closeSession(sessionId: String) {
        viewModelScope.launch {
            closeSessionInternal(sessionId)
        }
    }

    fun saveConnection(connection: RemoteConnection) {
        viewModelScope.launch {
            runCatching { connectionRepository.save(connection) }.fold(
                onSuccess = {
                    showSnackbar(
                        UiText.Resource(
                            if (connection.id == 0L) R.string.connection_saved else R.string.connection_updated,
                        ),
                    )
                },
                onFailure = {
                    showSnackbar(UiText.Resource(R.string.connection_save_failed))
                },
            )
        }
    }

    fun deleteConnection(connection: RemoteConnection) {
        viewModelScope.launch {
            runCatching { connectionRepository.delete(connection) }.fold(
                onSuccess = { showSnackbar(UiText.Resource(R.string.connection_deleted)) },
                onFailure = { showSnackbar(UiText.Resource(R.string.connection_delete_failed)) },
            )
        }
    }

    fun refreshTrash() {
        viewModelScope.launch { loadTrashEntries() }
    }

    fun toggleTrashSelection(id: String) {
        _trashState.update { state ->
            val selected = state.selectedIds.toMutableSet()
            if (!selected.add(id)) selected.remove(id)
            state.copy(selectedIds = selected)
        }
    }

    fun selectAllTrash() {
        _trashState.update { state -> state.copy(selectedIds = state.entries.mapTo(mutableSetOf()) { it.id }) }
    }

    fun clearTrashSelection() {
        _trashState.update { it.copy(selectedIds = emptySet()) }
    }

    fun restoreSelectedTrash() {
        val entries = selectedTrashEntries()
        if (entries.isEmpty()) return
        launchOperation(R.string.progress_restoring_from_trash, R.string.operation_restore) {
            val failures = runTrashActions(entries, trashManager::restore)
            loadTrashEntries()
            showTrashResult(
                entries = entries,
                failures = failures,
                successMessage = { count ->
                    UiText.Plural(R.plurals.items_restored, count, listOf(count))
                },
                failureAction = R.string.operation_restore,
            )
        }
    }

    fun deleteSelectedTrashPermanently() {
        val entries = selectedTrashEntries()
        if (entries.isEmpty()) return
        launchOperation(R.string.progress_deleting_permanently, R.string.operation_delete) {
            val failures = runTrashActions(entries, trashManager::deletePermanently)
            loadTrashEntries()
            showTrashResult(
                entries = entries,
                failures = failures,
                successMessage = { count ->
                    UiText.Plural(R.plurals.items_deleted, count, listOf(count))
                },
                failureAction = R.string.operation_delete,
            )
        }
    }

    fun emptyTrash() {
        launchOperation(R.string.progress_emptying_trash, R.string.operation_empty_trash) {
            trashManager.empty().fold(
                onSuccess = { removed ->
                    loadTrashEntries()
                    showSnackbar(
                        UiText.Resource(
                            if (removed == 0) R.string.trash_already_empty else R.string.trash_emptied,
                        ),
                    )
                },
                onFailure = { error ->
                    loadTrashEntries()
                    operationController.recordFailure(error)
                    showSnackbar(OperationMessages.failure(R.string.operation_empty_trash, error))
                },
            )
        }
    }

    fun addBookmark(path: String, name: String) {
        val source = _browseState.value.source
        if (source != FileSource.LOCAL) {
            showSnackbar(UiText.Resource(R.string.bookmarks_local_only))
            return
        }
        viewModelScope.launch {
            bookmarkDao.insertIfAbsent(Bookmark(name = name, path = path, source = source))
        }
    }

    fun removeBookmark(path: String, source: FileSource) {
        viewModelScope.launch {
            bookmarkDao.deleteByPath(path, source)
        }
    }

    // Snackbar
    private fun showSnackbar(message: UiText, action: SnackbarAction? = null) {
        _snackbarAction.value = action
        _snackbarMessage.value = message
    }

    private fun launchOperation(
        @StringRes progressLabel: Int,
        @StringRes operationName: Int,
        @StringRes cancelledMessage: Int = R.string.transfer_cancelled,
        readUris: List<String> = emptyList(),
        block: suspend () -> Unit,
    ) {
        val started = operationController.launch(
            label = UiText.Resource(progressLabel),
            readUris = readUris,
            cancellable = operationName in setOf(R.string.operation_upload, R.string.operation_paste, R.string.operation_download, R.string.audio_setting_tone, R.string.operation_extract, R.string.operation_compress),
            onFailure = { error ->
                showSnackbar(
                    if (error is CancellationException) UiText.Resource(cancelledMessage)
                    else OperationMessages.failure(operationName, error),
                )
            },
            onFinished = {},
            block = block,
        )
        if (!started) {
            val active = operationState.value as? OperationState.Running ?: return
            showSnackbar(UiText.Resource(R.string.operation_already_running, listOf(active.label)))
        }
    }

    fun cancelOperation() = operationController.cancel()
    fun dismissOperationResult() = operationController.dismissResult()

    private fun updateOperationProgress(progress: TransferProgress) = operationController.update(progress)

    private fun cancelInitialNavigation() {
        initialNavigationJob?.cancel()
        initialNavigationJob = null
    }

    private fun archiveProgressPublisher(
        @StringRes labelRes: Int,
        throttle: ArchiveProgressThrottle = ArchiveProgressThrottle(),
    ): (ArchiveProgress) -> Unit = { progress ->
        if (progress.phase == ArchivePhase.REMOVING) operationController.disallowCancel()
        throttle.accept(progress)?.let { elapsedNanos ->
            val label = when (progress.phase) {
                ArchivePhase.READING_SOURCE -> R.string.progress_reading_archive
                ArchivePhase.PREPARING -> R.string.progress_preparing_archive
                ArchivePhase.WRITING -> labelRes
                ArchivePhase.REMOVING -> R.string.progress_removing_extracted
            }
            updateOperationProgress(
                TransferProgress(
                    label = UiText.Resource(label),
                    completedItems = progress.completedEntries,
                    totalItems = progress.totalEntries,
                    currentItemName = progress.currentEntryName?.substringAfterLast('/'),
                    copiedBytes = progress.processedBytes,
                    totalBytes = progress.totalBytes,
                    elapsedNanos = elapsedNanos,
                    skippedItems = progress.skippedEntries,
                )
            )
        }
    }

    private suspend fun loadTrashEntries() {
        _trashState.update { it.copy(isLoading = true, error = null) }
        runCatching { trashManager.listEntries() }.fold(
            onSuccess = { entries ->
                _trashState.update { state ->
                    state.copy(
                        entries = entries,
                        selectedIds = state.selectedIds.intersect(entries.mapTo(mutableSetOf()) { it.id }),
                        isLoading = false,
                        error = null,
                    )
                }
            },
            onFailure = { error ->
                _trashState.update {
                    it.copy(
                        isLoading = false,
                        error = OperationMessages.reason(error),
                    )
                }
            },
        )
    }

    private fun selectedTrashEntries(): List<TrashEntry> {
        val state = _trashState.value
        return state.entries.filter { it.id in state.selectedIds }
    }

    private suspend fun runTrashActions(
        entries: List<TrashEntry>,
        action: suspend (TrashEntry) -> Result<Unit>,
    ): List<Throwable> = buildList {
        entries.forEach { entry -> action(entry).exceptionOrNull()?.let(::add) }
    }

    private fun showTrashResult(
        entries: List<TrashEntry>,
        failures: List<Throwable>,
        successMessage: (Int) -> UiText,
        @StringRes failureAction: Int,
    ) {
        failures.firstOrNull()?.let(operationController::recordFailure)
        val succeeded = entries.size - failures.size
        when {
            failures.isEmpty() -> showSnackbar(successMessage(succeeded))
            succeeded > 0 -> showSnackbar(
                UiText.Resource(
                    R.string.partial_trash_success,
                    listOf(succeeded, failures.size, UiText.Resource(failureAction)),
                ),
            )
            else -> showSnackbar(OperationMessages.failure(failureAction, failures.first()))
        }
    }

    private fun validFileNameOrNotify(name: String): String? =
        when (val result = FileNameValidator.validate(name)) {
            is FileNameValidationResult.Valid -> result.name
            is FileNameValidationResult.Invalid -> {
                showSnackbar(UiText.Resource(result.messageRes))
                null
            }
        }

    fun clearSnackbar() {
        _snackbarMessage.value = null
        _snackbarAction.value = null
    }

    // Settings
    fun setTheme(theme: AppTheme) {
        viewModelScope.launch { prefs.setTheme(theme) }
    }

    fun setShowHidden(show: Boolean) {
        viewModelScope.launch { prefs.setShowHidden(show) }
    }

    fun setUseTrash(useTrash: Boolean) {
        viewModelScope.launch { prefs.setUseTrash(useTrash) }
    }

    fun setAutoCloseSessions(enabled: Boolean) {
        if (!enabled) deferredAutoCloseSessionIds = emptySet()
        viewModelScope.launch { prefs.setAutoCloseSessions(enabled) }
    }

    fun setSessionAutoCloseTimeout(timeout: SessionAutoCloseTimeout) {
        viewModelScope.launch { prefs.setSessionAutoCloseTimeout(timeout) }
    }

    fun setHomeSectionVisible(section: HomeSection, visible: Boolean) {
        viewModelScope.launch { prefs.setHomeSectionVisible(section, visible) }
    }

    fun moveHomeSection(section: HomeSection, offset: Int) {
        viewModelScope.launch { prefs.moveHomeSection(section, offset) }
    }

    fun resetHomeLayout() {
        viewModelScope.launch { prefs.setHomeLayout(HomeLayout.DEFAULT) }
    }

    fun setLimitedAccessAccepted(accepted: Boolean) {
        viewModelScope.launch { prefs.setLimitedAccessAccepted(accepted) }
    }

    fun setSortBy(sortBy: SortBy) {
        viewModelScope.launch { prefs.setSortBy(sortBy) }
    }

    fun setSortOrder(order: SortOrder) {
        viewModelScope.launch { prefs.setSortOrder(order) }
    }

    fun setViewMode(mode: ViewMode) {
        viewModelScope.launch { prefs.setViewMode(mode) }
    }

    override fun onCleared() {
        remoteImageThumbnailSessions.values.forEach(RemoteImageThumbnailSession::close)
        remoteImageThumbnailSessions.clear()
        // viewModelScope is cancelled at this point, so close privileged pipes synchronously.
        sessionProviders.values.filterIsInstance<com.voyagerfiles.data.repository.RootFileProvider>().forEach { it.close() }
        super.onCleared()
        viewModelScope.launch {
            sessionProviders.values.forEach { it.disconnect() }
            if (sessionProviders.isEmpty()) fileProvider.disconnect()
        }
    }

    private suspend fun activateSessionInternal(sessionId: String): Boolean {
        val session = _sessions.value.firstOrNull { it.id == sessionId } ?: return false
        val provider = sessionProviders[sessionId] ?: return false
        fileProvider = provider
        browserSessionRootPath = session.rootPath
        sessionUseCounter += 1
        val usedAt = sessionUseCounter
        _sessions.update { sessions -> sessions.map { if (it.id == sessionId) it.copy(lastUsed = usedAt) else it } }
        _activeSession.value?.let { previous ->
            if (_sessions.value.any { it.id == previous.id }) {
                scrollMemory.leave(previous.id, _browseState.value.currentPath)
            }
        }
        scrollMemory.enter(session.id, session.currentPath, returning = true)
        _activeSession.value = session.copy(lastUsed = usedAt)
        _browseState.update {
            it.copy(
                currentPath = session.currentPath,
                source = session.source,
                selectedFiles = emptySet(),
                error = null,
                searchQuery = "",
                fileTypeFilter = FileTypeFilter.ALL,
            )
        }
        loadFiles(session.currentPath, session.id, provider)
        return true
    }

    private suspend fun closeSessionInternal(sessionId: String) {
        val closedSession = _sessions.value.firstOrNull { it.id == sessionId } ?: return
        if (closedSession.pinned) {
            updateSession(sessionId) { it.copy(pinned = false, isStartTab = false) }
            savePins()
        }
        remoteImageThumbnailSessions.remove(sessionId)?.close()
        sessionConnections.remove(sessionId)
        sessionProviders.remove(sessionId)?.let { provider ->
            if (provider in uploadingProviders) retiredUploadProviders.add(provider) else provider.disconnect()
        }
        val wasActive = _activeSession.value?.id == sessionId
        val remainingSessions = _sessions.value.filterNot { it.id == sessionId }
        _sessions.value = remainingSessions
        scrollMemory.forgetSession(sessionId)

        if (!wasActive) {
            refreshActiveSessionSnapshot()
            return
        }

        val nextSession = remainingSessions.maxByOrNull { it.lastUsed }
        if (nextSession != null) {
            activateSessionInternal(nextSession.id)
            return
        }

        fileProvider = FileProviderFactory.createLocal()
        browserSessionRootPath = null
        _activeSession.value = null
        scrollMemory.enter(null, closedSession.rootPath, returning = false)
        _browseState.update {
            it.copy(
                currentPath = closedSession.rootPath,
                files = emptyList(),
                isLoading = false,
                error = null,
                selectedFiles = emptySet(),
                source = FileSource.LOCAL,
                searchQuery = "",
                fileTypeFilter = FileTypeFilter.ALL,
            )
        }
    }

    private fun closeSessionsAfterInactivity(sessionIds: Set<String>) {
        val closedSessions = _sessions.value.filter { it.id in sessionIds }
        if (closedSessions.isEmpty()) return
        val closedProviders = closedSessions.mapNotNull { session ->
            remoteImageThumbnailSessions.remove(session.id)?.close()
            sessionConnections.remove(session.id)
            sessionProviders.remove(session.id)
        }.toSet()
        val remainingSessions = _sessions.value.filterNot { it.id in sessionIds }
        val activeSessionWasClosed = _activeSession.value?.id?.let { it in sessionIds } == true
        _sessions.value = remainingSessions
        sessionIds.forEach(scrollMemory::forgetSession)
        clearClipboard()
        _snackbarMessage.value = null

        if (activeSessionWasClosed) {
            val nextSession = remainingSessions.maxByOrNull { it.lastUsed }
            if (nextSession == null) {
                loadGuard.nextRequest(null)
                fileProvider = FileProviderFactory.createLocal()
                browserSessionRootPath = null
                _activeSession.value = null
                scrollMemory.enter(null, "/", returning = false)
                _browseState.update {
                    it.copy(
                        currentPath = "/",
                        files = emptyList(),
                        isLoading = false,
                        error = null,
                        selectedFiles = emptySet(),
                        source = FileSource.LOCAL,
                        searchQuery = "",
                        fileTypeFilter = FileTypeFilter.ALL,
                    )
                }
            } else {
                val nextProvider = checkNotNull(sessionProviders[nextSession.id])
                fileProvider = nextProvider
                browserSessionRootPath = nextSession.rootPath
                scrollMemory.enter(nextSession.id, nextSession.currentPath, returning = true)
                _activeSession.value = nextSession
                _browseState.update {
                    it.copy(
                        currentPath = nextSession.currentPath,
                        source = nextSession.source,
                        selectedFiles = emptySet(),
                        error = null,
                        searchQuery = "",
                        fileTypeFilter = FileTypeFilter.ALL,
                    )
                }
                viewModelScope.launch {
                    loadFiles(nextSession.currentPath, nextSession.id, nextProvider)
                }
            }
        } else {
            refreshActiveSessionSnapshot()
        }
        _sessionClosureGeneration.value += 1L
        viewModelScope.launch {
            closedProviders.forEach { provider -> runCatching { provider.disconnect() } }
        }
    }

    private fun updateSession(sessionId: String, transform: (BrowserSession) -> BrowserSession) {
        _sessions.update { sessions ->
            sessions.map { session ->
                if (session.id == sessionId) transform(session) else session
            }
        }
        refreshActiveSessionSnapshot()
    }

    private fun refreshActiveSessionSnapshot() {
        val activeId = _activeSession.value?.id ?: return
        _activeSession.value = _sessions.value.firstOrNull { it.id == activeId }
    }

    private fun isCurrentLoad(sessionId: String?): Boolean =
        sessionId == _activeSession.value?.id

    private var sessionCounter = 0L

    /** A fresh id, so several sessions can share a storage volume. */
    private fun newSessionId(kind: String): String = "$kind#${++sessionCounter}"

    private var sessionUseCounter = 0L

    private fun safSessionId(rootPath: String): String =
        "saf:$rootPath"

    private fun titleForLocalPath(path: String): String =
        BrowserNavigationBounds.normalizePath(path).substringAfterLast("/").ifEmpty { "Local Files" }

    private fun sourceForProtocol(protocol: ConnectionProtocol): FileSource = when (protocol) {
        ConnectionProtocol.SFTP -> FileSource.SFTP
        ConnectionProtocol.FTP -> FileSource.FTP
        ConnectionProtocol.SMB -> FileSource.SMB
        ConnectionProtocol.WEBDAV -> FileSource.WEBDAV
    }

    // Last in the class, so every property the restore touches is initialised first.
    init {
        viewModelScope.launch { restorePinnedSessions() }
    }
}

enum class ClipboardOperation {
    NONE, COPY, CUT,
}

private const val SHIZUKU_SESSION_ID = "shizuku:/"
private const val SHIZUKU_START_PATH = "/storage/emulated/0/Android"
