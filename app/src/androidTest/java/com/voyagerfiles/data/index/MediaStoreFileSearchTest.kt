package com.voyagerfiles.data.index

import android.app.Application
import android.os.Build
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.util.StorageVolumeInfo
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Searches the real MediaStore: the narrowing selection must be accepted and miss nothing. */
class MediaStoreFileSearchTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var folder: File
    private val tag = "zq" + UUID.randomUUID().toString().take(6)

    @Before
    fun setUp() {
        // Android 11's MediaStore doesn't index this app's own new files in time; the search uses it from 12.
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Environment.isExternalStorageManager())
        folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "voyager-$tag").apply { mkdirs() }
        for (name in listOf("Café $tag.txt", "IMG_2024_$tag.txt", "Straße $tag.txt", "Übung-$tag.txt", "other $tag.txt")) {
            folder.resolve(name).writeText(name)
        }
    }

    @After
    fun tearDown() {
        if (::folder.isInitialized) folder.deleteRecursively()
    }

    @Test
    fun accentsCaseAndSeparatorsDoNotMatterAndNothingElseMatches() {
        val search = MediaStoreFileSearch(application)
        val volume = StorageVolumeInfo("Internal", Environment.getExternalStorageDirectory().path, true, false, "mounted")
        fun names(query: String) = search.search("$query $tag", listOf(volume), showHidden = false).map { it.name }.toSet()

        assertEquals(setOf("Café $tag.txt"), names("cafe"))
        assertEquals(setOf("Café $tag.txt"), names("CAFÉ"))
        assertEquals(setOf("IMG_2024_$tag.txt"), names("img2024"))
        assertEquals(setOf("Straße $tag.txt"), names("strasse"))
        assertEquals(setOf("Übung-$tag.txt"), names("ubung"))
        assertEquals(setOf("Übung-$tag.txt"), names("übung"))
        assertEquals(5, names("txt").size)
    }
}
