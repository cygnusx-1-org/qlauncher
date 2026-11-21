/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import org.cygnusx1.qlauncher.ui.theme.QTheme

/**
 * A side panel holding one text field: the system keyboard opens at once; OK or the keyboard's
 * Done commits, BACK cancels.
 */
@Composable
fun TextEntryPanel(
    title: String,
    hint: String?,
    initial: String,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
    password: Boolean = false,
) {
    val fr = remember { FocusRequester() }
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val accent = QTheme.colors.accent
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    PanelCard {
        Column(Modifier.padding(PanelDimens.Inset)) {
            Spacer(Modifier.height(40.dp))
            Text(title, color = accent, style = QTheme.type.panelTitle)
            Spacer(Modifier.height(16.dp))
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = QTheme.type.panelRow.copy(color = Color.White),
                cursorBrush = SolidColor(accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone(value.text) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fr)
                    .testTag("text_entry")
                    .onPreviewKeyEvent { e ->
                        when {
                            (e.key == Key.Back || e.key == Key.Escape) && e.type == KeyEventType.KeyUp -> {
                                onCancel()
                                true
                            }
                            (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) && e.type == KeyEventType.KeyUp -> {
                                onDone(value.text)
                                true
                            }
                            else -> false
                        }
                    }
                    .padding(vertical = 8.dp)
                    .drawBehind { drawLine(accent, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx()) },
            )
            if (hint != null) {
                Spacer(Modifier.height(12.dp))
                Text(hint, color = Color.White.copy(alpha = 0.6f), style = QTheme.type.rowDescription)
            }
        }
    }
}
