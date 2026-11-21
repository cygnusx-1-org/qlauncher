/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeviceFactsTest {
    @Test
    fun hdrWithoutTypesIsTheLuminanceLine() {
        assertEquals("Max Average:500 maxLum:500 minLum:0", DeviceFacts.hdrDescription(IntArray(0), 500f, 500f, 0.05f))
    }

    @Test
    fun hdrTypesAreNamedOnce() {
        assertEquals(
            "Dolby Vision, HDR10, HLG, HDR10+ · Max Average:400 maxLum:1000 minLum:0",
            DeviceFacts.hdrDescription(intArrayOf(1, 2, 2, 3, 4, 9), 400f, 1000f, 0f),
        )
    }

    @Test
    fun buildDescriptionFormat() {
        assertEquals(
            "darcy-user 11 RQ1A.210105.003 13961458_4387.0822 release-keys",
            DeviceFacts.buildDescription("darcy", "user", "11", "RQ1A.210105.003", "13961458_4387.0822", "release-keys"),
        )
    }

    @Test
    fun suIsLookedUpWithoutRunningIt() {
        val seen = mutableListOf<String>()
        val none = DeviceFacts.hasSuBinary(listOf("/system/xbin"), "/usr/bin:/bin") { seen += it.path; false }
        assertFalse(none)
        assertEquals(listOf("/system/xbin/su", "/usr/bin/su", "/bin/su"), seen)
        assertTrue(DeviceFacts.hasSuBinary(listOf("/a", "/b"), null) { it == File("/b", "su") })
    }
}
