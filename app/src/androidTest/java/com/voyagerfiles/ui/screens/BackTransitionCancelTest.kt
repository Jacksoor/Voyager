package com.voyagerfiles.ui.screens

import android.os.Environment
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import com.voyagerfiles.viewmodel.FileBrowserViewModel
import org.junit.Assert.assertEquals
import androidx.navigation.compose.composable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

/**
 * A Back gesture that is started and then cancelled must leave the browser exactly where it was.
 * The user saw it stuck part off screen after cancelling the gesture that leaves for Home.
 */
class BackTransitionCancelTest {

    @get:Rule val clearedPins = ClearPinnedSessionsRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun homeButtonX(): Float =
        compose.onNode(hasContentDescription("Go to Home")).fetchSemanticsNode().positionInRoot.x

    private fun gesture(progresses: List<Float>, edge: Int, commit: Boolean = false) {
        val dispatcher = compose.activity.onBackPressedDispatcher
        val x = if (edge == BackEventCompat.EDGE_LEFT) 0f else 1000f
        compose.runOnIdle { dispatcher.dispatchOnBackStarted(BackEventCompat(x, 1200f, 0f, edge)) }
        progresses.forEach { progress ->
            compose.runOnIdle { dispatcher.dispatchOnBackProgressed(BackEventCompat(x, 1200f, progress, edge)) }
            compose.mainClock.advanceTimeBy(16)
        }
        compose.runOnIdle { if (commit) dispatcher.onBackPressed() else dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
    }

    @Test fun cancelledBackToHomeLeavesTheBrowserInPlace() {
        val viewModel = FileBrowserViewModel(compose.activity.application)
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        compose.setContent { MaterialTheme { AppNavigation(viewModel, hasAllFilesAccess = true, onRequestAllFilesAccess = {}) } }
        compose.waitUntil(10_000) { viewModel.pinsRestored.value }
        compose.runOnIdle { viewModel.openLocalFolder(downloads) }
        compose.waitUntil(10_000) { viewModel.activeSession.value?.startPath == downloads }
        // A pin at its folder: Back leaves the browser, so the navigation host animates it.
        compose.runOnIdle { viewModel.setSessionPinned(viewModel.activeSession.value!!.id, true) }

        repeat(2) { round ->
            // Open it from Home, as the user did right before the bug.
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Download"))
            compose.onNode(hasText("Download") and hasClickAction()).performClick()
            compose.waitUntil(10_000) { browserShown() }
            val restingX = homeButtonX()

            for (edge in listOf(BackEventCompat.EDGE_RIGHT, BackEventCompat.EDGE_LEFT)) {
                for (steps in listOf(listOf(0.02f, 0.05f), listOf(0.05f, 0.1f, 0.15f), listOf(0.1f, 0.3f, 0.5f))) {
                    gesture(steps, edge)
                    // Give the cancel animation time to finish; stuck means it never gets back.
                    runCatching { compose.waitUntil(3_000) { kotlin.math.abs(homeButtonX() - restingX) < 1f } }
                    assertEquals("round $round edge $edge steps $steps", restingX, homeButtonX(), 1f)
                }
            }
            // Leave for Home with a completed gesture, then go again.
            gesture(listOf(0.2f, 0.6f), BackEventCompat.EDGE_RIGHT, commit = true)
            compose.waitUntil(10_000) { !browserShown() }
        }
    }

    /**
     * The same gestures on a bare NavHost with the app's Back transition. Without [BackCancelRelay]
     * this fails too (navigation-compose 2.8.5 to 2.9.5): the bug is in the library.
     */
    @Test fun cancelledBackOnABareNavHost() {
        lateinit var nav: androidx.navigation.NavHostController
        compose.setContent {
            nav = androidx.navigation.compose.rememberNavController()
            BackCancelRelay { androidx.navigation.compose.NavHost(
                nav, startDestination = "a",
                popEnterTransition = {
                    slideIntoContainer(androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.End, androidx.compose.animation.core.tween(220)) +
                        androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(220), initialScale = 0.92f)
                },
                popExitTransition = {
                    slideOutOfContainer(androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.End, androidx.compose.animation.core.tween(220)) +
                        androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(220), targetScale = 0.92f)
                },
            ) {
                composable("a") { androidx.compose.material3.Text("Screen A") }
                composable("b") {
                    androidx.compose.material3.Text("Screen B", androidx.compose.ui.Modifier.padding(start = 100.dp))
                }
            } }
        }
        compose.runOnIdle { nav.navigate("b") }
        compose.waitForIdle()
        fun bX() = compose.onNode(hasText("Screen B")).fetchSemanticsNode().positionInRoot.x
        val restingX = bX()
        for (edge in listOf(BackEventCompat.EDGE_RIGHT, BackEventCompat.EDGE_LEFT)) {
            for (steps in listOf(listOf(0.02f, 0.05f), listOf(0.05f, 0.1f, 0.15f), listOf(0.1f, 0.3f, 0.5f))) {
                gesture(steps, edge)
                runCatching { compose.waitUntil(3_000) { kotlin.math.abs(bX() - restingX) < 1f } }
                assertEquals("bare edge $edge steps $steps", restingX, bX(), 1f)
            }
        }
    }

    private fun browserShown() =
        compose.onAllNodes(hasContentDescription("Go to Home")).fetchSemanticsNodes().isNotEmpty()
}
