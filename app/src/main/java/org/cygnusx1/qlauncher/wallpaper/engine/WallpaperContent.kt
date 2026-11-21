/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.engine

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.airbnb.lottie.LottieComposition
import org.cygnusx1.qlauncher.wallpaper.Wallpaper
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A wallpaper ready to draw. [width] and [height] are the original size (0 when unknown), which
 * the acceptance rules use; [seed] is the colour offered to the theme, null when there is none.
 */
sealed interface WallpaperContent {
    val width: Int
    val height: Int
    val seed: Int?

    /** A still picture, decoded in software so it can be blurred and sampled. */
    class Still(val bitmap: Bitmap, override val width: Int, override val height: Int, override val seed: Int?) : WallpaperContent

    /** An animated picture; [firstFrame] feeds blurred backgrounds and the seed. */
    class Animated(
        val drawable: Drawable,
        val firstFrame: Bitmap,
        override val width: Int,
        override val height: Int,
        override val seed: Int?,
    ) : WallpaperContent

    class Lottie(
        val composition: LottieComposition,
        val firstFrame: Bitmap?,
        override val width: Int,
        override val height: Int,
        override val seed: Int?,
    ) : WallpaperContent

    /** A video; nothing is decoded until it is played. */
    class Video(val uri: String, override val width: Int, override val height: Int) : WallpaperContent {
        override val seed: Int? get() = null
    }

    /** A plain colour. Black, white and greys give no seed: they would make a meaningless palette. */
    class Solid(val color: Int) : WallpaperContent {
        override val width: Int get() = 0
        override val height: Int get() = 0
        override val seed: Int?
            get() {
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                return if (r == g && g == b) null else color
            }
    }

    /** The animated gradient of the *Dynamic colors* provider. */
    data object DynamicColors : WallpaperContent {
        override val width: Int get() = 0
        override val height: Int get() = 0
        override val seed: Int? get() = null
    }
}

/** Turns a [Wallpaper] into something drawable. Runs off the main thread. */
fun interface WallpaperLoader {
    suspend fun load(wallpaper: Wallpaper): Result<WallpaperContent>
}

/** What [WallpaperHost][org.cygnusx1.qlauncher.wallpaper.ui.WallpaperHost] draws. */
class DisplayedWallpaper(
    val wallpaper: Wallpaper,
    val content: WallpaperContent,
    /** Increases with every change, so equal wallpapers shown twice still transition. */
    val sequence: Long,
    /** Chosen in the *Show wallpapers* gallery: drawn even when the wrong-format policy is *Skip*. */
    val picked: Boolean = false,
)

/** Decode-size arithmetic, kept pure for tests. */
object DecodeSize {
    /** Neither side of a decoded picture exceeds this. */
    const val MAX_SIDE = 4096

    /**
     * The size to decode an `imageWidth × imageHeight` picture at for a `screenWidth ×
     * screenHeight` screen: just large enough to cover the screen, never upscaled, at most
     * [MAX_SIDE] per side and four screens' worth of pixels.
     */
    fun target(imageWidth: Int, imageHeight: Int, screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        if (imageWidth <= 0 || imageHeight <= 0) return imageWidth to imageHeight
        var scale = max(screenWidth.toDouble() / imageWidth, screenHeight.toDouble() / imageHeight)
        scale = min(scale, 1.0)
        scale = min(scale, MAX_SIDE.toDouble() / max(imageWidth, imageHeight))
        val maxPixels = 4.0 * screenWidth * screenHeight
        val pixels = imageWidth.toDouble() * imageHeight * scale * scale
        if (pixels > maxPixels) scale *= sqrt(maxPixels / pixels)
        val w = ceil(imageWidth * scale - 1e-9).toInt().coerceAtLeast(1)
        val h = ceil(imageHeight * scale - 1e-9).toInt().coerceAtLeast(1)
        return w to h
    }

    /** The largest size of the same aspect that fits in `maxWidth × maxHeight` (for the 64 × 36 seed copy). */
    fun fitInside(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return maxWidth to maxHeight
        val scale = min(1.0, min(maxWidth.toDouble() / width, maxHeight.toDouble() / height))
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }
}
