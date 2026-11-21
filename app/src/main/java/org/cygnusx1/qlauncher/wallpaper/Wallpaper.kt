/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.cygnusx1.qlauncher.data.AppJson

/**
 * What a wallpaper is. Stored as Projectivy's type numbers (IMAGE 0, ANIMATED_DRAWABLE 2, LOTTIE 3,
 * VIDEO 4, COLOR 5), so a serialised wallpaper reads the same in both apps.
 */
@Serializable(with = WallpaperTypeSerializer::class)
enum class WallpaperType(val code: Int) {
    /** A still picture, or an animated GIF/WebP: the decoder decides. */
    IMAGE(0),

    /** An animated picture (GIF, animated WebP, APNG). */
    ANIMATED(2),

    /** A Lottie animation (`.json`). */
    LOTTIE(3),

    /** A video file, HTTP stream or RTSP live stream. */
    VIDEO(4),

    /** A plain colour; [Wallpaper.uri] is `#RRGGBB`. */
    COLOR(5),
    ;

    companion object {
        /** Projectivy code to type; DRAWABLE (1) and unknown codes are pictures. */
        fun fromCode(code: Int): WallpaperType = when (code) {
            2 -> ANIMATED
            3 -> LOTTIE
            4 -> VIDEO
            5 -> COLOR
            else -> IMAGE
        }
    }
}

/** Writes the Projectivy code; reads a code or a name. */
internal object WallpaperTypeSerializer : KSerializer<WallpaperType> {
    override val descriptor = PrimitiveSerialDescriptor("WallpaperType", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: WallpaperType) = encoder.encodeInt(value.code)

    override fun deserialize(decoder: Decoder): WallpaperType {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            val primitive = element as? JsonPrimitive ?: return WallpaperType.IMAGE
            primitive.intOrNull?.let { return WallpaperType.fromCode(it) }
            return WallpaperType.entries.firstOrNull { it.name.equals(primitive.content, ignoreCase = true) }
                ?: WallpaperType.IMAGE
        }
        return WallpaperType.fromCode(decoder.decodeInt())
    }
}

/**
 * A wallpaper's own display mode, in the plugin API's numbering (which differs from the
 * *Display mode* setting's): 0 none, 1 crop, 2 stretch, 3 blur, 4 fit center, 5 fit center over a
 * blurred copy.
 */
object WallpaperDisplayModes {
    const val DEFAULT = 0
    const val CROP = 1
    const val STRETCH = 2
    const val BLUR = 3
    const val FIT_CENTER = 4
    const val FIT_CENTER_BLURRED_BG = 5
}

/**
 * One wallpaper, as providers yield it and as it is stored (the *Media file* provider's value,
 * the last shown wallpaper).
 *
 * @property uri `http(s)://`, `content://`, `file://`, `android.resource://`, `rtsp(t|u)://`, a
 *   `#RRGGBB` colour for [WallpaperType.COLOR], or one of the internal URIs below.
 * @property displayMode the wallpaper's own display mode, see [WallpaperDisplayModes]; used when
 *   the *Display mode* setting is Automatic.
 * @property source a web page about the picture, opened by *This wallpaper › View*.
 * @property actionUri an intent URI to launch from the wallpaper (Random program).
 * @property width original width in pixels, 0 when unknown.
 * @property height original height in pixels, 0 when unknown.
 */
