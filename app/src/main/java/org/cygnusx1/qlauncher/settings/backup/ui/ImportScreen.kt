/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.container
import org.cygnusx1.qlauncher.reports.ErrorReports
import org.cygnusx1.qlauncher.settings.backup.BackupKind
import org.cygnusx1.qlauncher.settings.backup.ImportException
import org.cygnusx1.qlauncher.settings.backup.ImportOrigin
import org.cygnusx1.qlauncher.settings.backup.ImportPlan
import org.cygnusx1.qlauncher.settings.backup.ImportPreview
import org.cygnusx1.qlauncher.settings.backup.ImportSession
import org.cygnusx1.qlauncher.settings.backup.LauncherRestart
import org.cygnusx1.qlauncher.settings.backup.NotCarried
import org.cygnusx1.qlauncher.settings.backup.autobackup.AutoBackupEntry
import org.cygnusx1.qlauncher.settings.backup.open
import java.text.DateFormat
import java.util.Date

/** What *Import* reads. */
sealed interface ImportSource {
    /** A file picked in a file manager, or opened from one (`.qlbackup`, `.qltheme`, `.plbackup`, `.pltheme`). */
    data class Document(val uri: Uri) : ImportSource

    /** One of the automatic backups; no password is asked. */
    data class AutoBackup(val entry: AutoBackupEntry) : ImportSource
}

private sealed interface ImportState {
    data object Loading : ImportState
    data class Password(val wrong: Boolean) : ImportState
    data class Preview(val plan: ImportPlan, val confirming: Boolean = false) : ImportState
    data object Applying : ImportState
    data class Failed(val message: String) : ImportState
}

/**
 * The import flow: password (QLauncher files), a preview of what will be replaced (with unknown
 * keys and what cannot carry over from Projectivy), then all-or-nothing apply and a launcher
 * restart. [onDone] is called on BACK or after a failure is acknowledged; a successful import
 * does not return (the launcher restarts).
 */
