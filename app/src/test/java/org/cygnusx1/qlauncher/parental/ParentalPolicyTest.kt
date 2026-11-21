/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.parental

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentalPolicyTest {
    private fun h(hours: Int, minutes: Int = 0) = (hours * 60L + minutes) * 60_000L

    @Test
    fun withoutPartialProtectionItAlwaysApplies() {
        assertTrue(ParentalPolicy.inProtectedWindow(false, h(8), h(9), h(3)))
    }

    @Test
    fun windowWithinADay() {
        val inWindow = { t: Long -> ParentalPolicy.inProtectedWindow(true, h(8), h(20), t) }
        assertFalse(inWindow(h(7, 59)))
        assertTrue("start is inclusive", inWindow(h(8)))
        assertTrue(inWindow(h(19, 59)))
        assertFalse("end is exclusive", inWindow(h(20)))
    }

    @Test
    fun windowWrapsPastMidnight() {
        val inWindow = { t: Long -> ParentalPolicy.inProtectedWindow(true, h(21), h(7), t) }
        assertTrue(inWindow(h(21)))
        assertTrue(inWindow(h(23, 59)))
        assertTrue(inWindow(0L))
        assertTrue(inWindow(h(6, 59)))
        assertFalse(inWindow(h(7)))
        assertFalse(inWindow(h(12)))
        assertFalse(inWindow(h(20, 59)))
    }

    @Test
    fun equalEndsMeanTheWholeDay() {
        assertTrue(ParentalPolicy.inProtectedWindow(true, 0L, 0L, h(13)))
        assertTrue(ParentalPolicy.inProtectedWindow(true, h(9), h(9), h(2)))
    }

    @Test
    fun validationLasts15SecondsOr15Minutes() {
        val at = 1_000_000L
        assertFalse(ParentalPolicy.validationHolds(null, at, false))
        assertTrue(ParentalPolicy.validationHolds(at, at + 14_999, false))
        assertFalse(ParentalPolicy.validationHolds(at, at + 15_000, false))
        assertTrue(ParentalPolicy.validationHolds(at, at + 15_000, true))
        assertTrue(ParentalPolicy.validationHolds(at, at + 15 * 60_000L - 1, true))
        assertFalse(ParentalPolicy.validationHolds(at, at + 15 * 60_000L, true))
        assertFalse("a clock going backwards is not trusted", ParentalPolicy.validationHolds(at, at - 1, true))
    }
}