@Serializable
data class Wallpaper(
    val uri: String,
    val type: WallpaperType = WallpaperType.IMAGE,
    val displayMode: Int = WallpaperDisplayModes.DEFAULT,
    val title: String? = null,
    val source: String? = null,
    val author: String? = null,
    val licence: String? = null,
    val actionUri: String? = null,
    val width: Int = 0,
    val height: Int = 0,
) {
    /** True for the same picture, whatever metadata came with it. */
    fun sameAs(other: Wallpaper?): Boolean = other != null && other.uri == uri && other.type == type

    /** True for the built-in default or dynamic wallpaper (nothing to save or view). */
    val isInternal: Boolean get() = uri.startsWith(INTERNAL_SCHEME)

    fun toJson(): String = AppJson.encodeToString(serializer(), this)

    companion object {
        const val INTERNAL_SCHEME = "qlauncher:"

        /** The bundled default wallpaper. */
        val DEFAULT = Wallpaper(uri = "${INTERNAL_SCHEME}default", type = WallpaperType.IMAGE)

        /** The animated gradient of the *Dynamic colors* provider. */
        val DYNAMIC_COLORS = Wallpaper(uri = "${INTERNAL_SCHEME}dynamic-colors", type = WallpaperType.COLOR)

        /** The solid background of the *None* provider. */
        val NONE = Wallpaper(uri = "#000000", type = WallpaperType.COLOR)

        private val COLOR = Regex("^#([0-9A-Fa-f]{6})$")

        /** True for a value the *Media file* provider stores as a plain colour. */
        fun isColorValue(value: String): Boolean = COLOR.matches(value.trim())

        /** Parses a stored wallpaper; null when it is not one. */
        fun fromJson(json: String): Wallpaper? = if (json.isBlank()) {
            null
        } else {
            runCatching { AppJson.decodeFromString(serializer(), json) }.getOrNull()?.takeIf { it.uri.isNotBlank() }
        }

        /** Parses `#RRGGBB` (or `#AARRGGBB`) to an opaque ARGB colour. */
        fun parseColor(value: String): Int? {
            val hex = value.trim().removePrefix("#")
            val v = hex.toLongOrNull(16) ?: return null
            return when (hex.length) {
                6 -> (0xFF000000L or v).toInt()
                8 -> v.toInt()
                else -> null
            }
        }
    }
}

/**
 * An event a provider may react to. [eventType] is Projectivy's event number; a provider receives
 * an event only when `updateMode and eventType != 0` (LoadingError, −1, reaches every provider).
 */
sealed class WallpaperEvent(val eventType: Int) {
    /** The *Update frequency* timer fired, or the user asked for the next wallpaper. */
    data object TimeElapsed : WallpaperEvent(UpdateMode.TIME_ELAPSED)

    data class NowPlayingChanged(
        val isPlaying: Boolean,
        val title: String?,
        val artist: String?,
        val album: String?,
        val iconUri: String?,
    ) : WallpaperEvent(UpdateMode.NOW_PLAYING_CHANGED)

    /** An app or shortcut card got focus; colours are ARGB, 0 when unknown. */
    data class CardFocused(
        val title: String?,
        val packageName: String?,
        val lightColor: Int,
        val darkColor: Int,
    ) : WallpaperEvent(UpdateMode.CARD_FOCUSED)

    /**
     * A program card got focus.
     *
     * @param type `TvContractCompat.PreviewPrograms.TYPE_*` (below 100), or a media type for a Now
     *   Playing card (100 and above); null when unknown.
     * @param aspect the art's `TvContractCompat` aspect-ratio constant, −1 when unknown.
     */
    data class ProgramCardFocused(
        val title: String?,
        val type: Int?,
        val iconUri: String?,
        val aspect: Int,
    ) : WallpaperEvent(UpdateMode.PROGRAM_CARD_FOCUSED)

    data class LauncherIdleModeChanged(val isIdle: Boolean) : WallpaperEvent(UpdateMode.LAUNCHER_IDLE_MODE_CHANGED)

    /** Five loads in a row failed; the default wallpaper is shown. */
    data object LoadingError : WallpaperEvent(UpdateMode.LOADING_ERROR)

    /** True when a provider with [updateMode] receives this event. */
    fun reaches(updateMode: Int): Boolean = updateMode and eventType != 0
}

/** The update-mode bits. */
object UpdateMode {
    const val LOADING_ERROR = -1
    const val TIME_ELAPSED = 1
    const val NOW_PLAYING_CHANGED = 2
    const val CARD_FOCUSED = 4
    const val PROGRAM_CARD_FOCUSED = 8
    const val LAUNCHER_IDLE_MODE_CHANGED = 16
}
