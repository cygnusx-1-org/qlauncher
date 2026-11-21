/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.util

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.ui.theme.QPalette
import org.cygnusx1.qlauncher.ui.theme.QTheme

/** The localised sample word shown for [markup] in the help block. */
@StringRes
fun clockMarkupWordRes(markup: ClockMarkup): Int = when (markup) {
    ClockMarkup.Bold -> R.string.backup_clock_markup_bold
    ClockMarkup.Italic -> R.string.backup_clock_markup_italic
    ClockMarkup.Underline -> R.string.backup_clock_markup_underline
    ClockMarkup.Strikethrough -> R.string.backup_clock_markup_strikethrough
    ClockMarkup.Subscript -> R.string.backup_clock_markup_subscript
    ClockMarkup.Superscript -> R.string.backup_clock_markup_superscript
    ClockMarkup.HalfSize -> R.string.backup_clock_markup_half_size
    ClockMarkup.OneAndAHalfSize -> R.string.backup_clock_markup_one_and_a_half_size
}

/**
 * The help block that ends the clock-format page: each line is what to type, an arrow, and what
 * it gives at [timeMillis] (`d/MM/yy HH:mm →19/11/26 04:18`), then one line per markup.
 */
@Composable
fun ClockFormatHelpBlock(clock: ClockFormat, timeMillis: Long, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val lines = remember(clock, timeMillis / 60_000, resources) {
        ClockFormatHelp.lines(clock, timeMillis) { resources.getString(clockMarkupWordRes(it)) }
    }
    val arrow = stringResource(R.string.backup_clock_help_arrow)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (line in lines) {
            Text(
                text = buildAnnotatedString {
                    append(line.source)
                    append("  ")
                    append(arrow)
                    append(line.rendered)
                },
                style = QTheme.type.rowDescription,
                color = QPalette.TextDescription,
            )
        }
    }
}
