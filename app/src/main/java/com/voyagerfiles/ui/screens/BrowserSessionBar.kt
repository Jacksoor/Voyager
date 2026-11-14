package com.voyagerfiles.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voyagerfiles.R
import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.viewmodel.BrowserSession

internal const val SESSION_BAR_TEST_TAG = "session-bar"
internal const val SESSION_BAR_SESSIONS_TEST_TAG = "session-bar-sessions"
internal fun sessionBarItemTestTag(sessionId: String) = "session-bar-item:$sessionId"

/** One cell of the session bar, left to right. The sessions button always sits in the middle. */
internal sealed interface SessionBarSlot {
    data class Session(val session: BrowserSession) : SessionBarSlot
    data object Empty : SessionBarSlot
}

/**
 * Decides what the session bar shows. Its items are destinations only (Material's rule for
 * navigation bars and rails): the open sessions, plus the sessions button. The bottom bar has
 * [COLUMNS] equal cells with the sessions button in the centre one. Pinned sessions come first and
 * always stay; the most recently used of the others fill the remaining cells, kept in the order
 * they were opened so items do not jump when switching.
 *
 * Pinned sessions never move: they hold fixed cells from the left edge in pin order, whatever else
 * is open, and nothing pushes them out. With pins, the other sessions follow them left to right;
 * without pins, items gather towards the centre.
 */
internal object SessionBarModel {
    const val COLUMNS = 5
    const val SIDE_CELLS = COLUMNS - 1

    /**
     * How many sessions can be pinned: every cell the layout has for sessions, [cells] (the bottom
     * bar's four, or as many as fit in the rail). With all of them pinned, an unpinned session stays
     * reachable through the sessions sheet but has no cell.
     */
    fun canPinMore(sessions: List<BrowserSession>, cells: Int = SIDE_CELLS): Boolean =
        sessions.count { it.pinned } < cells

    /**
     * Where to go when the shown session [disconnectedId] is disconnected: the most recently used
     * other session that can show its files without connecting again, or null for Home.
     */
    fun sessionAfterDisconnect(sessions: List<BrowserSession>, disconnectedId: String): BrowserSession? =
        sessions
            .filter { it.id != disconnectedId && (!it.hasConnection || it.connected) }
            .maxByOrNull { it.lastUsed }

    /**
     * The sessions shown in [cells] cells. Pins come first and are never dropped by the rail
     * ([keepAllPins]; it scrolls when they outgrow the space), the bar shows its first [cells].
     */
    fun shownSessions(
        sessions: List<BrowserSession>,
        activeSessionId: String? = null,
        cells: Int = SIDE_CELLS,
        keepAllPins: Boolean = false,
    ): List<BrowserSession> {
        // Oldest pin first: a new pin always goes after the existing ones.
        val allPins = sessions.filter { it.pinned }.sortedBy { it.pinOrder }
        val pinned = if (keepAllPins) allPins else allPins.take(cells)
        val free = cells - pinned.size
        if (free <= 0) return pinned
        val others = sessions.filterNot { it.pinned }
        // The active session always gets a free cell; the rest go to the most recently used.
        val active = others.firstOrNull { it.id == activeSessionId }
        val chosen = (listOfNotNull(active) + others.sortedByDescending { it.lastUsed })
            .distinctBy { it.id }
            .take(free)
            .mapTo(mutableSetOf()) { it.id }
        return pinned + others.filter { it.id in chosen }
    }

    /** The [SIDE_CELLS] side cells, left half first. */
    fun slots(sessions: List<BrowserSession>, activeSessionId: String? = null): List<SessionBarSlot> {
        val shown = shownSessions(sessions, activeSessionId).map<BrowserSession, SessionBarSlot> { SessionBarSlot.Session(it) }
        if (sessions.any { it.pinned }) {
            // Fixed cells from the left.
            return shown + List(SIDE_CELLS - shown.size) { SessionBarSlot.Empty }
        }
        val half = SIDE_CELLS / 2
        val leftCount = (shown.size + 1) / 2
        val left = shown.take(leftCount)
        val right = shown.drop(leftCount)
        return List(half - left.size) { SessionBarSlot.Empty } + left +
            right + List(half - right.size) { SessionBarSlot.Empty }
    }
}

private val RAIL_CELL = 72.dp
private val BAR_TOP_INSET = 11.dp
private val RAIL_GAP = 8.dp
private val RAIL_PADDING = 8.dp

/**
 * How opaque a session's icon is: a Shizuku tab that isn't connected is dimmed, because the
 * outlined Android icon looks the same as the filled one.
 */
internal fun sessionIconAlpha(session: BrowserSession): Float =
    if (session.source == FileSource.SHIZUKU && !session.connected) DISCONNECTED_ICON_ALPHA else 1f

