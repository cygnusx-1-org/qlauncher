/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.cygnusx1.qlauncher.container
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.backup.ui.ImportScreen
import org.cygnusx1.qlauncher.settings.backup.ui.ImportSource
import org.cygnusx1.qlauncher.ui.theme.QLauncherTheme

/**
 * Opens a `.qlbackup`, `.qltheme`, `.plbackup` or `.pltheme` handed over by a file manager
 * (`ACTION_VIEW`, or `ACTION_SEND` with a stream) and runs the import flow.
 */
class ImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = sourceUri(intent)
        if (uri == null) {
            finish()
            return
        }
        val container = container
        val backup = container.backup
        setContent {
            val colors by container.theme.colors.collectAsState()
            val family by backup.fonts.fontFamily.collectAsState()
            val settings by container.settings.settings.collectAsState()
            QLauncherTheme(
                colors = colors,
                fontFamily = family,
                fontScale = settings[Keys.Font.scale],
                bold = settings[Keys.Font.bold],
                italic = settings[Keys.Font.italic],
            ) {
                ImportScreen(ImportSource.Document(uri), onDone = { finish() })
            }
        }
    }

    private fun sourceUri(intent: Intent?): Uri? {
        intent ?: return null
        intent.data?.let { return it }
        if (intent.action == Intent.ACTION_SEND) {
            return if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        }
        return null
    }
}
