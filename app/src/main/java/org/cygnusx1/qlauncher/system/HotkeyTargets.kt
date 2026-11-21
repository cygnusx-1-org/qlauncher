/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.system

/**
 * Something the hotkey help overlay can step through with the D-pad: display profiles (UP and
 * DOWN) or enabled TV inputs (LEFT and RIGHT). Neither reference device has a picture manager or
 * inputs, so both start as [Unavailable]; a display-profile or input backend plugs in by setting
 * [SystemModule.displayProfiles] or [SystemModule.inputs].
 *
 * Called on the main thread.
 */
interface HotkeyTarget {
    /** False when the device has nothing to step through; the overlay says so. */
    val available: Boolean

    /** The label of the current entry, or null. */
    fun currentLabel(): String?

    /** Moves by [delta] (−1 or +1), applies the entry and returns its label, or null when nothing changed. */
    fun step(delta: Int): String?

    /** The target for a device without the backend. */
    object Unavailable : HotkeyTarget {
        override val available: Boolean = false
        override fun currentLabel(): String? = null
        override fun step(delta: Int): String? = null
    }
}
