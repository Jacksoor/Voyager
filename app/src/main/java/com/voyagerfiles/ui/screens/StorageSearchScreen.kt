package com.voyagerfiles.ui.screens

import android.content.ActivityNotFoundException
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voyagerfiles.R
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileTypeFilter
import com.voyagerfiles.data.model.SortOrder
import com.voyagerfiles.data.model.SortBy
import com.voyagerfiles.ui.components.FileSortMenu
import com.voyagerfiles.ui.components.FileThumbnailOrIcon
import com.voyagerfiles.util.FileUtils
import com.voyagerfiles.util.StorageVolumeInfo
import com.voyagerfiles.viewmodel.SearchPhase
import com.voyagerfiles.viewmodel.StorageSearchState
import com.voyagerfiles.viewmodel.StorageSearchViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal const val STORAGE_SEARCH_QUERY_TAG = "storage-search-query"
internal const val STORAGE_SEARCH_RESULTS_TAG = "storage-search-results"
internal const val STORAGE_SEARCH_UNINDEXED_TAG = "storage-search-unindexed"

@Composable
fun StorageSearchScreen(
    showHidden: Boolean,
    hasAllFilesAccess: Boolean,
    onRequestAllFilesAccess: () -> Unit,
    onNavigateBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    searchViewModel: StorageSearchViewModel = viewModel(),
    requestedQuery: String? = null,
    onRequestedQueryConsumed: () -> Unit = {},
    /** Opens with this type listed (a Quick Access tile on Home). */
    requestedType: FileTypeFilter? = null,
    onRequestedTypeConsumed: () -> Unit = {},
) {
    val state by searchViewModel.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(showHidden, hasAllFilesAccess) { searchViewModel.configure(showHidden, hasAllFilesAccess) }
    LaunchedEffect(requestedType) {
        if (requestedType != null) {
            searchViewModel.setTypeFilter(requestedType)
            onRequestedTypeConsumed()
        }
    }
    LaunchedEffect(requestedQuery) {
        if (requestedQuery != null) {
            searchViewModel.setQuery(requestedQuery)
            searchViewModel.submit()
            onRequestedQueryConsumed()
        }
    }
    // Back from an opened result: the results stay; the index is only asked what changed.
    DisposableEffect(lifecycleOwner) {
        var stopped = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> stopped = true
                Lifecycle.Event.ON_START -> if (stopped) {
                    stopped = false
                    searchViewModel.onReturn()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun open(file: FileItem, containingFolder: Boolean) {
        scope.launch(Dispatchers.Main.immediate) {
            if (!searchViewModel.validate(file)) {
                snackbar.showSnackbar(context.getString(R.string.search_stale_item))
            } else if (containingFolder) {
                File(file.path).parent?.let(onOpenFolder)
            } else if (file.isDirectory) {
                onOpenFolder(file.path)
            } else {
                FileUtils.openFile(context, file).onFailure { error ->
                    snackbar.showSnackbar(context.getString(
                        if (error is ActivityNotFoundException) R.string.browser_no_file_handler else R.string.browser_file_open_failed,
                    ))
                }
            }
        }
    }
    StorageSearchContent(
        state = state,
        snackbar = snackbar,
        onQueryChange = searchViewModel::setQuery,
        onSubmit = {
            keyboard?.hide()
            searchViewModel.submit()
        },
        onTypeFilterChange = searchViewModel::setTypeFilter,
        onSortByChange = searchViewModel::setSortBy,
        onSortOrderChange = searchViewModel::setSortOrder,
        onSearchUnindexed = searchViewModel::searchUnindexedNow,
        onSearchUnindexedEveryTimeChange = searchViewModel::setSearchUnindexed,
        onRepairMediaIndexChange = searchViewModel::setRepairMediaIndex,
        onCancel = searchViewModel::cancel,
        onRequestAllFilesAccess = onRequestAllFilesAccess,
        onNavigateBack = onNavigateBack,
        onOpenFile = { open(it, containingFolder = false) },
        onOpenLocation = { open(it, containingFolder = true) },
        // Quick Access opens a listed type: browsing, not typing, so no keyboard.
        focusOnOpen = requestedType == null,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StorageSearchContent(
    state: StorageSearchState,
    snackbar: SnackbarHostState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onTypeFilterChange: (FileTypeFilter) -> Unit,
    onSortByChange: (SortBy) -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
    onSearchUnindexed: () -> Unit,
    onSearchUnindexedEveryTimeChange: (Boolean) -> Unit,
    onRepairMediaIndexChange: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onRequestAllFilesAccess: () -> Unit,
    onNavigateBack: () -> Unit,
    onOpenFile: (FileItem) -> Unit,
    onOpenLocation: (FileItem) -> Unit,
    focusOnOpen: Boolean = true,
) {
    val focus = remember { FocusRequester() }
    // The keyboard comes up once, on the first visit; not again after opening a result.
    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!focusedOnce && focusOnOpen && state.query.isEmpty()) runCatching { focus.requestFocus() }
        focusedOnce = true
    }
    val listState = rememberLazyListState()
    // A new query or filter starts at the best match. Without this the list would stay on its
    // footer, the only item while the first results load. Coming back keeps the position.
    var listedFor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.searchedQuery, state.typeFilter) {
        val key = state.typeFilter.name + "/" + state.searchedQuery
        if (listedFor != null && listedFor != key) listState.scrollToItem(0)
        listedFor = key
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.content_desc_back))
                    }
                },
                title = {
                    TextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(STORAGE_SEARCH_QUERY_TAG),
                        placeholder = { Text(stringResource(R.string.search_storage_title)) },
                        singleLine = true,
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChange("") }) {
                                    Icon(Icons.Filled.Close, stringResource(R.string.content_desc_clear_search))
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                },
                actions = {
                    // A listed type (no query) is sorted like a folder; query results are ranked.
                    if (state.browsing) FileSortMenu(state.sort.by, state.sort.order, onSortByChange, onSortOrderChange)
                    SearchOptionsMenu(state, onSearchUnindexedEveryTimeChange, onRepairMediaIndexChange)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(FileTypeFilter.entries, key = { it.name }) { filter ->
                    FilterChip(
                        selected = state.typeFilter == filter,
                        onClick = { onTypeFilterChange(filter) },
                        label = { Text(stringResource(filter.labelRes)) },
                    )
                }
            }
            // Same height busy or not, so the list doesn't jump.
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            when {
                state.phase == SearchPhase.DENIED -> Message(stringResource(R.string.search_permission_required)) {
                    TextButton(onClick = onRequestAllFilesAccess) { Text(stringResource(R.string.permission_grant_full_access)) }
                }
                state.searchedQuery.isEmpty() && state.browsedType == null && state.phase != SearchPhase.SEARCHING ->
                    Message(stringResource(R.string.search_idle_hint))
                else -> LazyColumn(Modifier.weight(1f).testTag(STORAGE_SEARCH_RESULTS_TAG), state = listState) {
                    val shown = state.shown
                    items(shown, key = { it.path }) { file ->
                        SearchResultRow(file, state.volumes, onClick = { onOpenFile(file) }, onOpenLocation = { onOpenLocation(file) })
                    }
                    item(key = "footer") { SearchFooter(state, shown.size, onSearchUnindexed, onCancel) }
                }
            }
        }
    }
}

