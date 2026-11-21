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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.container
import org.cygnusx1.qlauncher.reports.ErrorReports
import org.cygnusx1.qlauncher.settings.backup.BackupFormat
import org.cygnusx1.qlauncher.settings.backup.BackupKind
import org.cygnusx1.qlauncher.settings.backup.PasswordPolicy
import org.cygnusx1.qlauncher.settings.backup.PasswordProblem
import org.cygnusx1.qlauncher.settings.backup.PasswordStrength
import org.cygnusx1.qlauncher.settings.backup.PublicBackups
import org.cygnusx1.qlauncher.settings.backup.shareIntent

/** Where *Export* sends the file after writing it to the app's `files/backups/`. */
enum class ExportDestination {
    /** `Documents/QLauncher/` in shared storage, without a file picker ([PublicBackups]). */
    SaveLocally,

    /** `ACTION_SEND` to another app, such as LocalSend. */
    Share,
}

private sealed interface ExportState {
    data object Form : ExportState
    data object Working : ExportState
    /** [fileName], when set, is shown under [message] on a line of its own. */
    data class Finished(val message: String, val failed: Boolean = false, val fileName: String? = null) : ExportState
}

/**
 * The export flow for *Manage settings › Export* ([BackupKind.Settings]) and *Themes › Export*
 * ([BackupKind.Theme]): the password with show/hide and a strength hint, then the file is
 * written and saved to `Documents/QLauncher/` or shared. A password under eight characters is refused before anything is
 * written. [onDone] is called on BACK or when the user leaves the result.
 */
@Composable
fun ExportScreen(kind: BackupKind, destination: ExportDestination, onDone: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { context.container.backup.manager }
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<PasswordProblem?>(null) }
    var state by remember { mutableStateOf<ExportState>(ExportState.Form) }
    val firstRow = remember { FocusRequester() }

    val minLength = BackupFormat.MIN_PASSWORD_LENGTH
    val savedText = stringResource(R.string.backup_export_saved)
    val keptText = stringResource(R.string.backup_export_kept)
    val noPickerText = stringResource(R.string.backup_export_no_file_manager)
    val failedText = stringResource(R.string.backup_export_failed)
    val sharedText = stringResource(R.string.backup_export_shared)
    val shareTitle = stringResource(R.string.backup_share_title)

    fun runExport() {
        state = ExportState.Working
        scope.launch {
            val chars = password.toCharArray()
            val file = try {
                manager.export(kind, chars)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorReports.recordError("backup.export", e)
                state = ExportState.Finished(failedText.format(e.localizedMessage ?: e.javaClass.simpleName), failed = true)
                return@launch
            } finally {
                chars.fill('\u0000')
            }
            password = ""
            when (destination) {
                ExportDestination.SaveLocally -> state = try {
                    val shown = PublicBackups.save(context, file)
                    file.delete()
                    ExportState.Finished(savedText.format(shown.substringBeforeLast('/')), fileName = shown.substringAfterLast('/'))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // No storage permission (Android 9 and older) or no room: the file stays with QLauncher.
                    ExportState.Finished(keptText.format(file.path), failed = true)
                }
                ExportDestination.Share -> {
                    try {
                        context.startActivity(manager.shareIntent(context, file, shareTitle))
                        state = ExportState.Finished(sharedText.format(file.path))
                    } catch (_: ActivityNotFoundException) {
                        state = ExportState.Finished(noPickerText.format(file.path))
                    }
                }
            }
        }
    }

    // Android 9 and older need the storage permission to write to Documents; refused, the file
    // stays in QLauncher's own folder and the result says so.
    val writePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { runExport() }

    fun start() {
        val p = PasswordPolicy.check(password)
        problem = p
        if (p != null) return
        if (destination == ExportDestination.SaveLocally && PublicBackups.needsWritePermission(context)) {
            try {
                writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } catch (_: ActivityNotFoundException) {
                runExport()
            }
        } else {
            runExport()
        }
    }

    BackHandler { onDone() }
    LaunchedEffect(state) { runCatching { firstRow.requestFocus() } }

    val title = stringResource(if (kind == BackupKind.Theme) R.string.backup_export_theme_title else R.string.backup_export_settings_title)
    GuidedPage(
        breadcrumb = stringResource(if (kind == BackupKind.Theme) R.string.backup_themes else R.string.backup_manage_settings),
        title = title,
        description = stringResource(
            if (kind == BackupKind.Theme) R.string.backup_export_theme_description else R.string.backup_export_description,
            minLength,
        ),
        icon = if (kind == BackupKind.Theme) "palette" else "settings",
    ) {
        when (val s = state) {
            ExportState.Form -> exportForm(
                firstRow = firstRow,
                password = password,
                visible = visible,
                problem = problem,
                minLength = minLength,
                destination = destination,
                onPassword = { password = it; problem = null },
                onToggleVisible = { visible = !visible },
                onExport = ::start,
            )
            ExportState.Working -> item {
                GuidedRow(stringResource(R.string.backup_exporting), Modifier.focusRequester(firstRow), icon = "hourglass_empty")
            }
            is ExportState.Finished -> {
                item {
                    GuidedRow(
                        title = s.message,
                        subtitle = s.fileName,
                        modifier = Modifier.focusRequester(firstRow),
                        icon = if (s.failed) "error_outline" else "check_circle",
                        // The message names where the file went: all of it, however long.
                        titleMaxLines = Int.MAX_VALUE,
                        onClick = onDone,
                    )
                }
                item { GuidedRow(stringResource(R.string.backup_done), icon = "done", onClick = onDone) }
            }
        }
    }
}

