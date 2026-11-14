package com.voyagerfiles.ui.screens

import com.voyagerfiles.data.model.FileSource
import com.voyagerfiles.viewmodel.BrowserSession
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionBarModelTest {

    private fun session(id: String, lastUsed: Long, pinned: Boolean = false, pinOrder: Long = 0) = BrowserSession(
        id = id,
        originLabel = "Internal storage",
        source = FileSource.LOCAL,
        rootPath = "/storage/emulated/0",
        currentPath = "/storage/emulated/0/$id",
        lastUsed = lastUsed,
        pinned = pinned,
        pinOrder = pinOrder,
    )

    private fun List<SessionBarSlot>.ids() = map {
        when (it) {
            is SessionBarSlot.Session -> it.session.id
            SessionBarSlot.Empty -> "-"
        }
    }

    @Test
    fun pinsStayInTheOrderTheyWerePinnedNotOpened() {
        // "c" was opened last but pinned first, so it keeps the first cell.
        val sessions = listOf(
            session("a", 5, pinned = true, pinOrder = 2), session("b", 9),
            session("c", 1, pinned = true, pinOrder = 1),
        )
        assertEquals(listOf("c", "a", "b", "-"), SessionBarModel.slots(sessions).ids())
        val withNewPin = sessions.map { if (it.id == "b") it.copy(pinned = true, pinOrder = 3) else it }
        assertEquals(listOf("c", "a", "b", "-"), SessionBarModel.slots(withNewPin).ids())
    }

    @Test
    fun pinnedSessionsComeFirstAndStayWhenOthersAreMoreRecent() {
        val sessions = listOf(
            session("a", 9), session("p1", 0, pinned = true), session("b", 8),
            session("c", 7), session("p2", 1, pinned = true),
        )
        // Two pins, then the two most recent others in opening order.
        assertEquals(listOf("p1", "p2", "a", "b"), SessionBarModel.slots(sessions).ids())
    }

    @Test
    fun pinsKeepTheirCellsWhateverElseIsOpen() {
        val pin = session("p1", 0, pinned = true)
        assertEquals(listOf("p1", "-", "-", "-"), SessionBarModel.slots(listOf(pin)).ids())
        assertEquals(listOf("p1", "a", "-", "-"), SessionBarModel.slots(listOf(session("a", 3), pin)).ids())
        assertEquals(
            listOf("p1", "a", "b", "c"),
            SessionBarModel.slots(listOf(session("a", 3), pin, session("b", 2), session("c", 1))).ids(),
        )
    }

    @Test
    fun everyCellCanBePinned() {
        val four = listOf("p1", "p2", "p3", "p4").map { session(it, 0, pinned = true) }
        assertEquals(true, SessionBarModel.canPinMore(four.take(3)))
        assertEquals(false, SessionBarModel.canPinMore(four))
        // A tall rail has room for more.
        assertEquals(true, SessionBarModel.canPinMore(four, cells = 8))
    }

    @Test
    fun theRailShowsAsManyTabsAsFitAndNeverDropsAPin() {
        val sessions = (1..6).map { session("p$it", 0, pinned = true, pinOrder = it.toLong()) } +
            listOf(session("a", 3), session("b", 2), session("c", 1))
        assertEquals(
            listOf("p1", "p2", "p3", "p4", "p5", "p6", "a", "b"),
            SessionBarModel.shownSessions(sessions, cells = 8, keepAllPins = true).map { it.id },
        )
        // Less room than pins (a rotation): every pin stays, the rail scrolls.
        assertEquals(
            listOf("p1", "p2", "p3", "p4", "p5", "p6"),
            SessionBarModel.shownSessions(sessions, cells = 4, keepAllPins = true).map { it.id },
        )
        // The bottom bar shows its first four.
        assertEquals(listOf("p1", "p2", "p3", "p4"), SessionBarModel.shownSessions(sessions).map { it.id })
    }

    @Test
    fun anUnpinnedActiveSessionNeverPushesAPinOut() {
        val sessions = listOf(
            session("p1", 0, pinned = true), session("p2", 0, pinned = true),
            session("p3", 0, pinned = true), session("p4", 0, pinned = true), session("x", 5),
        )
        assertEquals(listOf("p1", "p2", "p3", "p4"), SessionBarModel.slots(sessions, activeSessionId = "x").ids())
    }

    @Test
    fun theActiveUnpinnedSessionGetsAFreeCellBeforeMoreRecentOnes() {
        val sessions = listOf(
            session("p1", 0, pinned = true), session("old", 1), session("new1", 8), session("new2", 9), session("new3", 7),
        )
        assertEquals(listOf("p1", "old", "new1", "new2"), SessionBarModel.slots(sessions, activeSessionId = "old").ids())
    }

    @Test
    fun itemsGatherTowardsTheCentreButton() {
        assertEquals(listOf("-", "a", "-", "-"), SessionBarModel.slots(listOf(session("a", 1))).ids())
        assertEquals(listOf("-", "a", "b", "-"), SessionBarModel.slots(listOf(session("a", 1), session("b", 2))).ids())
        assertEquals(
            listOf("a", "b", "c", "-"),
            SessionBarModel.slots(listOf(session("a", 1), session("b", 2), session("c", 3))).ids(),
        )
    }

    @Test
    fun overflowKeepsTheMostRecentlyUsedInOpeningOrder() {
        val sessions = listOf(session("a", 5), session("b", 1), session("c", 6), session("d", 2), session("e", 9))
        assertEquals(listOf("a", "c", "d", "e"), SessionBarModel.slots(sessions).ids())
    }

    @Test
    fun noSessionsLeavesOnlyTheCentreButton() {
        assertEquals(listOf("-", "-", "-", "-"), SessionBarModel.slots(emptyList()).ids())
    }

    @Test
    fun disconnectingGoesToTheLastUsedSessionThatNeedsNoNewConnection() {
        val nas = session("nas", 9).copy(source = FileSource.FTP, connected = true)
        val otherServer = session("sftp", 8).copy(source = FileSource.SFTP, connected = false)
        val downloads = session("downloads", 4)
        val storage = session("storage", 6)
        val sessions = listOf(nas, otherServer, downloads, storage)
        // The disconnected server at 8 would connect again, so the local session at 6 wins.
        assertEquals("storage", SessionBarModel.sessionAfterDisconnect(sessions, "nas")?.id)
        assertEquals("nas", SessionBarModel.sessionAfterDisconnect(sessions + otherServer.copy(id = "x", lastUsed = 1), "storage")?.id)
    }

    @Test
    fun disconnectingTheOnlyUsableSessionGoesHome() {
        val nas = session("nas", 9).copy(source = FileSource.FTP, connected = true)
        val otherServer = session("sftp", 8).copy(source = FileSource.SFTP, connected = false)
        assertEquals(null, SessionBarModel.sessionAfterDisconnect(listOf(nas, otherServer), "nas"))
        assertEquals(null, SessionBarModel.sessionAfterDisconnect(listOf(nas), "nas"))
    }
}
