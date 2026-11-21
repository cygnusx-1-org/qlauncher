/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup.autobackup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.cygnusx1.qlauncher.settings.backup.ImportException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

@OptIn(ExperimentalCoroutinesApi::class)
class AutoBackupStoreTest {
    @get:Rule
    val tmp = TemporaryFolder(File("build/test-tmp").apply { mkdirs() })

    private fun newKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `keeps the five newest, newest first`() = runTest {
        var now = 1_700_000_000_000L
        val dir = tmp.newFolder()
        val key = newKey()
        val store = AutoBackupStore(dir, AesGcmBlobCipher { key }, clock = { now }, io = StandardTestDispatcher(testScheduler))
        repeat(7) { i ->
            now += 60_000
            store.write(mapOf("manifest.json" to "{\"n\":$i}".encodeToByteArray()), if (i == 6) AutoBackupReason.BeforeImport else AutoBackupReason.Change)
        }
        val entries = store.entries.value
        assertEquals(5, entries.size)
        assertEquals(5, dir.listFiles()!!.size)
        assertEquals(entries.sortedByDescending { it.createdAt }, entries)
        assertEquals(now, entries.first().createdAt)
        assertEquals(AutoBackupReason.BeforeImport, entries.first().reason)
        assertTrue(entries.all { it.sizeBytes > 0 })
        assertArrayEquals("{\"n\":6}".encodeToByteArray(), store.read(entries.first())["manifest.json"])
        assertArrayEquals("{\"n\":2}".encodeToByteArray(), store.read(entries.last())["manifest.json"])
        assertEquals(entries, store.refresh())

        store.deleteAll()
        assertTrue(store.entries.value.isEmpty())
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `files are encrypted and unreadable with another key`() = runTest {
        val dir = tmp.newFolder()
        val store = AutoBackupStore(dir, AesGcmBlobCipher(newKey().let { k -> { k } }), io = StandardTestDispatcher(testScheduler))
        val entry = store.write(mapOf("settings.json" to "{\"clock.size\":48}".encodeToByteArray()), AutoBackupReason.Change)
        val raw = entry.file.readBytes()
        assertTrue(!String(raw, Charsets.ISO_8859_1).contains("clock.size"))
        val other = AutoBackupStore(dir, AesGcmBlobCipher(newKey().let { k -> { k } }), io = StandardTestDispatcher(testScheduler))
        try {
            other.read(entry)
            fail("expected Unreadable")
        } catch (_: ImportException.Unreadable) {
        }
    }

    @Test
    fun `file names carry time and reason`() {
        val name = AutoBackupStore.fileName(1_763_600_000_123L, AutoBackupReason.BeforeMigration)
        assertEquals("auto-20251120-005320-123-migration.qlauto", name)
        assertEquals(1_763_600_000_123L to AutoBackupReason.BeforeMigration, AutoBackupStore.parseName(name))
        assertEquals(null, AutoBackupStore.parseName("notes.txt"))
    }

    @Test
    fun `triggers fire once, ten seconds after the last change, never for the initial state`() = runTest {
        val state = MutableStateFlow<Any>(0)
        val fired = ArrayList<Any>()
        val job = launch { autoBackupTriggers(state).collect { fired += it } }
        runCurrent()
        advanceTimeBy(30_000)
        assertEquals(emptyList<Any>(), fired)

        state.value = 1
        advanceTimeBy(5_000)
        state.value = 2
        advanceTimeBy(9_000)
        assertEquals(emptyList<Any>(), fired)
        advanceTimeBy(1_001)
        assertEquals(listOf<Any>(2), fired)

        // The same value again is not a change.
        state.value = 2
        advanceTimeBy(20_000)
        assertEquals(listOf<Any>(2), fired)
        job.cancel()
    }
}
