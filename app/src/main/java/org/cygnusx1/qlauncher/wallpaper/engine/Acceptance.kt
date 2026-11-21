/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.engine

import org.cygnusx1.qlauncher.wallpaper.WallpaperDisplayModes

/** How a picture is fitted to the screen, in the order of the *Display mode* setting. */
enum class DisplayMode {
    AUTOMATIC,
    CROP,
    STRETCH,
    FIT_CENTER,
    FIT_CENTER_BLURRED,
    BLUR,
    ;

    /** True for the two modes that show the whole picture, which accept any format. */
    val isFit: Boolean get() = this == FIT_CENTER || this == FIT_CENTER_BLURRED

    companion object {
        fun fromSetting(value: Int): DisplayMode = entries.getOrElse(value) { AUTOMATIC }

        /** A wallpaper's own mode (plugin numbering) to a setting mode; null for "none". */
        fun fromWallpaper(code: Int): DisplayMode? = when (code) {
            WallpaperDisplayModes.CROP -> CROP
            WallpaperDisplayModes.STRETCH -> STRETCH
            WallpaperDisplayModes.BLUR -> BLUR
            WallpaperDisplayModes.FIT_CENTER -> FIT_CENTER
            WallpaperDisplayModes.FIT_CENTER_BLURRED_BG -> FIT_CENTER_BLURRED
            else -> null
        }
    }
}

/** The outcome of the acceptance step for one picture. */
sealed interface Placement {
    /** Draw the picture with [mode] (never [DisplayMode.AUTOMATIC]). */
    data class Draw(val mode: DisplayMode, val wrongFormat: Boolean) : Placement

    /** The picture is the wrong format and the policy is *Skip*: take the next one. */
    data object Skip : Placement
}

/**
 * The acceptance and display-mode rules.
 *
 * A picture is the *wrong format* when it is portrait or square (width ÷ height ≤ 1) or narrower
 * than *Minimum accepted width*. Unless the resolved display mode is one of the two *Fit center*
 * modes, such a picture is drawn with *In case of bad ratio/quality* instead, whose first choice
 * (0) skips it.
 */
object Acceptance {
    /** *In case of bad ratio/quality* value 0. */
    const val WRONG_FORMAT_SKIP = 0

    /** True when a [width] × [height] picture is the wrong format; unknown sizes (≤ 0) are fine. */
    fun isWrongFormat(width: Int, height: Int, minAcceptedWidth: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        return width.toFloat() / height.toFloat() <= 1f || width < minAcceptedWidth
    }

    /** The *Display mode* setting resolved for one wallpaper: Automatic takes the wallpaper's own, else Crop. */
    fun baseMode(displayModeSetting: Int, wallpaperMode: Int): DisplayMode {
        val setting = DisplayMode.fromSetting(displayModeSetting)
        if (setting != DisplayMode.AUTOMATIC) return setting
        return DisplayMode.fromWallpaper(wallpaperMode) ?: DisplayMode.CROP
    }

    /**
     * Where and how to draw a picture.
     *
     * @param wrongFormatSetting the *In case of bad ratio/quality* value: 0 Skip, else a display
     *   mode in the setting's numbering.
     */
    fun place(
        width: Int,
        height: Int,
        displayModeSetting: Int,
        wrongFormatSetting: Int,
        minAcceptedWidth: Int,
        wallpaperMode: Int = WallpaperDisplayModes.DEFAULT,
    ): Placement {
        val base = baseMode(displayModeSetting, wallpaperMode)
        if (base.isFit || !isWrongFormat(width, height, minAcceptedWidth)) return Placement.Draw(base, wrongFormat = false)
        if (wrongFormatSetting == WRONG_FORMAT_SKIP) return Placement.Skip
        val mode = DisplayMode.fromSetting(wrongFormatSetting).takeIf { it != DisplayMode.AUTOMATIC } ?: DisplayMode.CROP
        return Placement.Draw(mode, wrongFormat = true)
    }

    /**
     * How a wrong-format picture is drawn when the policy is *Skip* but it is on screen anyway:
     * its source had nothing else to show (album art, a program poster, a single media file).
     */
    val SKIP_FALLBACK = DisplayMode.BLUR

    /**
     * The mode to draw with, also when the policy says *Skip*: a picture the user [picked] in the
     * gallery is drawn with its own mode, any other is drawn with [SKIP_FALLBACK].
     */
    fun drawMode(
        width: Int,
        height: Int,
        displayModeSetting: Int,
        wrongFormatSetting: Int,
        minAcceptedWidth: Int,
        wallpaperMode: Int = WallpaperDisplayModes.DEFAULT,
        picked: Boolean = false,
    ): DisplayMode = when (val p = place(width, height, displayModeSetting, wrongFormatSetting, minAcceptedWidth, wallpaperMode)) {
        is Placement.Draw -> p.mode
        Placement.Skip -> if (picked) baseMode(displayModeSetting, wallpaperMode) else SKIP_FALLBACK
    }

    /** Down-sample factor and radius of the extra *Blur* setting `b`: radius b, down-sample b² ÷ 16 + 1. */
    fun extraBlur(b: Int): BlurSpec? = if (b <= 0) null else BlurSpec(downSample = b * b / 16 + 1, radius = b)

    /** The blur behind *Fit center + blurred background*. */
    val FIT_BACKGROUND_BLUR = BlurSpec(downSample = 8, radius = 8)

    /** The *Blur* display mode. */
    val BLUR_MODE_BLUR = BlurSpec(downSample = 16, radius = 25)
}

/** A blur done by scaling down by [downSample], blurring with [radius] and scaling back up. */
data class BlurSpec(val downSample: Int, val radius: Int) {
    /** A comparable radius in full-size pixels, for RenderEffect. */
    val effectiveRadiusPx: Float get() = (downSample * radius).toFloat() / 2f
}
