/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.system.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.ui.theme.MaterialIcon
import org.cygnusx1.qlauncher.ui.theme.QColors
import org.cygnusx1.qlauncher.ui.theme.QLauncherTheme
import org.cygnusx1.qlauncher.ui.theme.QPalette
import org.cygnusx1.qlauncher.ui.theme.QTheme

/** What the hotkey help overlay shows. Labels are null when the target has no current entry. */
data class HotkeyHelpState(
    val inputsAvailable: Boolean,
    val inputLabel: String?,
    val profilesAvailable: Boolean,
    val profileLabel: String?,
    val homeOverridden: Boolean,
)

/** The panel at the bottom of the screen shown by Menu twice (*Display info on "Menu" double press*). */
@Composable
fun HotkeyHelpOverlay(state: HotkeyHelpState, colors: QColors) {
    QLauncherTheme(colors = colors) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier
                    .padding(bottom = 48.dp)
                    .width(PANEL_WIDTH)
                    .clip(RoundedCornerShape(24.dp))
                    .background(QPalette.PanelBackground)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MaterialIcon("keyboard", size = 24.dp, tint = QTheme.colors.accent)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.system_hotkeys_title), style = QTheme.type.panelTitle, color = QPalette.TextPrimary)
                }
                HotkeyRow(
                    keys = stringResource(R.string.system_hotkeys_keys_left_right),
                    action = stringResource(R.string.system_hotkeys_change_input),
                    detail = if (state.inputsAvailable) state.inputLabel else stringResource(R.string.system_hotkeys_no_inputs),
                    enabled = state.inputsAvailable,
                )
                HotkeyRow(
                    keys = stringResource(R.string.system_hotkeys_keys_up_down),
                    action = stringResource(R.string.system_hotkeys_change_profile),
                    detail = if (state.profilesAvailable) state.profileLabel else stringResource(R.string.system_hotkeys_no_picture_manager),
                    enabled = state.profilesAvailable,
                )
                HotkeyRow(
                    keys = stringResource(R.string.system_hotkeys_keys_ok),
                    action = stringResource(R.string.system_hotkeys_start_launcher),
                    detail = null,
                    enabled = true,
                )
                HotkeyRow(
                    keys = stringResource(R.string.system_hotkeys_keys_home),
                    action = stringResource(R.string.system_hotkeys_home),
                    detail = if (state.homeOverridden) null else stringResource(R.string.system_hotkeys_home_off),
                    enabled = state.homeOverridden,
                )
                HotkeyRow(
                    keys = stringResource(R.string.system_hotkeys_keys_menu_twice),
                    action = stringResource(R.string.system_hotkeys_close),
                    detail = null,
                    enabled = true,
                )
            }
        }
    }
}

/**
 * One shortcut: the key glyphs in a fixed column, the action, and a detail line. The detail line
 * is always laid out (blank when there is nothing to say), so every row has the same height
 * whatever the device supports.
 */
@Composable
private fun HotkeyRow(keys: String, action: String, detail: String?, enabled: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            keys,
            style = QTheme.type.panelRow,
            color = if (enabled) QTheme.colors.accent else QPalette.TextDisabled,
            modifier = Modifier.width(KEYS_COLUMN),
            maxLines = 1,
        )
        Column {
            Text(action, style = QTheme.type.panelRow, color = if (enabled) QPalette.TextPrimary else QPalette.TextDisabled, maxLines = 1)
            Text(
                detail ?: "",
                style = QTheme.type.rowDescription,
                color = QPalette.TextDescription,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(DETAIL_LINE),
            )
        }
    }
}

/** What the idle countdown says will happen. */
enum class IdleOutcome { Shutdown, ScreenOff, Screensaver }

/** "User inactivity detected, shutdown in 60s / Press any button to cancel", centred. */
@Composable
fun IdleCountdownOverlay(outcome: IdleOutcome, secondsLeft: Int, colors: QColors) {
    QLauncherTheme(colors = colors) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(PANEL_WIDTH)
                    .clip(RoundedCornerShape(24.dp))
                    .background(QPalette.PanelBackground)
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MaterialIcon("bedtime", size = 48.dp, tint = QTheme.colors.accent)
                Spacer(Modifier.height(16.dp))
                val line = when (outcome) {
                    IdleOutcome.Shutdown -> stringResource(R.string.system_idle_countdown_shutdown, secondsLeft)
                    IdleOutcome.ScreenOff -> stringResource(R.string.system_idle_countdown_screen_off, secondsLeft)
                    IdleOutcome.Screensaver -> stringResource(R.string.system_idle_countdown_screensaver, secondsLeft)
                }
                Text(line, style = QTheme.type.panelTitle, color = QPalette.TextPrimary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.system_idle_countdown_cancel),
                    style = QTheme.type.pageDescription,
                    color = QPalette.TextTitleUnfocused,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private val PANEL_WIDTH = 480.dp
private val KEYS_COLUMN = 120.dp
private val DETAIL_LINE = 16.dp
