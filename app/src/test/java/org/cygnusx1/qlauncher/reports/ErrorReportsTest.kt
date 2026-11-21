/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.reports

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ErrorReportsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsRepository
    private lateinit var reports: ErrorReports

    @Before
    fun setUp() {
        server.start()
        // A real dispatcher: DataStore never finishes a write on a test scheduler nothing advances.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(tmp.root, "settings.preferences_pb")
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file }, scope)
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "reports").deleteRecursively()
        reports = ErrorReports(context, settings, scope, { OkHttpClient() }, server.url("/v1/reports").toString())
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun enable(on: Boolean) {
        runBlocking { settings.set(Keys.General.crashErrorReports, on) }
        // The settings state follows the store on the scope.
        repeat(100) { if (settings.current[Keys.General.crashErrorReports] != on) Thread.sleep(20) }
    }

    /** An occurrence [ago] ms before now (older than 30 days would be dropped as stale). */
    private fun occurrence(ago: Long, e: Throwable = IllegalStateException("boom")) =
        ReportFormat.pending("crash", null, "main", e, System.currentTimeMillis() - ago)

    @Test(timeout = 20_000)
    fun aStoredBatchIsSentGzippedAndDeleted() {
        enable(true)
        reports.write(occurrence(1_000))
        reports.write(occurrence(2_000))
        reports.write(occurrence(3_000, IllegalArgumentException("other")))
        server.enqueue(MockResponse.Builder().code(201).body("""{"id":"K7M2QX9P","receivedAt":"x"}""").build())

        reports.send()

        val request = server.takeRequest(5, TimeUnit.SECONDS) ?: error("nothing sent; enabled=${settings.current[Keys.General.crashErrorReports]}")
        assertEquals("gzip", request.headers["Content-Encoding"])
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        val text = GZIPInputStream(request.body!!.toByteArray().inputStream()).readBytes().decodeToString()
        val report = ReportFormat.json.decodeFromString(Report.serializer(), text)
        // Newest first: the two "boom" occurrences (newest 1 s ago), then "other" (3 s ago).
        assertEquals(listOf(2, 1), report.events.map { it.count })
        assertTrue(report.app.packageName.isNotEmpty())
        assertTrue(reports.pendingFiles().isEmpty())
    }

    @Test(timeout = 20_000)
    fun aServerErrorKeepsTheBatch() {
        enable(true)
        reports.write(occurrence(1_000))
        server.enqueue(MockResponse.Builder().code(503).build())
        reports.send()
        assertEquals(1, reports.pendingFiles().size)
    }

    @Test(timeout = 20_000)
    fun aRefusedBatchIsDropped() {
        enable(true)
        reports.write(occurrence(1_000))
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":"schema"}""").build())
        reports.send()
        assertTrue(reports.pendingFiles().isEmpty())
    }

    @Test(timeout = 20_000)
    fun nothingIsRecordedOrSentWhileOff() {
        enable(false)
        reports.record("install", IllegalStateException("x"))
        reports.write(occurrence(1_000))
        reports.send()
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = 20_000)
    fun expectedFailuresAreNotRecorded() {
        enable(true)
        reports.record("install", java.io.IOException("offline"))
        reports.record("install", kotlinx.coroutines.CancellationException("left"))
        assertTrue(reports.pendingFiles().isEmpty())
    }

    @Test(timeout = 20_000)
    fun turningItOffDeletesWhatIsWaiting() {
        enable(true)
        reports.start()
        reports.write(occurrence(1_000))
        enable(false)
        // The delete runs on the IO dispatcher.
        repeat(100) { if (reports.pendingFiles().isNotEmpty()) Thread.sleep(20) }
        assertTrue(reports.pendingFiles().isEmpty())
    }
}
