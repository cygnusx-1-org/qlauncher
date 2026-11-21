/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.update

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.cygnusx1.qlauncher.BuildConfig
import org.cygnusx1.qlauncher.install.AppInstaller
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.SettingsRepository
import java.io.IOException
import java.util.Locale

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latest: String) : UpdateState
    data class Available(val update: UpdateInfo) : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/**
 * QLauncher's own updates, modelled on Electric Eel's checker: the feed at [FEED_URL] is read
 * at most every [AUTO_INTERVAL_MS] while *General › Check for updates* is on, or at once from
 * *About › QLauncher › Check for updates*. A newer version is announced once with a toast, and
 * installed through [AppInstaller] like any other APK.
 */
class UpdateChecker(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val okHttp: () -> OkHttpClient,
    private val installer: () -> AppInstaller,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null

    /** From *Check for updates*: reads the feed now. */
    fun checkNow() {
        if (job?.isActive == true) return
        job = scope.launch { check() }
    }

    /**
     * On the home screen: reads the feed when the setting is on and the last read is old, and
     * calls [announce] (on the main thread) for a newer version not announced before.
     */
    fun autoCheck(announce: (String) -> Unit) {
        val s = settings.current
        if (!s[Keys.General.checkUpdates] || job?.isActive == true) return
        if (System.currentTimeMillis() - s[Keys.Internal.lastUpdateCheck] < AUTO_INTERVAL_MS) return
        job = scope.launch {
            val result = check()
            if (result is UpdateState.Available && result.update.versionName != settings.current[Keys.Internal.updateAnnounced]) {
                settings.set(Keys.Internal.updateAnnounced, result.update.versionName)
                withContext(Dispatchers.Main) { announce(result.update.versionName) }
            }
        }
    }

    private suspend fun check(): UpdateState {
        _state.value = UpdateState.Checking
        val result = try {
            val text = withContext(Dispatchers.IO) { fetch() }
            when (val r = UpdateManifest.parse(text, BuildConfig.VERSION_NAME, Build.SUPPORTED_ABIS.firstOrNull(), Locale.getDefault().language)) {
                is ManifestResult.UpToDate -> UpdateState.UpToDate(r.latest)
                is ManifestResult.Available -> UpdateState.Available(r.update)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            UpdateState.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Bad update feed", e)
            UpdateState.Failed(e.message ?: e.javaClass.simpleName)
        }
        if (result !is UpdateState.Failed) settings.set(Keys.Internal.lastUpdateCheck, System.currentTimeMillis())
        _state.value = result
        return result
    }

    private fun fetch(): String {
        val request = Request.Builder().url(FEED_URL).cacheControl(CacheControl.FORCE_NETWORK).build()
        okHttp().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body.string()
        }
    }

    /** Downloads and installs the available update; its progress is the installer's state. */
    fun install(context: Context) {
        val update = (_state.value as? UpdateState.Available)?.update ?: return
        installer().installUpdate(context, update.downloadUrls, installName(update))
    }

    companion object {
        private const val TAG = "QLUpdates"
        const val FEED_URL = "https://raw.githubusercontent.com/cygnusx-1-org/qlauncher/master/updates/qlauncher.json"
        const val AUTO_INTERVAL_MS = 12L * 60 * 60 * 1000

        /** The name the installer's messages use for the update. */
        fun installName(update: UpdateInfo) = "QLauncher ${update.versionName}"
    }
}
