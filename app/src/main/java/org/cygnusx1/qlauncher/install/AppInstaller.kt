/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.cygnusx1.qlauncher.apps.PackageEvent
import org.cygnusx1.qlauncher.apps.PmCompat
import org.cygnusx1.qlauncher.reports.ErrorReports
import java.io.File
import java.io.IOException

/** Where an installation is. */
sealed interface InstallState {
    data object Idle : InstallState

    /** [total] is -1 when the server does not say. */
    data class Downloading(val name: String, val bytes: Long, val total: Long) : InstallState {
        /** 0..1, or null when the size is unknown. */
        val progress: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
    }

    data class Installing(val name: String) : InstallState

    /** The system's install confirmation is showing. */
    data class AwaitingConfirmation(val name: String) : InstallState

    data class Success(val name: String, val packageName: String?) : InstallState

    /**
     * QLauncher may not install apps yet; the system page that allows it was opened. Call
     * [AppInstaller.onResume] when the user comes back and the install continues.
     */
    data class NeedsUnknownSources(val name: String) : InstallState

    /**
     * The code went to the Downloader app. With [entered] false Downloader was only opened and
     * the user has to type the code there; show it.
     */
    data class HandedToDownloader(val code: String, val entered: Boolean) : InstallState

    data class OpenedStore(val packageName: String) : InstallState

    /** The URL was a web page, not an APK; it was opened in a browser. */
    data class OpenedInBrowser(val url: String) : InstallState

    data class Failed(val reason: FailReason, val detail: String? = null) : InstallState
}

enum class FailReason {
    InvalidInput,
    NoUsbVolume,
    UnsupportedArchive,
    Network,
    HttpError,
    NotAnApk,
    FileUnreadable,
    InstallFailed,
    Cancelled,
    UnknownSourcesDenied,
    DownloaderMissing,
    NoStore,
}

/**
 * *Install 3rd party apps*: the bundled catalogue, direct downloads installed through a
 * [PackageInstaller] session, Downloader codes handed to the Downloader app, and the store.
 *
 * Downloads go to the cache directory with the shared OkHttp client. The install status comes
 * back through a receiver registered for the life of the process.
 */
