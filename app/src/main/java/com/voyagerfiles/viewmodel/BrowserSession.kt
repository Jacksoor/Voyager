package com.voyagerfiles.viewmodel

import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.isNetwork

/**
 * One open place in the browser, like a browser tab. It starts at [startPath] (a bookmark, a
 * connection's remote folder, a storage volume) and can move anywhere at or below [rootPath]:
 * the storage volume holding the start folder, or the server root for a connection.
 *
 * A pinned session is a named place instead: [startPath] is the pinned folder, the session stays
 * at or below it ([limitPath]) and its label keeps the pinned folder's name ([barLabel]).
 */
data class BrowserSession(
    val id: String,
    /** Names where the session lives: the storage volume or the connection. */
    val originLabel: String,
    val source: FileSource,
    val rootPath: String,
    val currentPath: String,
    val startPath: String = currentPath,
    val connectionId: Long? = null,
    val host: String? = null,
    /** Increases each time the session is shown, so the most recently used ones can be picked. */
    val lastUsed: Long = 0L,
    /** Kept in the session bar, safe from closing, and reopened after a restart. */
    val pinned: Boolean = false,
    /** When it was pinned, as a rising number: pins keep this order so older pins never move. */
    val pinOrder: Long = 0L,
    /** The SAF tree this session was opened from, needed to reopen it after a restart. */
    val treeUri: String? = null,
    /** For server sessions: a connection is open right now. Browsing again reconnects. */
    val connected: Boolean = false,
    /** A local session on removable storage (an SD card or USB drive). */
    val removable: Boolean = false,
    /** The pinned tab the app opens on launch; at most one session has it. */
    val isStartTab: Boolean = false,
) {
    /** Servers and Shizuku keep a connection open that can be dropped; root runs `su` per action. */
    val hasConnection: Boolean
        get() = source.isNetwork || source == FileSource.SHIZUKU

    /**
     * Root asks for `su` on every start, so its sessions are not kept. A Shizuku tab comes back
     * disconnected and connects again when it is opened, like a server tab.
     */
    val canPin: Boolean
        get() = source != FileSource.ROOT

    /** The start tab opens on launch without asking: Shizuku may need its permission first. */
    val canBeStartTab: Boolean
        get() = pinned && source != FileSource.SHIZUKU

    /** Disconnect keeps a server tab and frees the server; for Shizuku it would be the same as closing. */
    val canDisconnect: Boolean
        get() = source.isNetwork && connected

    /** The current folder's name, or [originLabel] at the root and where paths are not readable (SAF). */
    val label: String
        get() {
            if (source == FileSource.SAF || currentPath.contains("://")) return originLabel
            val normalized = BrowserNavigationBounds.normalizePath(currentPath)
            if (normalized == BrowserNavigationBounds.normalizePath(rootPath)) return originLabel
            return normalized.substringAfterLast("/").ifEmpty { originLabel }
        }

    /** The start folder's name, or [originLabel] when the session started at its root. */
    val startLabel: String
        get() = copy(currentPath = startPath).label

    /** How far up this session may go: the pinned folder for a pin, else the volume or server root. */
    val limitPath: String
        get() = if (pinned) startPath else rootPath

    /** The name shown on its tab: a pin keeps its pinned folder's name, other tabs follow the folder. */
    val barLabel: String
        get() = if (pinned) startLabel else label

    /** It has moved away from where it was opened, so it can be sent back there. */
    val isAwayFromStart: Boolean
        get() = BrowserNavigationBounds.normalizePath(currentPath) != BrowserNavigationBounds.normalizePath(startPath)

    val description: String
        get() {
            if (source == FileSource.LOCAL || source == FileSource.SAF) return currentPath
            val hostLabel = host?.takeIf { it.isNotBlank() }
            return listOfNotNull(source.name, hostLabel, currentPath).joinToString(" - ")
        }
}
