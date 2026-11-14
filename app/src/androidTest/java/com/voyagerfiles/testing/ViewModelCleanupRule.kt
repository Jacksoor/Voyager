package com.voyagerfiles.testing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * Clears view models that a test builds directly, after the test and its `@After` methods.
 *
 * All instrumented tests share one process. A view model that is never cleared keeps its
 * `viewModelScope` alive, so its preference collectors keep reacting to every later test and
 * the whole suite gets slower with each test.
 */
class ViewModelCleanupRule : ExternalResource() {
    private val store = ViewModelStore()
    private var count = 0

    fun <T : ViewModel> track(viewModel: T): T {
        synchronized(this) { store.put("vm-${count++}", viewModel) }
        return viewModel
    }

    override fun after() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
    }
}
