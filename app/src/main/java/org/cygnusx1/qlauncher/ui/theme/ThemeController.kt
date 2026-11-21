/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.theme

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.SettingsRepository

/**
 * Holds the wallpaper palette and the theme colours derived from it. The wallpaper engine feeds
 * seed colours in with [offerSeed]; everything that draws an accent reads [colors].
 */
class ThemeController(settings: SettingsRepository, scope: CoroutineScope) {
    private val _palette = MutableStateFlow(DynamicPalette.NONE)
    val palette: StateFlow<DynamicPalette> = _palette.asStateFlow()

    val colors: StateFlow<QColors> = combine(_palette, settings.flow(Keys.Look.accent)) { p, accent ->
        QColors.from(p, accent)
    }.stateIn(scope, SharingStarted.Eagerly, QColors.from(DynamicPalette.NONE, settings.current[Keys.Look.accent]))

    /**
     * Offers a new seed colour. It replaces the current one only when it differs in the top four
     * bits of a channel.
     */
    fun offerSeed(argb: Int) {
        val current = _palette.value.seed
        if (current == null || DynamicPalette.differs(current, argb)) {
            _palette.value = DynamicPalette.fromSeed(argb)
        }
    }
}
