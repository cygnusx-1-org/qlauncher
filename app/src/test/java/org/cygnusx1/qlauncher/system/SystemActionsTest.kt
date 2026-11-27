/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.system

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import org.cygnusx1.qlauncher.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Runs on Robolectric for a package manager to register settings pages in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SystemActionsTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    @Test
    fun fireOsComplianceHandlersArePlaceholders() {
        assertTrue(SystemActions.isSettingsPlaceholder(FIRE_OS, "$FIRE_OS.compliance.CTSDummyIntentHandler"))
        assertTrue(SystemActions.isSettingsPlaceholder(FIRE_OS, "$FIRE_OS.compliance.CTSDummySettingsHandler"))
    }

    @Test
    fun realPagesAreNotPlaceholders() {
        assertFalse(SystemActions.isSettingsPlaceholder(FIRE_OS, "$FIRE_OS.tv.accessibility.AccessibilityActivity"))
        assertFalse(SystemActions.isSettingsPlaceholder("com.android.tv.settings", "$FIRE_OS.compliance.CTSDummyIntentHandler"))
    }

    @Test
    fun accessibilitySettingsAnsweredOnlyByPlaceholderCountAsMissing() {
        registerAccessibilityPage(ComponentName(FIRE_OS, "$FIRE_OS.compliance.CTSDummyIntentHandler"))

        assertFalse(SystemActions(app).openAccessibilitySettings())
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun realAccessibilitySettingsOpen() {
        registerAccessibilityPage(ComponentName("com.android.tv.settings", "com.android.tv.settings.accessibility.AccessibilityActivity"))

        assertTrue(SystemActions(app).openAccessibilitySettings())
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, shadowOf(app).nextStartedActivity?.action)
    }

    @Test
    fun fireTvPointsAtHomeOnFire() {
        shadowOf(app.packageManager).setSystemFeature("amazon.hardware.fire_tv", true)

        assertEquals(R.string.system_accessibility_fire_os, SystemActions(app).accessibilityNoSettingsMessage())
    }

    @Test
    fun otherDevicesPointAtAndroidSettings() {
        assertEquals(R.string.system_accessibility_no_settings, SystemActions(app).accessibilityNoSettingsMessage())
    }

    private fun registerAccessibilityPage(component: ComponentName) {
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(
            component,
            IntentFilter(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { addCategory(Intent.CATEGORY_DEFAULT) },
        )
    }

    private companion object {
        const val FIRE_OS = "com.amazon.tv.settings.v2"
    }
}
