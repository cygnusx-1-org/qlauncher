/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.displayprofiles

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.cygnusx1.qlauncher.data.jsonDataStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfileDraftTest {
    @get:Rule
    val tmp = TemporaryFolder(File("build/test-tmp").apply { mkdirs() })

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun store() = DisplayProfileStore(jsonDataStore(File(tmp.newFolder(), "p.json"), DisplayProfileList.serializer(), DisplayProfileList(), scope), scope)

    @Test
    fun `a new profile left untouched is never stored`() = runBlocking {
        val store = store()
        val draft = ProfileDraft(existing = null, newName = store.nextDefaultName("Profile #"))
        assertEquals("Profile #1", draft.profile.value.name)
        assertTrue(draft.isNew)
        assertFalse(draft.isChanged)
        assertNull(draft.commit(store))
        assertTrue(store.profiles.value.isEmpty())
    }

    @Test
    fun `a changed profile is stored when the editor is left, and edits replace it`() = runBlocking {
        val store = store()
        val draft = ProfileDraft(existing = null, newName = "Profile #1")
        draft.setPreviewPattern(CalibrationPatterns.JUDDER)
        val saved = draft.commit(store)!!
        delay(100)
        assertEquals(listOf(saved), store.profiles.value)
        assertEquals("Profile #2", store.nextDefaultName("Profile #"))

        val edit = ProfileDraft(existing = saved, newName = "unused")
        edit.rename("  Cinema ")
        edit.edit { it.copy(brightness = 60, id = "ignored") }
        edit.commit(store)
        delay(100)
        val stored = store.profiles.value.single()
        assertEquals(saved.id, stored.id)
        assertEquals("Cinema", stored.name)
        assertEquals(60, stored.brightness)
    }

    @Test
    fun `there are twelve drawable SDR patterns and twelve listed HDR and DV ones`() {
        assertEquals(24, CalibrationPatterns.all.size)
        assertEquals(12, CalibrationPatterns.sdr.size)
        assertTrue(CalibrationPatterns.all.take(12).all { it.isAvailable })
        assertEquals(9, CalibrationPatterns.all.count { it.range == PatternRange.HDR && !it.isAvailable })
        assertEquals(3, CalibrationPatterns.all.count { it.range == PatternRange.DV && !it.isAvailable })
        assertEquals((0 until 24).toList(), CalibrationPatterns.all.map { it.index })
    }
}
