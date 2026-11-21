/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.displayprofiles

import android.content.Context

/**
 * A vendor backend that reads and applies picture settings (Amlogic's `SystemControlManager`,
 * Xiaomi's `PictureSettingsManager` and the like). QLauncher ships none: [find]
 * returns null on every device, and the profile editor then shows *No picture manager loaded*.
 */
interface PictureManager {
    /** A short name for the about page, e.g. "Amlogic". */
    val name: String

    /** The values the display uses now, to start a profile from. */
    suspend fun current(): DisplayProfile

    /** Applies the picture values of [profile]. */
    suspend fun apply(profile: DisplayProfile)

    companion object {
        /** The backend for this device, or null when none is available (always, for now). */
        @Suppress("UNUSED_PARAMETER")
        fun find(context: Context): PictureManager? = null
    }
}
