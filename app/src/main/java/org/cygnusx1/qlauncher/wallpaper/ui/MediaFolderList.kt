/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.settings.backup.ui.GuidedPage
import org.cygnusx1.qlauncher.settings.backup.ui.GuidedRow
import org.cygnusx1.qlauncher.settings.backup.ui.RowEnd
import org.cygnusx1.qlauncher.wallpaper.source.LocalMedia
import org.cygnusx1.qlauncher.wallpaper.source.MediaFolder
import java.io.File

/**
 * *Folder › Choose a folder* without a file manager: the folders that hold pictures or videos, on
 * the device and on USB drives ([LocalMedia]), each with how many it holds. Picking one calls
 * [onPick] with it; BACK calls [onBack].
 */
@Composable
fun MediaFolderList(onPick: (File) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var folders by remember { mutableStateOf<List<MediaFolder>?>(null) }
    val firstRow = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        folders = LocalMedia.folders(context, LocalMedia.files(context))
        withFrameNanos { }
        runCatching { firstRow.requestFocus() }
    }
    BackHandler { onBack() }

    GuidedPage(
        breadcrumb = stringResource(R.string.settings_wallpaper),
        title = stringResource(R.string.wp_folder_choose),
        description = stringResource(R.string.wp_folders_description),
        icon = "folder",
    ) {
        val list = folders
        when {
            list == null -> item {
                GuidedRow(stringResource(R.string.wp_media_searching), Modifier.focusRequester(firstRow), icon = "hourglass_empty")
            }
            list.isEmpty() -> item {
                GuidedRow(stringResource(R.string.wp_folders_none), Modifier.focusRequester(firstRow), icon = "folder")
            }
            else -> items(list, key = { it.dir.absolutePath }) { folder ->
                GuidedRow(
                    // The last part of the shown path: a volume's own name for its top folder, not "0".
                    title = folder.label.substringAfterLast('/'),
                    subtitle = stringResource(
                        R.string.wp_folders_entry,
                        folder.label,
                        pluralStringResource(R.plurals.wp_folder_files, folder.count, folder.count),
                    ),
                    modifier = if (folder == list.first()) Modifier.focusRequester(firstRow) else Modifier,
                    icon = "folder",
                    end = RowEnd.ChevronRight,
                    onClick = { onPick(folder.dir) },
                )
            }
        }
    }
}
