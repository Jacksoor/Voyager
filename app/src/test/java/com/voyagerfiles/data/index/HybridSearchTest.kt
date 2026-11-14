package com.voyagerfiles.data.index

import com.voyagerfiles.data.model.FileItem
import com.voyagerfiles.ui.screens.searchLocationLabel
import com.voyagerfiles.util.StorageVolumeInfo
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridSearchTest {

    @Test
    fun everyWordMustMatchAnywhereIgnoringCaseIncludingUmlauts() {
        val terms = SearchTerms.parse("  ÜBUNG  2024 ")
        assertEquals(listOf("ÜBUNG", "2024"), terms)
        assertTrue(SearchTerms.matches("Mathe-übung_2024.pdf", terms))
        assertFalse(SearchTerms.matches("Mathe-übung.pdf", terms))
        assertFalse(SearchTerms.isAscii("übung"))
        assertTrue(SearchTerms.isAscii("IMG_2024"))
    }

    @Test
    fun likePatternsEscapeLikesOwnCharacters() {
        assertEquals("%IMG\\_2024%", SearchTerms.likePattern("IMG_2024"))
        assertEquals("%100\\%%", SearchTerms.likePattern("100%"))
        assertEquals("%a\\\\b%", SearchTerms.likePattern("a\\b"))
    }

    @Test
    fun hiddenMeansADotNameBelowTheVolumeRoot() {
        val root = "/storage/emulated/0"
        assertTrue(SearchTerms.isHiddenBelow("$root/Pictures/.thumbnails/1.jpg", root))
        assertTrue(SearchTerms.isHiddenBelow("$root/.config", root))
        assertFalse(SearchTerms.isHiddenBelow("$root/Pictures/a.jpg", root))
    }

    @Test
    fun exactNamesComeFirstThenPrefixesThenWordStartsThenTheRestNewestFirst() {
        fun item(name: String, time: Long) = FileItem(name, "/s/$name", false, lastModified = Date(time))
        val ranked = SearchTerms.rank(
            listOf(
                item("old-report.txt", 5),
                item("myreport.txt", 9),
                item("report.txt", 1),
                item("Report 2024.pdf", 2),
                item("Report 2023.pdf", 3),
                item("TaxReport.pdf", 4),
            ),
            "report",
        )
        assertEquals(
            listOf("report.txt", "Report 2023.pdf", "Report 2024.pdf", "old-report.txt", "TaxReport.pdf", "myreport.txt"),
            ranked.map { it.name },
        )
    }

    @Test
    fun repairListsGoneRowsTopmostFirstAndMissingFilesOutsideNomediaAndDotFolders() {
        val root = "/s"
        val plan = MediaStoreRepair.plan(
            roots = listOf(root),
            indexed = setOf("/s/a.jpg", "/s/gone", "/s/gone/inner.jpg", "/s/gone-file.txt", "/s/Android/data/x"),
            visitedFiles = setOf(
                "/s/a.jpg",
                "/s/saves/save1.sav",
                "/s/Games/game/.nomedia",
                "/s/Games/game/sub/sprite.png",
                "/s/Pictures/.thumbnails/1.jpg",
            ),
            visitedDirs = setOf("/s/saves", "/s/Games", "/s/Games/game", "/s/Games/game/sub", "/s/Pictures", "/s/Pictures/.thumbnails"),
            // Android/data can't be listed, but it exists.
            exists = { it == "/s/Android/data/x" },
        )
        assertEquals(listOf("/s/gone", "/s/gone-file.txt", "/s/saves/save1.sav"), plan)
    }

    @Test
    fun repairIsCappedPerSearch() {
        val files = (1..1000).mapTo(HashSet()) { "/s/f$it.bin" }
        val plan = MediaStoreRepair.plan(listOf("/s"), emptySet(), files, emptySet()) { true }
        assertEquals(MediaStoreRepair.MAX_PATHS, plan.size)
    }

    @Test
    fun locationsReadFromTheVolumeDown() {
        val volumes = listOf(
            StorageVolumeInfo("Internal shared storage", "/storage/emulated/0", true, false, "mounted"),
            StorageVolumeInfo("SD card", "/storage/1234-ABCD", false, true, "mounted"),
        )
        assertEquals("DCIM › Camera", searchLocationLabel("/storage/emulated/0/DCIM/Camera/a.jpg", volumes, "Internal storage"))
        assertEquals("Internal storage", searchLocationLabel("/storage/emulated/0/a.jpg", volumes, "Internal storage"))
        assertEquals("SD card › Music", searchLocationLabel("/storage/1234-ABCD/Music/b.mp3", volumes, "Internal storage"))
        assertEquals("/data/other", searchLocationLabel("/data/other/c.txt", volumes, "Internal storage"))
    }
}