@Composable
fun ImportScreen(source: ImportSource, onDone: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { context.container.backup.manager }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ImportState>(ImportState.Loading) }
    var session by remember { mutableStateOf<ImportSession?>(null) }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val replaceRow = remember { FocusRequester() }
    val firstRow = remember { FocusRequester() }

    val notABackup = stringResource(R.string.backup_not_a_backup)
    val damaged = stringResource(R.string.backup_damaged)
    val tooNew = stringResource(R.string.backup_too_new)
    val unreadable = stringResource(R.string.backup_unreadable)
    val failedText = stringResource(R.string.backup_import_failed)

    fun messageFor(e: Exception): String = when (e) {
        is ImportException.NotABackup -> notABackup
        is ImportException.Damaged -> damaged
        is ImportException.TooNew -> tooNew.format(e.formatVersion)
        is ImportException.Unreadable -> unreadable
        else -> failedText.format(e.localizedMessage ?: e.javaClass.simpleName)
    }

    suspend fun decode(pw: CharArray?) {
        val s = session ?: return
        state = try {
            ImportState.Preview(manager.plan(s, pw))
        } catch (e: CancellationException) {
            throw e
        } catch (_: ImportException.WrongPassword) {
            ImportState.Password(wrong = true)
        } catch (e: Exception) {
            ImportState.Failed(messageFor(e))
        }
    }

    LaunchedEffect(source) {
        state = try {
            when (source) {
                is ImportSource.AutoBackup -> ImportState.Preview(manager.plan(source.entry))
                is ImportSource.Document -> {
                    val s = manager.open(context, source.uri)
                    session = s
                    if (s.needsPassword) ImportState.Password(wrong = false) else {
                        decode(null)
                        state
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportState.Failed(messageFor(e))
        }
    }
    DisposableEffect(Unit) { onDispose { session?.close() } }
    LaunchedEffect(state::class) { runCatching { firstRow.requestFocus() } }

    BackHandler {
        val s = state
        if (s is ImportState.Preview && s.confirming) state = s.copy(confirming = false) else if (s != ImportState.Applying) onDone()
    }

    fun apply(plan: ImportPlan) {
        state = ImportState.Applying
        scope.launch {
            try {
                manager.apply(plan)
                session?.close()
                LauncherRestart.restart(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorReports.recordError("backup.apply", e)
                state = ImportState.Failed(failedText.format(e.localizedMessage ?: e.javaClass.simpleName))
            }
        }
    }

    val confirm = state as? ImportState.Preview
    val expansion = if (confirm != null && confirm.confirming) {
        GuidedExpansion(
            title = stringResource(if (confirm.plan.preview.kind == BackupKind.Theme) R.string.backup_apply_theme else R.string.backup_replace_setup),
            subtitle = stringResource(R.string.backup_replace_detail),
            icon = "settings_backup_restore",
            options = listOf(
                stringResource(R.string.backup_cancel) to { state = confirm.copy(confirming = false) },
                stringResource(R.string.backup_ok) to { apply(confirm.plan) },
            ),
            returnFocus = replaceRow,
        )
    } else {
        null
    }
    GuidedPage(
        breadcrumb = stringResource(R.string.backup_manage_settings),
        title = stringResource(R.string.backup_import),
        description = stringResource(R.string.backup_import_description),
        icon = "settings_backup_restore",
        expansion = expansion,
    ) {
        when (val s = state) {
            ImportState.Loading -> item {
                GuidedRow(stringResource(R.string.backup_reading), Modifier.focusRequester(firstRow), icon = "hourglass_empty")
            }
            is ImportState.Password -> {
                item {
                    GuidedPasswordRow(
                        label = stringResource(R.string.backup_password),
                        value = password,
                        onValueChange = { password = it },
                        visible = visible,
                        modifier = Modifier.focusRequester(firstRow),
                        imeAction = ImeAction.Done,
                        onImeAction = { scope.launch { decode(password.toCharArray()) } },
                    )
                }
                item {
                    GuidedRow(
                        title = stringResource(if (visible) R.string.backup_password_hide else R.string.backup_password_show),
                        icon = if (visible) "visibility_off" else "visibility",
                        onClick = { visible = !visible },
                    )
                }
                item {
                    GuidedRow(
                        title = stringResource(R.string.backup_import_action),
                        // Always two lines, so a wrong password changes the text, not the layout.
                        subtitle = stringResource(if (s.wrong) R.string.backup_wrong_password else R.string.backup_password_hint),
                        subtitleColor = if (s.wrong) androidx.compose.ui.graphics.Color(0xFFE57373) else null,
                        icon = if (s.wrong) "error_outline" else "lock_open",
                        end = RowEnd.ChevronRight,
                        onClick = {
                            state = ImportState.Loading
                            scope.launch { decode(password.toCharArray()) }
                        },
                    )
                }
            }
            is ImportState.Preview -> previewRows(
                preview = s.plan.preview,
                firstRow = firstRow,
                replaceRow = replaceRow,
                onExpand = { state = s.copy(confirming = true) },
            )
            ImportState.Applying -> item {
                GuidedRow(stringResource(R.string.backup_applying), Modifier.focusRequester(firstRow), icon = "hourglass_empty")
            }
            is ImportState.Failed -> {
                item { GuidedRow(s.message, Modifier.focusRequester(firstRow), icon = "error_outline", titleMaxLines = Int.MAX_VALUE, onClick = onDone) }
                item { GuidedRow(stringResource(R.string.backup_close), icon = "close", onClick = onDone) }
            }
        }
    }
}

private fun LazyListScope.previewRows(
    preview: ImportPreview,
    firstRow: FocusRequester,
    replaceRow: FocusRequester,
    onExpand: () -> Unit,
) {
    item {
        val origin = stringResource(
            when (preview.origin) {
                ImportOrigin.QLauncherBackup -> R.string.backup_origin_ql_backup
                ImportOrigin.QLauncherTheme -> R.string.backup_origin_ql_theme
                ImportOrigin.ProjectivyBackup -> R.string.backup_origin_pl_backup
                ImportOrigin.ProjectivyTheme -> R.string.backup_origin_pl_theme
                ImportOrigin.AutomaticBackup -> R.string.backup_origin_auto
            },
        )
        val details = listOfNotNull(
            preview.themeName?.let { name ->
                if (preview.themeAuthor.isNullOrBlank()) name else stringResource(R.string.backup_preview_theme_by, name, preview.themeAuthor)
            },
            preview.writtenBy?.let { stringResource(R.string.backup_preview_version, it) },
            preview.createdAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) },
        ).joinToString(" · ").ifEmpty { null }
        GuidedRow(origin, Modifier.focusRequester(firstRow), subtitle = details, icon = "description")
    }
    item {
        GuidedRow(
            stringResource(R.string.backup_preview_settings),
            subtitle = pluralStringResource(R.plurals.backup_count_values, preview.settings, preview.settings),
            icon = "tune",
        )
    }
    preview.categories?.let { n ->
        item { GuidedRow(stringResource(R.string.backup_preview_categories), subtitle = pluralStringResource(R.plurals.backup_count_categories, n, n), icon = "category") }
    }
    preview.appCustomisations?.let { n ->
        item { GuidedRow(stringResource(R.string.backup_preview_apps), subtitle = pluralStringResource(R.plurals.backup_count_apps, n, n), icon = "apps") }
    }
    preview.displayProfiles?.let { n ->
        item { GuidedRow(stringResource(R.string.backup_preview_profiles), subtitle = pluralStringResource(R.plurals.backup_count_profiles, n, n), icon = "settings_display") }
    }
    if (preview.wallpapers > 0) {
        item { GuidedRow(stringResource(R.string.backup_preview_files), subtitle = pluralStringResource(R.plurals.backup_count_files, preview.wallpapers, preview.wallpapers), icon = "image") }
    }
    if (preview.unknownKeys.isNotEmpty()) {
        item {
            GuidedRow(
                pluralStringResource(R.plurals.backup_preview_unknown, preview.unknownKeys.size, preview.unknownKeys.size),
                subtitle = preview.unknownKeys.joinToString(", "),
                icon = "help_outline",
            )
        }
    }
    if (preview.skippedItems > 0) {
        item {
            GuidedRow(
                stringResource(R.string.backup_preview_skipped),
                subtitle = pluralStringResource(R.plurals.backup_preview_skipped_detail, preview.skippedItems, preview.skippedItems),
                icon = "apps_outage",
            )
        }
    }
    for (nc in preview.notCarried) {
        item {
            val (title, detail) = when (nc) {
                NotCarried.ParentalCode -> stringResource(R.string.backup_nc_parental) to stringResource(R.string.backup_nc_parental_detail)
                NotCarried.PremiumState -> stringResource(R.string.backup_nc_premium) to stringResource(R.string.backup_nc_premium_detail)
                is NotCarried.InputBindings -> stringResource(R.string.backup_nc_inputs) to pluralStringResource(R.plurals.backup_nc_inputs_detail, nc.count, nc.count)
                is NotCarried.DisplayProfileBindings -> stringResource(R.string.backup_nc_profile_bindings) to
                    pluralStringResource(R.plurals.backup_nc_profile_bindings_detail, nc.count, nc.count)
                is NotCarried.PluginNotInstalled -> stringResource(R.string.backup_nc_plugin) to stringResource(R.string.backup_nc_plugin_detail, nc.packageName)
                is NotCarried.PrivateIcons -> stringResource(R.string.backup_nc_icons) to pluralStringResource(R.plurals.backup_nc_icons_detail, nc.count, nc.count)
            }
            GuidedRow(title, subtitle = detail, icon = "block")
        }
    }
    item {
        GuidedRow(
            title = stringResource(if (preview.kind == BackupKind.Theme) R.string.backup_apply_theme else R.string.backup_replace_setup),
            modifier = Modifier.focusRequester(replaceRow),
            subtitle = stringResource(R.string.backup_replace_detail),
            icon = "settings_backup_restore",
            end = RowEnd.ExpandMore,
            onClick = onExpand,
        )
    }
}
