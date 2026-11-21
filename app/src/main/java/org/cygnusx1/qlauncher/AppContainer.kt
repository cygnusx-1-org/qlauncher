/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher

import android.app.Application
import android.os.Build
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Cache
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import org.cygnusx1.qlauncher.data.CategoryList
import org.cygnusx1.qlauncher.data.CategoryStore
import org.cygnusx1.qlauncher.data.ItemOverrideStore
import org.cygnusx1.qlauncher.data.ItemOverrides
import org.cygnusx1.qlauncher.data.jsonDataStore
import org.cygnusx1.qlauncher.settings.SettingsRepository
import org.cygnusx1.qlauncher.ui.theme.ThemeController
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manual dependency injection: one instance per process, created by [QLauncherApp].
 */
class AppContainer(val app: Application) {
    /** Lives as long as the process. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: SettingsRepository = SettingsRepository(
        PreferenceDataStoreFactory.create(scope = scope) { app.preferencesDataStoreFile("settings") },
        scope,
    )

    val categories: CategoryStore = CategoryStore(
        jsonDataStore(app, "categories.json", CategoryList.serializer(), CategoryList(), scope),
        scope,
    )

    val itemOverrides: ItemOverrideStore = ItemOverrideStore(
        jsonDataStore(app, "items.json", ItemOverrides.serializer(), ItemOverrides(), scope),
        scope,
    )

    val theme: ThemeController = ThemeController(settings, scope)

    val system: org.cygnusx1.qlauncher.system.SystemModule by lazy { org.cygnusx1.qlauncher.system.SystemModule(this) }

    val apps: org.cygnusx1.qlauncher.apps.AppsModule by lazy { org.cygnusx1.qlauncher.apps.AppsModule(this) }

    val backup: org.cygnusx1.qlauncher.settings.backup.BackupModule by lazy { org.cygnusx1.qlauncher.settings.backup.BackupModule(this) }

    val wallpaper: org.cygnusx1.qlauncher.wallpaper.WallpaperModule by lazy { org.cygnusx1.qlauncher.wallpaper.WallpaperModule(this) }

    /** *Enable anonymous crashes and errors*. */
    val reports: org.cygnusx1.qlauncher.reports.ErrorReports by lazy {
        org.cygnusx1.qlauncher.reports.ErrorReports(app, settings, scope, okHttp = { okHttp })
    }

    /** QLauncher's own updates. */
    val updates: org.cygnusx1.qlauncher.update.UpdateChecker by lazy {
        org.cygnusx1.qlauncher.update.UpdateChecker(settings, scope, { okHttp }, { apps.installer })
    }

    /** Shared HTTP client. The User-Agent names the app and nothing about the user. */
    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cache(Cache(File(app.cacheDir, "http"), 20L * 1024 * 1024))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.header("User-Agent") != null) {
                    chain.proceed(req)
                } else {
                    chain.proceed(req.newBuilder().header("User-Agent", USER_AGENT).build())
                }
            }
            .build()
    }

    /** Coil image loader: 50 MB disk cache, GIF/animated WebP/APNG, SVG and video frames. */
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(app)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttp }))
                // ImageDecoder plays GIF, animated WebP and APNG from Android 9; before that, GIF only.
                if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
                add(SvgDecoder.Factory())
                add(VideoFrameDecoder.Factory())
                apps.registerImageComponents(this)
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(app, 0.15).build() }
            .diskCache { DiskCache.Builder().directory(File(app.cacheDir, "images").toOkioPath()).maxSizeBytes(50L * 1024 * 1024).build() }
            .crossfade(200)
            .build()
    }

    companion object {
        /**
         * Names the app and its public project page (Wikimedia's User-Agent policy asks for a
         * contact URL), and nothing about the user.
         */
        val USER_AGENT = "QLauncher/${BuildConfig.VERSION_NAME} (+https://github.com/cygnusx-1-org/qlauncher; Android TV launcher) okhttp/5"
    }
}
