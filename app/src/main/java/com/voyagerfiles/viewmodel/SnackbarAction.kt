package com.voyagerfiles.viewmodel

/** A button shown next to a snackbar message. */
sealed interface SnackbarAction {
    /** Shows a local folder, switching to a session already there or opening one. */
    data class OpenFolder(val path: String) : SnackbarAction
}
