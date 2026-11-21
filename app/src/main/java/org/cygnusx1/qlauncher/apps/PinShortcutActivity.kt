/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.apps

import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.apps.ui.AppsConfirmPanel
import org.cygnusx1.qlauncher.apps.ui.AppsPreviewBox
import org.cygnusx1.qlauncher.container
import org.cygnusx1.qlauncher.ui.theme.QLauncherTheme

/**
 * Accepts `android.content.pm.action.CONFIRM_PIN_SHORTCUT`: another app asks to pin a shortcut
 * to QLauncher (Android sends this only to the default home app). After confirmation the
 * shortcut is stored and appears in the Shortcuts category. Widget requests are declined.
 * Pin requests exist from Android 8; before that the activity closes at once.
 */
class PinShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 26) {
            finish()
            return
        }
        val launcherApps = getSystemService(LauncherApps::class.java)
        val request = runCatching { launcherApps?.getPinItemRequest(intent) }.getOrNull()
        val info = request?.takeIf { it.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT && it.isValid }?.shortcutInfo
        if (request == null || info == null) {
            finish()
            return
        }
        val shortcuts = container.apps.shortcuts
        val label = (info.shortLabel ?: info.longLabel ?: info.id).toString()
        val appLabel = container.apps.apps.appLabel(info.`package`)
        setContent {
            val colors by container.theme.colors.collectAsStateWithLifecycle()
            val icon by produceState<ImageBitmap?>(null) {
                value = withContext(Dispatchers.IO) {
                    shortcuts.iconOf(info)?.let { d ->
                        runCatching { d.toBitmap(128, 128).asImageBitmap() }.getOrNull()
                    }
                }
            }
            QLauncherTheme(colors = colors) {
                AppsConfirmPanel(
                    title = getString(R.string.apps_pin_shortcut_title),
                    message = getString(R.string.apps_pin_shortcut_message, label, appLabel),
                    confirmLabel = getString(R.string.apps_pin_shortcut_confirm),
                    cancelLabel = getString(R.string.apps_cancel),
                    confirmIcon = "add",
                    onConfirm = {
                        lifecycleScope.launch {
                            val stored = shortcuts.accept(request)
                            Toast.makeText(
                                applicationContext,
                                if (stored != null) R.string.apps_pin_shortcut_added else R.string.apps_pin_shortcut_failed,
                                Toast.LENGTH_SHORT,
                            ).show()
                            finish()
                        }
                    },
                    onCancel = { finish() },
                    preview = icon?.let { bmp -> { AppsPreviewBox { Image(bmp, contentDescription = null, modifier = Modifier.fillMaxSize()) } } },
                )
            }
        }
    }
}
