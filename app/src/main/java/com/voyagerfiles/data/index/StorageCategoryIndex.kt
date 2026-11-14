package com.voyagerfiles.data.index

import androidx.annotation.StringRes
import com.voyagerfiles.R
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileTypeFilter
import com.voyagerfiles.util.StorageVolumeInfo
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

enum class StorageCategory(val filter: FileTypeFilter) {
    APPS(FileTypeFilter.APPS),
    VIDEOS(FileTypeFilter.VIDEOS),
    AUDIO(FileTypeFilter.AUDIO),
    IMAGES(FileTypeFilter.IMAGES),
    DOCUMENTS(FileTypeFilter.DOCUMENTS),
}

enum class CategoryScanStatus(@StringRes val labelRes: Int) {
    PENDING(R.string.category_pending),
    SCANNING(R.string.category_scanning),
    COMPLETE(R.string.category_complete),
    PARTIAL(R.string.category_partial),
    UNAVAILABLE(R.string.storage_unavailable),
    CANCELLED(R.string.category_cancelled),
    LIMITED(R.string.category_limited),
    DENIED(R.string.category_permission_required),
    FAILED(R.string.category_failed),
}

data class CategoryVolumeCoverage(
    val volume: StorageVolumeInfo,
    val status: CategoryScanStatus = CategoryScanStatus.PENDING,
    val filesExamined: Int = 0,
    val inaccessibleEntries: Int = 0,
    val skippedEntries: Int = 0,
)

data class CategoryIndexState(
    val status: CategoryScanStatus = CategoryScanStatus.PENDING,
    val files: List<FileItem> = emptyList(),
    val coverage: List<CategoryVolumeCoverage> = emptyList(),
    val staleEntries: Int = 0,
)

