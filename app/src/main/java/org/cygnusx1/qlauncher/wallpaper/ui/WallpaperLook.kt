/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.toArgb
import org.cygnusx1.qlauncher.settings.ColorKey
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.Settings
import org.cygnusx1.qlauncher.ui.theme.DynamicPalette
import org.cygnusx1.qlauncher.wallpaper.engine.Acceptance
import org.cygnusx1.qlauncher.wallpaper.engine.BlurSpec
import org.cygnusx1.qlauncher.wallpaper.engine.PictureAdjust

/** The video settings a player is built with. */
@Immutable
data class VideoSettings(
    val volume: Float,
    val tunneling: Boolean,
    val decoderFallback: Boolean,
    /** Seconds between frames sampled for colours; 0 = off. */
    val colorFrequencySeconds: Float,
    /** False when the video plays once and its end brings the next wallpaper. */
    val loop: Boolean,
)

/** Everything about how wallpapers are drawn, resolved from the settings and the palette. */
@Immutable
class WallpaperLook(
    val displayMode: Int,
    val wrongFormatMode: Int,
    val minAcceptedWidth: Int,
    /** The picture-adjustment matrix, null when it changes nothing. */
    val matrix: FloatArray?,
    val extraBlur: BlurSpec?,
    val shadowH: Float,
    val shadowV: Float,
    val shadowOpacity: Float,
    val shadowTint: Color,
    val transition: Int,
    val video: VideoSettings,
) {
    val colorFilter: ColorFilter? = matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it)) }

    /** True when anything changes the picture's pixels (a SurfaceView cannot show that). */
    val adjusted: Boolean get() = matrix != null || extraBlur != null

    override fun equals(other: Any?): Boolean = other is WallpaperLook &&
        displayMode == other.displayMode && wrongFormatMode == other.wrongFormatMode &&
        minAcceptedWidth == other.minAcceptedWidth && (matrix?.contentEquals(other.matrix) ?: (other.matrix == null)) &&
        extraBlur == other.extraBlur && shadowH == other.shadowH && shadowV == other.shadowV &&
        shadowOpacity == other.shadowOpacity && shadowTint == other.shadowTint && transition == other.transition &&
        video == other.video

    override fun hashCode(): Int = displayMode * 31 + wrongFormatMode * 17 + (matrix?.contentHashCode() ?: 0) + transition

    companion object {
        /** Projectivy's default shadow tint: black, dynamic (follows the palette's darkest tone). */
        private const val DEFAULT_SHADOW_TINT = 0xFF000001.toInt()

        fun from(s: Settings, palette: DynamicPalette): WallpaperLook {
            val tintStored = s[Keys.Wallpaper.tint]
            val tint = if (tintStored == ColorKey.UNSET) 0 else palette.resolve(tintStored, Color.Transparent).toArgb()
            val matrix = PictureAdjust.matrix(
                hue = s[Keys.Wallpaper.hue],
                saturation = s[Keys.Wallpaper.saturation],
                contrast = s[Keys.Wallpaper.contrast],
                brightness = s[Keys.Wallpaper.brightness],
                tint = tint,
                tintAmount = s[Keys.Wallpaper.tintAmount],
            ).takeUnless(PictureAdjust::isIdentity)
            val shadowStored = s[Keys.Wallpaper.shadowTint].let { if (it == ColorKey.UNSET) DEFAULT_SHADOW_TINT else it }
            val updateDelay = s[Keys.Wallpaper.updateDelay]
            val ignoreOnVideo = s[Keys.Wallpaper.ignoreUpdateOnVideo]
            return WallpaperLook(
                displayMode = s[Keys.Wallpaper.displayMode],
                wrongFormatMode = s[Keys.Wallpaper.wrongFormatMode],
                minAcceptedWidth = s[Keys.Wallpaper.minAcceptedWidth],
                matrix = matrix,
                extraBlur = Acceptance.extraBlur(s[Keys.Wallpaper.blur]),
                shadowH = s[Keys.Wallpaper.shadowHLength],
                shadowV = s[Keys.Wallpaper.shadowVLength],
                shadowOpacity = s[Keys.Wallpaper.shadowOpacity],
                shadowTint = palette.resolve(shadowStored, Color.Black),
                transition = s[Keys.Wallpaper.transition],
                video = VideoSettings(
                    volume = s[Keys.Wallpaper.videoVolume],
                    tunneling = s[Keys.Wallpaper.videoTunneling],
                    decoderFallback = s[Keys.Wallpaper.videoDecoderFallback],
                    colorFrequencySeconds = s[Keys.Wallpaper.videoColorFrequency],
                    loop = !(ignoreOnVideo && updateDelay != 0),
                ),
            )
        }
    }
}