private fun LazyListScope.exportForm(
    firstRow: FocusRequester,
    password: String,
    visible: Boolean,
    problem: PasswordProblem?,
    minLength: Int,
    destination: ExportDestination,
    onPassword: (String) -> Unit,
    onToggleVisible: () -> Unit,
    onExport: () -> Unit,
) {
    item {
        GuidedPasswordRow(
            label = stringResource(R.string.backup_password),
            value = password,
            onValueChange = onPassword,
            visible = visible,
            imeAction = ImeAction.Done,
            modifier = Modifier.focusRequester(firstRow),
        )
    }
    item {
        GuidedRow(
            title = stringResource(if (visible) R.string.backup_password_hide else R.string.backup_password_show),
            icon = if (visible) "visibility_off" else "visibility",
            onClick = onToggleVisible,
        )
    }
    item {
        // A refused password is said in this row, so no row appears or moves.
        val (label, color) = when (problem) {
            PasswordProblem.TooShort -> stringResource(R.string.backup_password_too_short, minLength) to ErrorColor
            null -> when (PasswordPolicy.strength(password)) {
                PasswordStrength.TooShort -> stringResource(R.string.backup_strength_too_short, minLength) to ErrorColor
                PasswordStrength.Weak -> stringResource(R.string.backup_strength_weak) to Color(0xFFFFB74D)
                PasswordStrength.Fair -> stringResource(R.string.backup_strength_fair) to Color(0xFFFFF176)
                PasswordStrength.Strong -> stringResource(R.string.backup_strength_strong) to Color(0xFF81C784)
            }
        }
        GuidedRow(
            title = stringResource(R.string.backup_password_strength),
            subtitle = label,
            subtitleColor = color,
            icon = if (problem != null) "error_outline" else "shield",
        )
    }
    item {
        GuidedRow(
            title = stringResource(if (destination == ExportDestination.SaveLocally) R.string.backup_export_save else R.string.backup_export_share),
            icon = if (destination == ExportDestination.SaveLocally) "save" else "share",
            end = RowEnd.ChevronRight,
            onClick = onExport,
        )
    }
}

private val ErrorColor = Color(0xFFE57373)
