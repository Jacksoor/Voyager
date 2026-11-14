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
import com.voyagerfiles.data.model.FileTypeFilter
import com.voyagerfiles.util.StorageVolumeInfo
import java.io.File
import java.util.Date
import java.util.Locale
import java.text.Normalizer

/**
 * A source that already knows the files of some volumes, so searching them needs no walk.
 * Volumes it doesn't [cover] are walked as before.
 */
interface IndexedFileSource {
    fun covers(volume: StorageVolumeInfo): Boolean

    /** Files and folders on [volumes] whose name contains every term of [query]. */
    fun search(query: String, volumes: List<StorageVolumeInfo>, showHidden: Boolean): List<FileItem>

    /** Every file (or folder, for [FileTypeFilter.FOLDERS]) of [type] on [volumes]: Quick Access. */
    fun ofType(type: FileTypeFilter, volumes: List<StorageVolumeInfo>, showHidden: Boolean): List<FileItem>

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
        // The selection can only return too many rows; the exact check below drops the extra ones.
        val clauses = terms.map { SearchTerms.selection(MediaStore.Files.FileColumns.DISPLAY_NAME, it) }
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, clauses.joinToString(" AND ") { it.first })
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, clauses.flatMap { it.second.asList() }.toTypedArray())
        }
        return rows(args, volumes, showHidden) { SearchTerms.matches(it.name, terms) }
    }

    // All rows, filtered by the same type rules as the walker; MediaStore's own media types
    // differ in places (e.g. which extensions count as documents).
    override fun ofType(type: FileTypeFilter, volumes: List<StorageVolumeInfo>, showHidden: Boolean): List<FileItem> =
        rows(Bundle(), volumes, showHidden, type::matches)

    private fun rows(args: Bundle, volumes: List<StorageVolumeInfo>, showHidden: Boolean, keep: (FileItem) -> Boolean): List<FileItem> {
        val roots = volumes.mapNotNull { it.path?.trimEnd('/') }
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
                val hidden = SearchTerms.isHiddenBelow(path, root)
                if (hidden && !showHidden) continue
                val isDirectory = c.getInt(format) == FORMAT_ASSOCIATION
                val item = FileItem(
                    name = fileName,
                    path = path,
                    isDirectory = isDirectory,
                    size = if (isDirectory) 0 else c.getLong(size),
                    lastModified = Date(c.getLong(modified) * 1000),
                    isHidden = hidden,
                    source = FileSource.LOCAL,
                )
                if (keep(item)) results += item
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

/**
 * How a search query matches names: every word, anywhere in the name, ignoring case, accents and
 * separators. "cafe" finds "Café", "ubung" finds "Übung", "img2024" finds "IMG_2024.jpg".
 */
object SearchTerms {
    fun parse(query: String): List<String> = query.trim().split(Regex("\\s+")).filter { fold(it).isNotEmpty() }

    fun matches(name: String, terms: List<String>): Boolean {
        val folded = fold(name)
        return terms.all { folded.contains(fold(it)) }
    }

    /**
     * [text] lowercased, without accents (é → e, Ü → u) or separators (space, `.`, `_`, `-`), with
     * the letters that don't decompose spelled out (ß → ss, æ → ae, œ → oe, ø → o, ł → l, đ → d).
     * Plain ASCII, most names, skips the Unicode work.
     */
    fun fold(text: String): String {
        val out = StringBuilder(text.length)
        var ascii = true
        for (c in text) {
            when {
                c.code >= 128 -> { ascii = false; break }
                c.isSeparator() -> Unit
                c in 'A'..'Z' -> out.append(c + 32)
                else -> out.append(c)
            }
        }
        if (ascii) return out.toString()
        out.setLength(0)
        val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        for (c in decomposed) {
            when {
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
                c.isSeparator() -> Unit
                else -> out.append(SPELLED_OUT[c] ?: c.toString())
            }
        }
        return out.toString()
    }

    /**
     * Narrows the MediaStore rows to a superset of the names [term] [matches], before the exact
     * check. ASCII names (most) must contain the term's characters in order, with anything (a
     * separator) between them: LIKE folds ASCII case itself. Names with any other character are
     * all returned and checked in Kotlin: folding them (accents, ß, Hangul syllables into jamo)
     * changes their length, so no pattern narrows them safely, and they are few (152 of 28,520
     * rows on a German phone).
     */
    fun selection(column: String, term: String): Pair<String, Array<String>> =
        "($column LIKE ? ESCAPE '\\' OR $column GLOB ?)" to arrayOf(plainPattern(term), NON_ASCII_GLOB)

    /** The term's characters in order, anything between them: "img2024" finds "IMG_2024". */
    fun plainPattern(term: String): String =
        "%" + fold(term).map { escapeLike(it) }.joinToString("%") + "%"

    private fun escapeLike(c: Char): String = if (c == '%' || c == '_' || c == '\\') "\\$c" else c.toString()

    /** A name with a character outside printable ASCII. */
    private const val NON_ASCII_GLOB = "*[^ -~]*"

    /** Whether a folder or file name below [root] on the way to [path] starts with a dot. */
    fun isHiddenBelow(path: String, root: String): Boolean =
        path.removePrefix(root).split('/').any { it.startsWith('.') }

    /**
     * Best matches first: the exact name, then names starting with the query, then names with a
     * word starting with it, then the rest (all ignoring accents and separators). Within each, a
     * name containing the query as typed comes first, then the most recently changed.
     */
    fun rank(files: List<FileItem>, query: String): List<FileItem> {
        val terms = parse(query)
        val first = terms.firstOrNull()?.let(::fold) ?: return files
        val whole = fold(terms.joinToString(""))
        val typed = query.trim()
        fun score(name: String): Int {
            val folded = fold(name)
            return when {
                folded == whole || fold(name.substringBeforeLast('.', name)) == whole -> 0
                folded.startsWith(first) -> 1
                words(name).any { fold(it).startsWith(first) } -> 2
                else -> 3
            }
        }
        // Each name is scored once; scoring inside the comparator ran the pattern for every comparison.
        return files.map { Triple(it, score(it.name), !it.name.contains(typed, ignoreCase = true)) }
            .sortedWith(
                compareBy<Triple<FileItem, Int, Boolean>> { it.second }.thenBy { it.third }
                    .thenByDescending { it.first.lastModified }.thenBy { it.first.path },
            )
            .map { it.first }
    }

    /** The words of a name: split at separators and brackets, and where lower case turns upper. */
    private fun words(name: String): List<String> = name.split(WORD_BREAK).filter { it.isNotEmpty() }

    private fun Char.isSeparator() = this == ' ' || this == '.' || this == '_' || this == '-' || isWhitespace()

    private val WORD_BREAK = Regex("[\\s._\\-()\\[\\]]+|(?<=\\p{Ll})(?=\\p{Lu})")

    private val SPELLED_OUT = mapOf('ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'ø' to "o", 'ł' to "l", 'đ' to "d", 'þ' to "th")
}
