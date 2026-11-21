/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.cygnusx1.qlauncher.util.DocumentPickers

/**
 * *Import › From a file › Other location*: returns a function that opens the system file picker for any
 * file (the extensions `.qlbackup`, `.qltheme`, `.plbackup` and `.pltheme` have no registered
 * MIME type) and reports the picked file to [onPicked]. The function returns false when the
 * device has no file picker; files can then still be opened from a file manager, which hands
 * them to `ImportActivity`.
 */
@Composable
fun rememberImportFilePicker(onPicked: (Uri) -> Unit): () -> Boolean {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(uri)
    }
    val context = LocalContext.current
    return remember(launcher) {
        {
            if (!DocumentPickers.available(context)) {
                false
            } else {
                try {
                    launcher.launch(arrayOf("*/*"))
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                }
            }
        }
    }
}