private const val DISCONNECTED_ICON_ALPHA = 0.38f

/** Server and Shizuku sessions show a filled icon while connected and an outlined one while not. */
internal fun sessionIcon(session: BrowserSession): ImageVector = when (session.source) {
    FileSource.LOCAL -> if (session.removable) Icons.Filled.SdStorage else Icons.Filled.PhoneAndroid
    FileSource.SAF -> Icons.Filled.FolderOpen
    FileSource.ROOT -> Icons.Filled.Security
    FileSource.SHIZUKU -> if (session.connected) Icons.Filled.Android else Icons.Outlined.Android
    FileSource.SFTP, FileSource.FTP, FileSource.SMB, FileSource.WEBDAV ->
        if (session.connected) Icons.Filled.Cloud else Icons.Outlined.Cloud
}

@Composable
internal fun BrowserSessionBar(
    sessions: List<BrowserSession>,
    activeSessionId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onOpenSessions: () -> Unit,
    modifier: Modifier = Modifier,
    onSetPinned: (String, Boolean) -> Unit = { _, _ -> },
    onDisconnect: (String) -> Unit = {},
    onReset: (String) -> Unit = {},
    onSetStartTab: (String, Boolean) -> Unit = { _, _ -> },
) {
    val slots = SessionBarModel.slots(sessions, activeSessionId)
    val canPinMore = SessionBarModel.canPinMore(sessions)
    val half = SessionBarModel.SIDE_CELLS / 2
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.testTag(SESSION_BAR_TEST_TAG),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(72.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val cell = Modifier.weight(1f).fillMaxHeight()
            slots.take(half).forEach { SessionBarCell(it, cell, activeSessionId, canPinMore, onSelect, onClose, onSetPinned, onDisconnect, onReset, onSetStartTab = onSetStartTab) }
            SessionsButton(count = sessions.size, onClick = onOpenSessions, modifier = cell)
            slots.drop(half).forEach { SessionBarCell(it, cell, activeSessionId, canPinMore, onSelect, onClose, onSetPinned, onDisconnect, onReset, onSetStartTab = onSetStartTab) }
        }
    }
}

/**
 * The session bar as a side rail, for tablets, TVs and phones in landscape: a bottom bar would take
 * height the file list needs and sit where scrolling happens. Items sit at the bottom: the sessions
 * button lowest, the sessions stacked upwards from it. Labels may take two lines.
 */
@Composable
internal fun BrowserSessionRail(
    sessions: List<BrowserSession>,
    activeSessionId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onOpenSessions: () -> Unit,
    modifier: Modifier = Modifier,
    onSetPinned: (String, Boolean) -> Unit = { _, _ -> },
    onDisconnect: (String) -> Unit = {},
    onReset: (String) -> Unit = {},
    onSetStartTab: (String, Boolean) -> Unit = { _, _ -> },
    /** Told how many tab cells fit, so pinning can allow that many. */
    onCapacity: (Int) -> Unit = {},
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxHeight().width(128.dp).testTag(SESSION_BAR_TEST_TAG),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxHeight()
                // System bars and cutouts only: the rail stays put when the keyboard opens.
                .windowInsetsPadding(
                    WindowInsets.systemBars.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
                ),
        ) {
            // As many tab cells as fit above the counter: about 8 on a tablet, 4 on a landscape phone.
            val cells = ((maxHeight - RAIL_PADDING * 2 - RAIL_GAP - RAIL_CELL) / RAIL_CELL).toInt().coerceAtLeast(1)
            LaunchedEffect(cells) { onCapacity(cells) }
            val shown = SessionBarModel.shownSessions(sessions, activeSessionId, cells, keepAllPins = true)
            val canPinMore = SessionBarModel.canPinMore(sessions, cells)
            val cell = Modifier.fillMaxWidth().height(RAIL_CELL)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Scrolls only when pins outgrow the space (after a rotation or in split screen).
                    .verticalScroll(rememberScrollState(Int.MAX_VALUE))
                    .heightIn(min = maxHeight)
                    .padding(vertical = RAIL_PADDING),
                horizontalAlignment = Alignment.CenterHorizontally,
                // Everything sits at the bottom, within thumb reach. Sessions stack upwards from the
                // counter, so the first pin sits right above it and pins keep their places.
                verticalArrangement = Arrangement.Bottom,
            ) {
                shown.asReversed().forEach { session ->
                    SessionBarCell(
                        SessionBarSlot.Session(session), cell, activeSessionId, canPinMore,
                        onSelect, onClose, onSetPinned, onDisconnect, onReset, labelLines = 2,
                        onSetStartTab = onSetStartTab,
                        menuBeside = true,
                    )
                }
                Spacer(Modifier.height(RAIL_GAP))
                SessionsButton(count = sessions.size, onClick = onOpenSessions, modifier = cell)
            }
        }
    }
}

