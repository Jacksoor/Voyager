package com.voyagerfiles.ui.screens

import androidx.test.core.app.ApplicationProvider
import com.voyagerfiles.data.local.PreferencesManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/**
 * Sets the device's pinned tabs aside for a test and puts them back afterwards. Every new
 * FileBrowserViewModel restores the pins, so tabs someone pinned while using the debug build
 * would otherwise show up in tests that count or pick tabs.
 */
class ClearPinnedSessionsRule : ExternalResource() {
    private val prefs = PreferencesManager(ApplicationProvider.getApplicationContext())
    private var saved: String? = null

    override fun before() {
        saved = runBlocking { prefs.pinnedSessions.first() }
        runBlocking { prefs.setPinnedSessions("") }
    }

    override fun after() {
        runBlocking { prefs.setPinnedSessions(saved.orEmpty()) }
    }
}
