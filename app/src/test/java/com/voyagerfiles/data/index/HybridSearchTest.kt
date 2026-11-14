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
    fun everyWordMustMatchAnywhereIgnoringCaseAccentsAndSeparators() {
        val terms = SearchTerms.parse("  ÜBUNG  2024 ")
        assertEquals(listOf("ÜBUNG", "2024"), terms)
        assertTrue(SearchTerms.matches("Mathe-übung_2024.pdf", terms))
        assertFalse(SearchTerms.matches("Mathe-übung.pdf", terms))
        assertTrue(SearchTerms.matches("IMG_20240317.jpg", SearchTerms.parse("img2024")))
        assertTrue(SearchTerms.matches("Café de Flore.jpg", SearchTerms.parse("cafe deflore")))
        assertTrue(SearchTerms.matches("Straße.txt", SearchTerms.parse("strasse")))
        assertTrue(SearchTerms.matches("Strasse.txt", SearchTerms.parse("straße")))
        // ue for ü is not folded: Ü is a u with an accent, not "ue".
        assertFalse(SearchTerms.matches("Übung.pdf", SearchTerms.parse("uebung")))
        // Only separators is nothing to search for.
        assertTrue(SearchTerms.parse(" - _ ").isEmpty())
    }

    @Test
    fun foldingDropsAccentsCaseAndSeparatorsAndSpellsOutTheRest() {
        assertEquals("matheubung2024pdf", SearchTerms.fold("Mathe-Übung_2024.PDF"))
        assertEquals("cafe", SearchTerms.fold("Café"))
        assertEquals("strasse", SearchTerms.fold("Straße"))
        assertEquals("aero", SearchTerms.fold("Ærø"))
        assertEquals("привет", SearchTerms.fold("Привет"))
    }

    @Test
    fun patternsNarrowMediaStoreWithoutMissingAMatch() {
        // ASCII names: the characters in order, separators allowed between them; LIKE folds case.
        assertEquals("%i%m%g%2%0%2%4%", SearchTerms.plainPattern("IMG_2024"))
        assertEquals("%1%0%0%\\%%", SearchTerms.plainPattern("100%"))
        // Other names are all returned and checked in Kotlin.
        val (selection, args) = SearchTerms.selection("name", "cafe")
        assertEquals("(name LIKE ? ESCAPE '\\' OR name GLOB ?)", selection)
        assertEquals(listOf("%c%a%f%e%", "*[^ -~]*"), args.toList())
    }

    @Test
    fun everyNameTheExactCheckAcceptsIsReturnedByTheSelection() {
        val names = listOf(
            "IMG_2024-03-17.jpg", "Report (final).PDF", "100% done.txt", "a_b.txt", "Café de Flore.jpg", "Übung.pdf",
            "Straße.txt", "Ærø.png", "사진", "사진.md", "dŵr.txt", "ĵaŭdo.odt", "Þór.txt", "Привет.doc", "😀 party.jpg",
            "ﬁle.txt", "İstanbul.jpg", "KELVIN.txt",
        )
        val queries = listOf(
            "img2024", "img 03", "final", "100%", "a_b", "cafe", "ubung", "strasse", "straße", "aero", "사진", "사", "dwr",
            "jaudo", "thor", "þór", "привет", "party", "😀", "istanbul", "kelvin", "jpg",
        )
        for (query in queries) {
            val terms = SearchTerms.parse(query)
            for (name in names.filter { SearchTerms.matches(it, terms) }) {
                for (term in terms) {
                    val (_, args) = SearchTerms.selection("name", term)
                    assertTrue("\"$query\" must reach \"$name\"", sqlLike(name, args[0]) || sqlGlobNonAscii(name))
                }
            }
        }
    }

    /** SQLite's LIKE with ESCAPE '\': `%` any run, `_` one code point, ASCII letters folded. */
    private fun sqlLike(text: String, pattern: String): Boolean {
        val regex = StringBuilder()
        val points = pattern.codePoints().toArray()
        var i = 0
        while (i < points.size) {
            val c = points[i]
            when {
                c == '\\'.code && i + 1 < points.size -> { i++; regex.append(literal(points[i])) }
                c == '%'.code -> regex.append("(?s:.*)")
                c == '_'.code -> regex.append("(?s:.)")
                else -> regex.append(literal(c))
            }
            i++
        }
        return Regex(regex.toString()).matches(text)
    }

    private fun literal(c: Int): String {
        val ch = String(Character.toChars(c))
        return if (c < 128 && Character.isLetter(c)) "[${ch.lowercase()}${ch.uppercase()}]" else Regex.escape(ch)
    }

    /** `GLOB '*[^ -~]*'`: some code point outside printable ASCII. */
    private fun sqlGlobNonAscii(text: String) = text.codePoints().anyMatch { it < 0x20 || it > 0x7E }

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
    fun nothingBelowAnUnreadableFolderCountsAsGoneAndDeclinedPathsAreNotSentAgain() {
        val plan = MediaStoreRepair.plan(
            roots = listOf("/s"),
            indexed = setOf("/s/Music/a.mp3", "/s/Music/sub/b.mp3", "/s/Music", "/s/old.txt", "/s/stuck.txt"),
            visitedFiles = setOf("/s/new.txt", "/s/refused.txt"),
            visitedDirs = emptySet(),
            // The walk couldn't list Music (a card failing mid-walk): its rows may well be fine.
            unreadable = setOf("/s/Music"),
            declined = setOf("/s/stuck.txt", "/s/refused.txt"),
            exists = { false },
        )
        assertEquals(listOf("/s/old.txt", "/s/new.txt"), plan)
        // Counted as fixed only if MediaStore changed: the rest is declined for this process.
        val before = setOf("/s/old.txt")
        assertEquals(1, MediaStoreRepair.settle(listOf("/s/old.txt", "/s/new.txt"), indexedBefore = before, indexedAfter = setOf("/s/old.txt", "/s/new.txt")))
        assertTrue("/s/old.txt" in MediaStoreRepair.declinedSnapshot())
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

    @Test
    fun aNameAsTypedRanksAboveOneThatOnlyMatchesWithoutAccents() {
        fun item(name: String, time: Long) = FileItem(name, "/s/$name", false, lastModified = Date(time))
        assertEquals(
            listOf("café.jpg", "cafe.jpg"),
            SearchTerms.rank(listOf(item("cafe.jpg", 9), item("café.jpg", 1)), "café").map { it.name },
        )
    }
}
