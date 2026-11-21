/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.container
import org.cygnusx1.qlauncher.settings.backup.autobackup.AutoBackupEntry
import org.cygnusx1.qlauncher.settings.backup.autobackup.AutoBackupReason
import org.cygnusx1.qlauncher.settings.backup.autobackup.AutoBackupStore
import java.text.DateFormat
import java.util.Date

/**
 * *Import › From automatic backup*: the automatic backups, newest first, with date, size and
 * why each was taken, and *Delete all* (Cancel / OK). Picking one calls [onPick]; show
 * [ImportScreen] with [ImportSource.AutoBackup] for it. [onDeleteAll] is called after the
 * backups were deleted. With [onBack], BACK is handled here; otherwise by the host.
 */
@Composable
fun AutoBackupList(onPick: (AutoBackupEntry) -> Unit, onDeleteAll: () -> Unit, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val store = remember { context.container.backup.manager.autoBackups }
    val entries by store.entries.collectAsState()
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    val firstRow = remember { FocusRequester() }
    val deleteRow = remember { FocusRequester() }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    LaunchedEffect(Unit) {
        store.refresh()
        runCatching { firstRow.requestFocus() }
    }
    // After *Delete all*, the focused row is gone: focus the "none" row instead.
    LaunchedEffect(entries.isEmpty()) {
        if (entries.isEmpty()) {
            withFrameNanos { }
            runCatching { firstRow.requestFocus() }
        }
    }
    if (onBack != null) BackHandler { if (confirming) confirming = false else onBack() }

    GuidedPage(
        breadcrumb = stringResource(R.string.backup_manage_settings),
        title = stringResource(R.string.backup_auto_title),
        description = stringResource(R.string.backup_auto_description, AutoBackupStore.DEFAULT_KEEP),
        icon = "history",
        expansion = if (confirming) {
            GuidedExpansion(
                title = stringResource(R.string.backup_auto_delete_all),
                subtitle = null,
                icon = "delete_sweep",
                options = listOf(
                    stringResource(R.string.backup_cancel) to { confirming = false },
                    stringResource(R.string.backup_ok) to {
                        confirming = false
                        scope.launch {
                            store.deleteAll()
                            onDeleteAll()
                        }
                    },
                ),
                returnFocus = deleteRow,
            )
        } else {
            null
        },
    ) {
        if (entries.isEmpty()) {
            item { GuidedRow(stringResource(R.string.backup_auto_none), Modifier.focusRequester(firstRow), icon = "history") }
            return@GuidedPage
        }
        items(entries, key = { it.file.name }) { entry ->
            val first = entry == entries.first()
            GuidedRow(
                title = format.format(Date(entry.createdAt)),
                subtitle = stringResource(
                    R.string.backup_auto_entry_detail,
                    Formatter.formatShortFileSize(context, entry.sizeBytes),
                    stringResource(reasonText(entry.reason)),
                ),
                modifier = if (first) Modifier.focusRequester(firstRow) else Modifier,
                icon = "restore",
                end = RowEnd.ChevronRight,
                onClick = { onPick(entry) },
            )
        }
        item {
            GuidedRow(
                title = stringResource(R.string.backup_auto_delete_all),
                modifier = Modifier.focusRequester(deleteRow),
                icon = "delete_sweep",
                end = RowEnd.ExpandMore,
                onClick = { confirming = true },
            )
        }
    }
}

private fun reasonText(reason: AutoBackupReason): Int = when (reason) {
    AutoBackupReason.Change -> R.string.backup_reason_change
    AutoBackupReason.BeforeImport -> R.string.backup_reason_import
    AutoBackupReason.BeforeMigration -> R.string.backup_reason_migration
    AutoBackupReason.BeforeReset -> R.string.backup_reason_reset
}
