/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KeysTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun namesAndProjectivyKeysAreUnique() {
        val names = Keys.registry.map { it.name }
        assertEquals(names.size, names.toSet().size)
        val pk = Keys.registry.mapNotNull { it.projectivyKey }
        assertEquals(pk.size, pk.toSet().size)
    }

    @Test
    fun defaultsAreInRange() {
        Keys.registry.forEach { k ->
            when (k) {
                is IntKey -> assertTrue(k.name, k.default in k.min..k.max)
                is FloatKey -> assertTrue(k.name, k.default in k.min..k.max)
                is ChoiceKey -> assertTrue(k.name, k.default in 0 until k.count)
                else -> Unit
            }
        }
    }

    @Test
    fun appendixADefaults() {
        assertEquals(0.3f, Keys.Cards.roundedCorners.default)
        assertEquals(8, Keys.Cards.spacing.default)
        assertEquals(32, Keys.Focus.glowSize.default)
        assertEquals(3, Keys.Focus.scale.default)
        assertEquals(60, Keys.Wallpaper.updateDelay.default)
        assertEquals(3, Keys.Wallpaper.transition.default)
        assertEquals(0, Keys.Wallpaper.wrongFormatMode.default)
        assertEquals(800, Keys.Wallpaper.minAcceptedWidth.default)
        assertEquals(0.5f, Keys.Categories.topMargin.default)
        assertEquals(0.05f, Keys.Categories.startMargin.default)
        assertEquals(0.95f, Keys.Categories.endMargin.default)
        assertEquals(0.3f, Keys.Categories.unselectedOpacity.default)
        assertEquals(6, Keys.Categories.dockPadding.default)
        assertEquals(3_600_000L, Keys.Power.idleTimeout.default)
        assertEquals(32, Keys.Clock.size.default)
        assertEquals(2.0f, Keys.Idle.timeout.default)
    }

    @Test
    fun coerceClampsAndRejects() {
        assertEquals(16, Keys.Cards.spacing.coerce(99))
        assertEquals(1, Keys.Cards.spacing.coerce(-3))
        assertEquals(1.5f, Keys.Cards.size.coerce(7.0))
        assertNull(Keys.Focus.effect.coerce(9))
        assertEquals(2, Keys.Focus.effect.coerce(2.0))
        assertNull(Keys.Cards.showTitle.coerce("maybe"))
        assertEquals(false, Keys.Cards.showTitle.coerce("false"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun onlyChangedValuesAreStoredAndExported() = runTest(UnconfinedTestDispatcher()) {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val file = File(tmp.root, "settings.preferences_pb")
        val repo = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { file }, scope.backgroundScope)
        repo.set(Keys.Cards.spacing, 12)
        repo.set(Keys.Cards.showTitle, true) // the default: not stored
        repo.set(Keys.Internal.onboardingCompleted, true) // never exported
        assertTrue(repo.current.isSet(Keys.Cards.spacing))
        assertFalse(repo.current.isSet(Keys.Cards.showTitle))
        val exported = repo.exportValues()
        assertEquals(mapOf("cards.spacing" to 12), exported)
        assertEquals(mapOf("cards.spacing" to 12), repo.exportValues(themeOnly = true))

        repo.importValues(mapOf(Keys.Clock.size to 48), clearOthers = true)
        assertEquals(48, repo.current[Keys.Clock.size])
        assertEquals(8, repo.current[Keys.Cards.spacing])
        assertTrue(repo.current[Keys.Internal.onboardingCompleted])

        // Enable channels keeps an explicit "off", so it no longer follows the permission.
        repo.set(Keys.General.enableChannels, false)
        assertTrue(repo.current.isSet(Keys.General.enableChannels))
        assertFalse(repo.current[Keys.General.enableChannels])

        repo.resetAll()
        assertFalse(repo.current.isSet(Keys.Internal.onboardingCompleted))
    }
}
