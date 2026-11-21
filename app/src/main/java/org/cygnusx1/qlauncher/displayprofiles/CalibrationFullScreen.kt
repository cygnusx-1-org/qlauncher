/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.displayprofiles

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent

/**
 * *Full Screen preview*: pattern [index] over the whole screen. BACK, OK or Menu leave it
 * (Menu also toggles it from the editor, as in Projectivy); LEFT and RIGHT step through the
 * drawable patterns with [onIndexChange].
 */
@Composable
fun CalibrationFullScreen(index: Int, onExit: () -> Unit, onIndexChange: (Int) -> Unit = {}) {
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onExit)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    CalibrationPattern(
        index = index,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onKeyEvent { e ->
                if (e.nativeKeyEvent.action != KeyEvent.ACTION_UP) return@onKeyEvent false
                val sdr = CalibrationPatterns.sdr.map { it.index }
                when (e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        onExit()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onIndexChange(sdr[(sdr.indexOf(index).coerceAtLeast(0) + 1) % sdr.size])
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onIndexChange(sdr[(sdr.indexOf(index).coerceAtLeast(0) - 1 + sdr.size) % sdr.size])
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    )
}
