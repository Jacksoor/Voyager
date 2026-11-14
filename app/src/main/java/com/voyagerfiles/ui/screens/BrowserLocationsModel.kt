package com.voyagerfiles.ui.screens

import androidx.annotation.StringRes
import com.voyagerfiles.R
import com.voyagerfiles.data.model.Bookmark
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.data.model.RemoteConnection
import com.voyagerfiles.util.StorageVolumeInfo
import com.voyagerfiles.viewmodel.BrowserNavigationBounds
import com.voyagerfiles.viewmodel.BrowserSession

/** Location groups that can grow long enough to need a recent-entries cut and a full list. */
internal enum class LocationGroup(@StringRes val labelRes: Int) {
    CONNECTIONS(R.string.browser_locations_connections),
    BOOKMARKS(R.string.home_bookmarks_title),
}

/** Elevated file access offered among the saved connections. */
enum class SystemAccess { ROOT, SHIZUKU }

/** Locations the browser's Sessions sheet can open as a new session without returning Home. A folder some local session is showing right now is omitted, because that session is listed directly above it; tapping any other listed location opens a new session there. Local entries follow the same all-files-access rule as Home. Connections are ordered by last connection and bookmarks by last use, so the overview can show the few most recent entries and leave the rest to the full list. */
data class BrowserLocationsModel(
    val connections: List<RemoteConnection>,
    val bookmarks: List<Bookmark>,
    val storageVolumes: List<StorageVolumeInfo>,
    /** Root (a `su` binary exists) and Shizuku (running), unless a session of that kind is open. */
    val systemAccess: List<SystemAccess> = emptyList(),
) {
    val isEmpty: Boolean
        get() = connections.isEmpty() && bookmarks.isEmpty() && storageVolumes.isEmpty() && systemAccess.isEmpty()

    val recentConnections: List<RemoteConnection>
        get() = connections.take(RECENT_LIMIT)

    val hasMoreConnections: Boolean
        get() = connections.size > RECENT_LIMIT

    val recentBookmarks: List<Bookmark>
        get() = bookmarks.take(RECENT_LIMIT)

    val hasMoreBookmarks: Boolean
        get() = bookmarks.size > RECENT_LIMIT

    companion object {
        /** How many entries of a group the overview shows before deferring to Show all. */
        const val RECENT_LIMIT = 3

        fun forState(
            sessions: List<BrowserSession>,
            connections: List<RemoteConnection>,
            bookmarks: List<Bookmark>,
            storageVolumes: List<StorageVolumeInfo>,
            hasAllFilesAccess: Boolean,
            rootAvailable: Boolean = false,
            shizukuAvailable: Boolean = false,
        ): BrowserLocationsModel {
            val openSources = sessions.mapTo(mutableSetOf()) { it.source }
            // A server with only pinned tabs stays listed: connecting opens an unpinned tab for it.
            val openConnectionIds = sessions.filterNot { it.pinned }.mapNotNull { it.connectionId }.toSet()
            val openLocalRoots = sessions
                .filter { it.source == FileSource.LOCAL }
                .map { BrowserNavigationBounds.normalizePath(it.currentPath) }
                .toSet()
            return BrowserLocationsModel(
                connections = connections
                    .filterNot { it.id in openConnectionIds }
                    .sortedByDescending { it.lastConnected },
                bookmarks = if (hasAllFilesAccess) {
                    bookmarks
                        .filter { bookmark ->
                            bookmark.source == FileSource.LOCAL &&
                                BrowserNavigationBounds.normalizePath(bookmark.path) !in openLocalRoots
                        }
                        .sortedWith(compareByDescending<Bookmark> { it.lastUsedAt }.thenByDescending { it.createdAt })
                } else {
                    emptyList()
                },
                storageVolumes = if (hasAllFilesAccess) {
                    storageVolumes.filter { volume ->
                        val path = volume.path
                        volume.isAvailable &&
                            path != null &&
                            BrowserNavigationBounds.normalizePath(path) !in openLocalRoots
                    }
                } else {
                    emptyList()
                },
                systemAccess = buildList {
                    if (rootAvailable && FileSource.ROOT !in openSources) add(SystemAccess.ROOT)
                    if (shizukuAvailable && FileSource.SHIZUKU !in openSources) add(SystemAccess.SHIZUKU)
                },
            )
        }
    }
}
