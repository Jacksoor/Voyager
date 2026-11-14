package com.voyagerfiles.data.index

import android.content.Context
import android.media.MediaScannerConnection
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Paths whose MediaStore rows are wrong, found by comparing a full walk with MediaStore. Scanning
 * them fixes the index for every app that reads it: a gone path loses its row (a gone folder
 * loses the rows below it), a file written past FUSE (e.g. by Shizuku) gains one.
 */
object MediaStoreRepair {
    /** At most this many paths per search, so a large mismatch can't keep the scanner busy. */
    const val MAX_PATHS = 300

    /**
     * What to scan after a walk of [roots] that saw [visitedFiles] and [visitedDirs], given what
     * MediaStore lists ([indexed]). Gone paths are confirmed with [exists]; of nested gone
     * folders only the topmost is listed. Files MediaStore leaves out on purpose are skipped:
     * anything inside a folder with a `.nomedia` file or below a dot-folder.
     */
    fun plan(
        roots: List<String>,
        indexed: Set<String>,
        visitedFiles: Set<String>,
        visitedDirs: Set<String>,
        exists: (String) -> Boolean,
    ): List<String> {
        val nomediaDirs = visitedFiles.filter { it.endsWith("/.nomedia") }.mapTo(HashSet()) { it.substringBeforeLast('/') }
        fun excluded(path: String): Boolean {
            val root = roots.firstOrNull { path.startsWith("$it/") } ?: return true
            if (SearchTerms.isHiddenBelow(path, root)) return true
            var dir = path.substringBeforeLast('/')
            while (dir.length >= root.length) {
                if (dir in nomediaDirs) return true
                dir = dir.substringBeforeLast('/')
            }
            return false
        }
        val missing = visitedFiles.asSequence().filter { it !in indexed && !excluded(it) }
        val gone = indexed.asSequence()
            .filter { it !in visitedFiles && it !in visitedDirs && roots.any { root -> it.startsWith("$root/") } }
            .filter { !exists(it) }
            .toSortedSet()
        // A gone folder's scan removes everything below it.
        val topGone = gone.filter { path ->
            var parent = path.substringBeforeLast('/')
            var covered = false
            while ('/' in parent && !covered) {
                covered = parent in gone
                parent = parent.substringBeforeLast('/')
            }
            !covered
        }
        return (topGone.asSequence() + missing).take(MAX_PATHS).toList()
    }

    /** Asks the media scanner to look at [paths]; returns once it reported all of them, or after 30 s. */
    suspend fun scan(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine { done ->
                var left = paths.size
                MediaScannerConnection.scanFile(context.applicationContext, paths.toTypedArray(), null) { _, _ ->
                    synchronized(this@MediaStoreRepair) {
                        left--
                        if (left == 0 && done.isActive) done.resume(Unit)
                    }
                }
            }
        }
    }
}
