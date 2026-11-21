/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.reports

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.cygnusx1.qlauncher.BuildConfig
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.SettingsRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.GZIPOutputStream

/**
 * *Enable anonymous crashes and errors* (off by default): with it on, uncaught crashes and the
 * unexpected errors passed to [record] are kept on disk and sent in batches to
 * [ENDPOINT]. With it off nothing is recorded, and turning it off deletes what is waiting.
 *
 * Expected failures are not errors: an [IOException] (network, a missing file) or a cancelled
 * coroutine is never recorded.
 */
class ErrorReports(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val okHttp: () -> OkHttpClient,
    private val endpoint: String = ENDPOINT,
) {
    private val dir = File(context.filesDir, "reports")
    private val sendLock = Mutex()
    private var sendJob: Job? = null

    private val enabled: Boolean get() = settings.current[Keys.General.crashErrorReports]

    /** Wraps the default handler: a crash is written synchronously, then the app dies as before. */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                if (enabled) write(ReportFormat.pending(ReportFormat.KIND_CRASH, null, thread.name, e, System.currentTimeMillis()))
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Sends what an earlier run left, and follows the setting: off deletes what is waiting. */
    fun start() {
        scope.launch {
            settings.settings.map { it[Keys.General.crashErrorReports] }.distinctUntilChanged().collect { on ->
                if (on) scheduleSend(START_DELAY_MS) else withContext(Dispatchers.IO) { clear() }
            }
        }
    }

    /** An unexpected error caught at [where] (a short tag such as `backup.import`). */
    fun record(where: String, e: Throwable) {
        if (!enabled || e is IOException || e is CancellationException) return
        val event = ReportFormat.pending(ReportFormat.KIND_ERROR, where, Thread.currentThread().name, e, System.currentTimeMillis())
        scope.launch(Dispatchers.IO) {
            runCatching { write(event) }
            scheduleSend(ERROR_DELAY_MS)
        }
    }

    internal fun write(event: PendingEvent) {
        dir.mkdirs()
        val file = File(dir, "${event.at}-${UUID.randomUUID()}.json")
        file.writeText(ReportFormat.json.encodeToString(PendingEvent.serializer(), event))
        trim()
    }

    /** At most [MAX_PENDING] occurrences are kept, the newest. */
    private fun trim() {
        val files = pendingFiles()
        if (files.size > MAX_PENDING) files.sortedBy { it.name }.take(files.size - MAX_PENDING).forEach { it.delete() }
    }

    internal fun pendingFiles(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.toList().orEmpty()

    private fun clear() {
        pendingFiles().forEach { it.delete() }
    }

    private fun scheduleSend(delayMs: Long) {
        if (sendJob?.isActive == true) return
        sendJob = scope.launch {
            delay(delayMs)
            sendLock.withLock { withContext(Dispatchers.IO) { send() } }
        }
    }

    /** One batch. Sent or refused for good, its files are deleted; a network or server error keeps them. */
    internal fun send() {
        if (!enabled) return
        val now = System.currentTimeMillis()
        val loaded = pendingFiles().mapNotNull { f ->
            val event = runCatching { ReportFormat.json.decodeFromString(PendingEvent.serializer(), f.readText()) }.getOrNull()
            if (event == null || now - event.at > MAX_AGE_MS) {
                f.delete()
                null
            } else {
                f to event
            }
        }
        if (loaded.isEmpty()) return
        val (report, covered) = ReportFormat.build(loaded.map { it.second }, appInfo(), systemInfo(), now)
        val body = gzip(ReportFormat.json.encodeToString(Report.serializer(), report).encodeToByteArray())
        val request = Request.Builder()
            .url(endpoint)
            .header("Content-Encoding", "gzip")
            .post(body.toRequestBody(JSON))
            .build()
        val code = try {
            okHttp().newCall(request).execute().use { it.code }
        } catch (e: IOException) {
            Log.i(TAG, "Reports not sent: ${e.javaClass.simpleName}")
            return
        }
        // 201 stored; 400 and 413 would be refused again, so they are dropped too.
        if (code == 201 || code == 400 || code == 413) {
            val coveredSet = covered.toSet()
            loaded.filter { it.second in coveredSet }.forEach { it.first.delete() }
            if (code != 201) Log.w(TAG, "Reports refused: HTTP $code")
        } else {
            Log.i(TAG, "Reports not sent: HTTP $code")
        }
    }

    private fun appInfo(): ReportApp = ReportApp(
        packageName = context.packageName,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        buildType = BuildConfig.BUILD_TYPE,
    )

    private fun systemInfo(): ReportSystem = ReportSystem(
        sdkInt = Build.VERSION.SDK_INT,
        release = Build.VERSION.RELEASE.orEmpty(),
        make = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        device = Build.DEVICE.orEmpty(),
        abis = Build.SUPPORTED_ABIS.toList(),
        tv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
    )

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "QLReports"
        const val ENDPOINT = "https://qlauncher-data.cygnusx-1.org/v1/reports"
        private val JSON = "application/json".toMediaType()
        private const val MAX_PENDING = 200
        private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        private const val START_DELAY_MS = 30_000L
        private const val ERROR_DELAY_MS = 60_000L

        @Volatile
        private var instance: ErrorReports? = null

        /** Makes [reports] the one [recordError] uses. */
        fun install(reports: ErrorReports) {
            instance = reports
            reports.installCrashHandler()
            reports.start()
        }

        /** Records an unexpected error from anywhere in the app; a no-op before [install]. */
        fun recordError(where: String, e: Throwable) {
            instance?.record(where, e)
        }
    }
}
