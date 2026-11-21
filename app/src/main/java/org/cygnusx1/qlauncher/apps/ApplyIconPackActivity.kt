/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.apps

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.apps.ui.AppsConfirmPanel
import org.cygnusx1.qlauncher.apps.ui.AppsPreviewBox
import org.cygnusx1.qlauncher.container
import org.cygnusx1.qlauncher.ui.theme.QLauncherTheme

/**
 * Lets an icon pack apply itself (`org.cygnusx1.qlauncher.APPLY_ICONPACK`, plus the
 * `tv.projectivy.APPLY_ICONPACK` / `com.spocky.projengmenu.APPLY_ICONPACK` actions existing packs
 * send). The pack's package comes in an extra, or else is the caller. A confirmation follows;
 * *Apply* sets *Cards › Icon pack*.
 */
class ApplyIconPackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requested = PACKAGE_EXTRAS.firstNotNullOfOrNull { intent?.getStringExtra(it)?.trim()?.ifEmpty { null } }
            ?: callingActivity?.packageName
            ?: referrer?.takeIf { it.scheme == "android-app" }?.host
        if (requested == null || requested == packageName) {
            finishWith(R.string.apps_iconpack_apply_missing)
            return
        }
        val iconPacks = container.apps.iconPacks
        setContent {
            val colors by container.theme.colors.collectAsStateWithLifecycle()
            val packs by iconPacks.packs.collectAsStateWithLifecycle()
            val pack = packs.firstOrNull { it.packageName == requested }
            val icon by produceState<ImageBitmap?>(null, requested) {
                value = withContext(Dispatchers.IO) {
                    runCatching { packageManager.getApplicationIcon(requested).toBitmap(128, 128).asImageBitmap() }.getOrNull()
                }
            }
            QLauncherTheme(colors = colors) {
                AppsConfirmPanel(
                    title = getString(R.string.apps_iconpack_apply_title),
                    message = getString(R.string.apps_iconpack_apply_message, pack?.label ?: requested),
                    confirmLabel = getString(R.string.apps_iconpack_apply_confirm),
                    cancelLabel = getString(R.string.apps_cancel),
                    onConfirm = {
                        if (pack == null) {
                            finishWith(R.string.apps_iconpack_apply_not_a_pack)
                        } else {
                            iconPacks.select(pack.packageName)
                            finishWith(R.string.apps_iconpack_applied)
                        }
                    },
                    onCancel = { finish() },
                    preview = icon?.let { bmp -> { AppsPreviewBox { Image(bmp, contentDescription = null, modifier = Modifier.fillMaxSize()) } } },
                )
            }
        }
    }

    private fun finishWith(@StringRes message: Int) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    private companion object {
        val PACKAGE_EXTRAS = listOf(
            "org.cygnusx1.qlauncher.extra.ICONPACK_PACKAGENAME",
            "com.spocky.projengmenu.extra.ICONPACK_PACKAGENAME",
            "tv.projectivy.extra.ICONPACK_PACKAGENAME",
        )
    }
}
