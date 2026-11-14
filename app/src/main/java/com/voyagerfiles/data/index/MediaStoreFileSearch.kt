package com.voyagerfiles.data.index

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.util.StorageVolumeInfo
import java.io.File
import java.util.Date

/**
 * A source that already knows the files of some volumes, so searching them needs no walk.
 * Volumes it doesn't [cover] are walked as before.
 */
interface IndexedFileSource {
    fun covers(volume: StorageVolumeInfo): Boolean

    /** Files and folders on [volumes] whose name contains every term of [query]. */
    fun search(query: String, volumes: List<StorageVolumeInfo>, showHidden: Boolean): List<FileItem>

    /** Every path it knows on [volumes], to compare with a walk. */
    fun allPaths(volumes: List<StorageVolumeInfo>): Set<String>
}

/**
 * Android's own index of shared storage. It answers a name query in tens of milliseconds where a
 * walk takes seconds, but leaves out folders with a `.nomedia` file (and anything written past
 * FUSE, e.g. by Shizuku), so those are only found by walking.
 */
@RequiresApi(Build.VERSION_CODES.R)
class MediaStoreFileSearch(private val context: Context) : IndexedFileSource {
    private val resolver: ContentResolver get() = context.contentResolver
    private val uri get() = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    override fun covers(volume: StorageVolumeInfo): Boolean {
        val path = volume.path ?: return false
        if (!volume.isAvailable) return false
        val name = runCatching {
            context.getSystemService(StorageManager::class.java).getStorageVolume(File(path))?.mediaStoreVolumeName
        }.getOrNull() ?: return false
        return name in MediaStore.getExternalVolumeNames(context)
    }

    override fun search(query: String, volumes: List<StorageVolumeInfo>, showHidden: Boolean): List<FileItem> {
        val terms = SearchTerms.parse(query)
        if (terms.isEmpty()) return emptyList()
        val roots = volumes.mapNotNull { it.path?.trimEnd('/') }
        val clauses = terms.filter(SearchTerms::isAscii)
        val args = Bundle().apply {
            if (clauses.isNotEmpty()) {
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    clauses.joinToString(" AND ") { "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'" },
                )
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, clauses.map(SearchTerms::likePattern).toTypedArray())
            }
        }
        val results = mutableListOf<FileItem>()
        resolver.query(uri, PROJECTION, args, null)?.use { c ->
            val data = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val name = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val size = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val modified = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val format = c.getColumnIndexOrThrow(FORMAT)
            while (c.moveToNext()) {
                val path = c.getString(data) ?: continue
                val root = roots.firstOrNull { path == it || path.startsWith("$it/") } ?: continue
                val fileName = c.getString(name) ?: path.substringAfterLast('/')
                // LIKE folds ASCII letters only; this also covers umlauts and the non-ASCII terms.
                if (!SearchTerms.matches(fileName, terms)) continue
                val hidden = SearchTerms.isHiddenBelow(path, root)
                if (hidden && !showHidden) continue
                val isDirectory = c.getInt(format) == FORMAT_ASSOCIATION
                results += FileItem(
                    name = fileName,
                    path = path,
                    isDirectory = isDirectory,
                    size = if (isDirectory) 0 else c.getLong(size),
                    lastModified = Date(c.getLong(modified) * 1000),
                    isHidden = hidden,
                    source = FileSource.LOCAL,
                )
            }
        }
        return results
    }

    override fun allPaths(volumes: List<StorageVolumeInfo>): Set<String> {
        val roots = volumes.mapNotNull { it.path?.trimEnd('/') }
        val paths = HashSet<String>()
        resolver.query(uri, arrayOf(MediaStore.Files.FileColumns.DATA), Bundle(), null)?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0) ?: continue
                if (roots.any { path == it || path.startsWith("$it/") }) paths += path
            }
        }
        return paths
    }

    private companion object {
        // MediaStore.Files.FileColumns.FORMAT is hidden in the SDK; its column name is public API.
        const val FORMAT = "format"
        /** MTP's "association" format: a folder. */
        const val FORMAT_ASSOCIATION = 0x3001
        val PROJECTION = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            FORMAT,
        )
    }
}

/** How a search query matches names: every word, anywhere in the name, ignoring case. */
object SearchTerms {
    fun parse(query: String): List<String> = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun matches(name: String, terms: List<String>): Boolean = terms.all { name.contains(it, ignoreCase = true) }

    fun isAscii(term: String): Boolean = term.all { it.code < 128 }

    /** A LIKE pattern for [term] anywhere, with LIKE's own characters escaped by `\`. */
    fun likePattern(term: String): String =
        "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    /** Whether a folder or file name below [root] on the way to [path] starts with a dot. */
    fun isHiddenBelow(path: String, root: String): Boolean =
        path.removePrefix(root).split('/').any { it.startsWith('.') }

    /**
     * Best matches first: the exact name, then names starting with the query, then names with a
     * word starting with it, then the rest; within each, the most recently changed first.
     */
    fun rank(files: List<FileItem>, query: String): List<FileItem> {
        val terms = parse(query)
        val first = terms.firstOrNull() ?: return files
        val whole = terms.joinToString(" ")
        fun score(name: String): Int {
            val stem = name.substringBeforeLast('.', name)
            return when {
                name.equals(whole, ignoreCase = true) || stem.equals(whole, ignoreCase = true) -> 0
                name.startsWith(first, ignoreCase = true) -> 1
                WORD_START.findAll(name).any { name.startsWith(first, it.range.first, ignoreCase = true) } -> 2
                else -> 3
            }
        }
        // Each name is scored once; scoring inside the comparator ran the pattern for every comparison.
        return files.map { it to score(it.name) }
            .sortedWith(compareBy<Pair<FileItem, Int>> { it.second }.thenByDescending { it.first.lastModified }.thenBy { it.first.path })
            .map { it.first }
    }

    /** Where a word starts inside a name: after a separator, or at a lower-to-upper case change. */
    private val WORD_START = Regex("(?<=[\\s._\\-()\\[\\]])\\S|(?<=\\p{Ll})\\p{Lu}")
}
