package com.voyagerfiles.viewmodel

/** What launching does with the start tab (see [FileBrowserViewModel.startTabPlan]). */
sealed interface StartTabPlan {
    /** No start tab: the app opens Home. */
    data object None : StartTabPlan

    /** A device or folder-picker start tab is being shown: go to the browser. */
    data object Opened : StartTabPlan

    /** A device start tab, but the app lacks full storage access: stay on Home and say so. */
    data class NeedsAccess(val label: String) : StartTabPlan

    /** The start tab could not come back (access withdrawn or connection deleted). */
    data class Unavailable(val label: String) : StartTabPlan

    /** A server start tab: connect while Home stays usable, then open it or report the failure. */
    data class Server(val sessionId: String, val label: String) : StartTabPlan
}
