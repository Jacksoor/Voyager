package com.voyagerfiles.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserBackModelTest {

    private fun target(selection: Boolean = false, search: Boolean = false, up: String? = null) =
        backTarget(selection, search, up) { it.substringAfterLast('/') }

    @Test
    fun backClearsASelectionFirst() {
        assertEquals(BackTarget.ClearSelection, target(selection = true, search = true, up = "/a"))
    }

    @Test
    fun thenItClosesCompactSearch() {
        assertEquals(BackTarget.CloseSearch, target(search = true, up = "/a"))
    }

    @Test
    fun thenItGoesUpAFolderNamedForTheChip() {
        assertEquals(BackTarget.Folder("Photos", "/storage/emulated/0/Photos"), target(up = "/storage/emulated/0/Photos"))
    }

    @Test
    fun atTheTabsLimitItLeaves() {
        assertEquals(BackTarget.Leave, target())
    }
}
