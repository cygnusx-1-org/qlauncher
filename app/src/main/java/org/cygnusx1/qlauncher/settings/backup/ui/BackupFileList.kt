/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.container
import org.cygnusx1.qlauncher.settings.backup.BackupFile
import org.cygnusx1.qlauncher.settings.backup.BackupFormat
import org.cygnusx1.qlauncher.settings.backup.BackupKind
import org.cygnusx1.qlauncher.settings.backup.PublicBackups
import org.cygnusx1.qlauncher.util.DocumentPickers
import java.text.DateFormat
import java.util.Date

/**
 * *Import › From a file*: the backup files QLauncher can find by itself ([PublicBackups]), newest
 * first, so that importing needs no file manager. Without broad storage access a row offers it
 * (*All files access* from Android 11, the storage permission before); the list is read again when
 * the screen comes back. When the device has a system file picker, a last row offers it too.
 * Picking a file calls [onPick]; BACK calls [onBack].
 */
@Composable
fun BackupFileList(kind: BackupKind, onPick: (Uri) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { context.container.backup.manager }
    val extensions = remember(kind) {
        if (kind == BackupKind.Theme) {
            setOf(BackupFormat.EXT_THEME, BackupFormat.EXT_PL_THEME)
        } else {
            setOf(BackupFormat.EXT_BACKUP, BackupFormat.EXT_PL_BACKUP)
        }
    }
    var files by remember { mutableStateOf<List<BackupFile>?>(null) }
    var broadAccess by remember { mutableStateOf(PublicBackups.hasBroadAccess(context)) }
    var scan by remember { mutableIntStateOf(0) }
    val firstRow = remember { FocusRequester() }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val hasPicker = remember { DocumentPickers.available(context) }
    val browse = rememberImportFilePicker(onPick)
    val readPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { scan++ }
    val noAccessPage = stringResource(R.string.backup_files_access_unavailable, PublicBackups.allFilesAccessAdbCommand(context))

    // Read again on every return: the user may have granted access or copied a file meanwhile.
    LifecycleResumeEffect(Unit) {
        scan++
        onPauseOrDispose { }
    }
    LaunchedEffect(scan) {
        // Set together, so the *Allow access* row and the files it unlocks change in one frame.
        val access = PublicBackups.hasBroadAccess(context)
        val found = PublicBackups.find(context, manager.exportDir, extensions)
        broadAccess = access
        files = found
    }
    // Focus the first row once the list is read, and again whenever a rescan changes it: the
    // focused row may be gone (*Allow access* after access is granted, a file deleted meanwhile),
    // which would leave nothing focused.
    LaunchedEffect(files, broadAccess) {
        if (files != null) {
            withFrameNanos { }
            runCatching { firstRow.requestFocus() }
        }
    }
    BackHandler { onBack() }

    fun requestAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            readPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            return
        }
        val intent = PublicBackups.allFilesAccessIntent(context)
        try {
            if (intent == null) throw ActivityNotFoundException()
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, noAccessPage, Toast.LENGTH_LONG).show()
        }
    }

    val theme = kind == BackupKind.Theme
    GuidedPage(
        breadcrumb = stringResource(if (theme) R.string.backup_themes else R.string.backup_manage_settings),
        title = stringResource(if (theme) R.string.backup_files_theme_title else R.string.backup_files_title),
        description = stringResource(R.string.backup_files_description, PublicBackups.shownFolder),
        icon = "folder_open",
    ) {
        val list = files
        when {
            list == null -> item {
                GuidedRow(stringResource(R.string.backup_files_searching), Modifier.focusRequester(firstRow), icon = "hourglass_empty")
            }
            list.isEmpty() -> item {
                GuidedRow(stringResource(R.string.backup_files_none), Modifier.focusRequester(firstRow), icon = "folder_open")
            }
            else -> items(list, key = { it.uri.toString() }) { file ->
                GuidedRow(
                    title = file.name,
                    subtitle = stringResource(
                        R.string.backup_files_entry_detail,
                        file.location,
                        format.format(Date(file.modified)),
                        Formatter.formatShortFileSize(context, file.size),
                    ),
                    modifier = if (file == list.first()) Modifier.focusRequester(firstRow) else Modifier,
                    icon = if (theme) "palette" else "restore",
                    end = RowEnd.ChevronRight,
                    onClick = { onPick(file.uri) },
                )
            }
        }
        if (list != null && !broadAccess) {
            item {
                GuidedRow(
                    title = stringResource(R.string.backup_files_access),
                    subtitle = stringResource(R.string.backup_files_access_desc),
                    icon = "folder_special",
                    end = RowEnd.ChevronRight,
                    onClick = ::requestAccess,
                )
            }
        }
        if (list != null && hasPicker) {
            item {
                GuidedRow(
                    title = stringResource(R.string.backup_files_browse),
                    subtitle = stringResource(R.string.backup_files_browse_desc),
                    icon = "folder_open",
                    end = RowEnd.ChevronRight,
                    onClick = { browse() },
                )
            }
        }
    }
}