/** Scans ordinary shared-storage paths only. Links are never traversed and directory handles are closed on cancellation. */
class StorageCategoryIndex(
    private val maxResults: Int = 50_000,
    private val maxDepth: Int = 128,
    private val canRead: (Path) -> Boolean = Files::isReadable,
) {
    init {
        require(maxResults > 0 && maxDepth > 0)
    }

    /** Every file of [type] (folders for [FileTypeFilter.FOLDERS]); the rest as for [search]. */
    fun scanType(
        type: FileTypeFilter,
        volumes: List<StorageVolumeInfo>,
        showHidden: Boolean,
        visitor: WalkVisitor? = null,
        listHidden: Boolean = showHidden,
        hasPermission: () -> Boolean,
    ): Flow<CategoryIndexState> = scanMatching(
        volumes, showHidden, listHidden, hasPermission, includeDirectories = type == FileTypeFilter.FOLDERS, visitor = visitor,
        accepts = type::matches,
    )

    /**
     * Files and folders whose name contains every word of [query]. [showHidden] walks into dot
     * folders; [listHidden] also returns what matches there. [visitor] sees every file and folder
     * the walk reaches, matching or not, and every one it couldn't read (to compare the walk with
     * MediaStore).
     */
    fun search(
        query: String,
        volumes: List<StorageVolumeInfo>,
        showHidden: Boolean,
        visitor: WalkVisitor? = null,
        listHidden: Boolean = showHidden,
        hasPermission: () -> Boolean,
    ): Flow<CategoryIndexState> {
        val terms = SearchTerms.parse(query)
        require(terms.isNotEmpty()) { "Enter a file name to search" }
        return scanMatching(volumes, showHidden, listHidden, hasPermission, includeDirectories = true, visitor = visitor) {
            SearchTerms.matches(it.name, terms)
        }
    }

    /** What a walk reached, for comparing it with MediaStore. Paths are real paths. */
    interface WalkVisitor {
        fun visited(path: String, isDirectory: Boolean)

        /** A folder (or volume root) that couldn't be read or listed in full. */
        fun unreadable(path: String)
    }

    private fun scanMatching(
        volumes: List<StorageVolumeInfo>,
        showHidden: Boolean,
        listHidden: Boolean,
        hasPermission: () -> Boolean,
        includeDirectories: Boolean = false,
        visitor: WalkVisitor? = null,
        accepts: (FileItem) -> Boolean,
    ): Flow<CategoryIndexState> = flow {
        val matches = mutableListOf<FileItem>()
        val coverage = volumes.map(::CategoryVolumeCoverage).toMutableList()
        val visitedRoots = mutableListOf<Path>()
        var currentVolume = 0
        var visitedSinceUpdate = 0
        var lastUpdate = System.nanoTime()

        suspend fun checkAccess() {
            currentCoroutineContext().ensureActive()
            if (!hasPermission()) throw StoragePermissionRevoked()
        }

        suspend fun publish(status: CategoryScanStatus = CategoryScanStatus.SCANNING) {
            checkAccess()
            emit(CategoryIndexState(status, matches.toList(), coverage.toList()))
            visitedSinceUpdate = 0
            lastUpdate = System.nanoTime()
        }

        fun inaccessible(path: Path? = null) {
            if (path != null) visitor?.unreadable(path.toString())
            coverage[currentVolume] = coverage[currentVolume].let {
                it.copy(inaccessibleEntries = it.inaccessibleEntries + 1)
            }
        }

        fun skipped() {
            coverage[currentVolume] = coverage[currentVolume].let { it.copy(skippedEntries = it.skippedEntries + 1) }
        }

        suspend fun visit(path: Path, depth: Int) {
            // The permission is checked when progress is published (every 256 entries or 250 ms):
            // the check is IPC, 0.33 ms on a phone, 10 s for 31k entries if done per entry.
            currentCoroutineContext().ensureActive()
            visitedSinceUpdate++
            if (visitedSinceUpdate >= 256 || System.nanoTime() - lastUpdate >= 250_000_000L) publish()
            try {
                if (depth > 0 && path in visitedRoots) {
                    skipped()
                    return
                }
                if (depth > 0 && !showHidden && path.fileName.toString().startsWith('.')) {
                    skipped()
                    return
                }
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (attributes.isSymbolicLink || (!attributes.isDirectory && !attributes.isRegularFile)) {
                    skipped()
                    return
                }
                // Folders only: a check per file costs more than the rest of the walk (4 s vs 10 s
                // for 31k files on a phone), and an unreadable folder is what hides files.
                if (attributes.isDirectory && !canRead(path)) {
                    inaccessible(path)
                    return
                }
                if (depth > 0) visitor?.visited(path.toString(), attributes.isDirectory)
                val file = FileItem(
                    name = path.fileName?.toString() ?: path.toString(),
                    path = path.toString(),
                    isDirectory = attributes.isDirectory,
                    size = if (attributes.isDirectory) 0 else attributes.size(),
                    lastModified = Date(attributes.lastModifiedTime().toMillis()),
                    isHidden = path.any { it.toString().startsWith('.') },
                )
                val listed = listHidden || !file.isHidden
                if ((!attributes.isDirectory || includeDirectories && depth > 0) && listed && accepts(file)) {
                    if (matches.size == maxResults) throw CategoryResultLimit()
                    matches += file
                }
                if (attributes.isDirectory) {
                    if (depth >= maxDepth) {
                        skipped()
                        return
                    }
                    Files.newDirectoryStream(path).use { children ->
                        for (child in children) visit(child, depth + 1)
                    }
                } else {
                    coverage[currentVolume] = coverage[currentVolume].let { it.copy(filesExamined = it.filesExamined + 1) }
                }
            } catch (_: IOException) {
                inaccessible(path)
            } catch (_: SecurityException) {
                inaccessible(path)
            } catch (_: java.nio.file.DirectoryIteratorException) {
                inaccessible(path)
            }
        }

        try {
            checkAccess()
            publish()
            for ((index, volume) in volumes.withIndex()) {
                currentVolume = index
                checkAccess()
                if (!volume.isAvailable) {
                    coverage[index] = coverage[index].copy(status = CategoryScanStatus.UNAVAILABLE)
                    publish()
                    continue
                }
                coverage[index] = coverage[index].copy(status = CategoryScanStatus.SCANNING)
                try {
                    val root = java.io.File(checkNotNull(volume.path)).toPath().toRealPath()
                    if (visitedRoots.any { root.startsWith(it) }) {
                        skipped()
                    } else {
                        visitedRoots.add(root)
                        visit(root, 0)
                    }
                } catch (_: IOException) {
                    inaccessible(java.io.File(checkNotNull(volume.path)).toPath())
                } catch (_: SecurityException) {
                    inaccessible(java.io.File(checkNotNull(volume.path)).toPath())
                }
                coverage[index] = coverage[index].let {
                    it.copy(status = if (it.inaccessibleEntries > 0 || it.skippedEntries > 0) CategoryScanStatus.PARTIAL else CategoryScanStatus.COMPLETE)
                }
                publish()
            }
            matches.sortWith(compareBy<FileItem> { it.name.lowercase() }.thenBy { it.path })
            publish(when {
                coverage.isEmpty() -> CategoryScanStatus.UNAVAILABLE
                coverage.any { it.status != CategoryScanStatus.COMPLETE } -> CategoryScanStatus.PARTIAL
                else -> CategoryScanStatus.COMPLETE
            })
        } catch (_: StoragePermissionRevoked) {
            emit(CategoryIndexState(status = CategoryScanStatus.DENIED))
        } catch (_: CategoryResultLimit) {
            coverage[currentVolume] = coverage[currentVolume].copy(status = CategoryScanStatus.LIMITED)
            currentCoroutineContext().ensureActive()
            emit(if (hasPermission()) {
                CategoryIndexState(CategoryScanStatus.LIMITED, matches.toList(), coverage.toList())
            } else {
                CategoryIndexState(status = CategoryScanStatus.DENIED)
            })
        }
    }.flowOn(Dispatchers.IO)

    /**
     * A result is a snapshot: reject removed, replaced, unreadable or out-of-scope files before
     * opening it. A file whose size or time differ still counts: MediaStore can lag behind a
     * file's last write.
     */
    fun isOpenable(file: FileItem, volumes: List<StorageVolumeInfo>, hasPermission: () -> Boolean): Boolean {
        if (!hasPermission()) return false
        return try {
            val path = java.io.File(file.path).toPath()
            val resolved = path.toRealPath()
            val roots = volumes.filter { it.isAvailable }.mapNotNull { it.path?.trimEnd('/') }
            // Below a volume's own path (which may itself sit behind a link) no link may lead elsewhere.
            val own = roots.firstOrNull { file.path == it || file.path.startsWith("$it/") }
            val inScope = if (own != null) {
                resolved == java.io.File(own).toPath().toRealPath().resolve(file.path.removePrefix(own).removePrefix("/"))
            } else {
                resolved == path && roots.any { root ->
                    runCatching { resolved.startsWith(java.io.File(root).toPath().toRealPath()) }.getOrDefault(false)
                }
            }
            if (!inScope || !canRead(path)) return false
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            val sameType = if (file.isDirectory) attributes.isDirectory else attributes.isRegularFile
            sameType && hasPermission()
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private class StoragePermissionRevoked : RuntimeException()
    private class CategoryResultLimit : RuntimeException()
}
