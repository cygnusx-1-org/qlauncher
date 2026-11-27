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
import android.provider.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Runs on Robolectric for `ComponentName` and the secure settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccessibilityStatusTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    @Test
    fun homeOnFireIsFoundInTheShortFormItWrites() {
        val list = "io.github.sds100.keymapper/io.github.sds100.keymapper.system.accessibility.MyAccessibilityService:" +
            "io.github.toolicious.homeonfire/.HijackService"

        assertTrue(AccessibilityStatus.isListed(list, AccessibilityStatus.HOME_ON_FIRE))
    }

    @Test
    fun homeOnFireIsFoundInTheFullForm() {
        assertTrue(AccessibilityStatus.isListed("io.github.toolicious.homeonfire/io.github.toolicious.homeonfire.HijackService", AccessibilityStatus.HOME_ON_FIRE))
    }

    @Test
    fun otherServicesAndEmptyListsAreNotHomeOnFire() {
        assertFalse(AccessibilityStatus.isListed("io.github.sds100.keymapper/.system.accessibility.MyAccessibilityService", AccessibilityStatus.HOME_ON_FIRE))
        assertFalse(AccessibilityStatus.isListed("io.github.toolicious.homeonfire/.OtherService", AccessibilityStatus.HOME_ON_FIRE))
        assertFalse(AccessibilityStatus.isListed(null, AccessibilityStatus.HOME_ON_FIRE))
        assertFalse(AccessibilityStatus.isListed("", AccessibilityStatus.HOME_ON_FIRE))
    }

    @Test
    fun statusFollowsTheSecureSetting() {
        val status = AccessibilityStatus(app)
        assertFalse(status.homeOnFireEnabled.value)
        assertFalse(status.enabled.value)

        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "io.github.toolicious.homeonfire/.HijackService")
        status.refresh()

        assertTrue(status.homeOnFireEnabled.value)
        assertFalse(status.enabled.value)

        Settings.Secure.putString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "io.github.toolicious.homeonfire/.HijackService:" + ComponentName(app, QLAccessibilityService::class.java).flattenToShortString(),
        )
        status.refresh()

        assertTrue(status.homeOnFireEnabled.value)
        assertTrue(status.enabled.value)
    }
}
