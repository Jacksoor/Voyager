package com.voyagerfiles.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.voyagerfiles.data.index.CategoryScanStatus
import com.voyagerfiles.data.index.IndexedFileSource
import com.voyagerfiles.data.index.MediaStoreFileSearch
import com.voyagerfiles.data.index.MediaStoreRepair
import com.voyagerfiles.data.index.SearchTerms
import com.voyagerfiles.data.index.StorageCategoryIndex
import com.voyagerfiles.data.local.PreferencesManager
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileTypeFilter
import com.voyagerfiles.util.FileUtils
import com.voyagerfiles.util.StorageVolumeInfo
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SearchPhase {
    /** Nothing to search for yet. */
    IDLE,
    /** Asking the index; earlier results stay on screen meanwhile. */
    SEARCHING,
    /** Indexed results are shown; folders the index leaves out are being walked. */
    WALKING,
    DONE,
    DENIED,
    FAILED,
}

data class StorageSearchState(
    val query: String = "",
    val typeFilter: FileTypeFilter = FileTypeFilter.ALL,
    /** The query [results] belong to. */
    val searchedQuery: String = "",
    /** Best match first, every type. */
    val results: List<FileItem> = emptyList(),
    /** [results] of the chosen type; computed with them, off the main thread. */
    val shown: List<FileItem> = emptyList(),
    val phase: SearchPhase = SearchPhase.IDLE,
    /** Folders the index leaves out were searched too for [searchedQuery]. */
    val searchedUnindexed: Boolean = false,
    /** Search them every time, not only on request. */
    val searchUnindexed: Boolean = false,
    val repairMediaIndex: Boolean = true,
    /** Whether an index answers at all (API 30+); without one every search walks. */
    val hasIndex: Boolean = false,
    /** The walk stopped early: cancelled or too many results. */
    val incomplete: Boolean = false,
    val inaccessibleFolders: Int = 0,
    /** Results that turned out to be gone or changed when opened. */
    val staleEntries: Int = 0,
    /** Paths sent to the media scanner after the last full walk. */
    val repairedEntries: Int = 0,
    val volumes: List<StorageVolumeInfo> = emptyList(),
) {
    val busy: Boolean get() = phase == SearchPhase.SEARCHING || phase == SearchPhase.WALKING
}

/**
 * Storage search: MediaStore answers at once ([IndexedFileSource]); volumes it doesn't cover are
 * walked as before, and the folders it leaves out (`.nomedia`, files written past FUSE) are walked
 * on request or every time ([StorageSearchState.searchUnindexed]). A full walk also tells the
 * media scanner what MediaStore got wrong, which fixes it for other apps too.
 */