@Composable
private fun SessionBarCell(
    slot: SessionBarSlot,
    cellModifier: Modifier,
    activeSessionId: String?,
    canPinMore: Boolean,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onSetPinned: (String, Boolean) -> Unit,
    onDisconnect: (String) -> Unit,
    onReset: (String) -> Unit,
    labelLines: Int = 1,
    onSetStartTab: (String, Boolean) -> Unit = { _, _ -> },
    /** The rail: the menu opens beside the tab instead of above it. */
    menuBeside: Boolean = false,
) {
    when (slot) {
        SessionBarSlot.Empty -> Spacer(cellModifier)
        is SessionBarSlot.Session -> {
            val session = slot.session
            val menu = remember { TabMenuState() }
            val haptics = LocalHapticFeedback.current
            val viewConfiguration = LocalViewConfiguration.current
            val quickLongPress = remember(viewConfiguration) {
                object : ViewConfiguration by viewConfiguration {
                    override val longPressTimeoutMillis: Long
                        get() = minOf(TAB_MENU_LONG_PRESS_MS, viewConfiguration.longPressTimeoutMillis)
                }
            }
            Box(modifier = cellModifier.tabMenuDrag(menu, haptics)) {
                CompositionLocalProvider(LocalViewConfiguration provides quickLongPress) {
                    BarItem(
                        icon = sessionIcon(session),
                        iconAlpha = sessionIconAlpha(session),
                        label = session.barLabel,
                        selected = session.id == activeSessionId,
                        onClick = { onSelect(session.id) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menu.open()
                        },
                        pinned = session.pinned,
                        startTab = session.isStartTab,
                        sublabel = if (session.pinned && session.isAwayFromStart) "› ${session.label}" else null,
                        labelLines = labelLines,
                        compact = labelLines == 1,
                        modifier = Modifier.fillMaxWidth().testTag(sessionBarItemTestTag(session.id)),
                    )
                }
                SessionTabMenu(
                    session = session,
                    state = menu,
                    beside = menuBeside,
                    canPinMore = canPinMore,
                    onClose = onClose,
                    onSetPinned = onSetPinned,
                    onDisconnect = onDisconnect,
                    onReset = onReset,
                    onSetStartTab = onSetStartTab,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    pinned: Boolean = false,
    /** The tab the app opens on launch: a rocket instead of the pin. */
    startTab: Boolean = false,
    /** For a pin deeper inside its folder: the current folder, shown under the pinned name. */
    sublabel: String? = null,
    labelLines: Int = 1,
    iconAlpha: Float = 1f,
    /**
     * The bottom bar: items are laid out from the top, so icon and name sit at the same height on
     * every tab and a second line hangs below the name (into the gesture-bar area if need be).
     */
    compact: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val pinnedState = stringResource(R.string.session_pinned)
    val startState = stringResource(R.string.session_start_tab)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .then(if (compact) Modifier.wrapContentHeight(Alignment.Top, unbounded = true) else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics {
                role = Role.Tab
                this.selected = selected
                if (pinned) stateDescription = if (startTab) "$pinnedState, $startState" else pinnedState
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (compact) Arrangement.Top else Arrangement.Center,
    ) {
        // Centres a one-line item in the 72 dp bar, so every tab's icon and name line up.
        if (compact) Spacer(Modifier.height(BAR_TOP_INSET))
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 30.dp)
                .background(
                    if (selected) colors.secondaryContainer else colors.surfaceContainer,
                    RoundedCornerShape(15.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = (if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant).copy(alpha = iconAlpha),
            )
            if (pinned) {
                // The start tab's rocket takes the pin's place: it launches with the app.
                Icon(
                    if (startTab) Icons.Filled.RocketLaunch else Icons.Filled.PushPin,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 2.dp, end = 6.dp)
                        .size(12.dp),
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
            // With a second line, the name keeps one line.
            maxLines = if (sublabel != null) 1 else labelLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
        if (sublabel != null) {
            Text(
                sublabel,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

/** The centre button: a tab counter, as browsers use for their tab switcher. Opens the sessions sheet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionsButton(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.content_desc_sessions)
    val state = pluralStringResource(R.plurals.sessions_open_count, count, count)
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(colors.primaryContainer)
                .combinedClickable(onClick = onClick)
                .testTag(SESSION_BAR_SESSIONS_TEST_TAG)
                .semantics {
                    contentDescription = description
                    stateDescription = state
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .border(2.dp, colors.onPrimaryContainer, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    count.coerceAtMost(99).toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onPrimaryContainer,
                )
            }
        }
    }
}
