package com.voyagerfiles.ui.screens

import android.os.Environment
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import com.voyagerfiles.R
import com.voyagerfiles.app.MainActivity
import com.voyagerfiles.app.VoyagerApp
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Back in the real app: up a folder, then out of the browser, with the predictive gesture's chip. */
class BrowserPredictiveBackTest {
    @get:Rule val clearedPins = ClearPinnedSessionsRule()
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val browser get() = (compose.activity.application as VoyagerApp).browserViewModel

    private fun onHome(): Boolean = compose.onAllNodesWithContentDescription(
        compose.activity.getString(R.string.search_storage_title),
    ).fetchSemanticsNodes().isNotEmpty()

    @Test fun backGoesUpFoldersThenLeavesForHome() {
        assumeTrue(Environment.isExternalStorageManager())
        val storage = Environment.getExternalStorageDirectory()
        val download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val inner = File(download, "predictive-back-${System.nanoTime()}").apply { mkdirs() }
        try {
            // Open main storage from Home, then go two folders deeper.
            compose.onNodeWithText("Internal shared storage").performClick()
            compose.waitUntil(10_000) { browser.browseState.value.currentPath == storage.path }
            compose.runOnIdle { browser.navigateTo(inner.path) }
            compose.waitUntil(10_000) { browser.browseState.value.currentPath == inner.path && !browser.browseState.value.isLoading }

            // Back goes up folder by folder, then leaves the browser for Home (the navigation host's
            // handler, which previews Home during a gesture). A real edge swipe was checked by hand:
            // whether a scripted swipe counts as the system gesture depends on the emulator.
            Espresso.pressBack()
            compose.waitUntil(10_000) { browser.browseState.value.currentPath == download.path }
            Espresso.pressBack()
            compose.waitUntil(10_000) { browser.browseState.value.currentPath == storage.path }
            Espresso.pressBack()
            compose.waitUntil(10_000) { onHome() }
        } finally {
            inner.deleteRecursively()
        }
    }
}
