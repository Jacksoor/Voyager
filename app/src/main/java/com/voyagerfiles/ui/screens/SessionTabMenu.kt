package com.voyagerfiles.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.voyagerfiles.R
import com.voyagerfiles.viewmodel.BrowserSession

/** One entry of a session's menu. */
internal class SessionMenuEntry(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val run: () -> Unit,
)

/** The line above a session's menu entries: where the session is (and its pinned folder). */
@Composable
internal fun sessionMenuHeader(session: BrowserSession): String =
    if (session.pinned) {
        stringResource(R.string.session_menu_pinned, session.startLabel, session.description)
    } else {
        session.description
    }

/** A session's menu: pin, start tab, back to its start folder, disconnect and close. */
@Composable
internal fun sessionMenuEntries(
    session: BrowserSession,
    canPinMore: Boolean,
    onClose: (String) -> Unit,
    onSetPinned: (String, Boolean) -> Unit,
    onDisconnect: (String) -> Unit,
    onReset: (String) -> Unit,
    onSetStartTab: (String, Boolean) -> Unit,
): List<SessionMenuEntry> = buildList {
    if (session.canPin) {
        add(
            SessionMenuEntry(
                label = if (session.pinned) stringResource(R.string.action_unpin_session)
                // A server or Shizuku is pinned as a whole, under its name.
                else stringResource(R.string.action_pin_folder, if (session.pinsWholePlace) session.originLabel else session.label),
                icon = if (session.pinned) Icons.Outlined.PushPin else Icons.Filled.PushPin,
                // Unpinning always works; pinning only while the bar has a cell for it.
                enabled = session.pinned || canPinMore,
                run = { onSetPinned(session.id, !session.pinned) },
            ),
        )
    }
    if (session.canBeStartTab) {
        add(
            SessionMenuEntry(
                label = stringResource(if (session.isStartTab) R.string.action_unset_start_tab else R.string.action_set_start_tab),
                icon = if (session.isStartTab) Icons.Outlined.RocketLaunch else Icons.Filled.RocketLaunch,
                run = { onSetStartTab(session.id, !session.isStartTab) },
            ),
        )
    }
    if (session.isAwayFromStart) {
        add(SessionMenuEntry(stringResource(R.string.action_reset_session, session.startLabel), Icons.Filled.Restore) { onReset(session.id) })
    }
    if (session.canDisconnect) {
        add(SessionMenuEntry(stringResource(R.string.action_disconnect), Icons.Outlined.CloudOff) { onDisconnect(session.id) })
    }
    // A pinned session is locked: unpin it first to close it.
    if (!session.pinned) {
        add(SessionMenuEntry(stringResource(R.string.content_desc_close_session), Icons.Filled.Close) { onClose(session.id) })
    }
}

/**
 * [value] while [open], and the last value from then once it closes: an entry runs as the menu
 * starts to fade, and must not change the menu (a "Back to" entry vanishing) while it does.
 */
@Composable
private fun <T : Any> keptWhileClosing(open: Boolean, value: T): T {
    val kept = remember { arrayOfNulls<Any>(1) }
    if (open || kept[0] == null) kept[0] = value
    @Suppress("UNCHECKED_CAST")
    return kept[0] as T
}

@Composable
private fun SessionMenuHeaderText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .width(240.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** A session's menu as a standard dropdown, for Home's session cards. */
@Composable
internal fun SessionMenu(
    session: BrowserSession,
    expanded: Boolean,
    onDismiss: () -> Unit,
    canPinMore: Boolean,
    onClose: (String) -> Unit,
    onSetPinned: (String, Boolean) -> Unit,
    onDisconnect: (String) -> Unit,
    onReset: (String) -> Unit,
    onSetStartTab: (String, Boolean) -> Unit,
) {
    val entries = keptWhileClosing(expanded, sessionMenuEntries(session, canPinMore, onClose, onSetPinned, onDisconnect, onReset, onSetStartTab))
    val header = keptWhileClosing(expanded, sessionMenuHeader(session))
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        SessionMenuHeaderText(header)
        entries.forEach { entry ->
            DropdownMenuItem(
                text = { Text(entry.label) },
                leadingIcon = { Icon(entry.icon, contentDescription = null) },
                enabled = entry.enabled,
                onClick = {
                    onDismiss()
                    entry.run()
                },
            )
        }
    }
}

/**
 * The menu of a tab in the session bar or rail, and the gesture that drives it: a long press opens
 * it right next to the tab; sliding onto an entry and letting go runs that entry, like an app's
 * shortcuts in the launcher. Letting go without reaching an entry leaves the menu open for taps.
 */
@Stable
internal class TabMenuState {
    var expanded by mutableStateOf(false)
        private set
    /** The entry under the finger while the long press is held, or -1. */
    var hovered by mutableIntStateOf(-1)
        private set
    private var dragging = false
    private var reachedEntry = false
    internal val entryBounds = mutableStateMapOf<Int, Rect>()
    internal var entries: List<SessionMenuEntry> = emptyList()

