package com.voyagerfiles.viewmodel

import com.voyagerfiles.data.model.FileSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class BrowserSessionTest {

    private fun local(currentPath: String) = BrowserSession(
        id = "local#1",
        originLabel = "Internal storage",
        source = FileSource.LOCAL,
        rootPath = "/storage/emulated/0",
        currentPath = currentPath,
    )

    @Test
    fun labelFollowsTheCurrentFolder() {
        assertEquals("Download", local("/storage/emulated/0/Download").label)
        assertEquals("Photos", local("/storage/emulated/0/Download/Photos/").label)
    }

    @Test
    fun labelIsTheOriginAtTheRoot() {
        assertEquals("Internal storage", local("/storage/emulated/0").label)
        val ftp = BrowserSession("remote:1", "FRITZ!Box", FileSource.FTP, rootPath = "/", currentPath = "/", connectionId = 1)
        assertEquals("FRITZ!Box", ftp.label)
        assertEquals("music", ftp.copy(currentPath = "/music").label)
    }

    @Test
    fun aSessionKnowsItsStartAndWhetherItLeftIt() {
        val downloads = local("/storage/emulated/0/Download").copy(startPath = "/storage/emulated/0/Download")
        assertEquals("Download", downloads.startLabel)
        assertEquals(false, downloads.isAwayFromStart)
        val deeper = downloads.copy(currentPath = "/storage/emulated/0/Download/Photos")
        assertEquals(true, deeper.isAwayFromStart)
        assertEquals("Download", deeper.startLabel)
        val fromRoot = local("/storage/emulated/0/DCIM").copy(startPath = "/storage/emulated/0/")
        assertEquals("Internal storage", fromRoot.startLabel)
    }

    @Test
    fun aPinIsANamedPlace() {
        val pin = local("/storage/emulated/0/Pictures/Photos/2024")
            .copy(startPath = "/storage/emulated/0/Pictures/Photos", pinned = true)
        assertEquals("Photos", pin.barLabel)
        assertEquals("2024", pin.label)
        assertEquals("/storage/emulated/0/Pictures/Photos", pin.limitPath)
        val free = pin.copy(pinned = false)
        assertEquals("2024", free.barLabel)
        assertEquals("/storage/emulated/0", free.limitPath)
    }

    @Test
    fun safSessionsKeepTheirName() {
        val saf = BrowserSession(
            "saf:x", "USB drive", FileSource.SAF,
            rootPath = "content://a/tree/b/document/b", currentPath = "content://a/tree/b/document/b%2Fc",
        )
        assertEquals("USB drive", saf.label)
    }

    @Test
    fun containingRootPicksTheDeepestVolume() {
        val roots = listOf("/storage/emulated/0", "/storage/1234-ABCD", "/storage/emulated/0/nested")
        assertEquals("/storage/1234-ABCD", BrowserNavigationBounds.containingRoot("/storage/1234-ABCD/DCIM", roots))
        assertEquals("/storage/emulated/0", BrowserNavigationBounds.containingRoot("/storage/emulated/0/Download/", roots))
        assertEquals("/storage/emulated/0/nested", BrowserNavigationBounds.containingRoot("/storage/emulated/0/nested/x", roots))
        assertNull(BrowserNavigationBounds.containingRoot("/data/user/0/com.voyagerfiles/cache", roots))
        assertNull(BrowserNavigationBounds.containingRoot("/storage/emulated/01", roots))
        assertNull(BrowserNavigationBounds.containingRoot("/x", listOf("/")))
    }

    @Test
    fun aShizukuTabCanBePinnedButNotStartTheAppOrDisconnect() {
        val shizuku = BrowserSession(
            id = "shizuku:/",
            originLabel = "Shizuku",
            source = FileSource.SHIZUKU,
            rootPath = "/",
            currentPath = "/storage/emulated/0/Android",
            pinned = true,
            connected = true,
        )
        assertTrue(shizuku.canPin)
        assertFalse(shizuku.canBeStartTab)
        assertFalse(shizuku.canDisconnect)
        assertFalse(shizuku.copy(source = FileSource.ROOT).canPin)
        assertTrue(shizuku.copy(source = FileSource.SFTP).canDisconnect)
        assertTrue(shizuku.copy(source = FileSource.LOCAL).canBeStartTab)
    }
}
