package com.voyagerfiles.viewmodel

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A pinned session as stored across restarts: where it lives, its pinned folder, the last folder
 * it showed, and whether it is the start tab the app opens on launch.
 */
sealed interface PinnedSession {
    val startPath: String
    val currentPath: String
    val isStart: Boolean

    data class Local(
        override val startPath: String,
        override val currentPath: String,
        override val isStart: Boolean = false,
    ) : PinnedSession

    data class Remote(
        val connectionId: Long,
        override val startPath: String,
        override val currentPath: String,
        override val isStart: Boolean = false,
    ) : PinnedSession

    /** The Shizuku tab comes back disconnected; opening it connects to Shizuku again. */
    data class Shizuku(
        override val startPath: String,
        override val currentPath: String,
        override val isStart: Boolean = false,
    ) : PinnedSession

    /** SAF folders come back at their root: a document deeper down has no known parents after a restart. */
    data class Saf(
        val treeUri: String,
        override val startPath: String,
        override val currentPath: String,
        override val isStart: Boolean = false,
    ) : PinnedSession
}

/**
 * One pinned session per line, its fields separated by tabs and URL-encoded so any path survives:
 * kind, key (connection id or tree URI), pinned folder, last folder, start mark. Lines from before
 * the start mark existed have four fields and read as not the start tab.
 */
object PinnedSessionCodec {
    private const val LOCAL = "local"
    private const val REMOTE = "remote"
    private const val SAF = "saf"
    private const val SHIZUKU = "shizuku"
    private const val START = "start"

    fun encode(pins: List<PinnedSession>): String = pins.joinToString("\n") { pin ->
        val fields = when (pin) {
            is PinnedSession.Local -> listOf(LOCAL, "", pin.startPath, pin.currentPath)
            is PinnedSession.Remote -> listOf(REMOTE, pin.connectionId.toString(), pin.startPath, pin.currentPath)
            is PinnedSession.Saf -> listOf(SAF, pin.treeUri, pin.startPath, pin.currentPath)
            is PinnedSession.Shizuku -> listOf(SHIZUKU, "", pin.startPath, pin.currentPath)
        } + (if (pin.isStart) START else "")
        fields.joinToString("\t") { URLEncoder.encode(it, "UTF-8") }
    }

    /** Lines it cannot read are skipped, so one bad entry never loses the others. */
    fun decode(text: String?): List<PinnedSession> = text.orEmpty().lineSequence().mapNotNull { line ->
        val fields = line.split("\t").map { URLDecoder.decode(it, "UTF-8") }
        if (fields.size !in 4..5 || fields[2].isEmpty() || fields[3].isEmpty()) return@mapNotNull null
        val (kind, key, start, current) = fields
        val isStart = fields.getOrNull(4) == START
        when (kind) {
            LOCAL -> PinnedSession.Local(start, current, isStart)
            REMOTE -> key.toLongOrNull()?.let { PinnedSession.Remote(it, start, current, isStart) }
            SAF -> key.takeIf { it.isNotEmpty() }?.let { PinnedSession.Saf(it, start, current, isStart) }
            SHIZUKU -> PinnedSession.Shizuku(start, current, isStart)
            else -> null
        }
    }.toList()
}
