/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.parental

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.cygnusx1.qlauncher.data.ItemKeys
import org.cygnusx1.qlauncher.data.ItemOverride
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.SettingKey
import org.cygnusx1.qlauncher.settings.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ParentalManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeClock : ParentalClock {
        var elapsed = 5_000_000L
        var timeOfDay = 12 * 3_600_000L
        override fun elapsedMillis() = elapsed
        override fun millisOfDay() = timeOfDay
    }

    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsRepository
    private val overrides = MutableStateFlow<Map<String, ItemOverride>>(emptyMap())
    private val clock = FakeClock()
    private lateinit var manager: ParentalManager

    private val netflix = ItemKeys.app("com.example.netflix", "com.example.netflix.Main")

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("settings.preferences_pb").also { it.delete() } }
        settings = SettingsRepository(store, scope)
        manager = ParentalManager(
            settings = settings,
            overrides = overrides,
            updateOverride = { key, transform ->
                overrides.value = overrides.value + (key to transform(overrides.value[key] ?: ItemOverride()))
            },
            scope = scope,
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun seq(s: String) = ParentalToken.parse(s)!!

    private fun <T : Any> set(key: SettingKey<T>, value: T) = runBlocking {
        settings.set(key, value)
        settings.settings.first { it[key] == value }
    }

    private fun enter(matcher: CodeMatcher, s: String) = seq(s).map { matcher.onToken(it) }.last()

    @Test
    fun noCodeMeansNothingIsAsked() {
        manager.setLocked(netflix, true)
        assertFalse(manager.isCodeSet())
        assertFalse(manager.requiresCode())
        assertFalse(manager.needsCodeToLaunch(netflix))
        assertNull(manager.newMatcher())
    }

    @Test
    fun settingACodeStoresOnlyAHashAndShowsTheRecoveryCodeOnce() = runBlocking {
        val recovery = manager.setCode(seq("UDLR42"))
        assertNotNull(recovery)
        assertTrue(Regex("[1-4]{4} [1-4]{4} [1-4]{4}").matches(recovery!!))
        val stored = settings.current[Keys.Parental.codeHash]
        assertTrue(stored.startsWith("v1:6:"))
        assertFalse(stored.contains("UDLR42"))
        assertTrue(manager.isCodeSet())
        assertTrue(manager.codeSet.first { it })

        val recoveryHash = settings.current[Keys.Parental.recoveryHash]
        assertNull("a later change keeps the recovery code", manager.setCode(seq("1111")))
        assertEquals(recoveryHash, settings.current[Keys.Parental.recoveryHash])

        // The shown digits unlock.
        clock.elapsed += 60_000
        val m = manager.newMatcher()!!
        assertEquals(CodeMatcher.Result.RecoveryMatched, enter(m, recovery.replace(" ", "")))
    }

    @Test
    fun lockedAppNeedsTheCodeUntilAValidationAndAgainAfter15Seconds() = runBlocking {
        manager.setCode(seq("ULDR"))
        manager.setLocked(netflix, true)
        assertTrue(manager.isLocked(netflix))
        assertTrue(manager.isPackageLocked("com.example.netflix"))
        assertFalse(manager.isPackageLocked("com.example.other"))

        assertFalse("setting the code counts as a validation", manager.needsCodeToLaunch(netflix))
        clock.elapsed += 15_000
        assertTrue(manager.needsCodeToLaunch(netflix))
        assertTrue(manager.needsCodeForPackage("com.example.netflix"))

        val m = manager.newMatcher()!!
        assertEquals(CodeMatcher.Result.Matched, enter(m, "DDULDR"))
        manager.onValidated()
        assertFalse(manager.needsCodeToLaunch(netflix))
        clock.elapsed += 14_999
        assertFalse(manager.needsCodeToLaunch(netflix))
        clock.elapsed += 1
        assertTrue(manager.needsCodeToLaunch(netflix))
    }

    @Test
    fun longDurationRemembersAValidationFor15Minutes() = runBlocking {
        manager.setCode(seq("12"))
        set(Keys.Parental.longDuration, true)
        manager.onValidated()
        clock.elapsed += 15 * 60_000L - 1
        assertFalse(manager.requiresCode())
        clock.elapsed += 1
        assertTrue(manager.requiresCode())
    }

    @Test
    fun outsideTheProtectionWindowLockedAppsOpen() = runBlocking {
        manager.setCode(seq("12"))
        manager.setLocked(netflix, true)
        clock.elapsed += 60_000
        set(Keys.Parental.partial, true)
        set(Keys.Parental.partialStart, 20 * 3_600_000L)
        set(Keys.Parental.partialEnd, 6 * 3_600_000L)

        clock.timeOfDay = 12 * 3_600_000L
        assertFalse(manager.needsCodeToLaunch(netflix))
        clock.timeOfDay = 22 * 3_600_000L
        assertTrue(manager.needsCodeToLaunch(netflix))
        clock.timeOfDay = 5 * 3_600_000L
        assertTrue(manager.needsCodeToLaunch(netflix))
    }

    @Test
    fun settingsProtectionAppliesAroundTheClock() = runBlocking {
        manager.setCode(seq("12"))
        clock.elapsed += 60_000
        assertFalse("off by default", manager.needsCodeForSettings())
        set(Keys.Parental.protectSettings, true)
        set(Keys.Parental.partial, true)
        set(Keys.Parental.partialStart, 20 * 3_600_000L)
        set(Keys.Parental.partialEnd, 21 * 3_600_000L)
        clock.timeOfDay = 9 * 3_600_000L
        assertTrue(manager.needsCodeForSettings())
        manager.onValidated()
        assertFalse(manager.needsCodeForSettings())
    }

    @Test
    fun clearingTheCodeRemovesBothHashes() = runBlocking {
        manager.setCode(seq("12"))
        manager.clearCode()
        assertFalse(manager.isCodeSet())
        assertEquals("", settings.current[Keys.Parental.codeHash])
        assertEquals("", settings.current[Keys.Parental.recoveryHash])
        assertNotNull("a new code gets a new recovery code", manager.setCode(seq("34")))
    }

    @Test
    fun unlockingAnApp() {
        manager.setLocked(netflix, true)
        manager.setLocked(netflix, false)
        assertFalse(manager.isLocked(netflix))
    }
}