@Composable
private fun SearchOptionsMenu(
    state: StorageSearchState,
    onSearchUnindexedEveryTimeChange: (Boolean) -> Unit,
    onRepairMediaIndexChange: (Boolean) -> Unit,
) {
    if (!state.hasIndex) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.content_desc_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CheckItem(stringResource(R.string.search_unindexed_every_time), state.searchUnindexed) {
                onSearchUnindexedEveryTimeChange(!state.searchUnindexed)
            }
            CheckItem(stringResource(R.string.search_repair_media_index), state.repairMediaIndex) {
                onRepairMediaIndexChange(!state.repairMediaIndex)
            }
        }
    }
}

@Composable
private fun CheckItem(label: String, checked: Boolean, onToggle: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
        onClick = onToggle,
        modifier = Modifier.semantics {
            role = Role.Checkbox
            toggleableState = ToggleableState(checked)
        },
    )
}

@Composable
private fun Message(text: String, action: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}

/** One result: its icon, name and the folder it is in; the trailing button opens that folder. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResultRow(file: FileItem, volumes: List<StorageVolumeInfo>, onClick: () -> Unit, onOpenLocation: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .combinedClickable(onClick = onClick, onLongClick = onOpenLocation)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumbnailOrIcon(file = file, iconSize = 40.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                locationLabel(file.path, volumes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onOpenLocation) {
            Icon(Icons.Filled.FolderOpen, stringResource(R.string.content_desc_open_location_named, file.path))
        }
    }
}

/**
 * The folder a result is in, from its volume down: "DCIM › Camera" on internal storage, with the
 * volume's name first on others ("SD card › Music"), and the volume's name alone at its root.
 */
