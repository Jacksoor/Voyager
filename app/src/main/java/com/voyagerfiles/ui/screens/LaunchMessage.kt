package com.voyagerfiles.ui.screens

/**
 * What Home tells the user when the start tab can't be opened on launch. A start tab that can be
 * opened (on the device or a server) opens straight in the browser, which shows its own progress.
 */
sealed interface LaunchMessage {
    val label: String

    /** A device start tab needs full storage access. */
    data class NeedsAccess(override val label: String) : LaunchMessage

    /** The start tab could not come back after a restart. */
    data class Unavailable(override val label: String) : LaunchMessage
}
