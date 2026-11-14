package com.voyagerfiles.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedSessionCodecTest {

    @Test
    fun everyKindSurvivesARoundTripInOrder() {
        val pins = listOf(
            PinnedSession.Remote(7, "/pub", "/pub/music/Jazz & Blues"),
            PinnedSession.Local("/storage/emulated/0/Download", "/storage/emulated/0/Download/Holiday photos"),
            PinnedSession.Saf(
                "content://com.android.externalstorage.documents/tree/1234-ABCD%3AMusic",
                "content://com.android.externalstorage.documents/tree/1234-ABCD%3AMusic/document/1234-ABCD%3AMusic",
                "content://com.android.externalstorage.documents/tree/1234-ABCD%3AMusic/document/1234-ABCD%3AMusic",
            ),
        )
        assertEquals(pins, PinnedSessionCodec.decode(PinnedSessionCodec.encode(pins)))
    }

    @Test
    fun tabsNewlinesAndPercentSignsInNamesAreKept() {
        val pins = listOf(PinnedSession.Local("/a\tb", "/a\tb/c\nd/100% done"))
        assertEquals(pins, PinnedSessionCodec.decode(PinnedSessionCodec.encode(pins)))
    }

    @Test
    fun theStartTabMarkSurvivesAndOldLinesReadAsNotStart() {
        val pins = listOf(
            PinnedSession.Local("/a", "/a/b"),
            PinnedSession.Remote(3, "/music", "/music", isStart = true),
        )
        assertEquals(pins, PinnedSessionCodec.decode(PinnedSessionCodec.encode(pins)))
        // A line saved before the mark existed has four fields.
        val oldLine = listOf("local", "", "%2Fx", "%2Fx%2Fy").joinToString("\t")
        assertEquals(listOf(PinnedSession.Local("/x", "/x/y", isStart = false)), PinnedSessionCodec.decode(oldLine))
    }

    @Test
    fun nothingStoredMeansNoPins() {
        assertEquals(emptyList<PinnedSession>(), PinnedSessionCodec.decode(null))
        assertEquals(emptyList<PinnedSession>(), PinnedSessionCodec.decode(""))
        assertEquals("", PinnedSessionCodec.encode(emptyList()))
    }

    @Test
    fun unreadableLinesAreSkippedAndTheRestKept() {
        val good = PinnedSessionCodec.encode(listOf(PinnedSession.Local("/x", "/x/y")))
        val text = listOf("garbage", "remote\tnot-a-number\t%2F\t%2F", "ftp\t1\t%2F\t%2F", good).joinToString("\n")
        assertEquals(listOf(PinnedSession.Local("/x", "/x/y")), PinnedSessionCodec.decode(text))
    }
}
