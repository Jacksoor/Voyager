package com.voyagerfiles.ui.screens

import android.content.ContextWrapper
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.voyagerfiles.R
import kotlin.math.abs
import kotlin.math.sign
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow

internal const val BACK_CHIP_TEST_TAG = "browser-back-chip"

/** The hosting activity as lifecycle owner, or the current one where there is no such activity. */
@Composable
internal fun activityLifecycleOwner(): LifecycleOwner {
    var context = LocalContext.current
    while (context is ContextWrapper) {
        if (context is LifecycleOwner) return context
        context = context.baseContext
    }
    return LocalLifecycleOwner.current
}

/** What Back does in the browser right now, so the predictive gesture can show it. */
sealed interface BackTarget {
    data object ClearSelection : BackTarget
    data object CloseSearch : BackTarget
    /** Up one folder, to [name] at [path]. */
    data class Folder(val name: String, val path: String) : BackTarget
    /** Leaves the browser (for Home, or search when it came from there). */
    data object Leave : BackTarget
}

/**
 * Back's rules in order: clear a selection, close compact search, go up a folder ([upPath] is
 * null at the tab's limit: its pinned folder, or its start or root), else leave the browser.
 */
internal fun backTarget(
    selectionActive: Boolean,
    compactSearchOpen: Boolean,
    upPath: String?,
    folderName: (String) -> String,
): BackTarget = when {
    selectionActive -> BackTarget.ClearSelection
    compactSearchOpen -> BackTarget.CloseSearch
    upPath != null -> BackTarget.Folder(folderName(upPath), upPath)
    else -> BackTarget.Leave
}

/** How much the screen shrinks and slides while a Back gesture to a folder is held (Material's values). */
internal fun Modifier.predictiveBackShift(progress: Float, fromLeft: Boolean, active: Boolean): Modifier =
    if (!active || progress <= 0f) this else graphicsLayer {
        val eased = FastOutSlowInEasing.transform(progress)
        val scale = 1f - 0.08f * eased
        scaleX = scale
        scaleY = scale
        translationX = (if (fromLeft) 1f else -1f) * 24.dp.toPx() * eased
        shape = RoundedCornerShape(28.dp * eased)
        clip = true
    }

/** How long a Back gesture is held before its chip shows, so a quick swipe doesn't flash it. */
internal const val BACK_CHIP_DELAY_MS = 120L

/*
 * The system's back arrow (AOSP SystemUI, BackPanelController.setVerticalTranslation): anchored at
 * the touch-down height, it follows the finger through a stiff rubber band, at most half of the
 * panel's height (268 dp) minus the arrow's background (48 dp). A 100 dp finger move shifts it by
 * about 15 dp. The chip uses the same rules so it stays beside the arrow.
 */
internal val BACK_ARROW_MAX_Y_SHIFT = 110.dp
private const val BACK_ARROW_RUBBERBAND = 15f
private val BACK_ARROW_Y_EASING = CubicBezierEasing(0.5f, 1.15f, 0.41f, 0.94f)

/** How far the chip moves for a finger [offset] from where the swipe started (both in px). */
internal fun backChipYShift(offset: Float, maxShift: Float): Float {
    if (maxShift <= 0f || offset == 0f) return 0f
    val progress = (abs(offset) / (maxShift * BACK_ARROW_RUBBERBAND)).coerceIn(0f, 1f)
    return BACK_ARROW_Y_EASING.transform(progress) * maxShift * sign(offset)
}

/** How far above the finger the chip sits, so the finger doesn't cover it. */
private val BACK_CHIP_LIFT = 64.dp

/**
 * The chip naming where a held Back gesture leads ("Photos", "Clear selection"). It sits next to
 * the edge the swipe starts from, a little above [touchY] (window pixels; NaN centres it), and
 * fades and slides in once [visible] (see [BACK_CHIP_DELAY_MS]).
 */
@Composable
internal fun BackDestinationChip(
    target: BackTarget,
    progress: Float,
    fromLeft: Boolean,
    /** Space taken at the start edge (the session rail), so the chip sits over the content. */
    startInset: Dp = 0.dp,
    touchY: Float = Float.NaN,
    visible: Boolean = true,
) {
    val active = visible && progress > 0f
    // In from the swipe edge while the gesture is held; back out the same way when it ends.
    val shown by animateFloatAsState(
        if (active) 1f else 0f,
        if (active) {
            tween(220, easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f))
        } else {
            tween(160, easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f))
        },
        label = "backChip",
    )
    val currentText = when (target) {
        BackTarget.ClearSelection -> stringResource(R.string.back_clear_selection)
        BackTarget.CloseSearch -> stringResource(R.string.back_close_search)
        is BackTarget.Folder -> target.name
        BackTarget.Leave -> stringResource(R.string.back_leave_browser)
    }
    // While it leaves, it keeps naming where the gesture led, even once Back has moved on.
    var text by remember { mutableStateOf(currentText) }
    if (active && text != currentText) text = currentText
    if (!active && shown == 0f) return
    var areaTop by remember { mutableFloatStateOf(0f) }
    var areaHeight by remember { mutableIntStateOf(0) }
    var chipHeight by remember { mutableIntStateOf(0) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                areaTop = it.positionInWindow().y
                areaHeight = it.size.height
            },
        contentAlignment = if (fromLeft) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp,
            modifier = Modifier
                // Clear of the system's back arrow, which sits at the swipe edge.
                .padding(start = startInset + 72.dp, end = 72.dp)
                .onSizeChanged { chipHeight = it.height }
                .graphicsLayer {
                    alpha = shown
                    // Slides in from the swipe edge as it appears.
                    translationX = (if (fromLeft) -1f else 1f) * 40.dp.toPx() * (1f - shown)
                    if (!touchY.isNaN() && areaHeight > 0) {
                        // Above the finger: from the centre (where the Box puts it) to there.
                        val half = chipHeight / 2f
                        val y = (touchY - areaTop - BACK_CHIP_LIFT.toPx()).coerceIn(half, areaHeight - half)
                        translationY = y - areaHeight / 2f
                    }
                }
                .testTag(BACK_CHIP_TEST_TAG),
        ) {
            // The system's arrow beside it already says "back": the chip only names where to.
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