internal fun searchLocationLabel(path: String, volumes: List<StorageVolumeInfo>, internalName: String): String {
    val parent = path.substringBeforeLast('/')
    val volume = volumes.filter { v -> v.path != null && (parent == v.path || parent.startsWith(v.path.trimEnd('/') + "/")) }
        .maxByOrNull { it.path!!.length }
        ?: return parent
    val inside = parent.removePrefix(volume.path!!.trimEnd('/')).trim('/').split('/').filter { it.isNotEmpty() }
    val volumeName = if (volume.isPrimary) internalName else volume.description
    return when {
        inside.isEmpty() -> volumeName
        volume.isPrimary -> inside.joinToString(" › ")
        else -> (listOf(volumeName) + inside).joinToString(" › ")
    }
}

@Composable
private fun locationLabel(path: String, volumes: List<StorageVolumeInfo>): String =
    searchLocationLabel(path, volumes, stringResource(R.string.storage_internal))

@Composable
private fun SearchFooter(state: StorageSearchState, shownCount: Int, onSearchUnindexed: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        val style = MaterialTheme.typography.bodySmall
        when {
            state.phase == SearchPhase.FAILED -> Text(stringResource(R.string.category_failed), style = style, color = muted)
            shownCount == 0 && !state.busy -> Text(stringResource(R.string.search_no_matching_items), style = MaterialTheme.typography.bodyLarge, color = muted)
            else -> Text(pluralStringResource(R.plurals.search_result_count, shownCount, shownCount), style = style, color = muted)
        }
        if (state.phase == SearchPhase.WALKING) {
            Text(stringResource(R.string.search_walking_unindexed), style = style, color = muted)
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
        if (state.incomplete) Text(stringResource(R.string.search_incomplete), style = style, color = muted)
        if (state.inaccessibleFolders > 0) {
            Text(pluralStringResource(R.plurals.search_inaccessible_folders, state.inaccessibleFolders, state.inaccessibleFolders), style = style, color = muted)
        }
        if (state.repairedEntries > 0) {
            Text(pluralStringResource(R.plurals.search_repaired_entries, state.repairedEntries, state.repairedEntries), style = style, color = muted)
        }
        if (state.phase == SearchPhase.DONE && !state.searchedUnindexed) {
            TextButton(onClick = onSearchUnindexed, modifier = Modifier.testTag(STORAGE_SEARCH_UNINDEXED_TAG)) {
                Text(stringResource(R.string.search_unindexed_action))
            }
            Text(stringResource(R.string.search_unindexed_explained), style = style, color = muted, textAlign = TextAlign.Center)
        }
    }
}
