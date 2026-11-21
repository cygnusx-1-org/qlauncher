/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.plugin

import android.os.IBinder
import android.os.Parcel
import org.cygnusx1.qlauncher.wallpaper.Wallpaper
import org.cygnusx1.qlauncher.wallpaper.WallpaperEvent
import org.cygnusx1.qlauncher.wallpaper.WallpaperType

/**
 * The wallpaper-provider plugin protocol, written from the published API of
 * github.com/spocky/projectivy-plugin-wallpaper-provider (Apache-2.0; `api` module, commit
 * 60ec7b4, 2026-04-25):
 *
 * ```
 * interface IWallpaperProviderService {           // tv.projectivy.plugin.wallpaperprovider.api
 *     List<Wallpaper> getWallpapers(in Event event);
 *     String getPreferences();
 *     void setPreferences(String params);
 * }
 * @Parcelize data class Wallpaper(uri: String, type: Int, displayMode: Int, title: String?,
 *     source: String?, author: String?, actionUri: String?)
 * sealed class Event(eventType: Int)  // @Parcelize subclasses, eventType written first
 * ```
 *
 * The client side is written by hand instead of with AIDL, so no `tv.projectivy` classes are
 * compiled into the app and no AIDL build feature is needed. Only the interface descriptor and
 * the parcel layout cross the process boundary, and both match the published API.
 */
object PluginApi {
    /** Services answering this action are wallpaper plugins. */
    const val ACTION = "tv.projectivy.plugin.WALLPAPER_PROVIDER"

    /** The AIDL interface descriptor the plugins' stubs check. */
    const val DESCRIPTOR = "tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService"

    const val TRANSACTION_GET_WALLPAPERS = IBinder.FIRST_CALL_TRANSACTION
    const val TRANSACTION_GET_PREFERENCES = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TRANSACTION_SET_PREFERENCES = IBinder.FIRST_CALL_TRANSACTION + 2

    /** Broadcast by a plugin when its wallpapers changed. */
    const val ACTION_PROVIDER_UPDATED = "tv.projectivy.plugin.action.WALLPAPER_PROVIDER_UPDATED"
    const val EXTRA_PROVIDER_ID = "tv.projectivy.plugin.extra.PROVIDER_ID"

    // Service meta-data.
    const val META_API_VERSION = "apiVersion"
    const val META_UUID = "uuid"
    const val META_NAME = "name"
    const val META_SETTINGS_ACTIVITY = "settingsActivity"
    const val META_CACHE_MILLIS = "itemsCacheDurationMillis"
    const val META_UPDATE_MODE = "updateMode"

    const val SUPPORTED_API_VERSION = 1
    const val DEFAULT_UPDATE_MODE = 1
    const val DEFAULT_CACHE_MILLIS = 3_600_000L

    // Plugin wallpaper types.
    private const val TYPE_IMAGE = 0
    private const val TYPE_DRAWABLE = 1
    private const val TYPE_ANIMATED_DRAWABLE = 2
    private const val TYPE_LOTTIE = 3
    private const val TYPE_VIDEO = 4
    private const val TYPE_COLOR = 5

    /**
     * Writes [event] as a `@Parcelize` subclass of the plugin's `Event` writes itself: its event
     * type first, then its properties in declaration order. `TimeElapsed` is an object, which
     * Parcelize writes as the single int 1, its event type.
     */
    fun writeEvent(parcel: Parcel, event: WallpaperEvent) {
        when (event) {
            WallpaperEvent.TimeElapsed, WallpaperEvent.LoadingError -> parcel.writeInt(1)
            is WallpaperEvent.NowPlayingChanged -> {
                parcel.writeInt(event.eventType)
                parcel.writeInt(if (event.isPlaying) 1 else 0)
                parcel.writeString(event.title)
                parcel.writeString(event.artist)
                parcel.writeString(event.album)
                parcel.writeString(event.iconUri)
            }
            is WallpaperEvent.CardFocused -> {
                parcel.writeInt(event.eventType)
                parcel.writeInt(event.lightColor)
                parcel.writeInt(event.darkColor)
                parcel.writeString(event.title)
                parcel.writeString(event.packageName)
            }
            is WallpaperEvent.ProgramCardFocused -> {
                parcel.writeInt(event.eventType)
                parcel.writeString(event.title)
                val t = event.type
                if (t == null) {
                    parcel.writeInt(0)
                } else {
                    parcel.writeInt(1)
                    parcel.writeInt(t)
                }
                parcel.writeString(event.iconUri)
                parcel.writeInt(event.aspect)
            }
            is WallpaperEvent.LauncherIdleModeChanged -> {
                parcel.writeInt(event.eventType)
                parcel.writeInt(if (event.isIdle) 1 else 0)
            }
        }
    }

    /** Reads one plugin `Wallpaper` (field order of its constructor). */
    fun readWallpaper(parcel: Parcel): Wallpaper? {
        val uri = parcel.readString()
        val type = parcel.readInt()
        val displayMode = parcel.readInt()
        val title = parcel.readString()
        val source = parcel.readString()
        val author = parcel.readString()
        val actionUri = parcel.readString()
        if (uri.isNullOrBlank()) return null
        val t = when (type) {
            TYPE_IMAGE, TYPE_DRAWABLE -> WallpaperType.IMAGE
            TYPE_ANIMATED_DRAWABLE -> WallpaperType.ANIMATED
            TYPE_LOTTIE -> WallpaperType.LOTTIE
            TYPE_VIDEO -> WallpaperType.VIDEO
            TYPE_COLOR -> WallpaperType.COLOR
            else -> WallpaperType.IMAGE
        }
        return Wallpaper(uri = uri, type = t, displayMode = displayMode, title = title, source = source, author = author, actionUri = actionUri)
    }

    /** Writes a plugin `Wallpaper`; used by tests to play the plugin's side. */
    fun writeWallpaper(parcel: Parcel, w: Wallpaper) {
        parcel.writeString(w.uri)
        parcel.writeInt(w.type.code)
        parcel.writeInt(w.displayMode)
        parcel.writeString(w.title)
        parcel.writeString(w.source)
        parcel.writeString(w.author)
        parcel.writeString(w.actionUri)
    }

    /** Reads a list written with `writeTypedList`: a count (−1 for null), then a presence flag and the item for each. */
    fun readWallpaperList(parcel: Parcel): List<Wallpaper> {
        val n = parcel.readInt()
        if (n < 0) return emptyList()
        val out = ArrayList<Wallpaper>(n.coerceAtMost(1000))
        repeat(n) {
            if (parcel.readInt() != 0) readWallpaper(parcel)?.let(out::add)
        }
        return out
    }
}

/** A hand-written AIDL proxy for `IWallpaperProviderService`. Calls block; run them off the main thread. */
class WallpaperProviderServiceProxy(private val remote: IBinder) {
    fun getWallpapers(event: WallpaperEvent): List<Wallpaper> = call(PluginApi.TRANSACTION_GET_WALLPAPERS, { data ->
        data.writeInt(1)
        PluginApi.writeEvent(data, event)
    }) { reply -> PluginApi.readWallpaperList(reply) }

    fun getPreferences(): String? = call(PluginApi.TRANSACTION_GET_PREFERENCES, {}) { it.readString() }

    fun setPreferences(params: String?) {
        call(PluginApi.TRANSACTION_SET_PREFERENCES, { it.writeString(params) }) { }
    }

    private fun <T> call(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(PluginApi.DESCRIPTOR)
            write(data)
            remote.transact(code, data, reply, 0)
            reply.readException()
            return read(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