class StorageSearchViewModel @JvmOverloads constructor(
    application: Application,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    private val walker: StorageCategoryIndex = StorageCategoryIndex(),
    private val index: IndexedFileSource? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) MediaStoreFileSearch(application) else null,
    private val volumeProvider: () -> List<StorageVolumeInfo> = { FileUtils.getStorageVolumes(application) },
    private val permissionGranted: () -> Boolean = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(application, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    },
    private val preferences: PreferencesManager? = PreferencesManager(application),
    private val repairScanner: suspend (List<String>) -> Unit = { MediaStoreRepair.scan(application, it) },
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(
        StorageSearchState(query = savedState.get<String>(QUERY_KEY).orEmpty(), hasIndex = index != null),
    )
    val state = _state.asStateFlow()

    private var showHidden = false
    private var accessGranted = false
    private var configured = false
    private var typingJob: Job? = null
    private var searchJob: Job? = null
    private var generation = 0L
    /** Results the index gave for the shown query, and those only a walk found. */
    private var indexedResults = emptyList<FileItem>()
    private var walkedResults = emptyList<FileItem>()

    init {
        viewModelScope.launch {
            val prefs = preferences ?: return@launch
            _state.update {
                it.copy(searchUnindexed = prefs.searchUnindexed.first(), repairMediaIndex = prefs.searchRepairMediaIndex.first())
            }
        }
    }

    /** The screen's current access and hidden-files setting; the first call runs a restored query. */
    fun configure(showHidden: Boolean, accessGranted: Boolean) {
        val changed = !configured || showHidden != this.showHidden || accessGranted != this.accessGranted
        this.showHidden = showHidden
        this.accessGranted = accessGranted
        configured = true
        if (changed) search(_state.value.query, walkUnindexed = null)
    }

    /** Typing searches as you go, after a short pause. */
    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        savedState[QUERY_KEY] = query
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            delay(TYPING_PAUSE_MS)
            search(query, walkUnindexed = null)
        }
    }

    /** The keyboard's search key: search now, without waiting for the pause. */
    fun submit() {
        typingJob?.cancel()
        if (_state.value.query.trim() != _state.value.searchedQuery || _state.value.phase == SearchPhase.IDLE) {
            search(_state.value.query, walkUnindexed = null)
        }
    }

    fun setTypeFilter(filter: FileTypeFilter) {
        _state.update { it.copy(typeFilter = filter) }
        refilter()
    }

    private var filterJob: Job? = null

    /** Applies the chosen type to the results, off the main thread (results can be thousands). */
    private fun refilter() {
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            val state = _state.value
            val shown = withContext(Dispatchers.Default) { state.results.filter(state.typeFilter::matches) }
            _state.update { if (it.results === state.results && it.typeFilter == state.typeFilter) it.copy(shown = shown) else it }
        }
    }

    /**
     * Merges and ranks the current results and applies the type, all off the main thread: ranking
     * thousands of names while typing would make the keyboard lag.
     */
    private suspend fun ranked(query: String): Ranked {
        val indexed = indexedResults
        val walked = walkedResults
        val filter = _state.value.typeFilter
        return withContext(Dispatchers.Default) {
            val byPath = LinkedHashMap<String, FileItem>(indexed.size + walked.size)
            indexed.forEach { byPath[it.path] = it }
            walked.forEach { byPath.putIfAbsent(it.path, it) }
            val all = SearchTerms.rank(byPath.values.toList(), query)
            Ranked(all, all.filter(filter::matches), filter)
        }
    }

    private class Ranked(val all: List<FileItem>, val shown: List<FileItem>, val filter: FileTypeFilter)

    /** [state] with new results; refiltered if the type changed while they were computed. */
    private fun StorageSearchState.withResults(results: Ranked): StorageSearchState =
        copy(results = results.all, shown = results.shown)

    /** Walk the folders the index leaves out, for the shown query. */
    fun searchUnindexedNow() {
        search(_state.value.searchedQuery.ifBlank { _state.value.query }, walkUnindexed = true)
    }

    fun setSearchUnindexed(enabled: Boolean) {
        _state.update { it.copy(searchUnindexed = enabled) }
        viewModelScope.launch { preferences?.setSearchUnindexed(enabled) }
        if (enabled && !_state.value.searchedUnindexed && _state.value.searchedQuery.isNotBlank()) searchUnindexedNow()
    }

    fun setRepairMediaIndex(enabled: Boolean) {
        _state.update { it.copy(repairMediaIndex = enabled) }
        viewModelScope.launch { preferences?.setSearchRepairMediaIndex(enabled) }
    }

    /**
     * Back from an opened result: the results stay. The index is asked again (it is cheap) so
     * files deleted meanwhile drop out and new ones appear; walked results are kept if they still
     * exist. Nothing is walked again.
     */
    fun onReturn() {
        val query = _state.value.searchedQuery
        if (query.isBlank() || _state.value.busy || !accessGranted || !permissionGranted()) return
        val request = ++generation
        searchJob = viewModelScope.launch {
            try {
                val volumes = withContext(Dispatchers.IO) { volumeProvider().filter { it.isAvailable } }
                val indexedVolumes = withContext(Dispatchers.IO) { coveredVolumes(volumes) }
                val indexed = withContext(Dispatchers.IO) { index?.search(query, indexedVolumes, showHidden).orEmpty() }
                val walked = withContext(Dispatchers.IO) { walkedResults.filter { File(it.path).exists() } }
                if (request != generation) return@launch
                indexedResults = indexed
                walkedResults = walked
                val results = ranked(query)
                if (request != generation) return@launch
                _state.update { it.withResults(results).copy(volumes = volumes) }
                refilterIfStale(results)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The results on screen stay as they were.
            }
        }
    }

    /** Stops a walk; what it found so far stays. */
    fun cancel() {
        generation++
        searchJob?.cancel()
        _state.update { if (it.busy) it.copy(phase = SearchPhase.DONE, incomplete = true) else it }
    }

    /** Checks a result against the disk before it is opened; a gone or changed one is removed. */
    suspend fun validate(file: FileItem): Boolean {
        if (!permissionGranted()) {
            cancel()
            _state.update { it.copy(phase = SearchPhase.DENIED, results = emptyList(), shown = emptyList()) }
            return false
        }
        val current = withContext(Dispatchers.IO) {
            runCatching { walker.isOpenable(file, volumeProvider(), permissionGranted) }.getOrDefault(false)
        }
        if (!current) {
            indexedResults = indexedResults.filterNot { it.path == file.path }
            walkedResults = walkedResults.filterNot { it.path == file.path }
            _state.update {
                it.copy(
                    results = it.results.filterNot { r -> r.path == file.path },
                    shown = it.shown.filterNot { r -> r.path == file.path },
                    staleEntries = it.staleEntries + 1,
                )
            }
            // A row for a file that is gone: tell the scanner, so MediaStore forgets it.
            if (_state.value.repairMediaIndex && !withContext(Dispatchers.IO) { File(file.path).exists() }) {
                viewModelScope.launch { runCatching { repairScanner(listOf(file.path)) } }
            }
        }
        return current
    }

    /** [walkUnindexed] null: walk the indexed volumes only when the user asked for it every time. */
    private fun search(rawQuery: String, walkUnindexed: Boolean?) {
        val query = rawQuery.trim()
        val request = ++generation
        searchJob?.cancel()
        if (!configured) return
        if (!accessGranted || !permissionGranted()) {
            indexedResults = emptyList()
            walkedResults = emptyList()
            _state.update { it.copy(searchedQuery = query, results = emptyList(), shown = emptyList(), phase = SearchPhase.DENIED) }
            return
        }
        // Only separators ("-", "_") is nothing to search for.
        if (SearchTerms.parse(query).isEmpty()) {
            indexedResults = emptyList()
            walkedResults = emptyList()
            _state.update {
                it.copy(searchedQuery = "", results = emptyList(), shown = emptyList(), phase = SearchPhase.IDLE, searchedUnindexed = false,
                    incomplete = false, inaccessibleFolders = 0, staleEntries = 0, repairedEntries = 0)
            }
            return
        }
        val walkIndexed = walkUnindexed ?: _state.value.searchUnindexed
        val sameQuery = query == _state.value.searchedQuery
        _state.update { it.copy(phase = SearchPhase.SEARCHING) }
        searchJob = viewModelScope.launch {
            try {
                val volumes = withContext(Dispatchers.IO) { volumeProvider().filter { it.isAvailable } }
                val indexedVolumes = withContext(Dispatchers.IO) { coveredVolumes(volumes) }
                val otherVolumes = volumes - indexedVolumes.toSet()
                val indexed = withContext(Dispatchers.IO) { index?.search(query, indexedVolumes, showHidden).orEmpty() }
                if (request != generation) return@launch
                indexedResults = indexed
                // A new walk of the same query keeps what the last one found until it has more.
                if (!sameQuery) walkedResults = emptyList()
                val walkVolumes = otherVolumes + if (walkIndexed) indexedVolumes else emptyList()
                val results = ranked(query)
                if (request != generation) return@launch
                _state.update {
                    it.withResults(results).copy(
                        searchedQuery = query,
                        phase = if (walkVolumes.isEmpty()) SearchPhase.DONE else SearchPhase.WALKING,
                        // Without an index (or on volumes it doesn't cover) the walk covers everything.
                        searchedUnindexed = walkIndexed || indexedVolumes.isEmpty(),
                        incomplete = false,
                        inaccessibleFolders = 0,
                        staleEntries = if (sameQuery) it.staleEntries else 0,
                        repairedEntries = if (sameQuery) it.repairedEntries else 0,
                        volumes = volumes,
                    )
                }
                refilterIfStale(results)
                if (walkVolumes.isEmpty()) return@launch
                walk(request, query, walkVolumes, repair = walkIndexed && _state.value.repairMediaIndex, indexedVolumes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) {
                    _state.update { it.copy(phase = if (permissionGranted()) SearchPhase.FAILED else SearchPhase.DENIED) }
                }
            }
        }
    }

    private suspend fun walk(
        request: Long,
        query: String,
        volumes: List<StorageVolumeInfo>,
        repair: Boolean,
        indexedVolumes: List<StorageVolumeInfo>,
    ) {
        val visitedFiles = HashSet<String>()
        val visitedDirs = HashSet<String>()
        // The walker reports real paths; MediaStore and the rest of the app use the volume's own
        // (a volume path behind a symlink would otherwise list every result twice).
        val realRoots = volumes.mapNotNull { v ->
            val path = v.path?.trimEnd('/') ?: return@mapNotNull null
            val real = runCatching { File(path).canonicalPath.trimEnd('/') }.getOrDefault(path)
            (real to path).takeIf { real != path }
        }
        fun volumePath(path: String): String {
            val (real, own) = realRoots.firstOrNull { (real, _) -> path == real || path.startsWith("$real/") } ?: return path
            return own + path.removePrefix(real)
        }
        // To compare with MediaStore the walk must see hidden entries too (`.nomedia` files).
        val walkHidden = showHidden || repair
        val onVisit: ((String, Boolean) -> Unit)? = if (repair) { path, isDirectory ->
            if (isDirectory) visitedDirs += volumePath(path) else visitedFiles += volumePath(path)
        } else null
        var last: com.voyagerfiles.data.index.CategoryIndexState? = null
        walker.search(query, volumes, walkHidden, onVisit, permissionGranted).collect { result ->
            if (request != generation) return@collect
            last = result
            if (result.status == CategoryScanStatus.DENIED) {
                _state.update { it.copy(phase = SearchPhase.DENIED, results = emptyList(), shown = emptyList()) }
                return@collect
            }
            walkedResults = withContext(Dispatchers.Default) {
                result.files.filter { showHidden || !it.isHidden }.map { it.copy(path = volumePath(it.path)) }
            }
            val results = ranked(query)
            if (request != generation) return@collect
            _state.update {
                it.withResults(results).copy(inaccessibleFolders = result.coverage.sumOf { c -> c.inaccessibleEntries })
            }
        }
        if (request != generation) return
        val status = last?.status
        if (status == CategoryScanStatus.DENIED) return
        var repaired = 0
        val complete = status == CategoryScanStatus.COMPLETE || status == CategoryScanStatus.PARTIAL
        val source = index
        if (repair && complete && source != null && indexedVolumes.isNotEmpty()) {
            repaired = runCatching {
                val paths = withContext(Dispatchers.IO) {
                    MediaStoreRepair.plan(
                        roots = indexedVolumes.mapNotNull { it.path?.trimEnd('/') },
                        indexed = source.allPaths(indexedVolumes),
                        visitedFiles = visitedFiles,
                        visitedDirs = visitedDirs,
                        exists = { File(it).exists() },
                    )
                }
                repairScanner(paths)
                paths.size
            }.getOrDefault(0)
        }
        // The walk saw every indexed folder: index results for files that are gone go too.
        if (indexedVolumes.isNotEmpty() && volumes.containsAll(indexedVolumes)) {
            indexedResults = withContext(Dispatchers.IO) { indexedResults.filter { File(it.path).exists() } }
        }
        val results = ranked(query)
        if (request != generation) return
        _state.update {
            it.withResults(results).copy(
                phase = SearchPhase.DONE,
                incomplete = status == CategoryScanStatus.LIMITED,
                repairedEntries = it.repairedEntries + repaired,
            )
        }
    }

    private fun coveredVolumes(volumes: List<StorageVolumeInfo>): List<StorageVolumeInfo> =
        index?.let { source -> volumes.filter { runCatching { source.covers(it) }.getOrDefault(false) } }.orEmpty()

    /** The type changed while [results] were computed for the old one. */
    private fun refilterIfStale(results: Ranked) {
        if (_state.value.typeFilter != results.filter) refilter()
    }

    private companion object {
        const val QUERY_KEY = "query"
        const val TYPING_PAUSE_MS = 150L
    }
}
