package com.voyagerfiles.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackChipShiftTest {
    private val max = 110f

    @Test fun theChipBarelyFollowsTheFinger() {
        // As the system arrow: a 100 dp move shifts it by roughly 15 dp, in the finger's direction.
        val down = backChipYShift(100f, max)
        assertTrue("shift $down", down in 10f..20f)
        assertEquals(-down, backChipYShift(-100f, max), 0.001f)
        assertEquals(0f, backChipYShift(0f, max), 0f)
    }

    @Test fun theShiftNeverPassesTheArrowsLimit() {
        assertTrue(backChipYShift(100_000f, max) <= max * 1.2f)
    }
}
