package com.voyagerfiles.ui.screens

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Passes Back gestures to [content] through a dispatcher of its own, and on a cancelled gesture
 * first brings the progress back to 0 before cancelling.
 *
 * navigation-compose's NavHost (2.8.5, still in 2.9.5) seeks its screen transition with the
 * gesture, and on cancel animates that seek back to the start itself; that animation can stop
 * part way, leaving the screen shifted off to the side for good. With the progress already at 0
 * when the cancel arrives, the NavHost has nothing left to animate and snaps straight back.
 * See BackTransitionCancelTest.
 */
@Composable
internal fun BackCancelRelay(content: @Composable () -> Unit) {
    val outer = LocalOnBackPressedDispatcherOwner.current
    if (outer == null) {
        content()
        return
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val relay = remember { Relay(scope) }
    val owner = remember(lifecycleOwner) {
        object : OnBackPressedDispatcherOwner {
            override val onBackPressedDispatcher: OnBackPressedDispatcher get() = relay.inner
            override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
        }
    }
    DisposableEffect(outer, lifecycleOwner) {
        outer.onBackPressedDispatcher.addCallback(lifecycleOwner, relay.forward)
        onDispose { relay.forward.remove() }
    }
    CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner, content = content)
}

private class Relay(private val scope: CoroutineScope) {
    private var easing: Job? = null
    private var last: BackEventCompat? = null

    val forward: OnBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            finishEasing()
            last = backEvent
            inner.dispatchOnBackStarted(backEvent)
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            last = backEvent
            inner.dispatchOnBackProgressed(backEvent)
        }

        override fun handleOnBackPressed() {
            finishEasing()
            last = null
            inner.onBackPressed()
        }

        override fun handleOnBackCancelled() {
            val from = last ?: return inner.dispatchOnBackCancelled()
            last = null
            easing = scope.launch {
                animate(from.progress, 0f, animationSpec = tween((from.progress * CANCEL_MS).toInt())) { value, _ ->
                    inner.dispatchOnBackProgressed(BackEventCompat(from.touchX, from.touchY, value, from.swipeEdge))
                }
                inner.dispatchOnBackProgressed(BackEventCompat(from.touchX, from.touchY, 0f, from.swipeEdge))
                // Let the NavHost seek to 0 before it hears of the cancel.
                repeat(2) { withFrameNanos { } }
                easing = null
                inner.dispatchOnBackCancelled()
            }
        }
    }

    /** The relay takes the system's gestures only while something inside wants Back. */
    val inner: OnBackPressedDispatcher = OnBackPressedDispatcher(null) { hasEnabled -> forward.isEnabled = hasEnabled }

    /** A new gesture or a press during the easing ends it: cancel right away from where it is. */
    private fun finishEasing() {
        val job = easing ?: return
        easing = null
        job.cancel()
        inner.dispatchOnBackCancelled()
    }

    private companion object {
        /** How long easing back from a full gesture takes; shorter ones take less. */
        const val CANCEL_MS = 200
    }
}
