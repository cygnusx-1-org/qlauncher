/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.ui

import org.cygnusx1.qlauncher.wallpaper.engine.DisplayMode
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** An integer rectangle (left, top, right, bottom), free of Android types for tests. */
data class Box4(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Where a picture's pixels go for a display mode: [src] is the part of the picture drawn, [dst]
 * where it lands in the view. For [DisplayMode.BLUR] and the background of
 * [DisplayMode.FIT_CENTER_BLURRED], [cover] is the centre crop that fills the view.
 */
data class StillGeometry(val src: Box4, val dst: Box4, val cover: Box4) {
    companion object {
        fun of(imageWidth: Int, imageHeight: Int, mode: DisplayMode, viewWidth: Int, viewHeight: Int): StillGeometry {
            val full = Box4(0, 0, imageWidth, imageHeight)
            val view = Box4(0, 0, viewWidth, viewHeight)
            val cover = coverCrop(imageWidth, imageHeight, viewWidth, viewHeight)
            return when (mode) {
                DisplayMode.STRETCH -> StillGeometry(full, view, cover)
                DisplayMode.FIT_CENTER, DisplayMode.FIT_CENTER_BLURRED -> StillGeometry(full, fitInside(imageWidth, imageHeight, viewWidth, viewHeight), cover)
                DisplayMode.AUTOMATIC, DisplayMode.CROP, DisplayMode.BLUR -> StillGeometry(cover, view, cover)
            }
        }

        /** The centred part of the picture with the view's aspect ratio. */
        fun coverCrop(iw: Int, ih: Int, vw: Int, vh: Int): Box4 {
            if (iw <= 0 || ih <= 0 || vw <= 0 || vh <= 0) return Box4(0, 0, max(iw, 0), max(ih, 0))
            val scale = max(vw.toDouble() / iw, vh.toDouble() / ih)
            val w = min(iw, (vw / scale).roundToInt().coerceAtLeast(1))
            val h = min(ih, (vh / scale).roundToInt().coerceAtLeast(1))
            val l = (iw - w) / 2
            val t = (ih - h) / 2
            return Box4(l, t, l + w, t + h)
        }

        /** The picture scaled to fit inside the view, centred. */
        fun fitInside(iw: Int, ih: Int, vw: Int, vh: Int): Box4 {
            if (iw <= 0 || ih <= 0) return Box4(0, 0, vw, vh)
            val scale = min(vw.toDouble() / iw, vh.toDouble() / ih)
            val w = (iw * scale).roundToInt()
            val h = (ih * scale).roundToInt()
            val l = (vw - w) / 2
            val t = (vh - h) / 2
            return Box4(l, t, l + w, t + h)
        }
    }
}

/**
 * Playback positions of videos left while the launcher was in the background: kept for a video
 * of at least 30 s with more than 10 s left, and resumed if the same video is still current on
 * return.
 */
class VideoPositionMemory {
    private val positions = ConcurrentHashMap<String, Long>()

    /** Remembers [positionMs] of [uri] when the rules allow it; forgets it otherwise. */
    fun leave(uri: String, positionMs: Long, durationMs: Long) {
        if (shouldKeep(positionMs, durationMs)) positions[uri] = positionMs else positions.remove(uri)
    }

    fun take(uri: String): Long? = positions.remove(uri)

    /** Drops every position except the one of [uri] (another wallpaper became current). */
    fun keepOnly(uri: String?) {
        positions.keys.removeAll { it != uri }
    }

    companion object {
        private const val MIN_DURATION_MS = 30_000L
        private const val MIN_REMAINING_MS = 10_000L

        fun shouldKeep(positionMs: Long, durationMs: Long): Boolean =
            durationMs >= MIN_DURATION_MS && durationMs - positionMs > MIN_REMAINING_MS && positionMs > 0
    }
}
