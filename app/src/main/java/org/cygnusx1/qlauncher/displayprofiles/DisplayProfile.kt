/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.displayprofiles

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One display profile: a name, a stable id, the calibration pattern shown while editing, and
 * the picture values a vendor picture manager applies. The picture fields and their defaults are
 * those of Projectivy's profile model, so an imported profile maps one to one.
 *
 * Gains and offsets are in the vendor's raw units (gain 1024 = ×1, offset 1000 = 0).
 */
@Serializable
data class DisplayProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Index into [CalibrationPatterns.all]. */
    val previewPattern: Int = 0,
    /** Ask the picture manager for the current values when the profile is first applied. */
    val initFromCurrent: Boolean = false,
    val brightness: Int = 50,
    val contrast: Int = 50,
    val saturation: Int = 50,
    val sharpness: Int = 50,
    val hue: Int = 50,
    val redGain: Int = 1024,
    val greenGain: Int = 1024,
    val blueGain: Int = 1024,
    val redOffset: Int = 1000,
    val greenOffset: Int = 1000,
    val blueOffset: Int = 1000,
    val dynamicColor: Boolean = false,
    val dynamicContrast: Boolean = false,
    /** Motion compensation (MEMC). */
    val memc: Boolean = false,
    val noiseReduction: Boolean = false,
    val deinterlacing: Boolean = false,
    /** Video zoom, 30 to 120 (percent). */
    val zoom: Int = 100,
    val backlight: Int = 100,
    val backlightMode: Int = 0,
    val gammaMode: Int = 0,
) {
    companion object {
        const val ZOOM_MIN = 30
        const val ZOOM_MAX = 120
    }
}

/** The stored profiles, in creation order. */
@Serializable
data class DisplayProfileList(val profiles: List<DisplayProfile> = emptyList())
