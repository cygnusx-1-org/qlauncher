/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** LEFT and RIGHT along a slider that is mirrored in RTL (Arabic, Persian, Hebrew). */
class StepSliderTest {
    @Test
    fun ltrRightStepsTowardTheEnd() {
        assertEquals(1, dpadStep(right = true, rtl = false))
        assertEquals(-1, dpadStep(right = false, rtl = false))
    }

    @Test
    fun rtlLeftStepsTowardTheEnd() {
        assertEquals(1, dpadStep(right = false, rtl = true))
        assertEquals(-1, dpadStep(right = true, rtl = true))
    }
}