    fun open() {
        expanded = true
        dragging = true
        reachedEntry = false
        hovered = -1
    }

    fun dismiss() {
        expanded = false
        dragging = false
        hovered = -1
    }

    /** The finger moved to [screen] (screen pixels) while the opening press is still held. */
    internal fun drag(screen: Offset, haptics: HapticFeedback) {
        if (!dragging || !expanded) return
        val index = entryBounds.entries.firstOrNull { (i, rect) -> rect.contains(screen) && entries.getOrNull(i)?.enabled == true }?.key ?: -1
        if (index != hovered) {
            hovered = index
            if (index >= 0) {
                reachedEntry = true
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }

    /** The opening press ended at [screen]: run the entry there, or keep the menu for taps. */
    internal fun release(screen: Offset) {
        if (!dragging) return
        dragging = false
        val index = entryBounds.entries.firstOrNull { (i, rect) -> rect.contains(screen) && entries.getOrNull(i)?.enabled == true }?.key
        hovered = -1
        when {
            index != null -> {
                val entry = entries.getOrNull(index)
                dismiss()
                entry?.run?.invoke()
            }
            // Slid over the entries and let go elsewhere: changed their mind.
            reachedEntry -> dismiss()
        }
    }
}

/**
 * Follows a press that opened the tab's menu, without taking it from the tab's own click and long
 * click handling. Android keeps sending a touch to the window it started in, so the tab sees the
 * finger even over the menu's popup window; positions are compared in screen pixels.
 */
internal fun Modifier.tabMenuDrag(state: TabMenuState, haptics: HapticFeedback): Modifier {
    var coordinates: LayoutCoordinates? = null
    return this
        .onGloballyPositioned { coordinates = it }
        .pointerInput(state) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    val screen = coordinates?.takeIf { it.isAttached }?.localToScreen(change.position) ?: continue
                    if (change.pressed) state.drag(screen, haptics) else state.release(screen)
                }
            }
        }
}

/** A tab's long press opens its menu a little sooner than Android's default (400 ms on most phones). */
internal const val TAB_MENU_LONG_PRESS_MS = 300L

/** Where a tab's menu goes: right above a bar tab, or beside a rail tab, kept on screen. */
private class TabMenuPosition(private val beside: Boolean, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        return if (beside) {
            val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right + gap
            else anchorBounds.left - gap - popupContentSize.width
            val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
            IntOffset(x.coerceIn(0, maxX), anchorBounds.top.coerceIn(0, maxY))
        } else {
            val x = anchorBounds.center.x - popupContentSize.width / 2
            val y = anchorBounds.top - gap - popupContentSize.height
            IntOffset(x.coerceIn(0, maxX), y.coerceAtLeast(0))
        }
    }
}

/** A tab's menu: [TabMenuState] opens it, and it highlights the entry under a held finger. */
@Composable
internal fun SessionTabMenu(
    session: BrowserSession,
    state: TabMenuState,
    beside: Boolean,
    canPinMore: Boolean,
    onClose: (String) -> Unit,
    onSetPinned: (String, Boolean) -> Unit,
    onDisconnect: (String) -> Unit,
    onReset: (String) -> Unit,
    onSetStartTab: (String, Boolean) -> Unit,
) {
    val entries = keptWhileClosing(state.expanded, sessionMenuEntries(session, canPinMore, onClose, onSetPinned, onDisconnect, onReset, onSetStartTab))
    state.entries = entries
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = state.expanded
    if (!visible.currentState && !visible.targetState) {
        state.entryBounds.clear()
        return
    }
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val header = keptWhileClosing(state.expanded, sessionMenuHeader(session))
    Popup(
        popupPositionProvider = remember(beside, gap) { TabMenuPosition(beside, gap) },
        onDismissRequest = state::dismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(tween(90)) + scaleIn(tween(120, easing = easing), initialScale = 0.92f, transformOrigin = TransformOrigin(0.5f, if (beside) 0f else 1f)),
            exit = fadeOut(tween(90)),
        ) {
            Surface(
                shape = MenuDefaults.shape,
                color = MenuDefaults.containerColor,
                tonalElevation = MenuDefaults.TonalElevation,
                shadowElevation = MenuDefaults.ShadowElevation,
            ) {
                Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 112.dp, max = 280.dp).padding(vertical = 8.dp)) {
                    SessionMenuHeaderText(header)
                    entries.forEachIndexed { index, entry ->
                        DropdownMenuItem(
                            text = { Text(entry.label) },
                            leadingIcon = { Icon(entry.icon, contentDescription = null) },
                            enabled = entry.enabled,
                            onClick = {
                                state.dismiss()
                                entry.run()
                            },
                            modifier = Modifier
                                .onGloballyPositioned { coordinates ->
                                    val topLeft = coordinates.localToScreen(Offset.Zero)
                                    state.entryBounds[index] = Rect(topLeft, Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
                                }
                                .then(
                                    if (state.hovered == index) {
                                        Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }
            }
        }
    }
}
