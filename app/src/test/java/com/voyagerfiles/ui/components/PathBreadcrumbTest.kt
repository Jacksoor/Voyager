package com.voyagerfiles.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PathBreadcrumbTest {

    @Test
    fun sessionRootShowsAsItsLabelAndHidesWhatIsAbove() {
        assertEquals(
            listOf(
                Crumb("Internal storage", "/storage/emulated/0"),
                Crumb("Download", "/storage/emulated/0/Download"),
                Crumb("Photos", "/storage/emulated/0/Download/Photos"),
            ),
            breadcrumbs("/storage/emulated/0/Download/Photos", "/storage/emulated/0", "Internal storage"),
        )
        assertEquals(
            listOf(Crumb("Internal storage", "/storage/emulated/0")),
            breadcrumbs("/storage/emulated/0/", "/storage/emulated/0", "Internal storage"),
        )
    }

    @Test
    fun withoutASessionRootTheBarStartsAtSlash() {
        assertEquals(
            listOf(Crumb("/", "/"), Crumb("pub", "/pub"), Crumb("music", "/pub/music")),
            breadcrumbs("/pub/music", "/", "fritz.box"),
        )
        assertEquals(listOf(Crumb("/", "/"), Crumb("a", "/a")), breadcrumbs("/a"))
    }

    @Test
    fun partsAboveAPinnedFolderAreMarkedOutside() {
        val crumbs = breadcrumbs(
            "/storage/emulated/0/Pictures/Photos/2024", "/storage/emulated/0", "Storage",
            limitPath = "/storage/emulated/0/Pictures/Photos",
        )
        assertEquals(listOf(true, true, false, false), crumbs.map { it.outside })
        assertEquals(listOf("Storage", "Pictures", "Photos", "2024"), crumbs.map { it.label })
    }

    @Test
    fun aPathOutsideTheRootFallsBackToSlash() {
        assertEquals(listOf(Crumb("/", "/"), Crumb("data", "/data")), breadcrumbs("/data", "/storage/emulated/0", "Internal"))
    }
}