class AppInstaller internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val okHttp: () -> OkHttpClient,
    packageEvents: SharedFlow<PackageEvent>,
) {
    private val pm: PackageManager = context.packageManager
    private val downloadDir = File(context.cacheDir, "apps_install")
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    private val _catalogue = MutableStateFlow<List<CatalogueEntry>>(emptyList())
    private val _installed = MutableStateFlow<Set<String>>(emptySet())
    private var job: Job? = null
    private var pending: (() -> Unit)? = null
    private var pendingName: String? = null

    val state: StateFlow<InstallState> = _state.asStateFlow()

    /** The bundled catalogue, loaded once at start. */
    val catalogue: StateFlow<List<CatalogueEntry>> = _catalogue.asStateFlow()

    /** Catalogue packages that are installed (the tick on an entry). */
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = onStatus(intent)
    }

    init {
        ContextCompat.registerReceiver(context, statusReceiver, IntentFilter(ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch(Dispatchers.IO) {
            _catalogue.value = runCatching {
                context.assets.open(Catalogue.ASSET).use { Catalogue.parse(it.readBytes().decodeToString()) }
            }.getOrElse { emptyList() }
            refreshInstalled()
        }
        scope.launch { packageEvents.collect { withContext(Dispatchers.IO) { refreshInstalled() } } }
    }

    private fun refreshInstalled() {
        val packages = _catalogue.value.mapNotNull { it.packageName } + DOWNLOADER_PACKAGE
        _installed.value = packages.filterTo(HashSet()) { PmCompat.isInstalled(pm, it) }
    }

    fun isInstalled(entry: CatalogueEntry): Boolean = entry.packageName?.let { it in _installed.value } == true

    /** The Downloader app is installed (Downloader codes can be used). */
    val downloaderInstalled: Boolean get() = PmCompat.isInstalled(pm, DOWNLOADER_PACKAGE)

    /** Starts installing [entry] with [method] (its default when null). */
    fun install(context: Context, entry: CatalogueEntry, method: InstallMethod? = entry.defaultMethod) {
        when (method) {
            InstallMethod.Store -> entry.packageName?.let { _state.value = openStore(context, it) }
            InstallMethod.Downloader -> entry.downloader?.let { _state.value = handToDownloader(context, it) }
            InstallMethod.Direct -> entry.url?.let { installFrom(context, InstallUrls.parse(it), entry.name) }
            null -> _state.value = InstallState.Failed(FailReason.InvalidInput)
        }
    }

    /** QLauncher's own update: the first of [urls] (the device's ABI first), installed over this app. */
    fun installUpdate(context: Context, urls: List<String>, name: String) {
        val url = urls.firstOrNull() ?: return
        installFrom(context, InstallSource.Remote(url), name)
    }

    /** Installs from the *Custom URL / Downloader code* field. */
    fun installFromInput(context: Context, text: String) {
        installFrom(context, InstallUrls.parse(text), text.trim())
    }

    private fun installFrom(context: Context, source: InstallSource, name: String) {
        when (source) {
            is InstallSource.DownloaderCode -> _state.value = handToDownloader(context, source.code)
            is InstallSource.Invalid -> _state.value = InstallState.Failed(
                when (source.reason) {
                    InstallSource.Reason.NoUsbVolume -> FailReason.NoUsbVolume
                    InstallSource.Reason.Archive -> FailReason.UnsupportedArchive
                    else -> FailReason.InvalidInput
                },
            )
            is InstallSource.Remote -> withInstallPermission(context, name) { start { downloadAndInstall(source.url, name) } }
            is InstallSource.LocalFile -> withInstallPermission(context, name) { start { installLocal(source.path, name) } }
        }
    }

    /** Runs [action] once QLauncher may install packages, opening the system page first if needed. */
    private fun withInstallPermission(context: Context, name: String, action: () -> Unit) {
        if (mayInstall()) {
            action()
            return
        }
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
        } else {
            // Before Android 8 *Unknown sources* is one switch for the whole device, in Security.
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        if (PmCompat.start(context, intent)) {
            pending = action
            pendingName = name
            _state.value = InstallState.NeedsUnknownSources(name)
        } else {
            _state.value = InstallState.Failed(FailReason.UnknownSourcesDenied)
        }
    }

    /** Call from `onResume` of the page that started an install: continues after the permission page. */
    fun onResume() {
        val action = pending ?: return
        pending = null
        if (mayInstall()) {
            action()
        } else {
            _state.value = InstallState.Failed(FailReason.UnknownSourcesDenied, pendingName)
        }
        pendingName = null
    }

    /** QLauncher's own *Install unknown apps* permission from Android 8, the device-wide *Unknown sources* before. */
    private fun mayInstall(): Boolean = if (Build.VERSION.SDK_INT >= 26) {
        pm.canRequestPackageInstalls()
    } else {
        @Suppress("DEPRECATION")
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
    }

    fun cancel() {
        job?.cancel()
        job = null
        pending = null
        if (_state.value is InstallState.Downloading) _state.value = InstallState.Failed(FailReason.Cancelled)
    }

    /** Back to [InstallState.Idle] after the page has shown a result. */
    fun reset() {
        if (_state.value !is InstallState.Downloading && _state.value !is InstallState.Installing) _state.value = InstallState.Idle
    }

    private fun start(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _state.value = InstallState.Failed(FailReason.Network, e.message)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Install failed", e)
                ErrorReports.recordError("install", e)
                _state.value = InstallState.Failed(FailReason.InstallFailed, e.message)
            }
        }
    }

    private suspend fun downloadAndInstall(url: String, name: String) {
        _state.value = InstallState.Downloading(name, 0, -1)
        downloadDir.mkdirs()
        downloadDir.listFiles()?.forEach { it.delete() }
        val file = File(downloadDir, InstallUrls.fileNameOf(url))
        val call = okHttp().newCall(Request.Builder().url(url).build())
        currentCoroutineContext()[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        call.execute().use { response ->
            if (!response.isSuccessful) {
                _state.value = InstallState.Failed(FailReason.HttpError, response.code.toString())
                return
            }
            val type = response.header("Content-Type").orEmpty()
            if (type.startsWith("text/html", ignoreCase = true)) {
                withContext(Dispatchers.Main) { openInBrowser(response.request.url.toString()) }
                return
            }
            val body = response.body
            val total = body.contentLength()
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport >= REPORT_STEP || done == total) {
                            lastReport = done
                            _state.value = InstallState.Downloading(name, done, total)
                        }
                    }
                }
            }
        }
        if (!isApk(file)) {
            file.delete()
            _state.value = InstallState.Failed(FailReason.NotAnApk)
            return
        }
        commit(file, name, deleteAfter = true)
    }

    private suspend fun installLocal(path: String, name: String) {
        val file = File(path)
        if (!file.canRead()) {
            _state.value = InstallState.Failed(FailReason.FileUnreadable, path)
            return
        }
        if (!isApk(file)) {
            _state.value = InstallState.Failed(FailReason.NotAnApk)
            return
        }
        commit(file, name, deleteAfter = false)
    }

    private fun isApk(file: File): Boolean = runCatching {
        file.inputStream().use { s ->
            val b = ByteArray(4)
            s.read(b) == 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte()
        }
    }.getOrDefault(false)

    private fun commit(file: File, name: String, deleteAfter: Boolean) {
        _state.value = InstallState.Installing(name)
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 26) setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 34) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val status = Intent(ACTION_STATUS)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_NAME, name)
                    .putExtra(EXTRA_FILE, if (deleteAfter) file.absolutePath else null)
                val pi = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    status,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pi.intentSender)
            }
        } catch (e: IOException) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        } catch (e: RuntimeException) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    private fun onStatus(intent: Intent) {
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null && PmCompat.start(context, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
                    _state.value = InstallState.AwaitingConfirmation(name)
                } else {
                    _state.value = InstallState.Failed(FailReason.InstallFailed, "confirmation")
                }
                return
            }
            PackageInstaller.STATUS_SUCCESS -> {
                _state.value = InstallState.Success(name, intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME))
                scope.launch(Dispatchers.IO) { refreshInstalled() }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = InstallState.Failed(FailReason.Cancelled)
            else -> _state.value = InstallState.Failed(
                FailReason.InstallFailed,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            )
        }
        intent.getStringExtra(EXTRA_FILE)?.let { path -> scope.launch(Dispatchers.IO) { File(path).delete() } }
    }

    private fun openInBrowser(url: String) {
        val ok = PmCompat.start(context, Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        _state.value = if (ok) InstallState.OpenedInBrowser(url) else InstallState.Failed(FailReason.NotAnApk, url)
    }

    /** Hands a Downloader code to the Downloader app. */
    fun handToDownloader(context: Context, code: String): InstallState {
        if (!PmCompat.isInstalled(pm, DOWNLOADER_PACKAGE)) return InstallState.Failed(FailReason.DownloaderMissing, code)
        val direct = listOf(
            Intent(Intent.ACTION_VIEW, "downloader://$code".toUri()),
            Intent(Intent.ACTION_VIEW, "https://aftv.news/$code".toUri()),
        )
        for (i in direct) {
            if (PmCompat.start(context, i.setPackage(DOWNLOADER_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
                return InstallState.HandedToDownloader(code, entered = true)
            }
        }
        val launch = pm.getLeanbackLaunchIntentForPackage(DOWNLOADER_PACKAGE) ?: pm.getLaunchIntentForPackage(DOWNLOADER_PACKAGE)
        return if (launch != null && PmCompat.start(context, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            InstallState.HandedToDownloader(code, entered = false)
        } else {
            InstallState.Failed(FailReason.DownloaderMissing, code)
        }
    }

    /** Opens [packageName] in the store (`market://details?id=`), else its Play web page. */
    fun openStore(context: Context, packageName: String): InstallState {
        val market = Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri())
        val web = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$packageName".toUri())
        return if (PmCompat.start(context, market) || PmCompat.start(context, web)) {
            InstallState.OpenedStore(packageName)
        } else {
            InstallState.Failed(FailReason.NoStore, packageName)
        }
    }

    companion object {
        const val DOWNLOADER_PACKAGE = "com.esaba.downloader"
        private const val ACTION_STATUS = "org.cygnusx1.qlauncher.install.STATUS"
        private const val EXTRA_NAME = "org.cygnusx1.qlauncher.install.NAME"
        private const val EXTRA_FILE = "org.cygnusx1.qlauncher.install.FILE"
        private const val REPORT_STEP = 256L * 1024
        private const val TAG = "QLInstall"
    }
}
