/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cygnusx1.qlauncher.util.DocumentPickers
import org.cygnusx1.qlauncher.HomeSlots
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.container
import org.cygnusx1.qlauncher.displayprofiles.CalibrationFullScreen
import org.cygnusx1.qlauncher.parental.ParentalPrompt
import org.cygnusx1.qlauncher.parental.ParentalPromptMode
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.backup.BackupKind
import org.cygnusx1.qlauncher.settings.backup.ui.AutoBackupList
import org.cygnusx1.qlauncher.settings.backup.ui.BackupFileList
import org.cygnusx1.qlauncher.wallpaper.source.LocalMedia
import org.cygnusx1.qlauncher.wallpaper.source.MediaItem
import org.cygnusx1.qlauncher.wallpaper.ui.MediaFolderList
import org.cygnusx1.qlauncher.settings.backup.ui.ExportDestination
import org.cygnusx1.qlauncher.settings.backup.ui.ExportScreen
import org.cygnusx1.qlauncher.settings.backup.ui.ImportScreen
import org.cygnusx1.qlauncher.settings.backup.ui.ImportSource
import org.cygnusx1.qlauncher.system.SystemActions
import org.cygnusx1.qlauncher.ui.EnvironmentProbe
import org.cygnusx1.qlauncher.ui.LocaleHelper
import org.cygnusx1.qlauncher.ui.components.ColorPickerPanel
import org.cygnusx1.qlauncher.ui.components.GalleryItem
import org.cygnusx1.qlauncher.ui.components.PanelHost
import org.cygnusx1.qlauncher.ui.components.TiltedPreview
import org.cygnusx1.qlauncher.ui.components.WallpaperGallery
import org.cygnusx1.qlauncher.ui.home.HomePreview
import org.cygnusx1.qlauncher.ui.settings.model.Environment
import org.cygnusx1.qlauncher.ui.settings.model.Row
import org.cygnusx1.qlauncher.ui.settings.model.RowContext
import org.cygnusx1.qlauncher.apps.iconpack.IconSetMatch
import org.cygnusx1.qlauncher.ui.settings.pages.AccessibilityStatus
import org.cygnusx1.qlauncher.ui.settings.pages.IconMatchPanel
import org.cygnusx1.qlauncher.ui.settings.pages.PageEnv
import org.cygnusx1.qlauncher.ui.settings.pages.PageIds
import org.cygnusx1.qlauncher.ui.settings.pages.PageRevision
import org.cygnusx1.qlauncher.ui.settings.pages.SettingsActions
import org.cygnusx1.qlauncher.ui.settings.pages.SettingsPages
import org.cygnusx1.qlauncher.ui.theme.QLauncherTheme
import org.cygnusx1.qlauncher.ui.theme.QTheme
import org.cygnusx1.qlauncher.util.QrCode
import org.cygnusx1.qlauncher.wallpaper.ui.DynamicColorsBackground
import org.cygnusx1.qlauncher.wallpaper.ui.WallpaperHost

/**
 * Hosts the guided settings pages. Opened on [EXTRA_PAGE] (default: the root page) by the root
 * side panel, by `APPLICATION_PREFERENCES`, or by other screens.
 */
class SettingsActivity : ComponentActivity(), SettingsActions {

    private val environment = MutableStateFlow(Environment())
    private val revision = MutableStateFlow(0L)

    /** What covers the pages: a side panel or a full-screen flow. */
    private sealed interface Overlay {
        data class Color(val initial: Int, val allowDynamic: Boolean, val onPicked: (Int) -> Unit) : Overlay
        data object Gallery : Overlay
        data object ParentalSetup : Overlay
        data class Export(val theme: Boolean, val share: Boolean) : Overlay
        data class Import(val uri: Uri) : Overlay
        data object AutoBackups : Overlay
        data class AutoImport(val source: ImportSource) : Overlay
        data class Files(val theme: Boolean) : Overlay
        data class MediaFiles(val items: List<MediaItem>, val onPicked: (Uri, String?) -> Unit) : Overlay
        data class MediaFolders(val onPicked: (Uri) -> Unit) : Overlay
        data class Pattern(val index: Int) : Overlay
        data class IconMatches(val matches: List<IconSetMatch>) : Overlay
        data object ParentalVerify : Overlay
    }

    private var overlay by mutableStateOf<Overlay?>(null)
    private var folderCallback: ((Uri) -> Unit)? = null
    private var documentCallback: ((Uri) -> Unit)? = null
    private var mediaAccessCallback: (() -> Unit)? = null

    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            folderCallback?.invoke(uri)
        }
        folderCallback = null
    }
    // Refused or not, the list shows what can be seen (limited access on Android 14 shows the chosen items).
    private val mediaPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        mediaAccessCallback?.invoke()
        mediaAccessCallback = null
    }
    private val pickDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            documentCallback?.invoke(uri)
        }
        documentCallback = null
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = intent.getStringExtra(EXTRA_PAGE) ?: PageIds.ROOT
        val container = container
        AccessibilityStatus.provider = { container.system.accessibility.started.value }
        // Settings can be opened from outside the launcher (APPLICATION_PREFERENCES), so the
        // parental code is asked here too when launcher settings are protected.
        if (savedInstanceState == null && container.system.parental.needsCodeForSettings()) overlay = Overlay.ParentalVerify

        // Rows also depend on state outside the settings; any change rebuilds them.
        lifecycleScope.launch {
            val wp = container.wallpaper
            merge(
                wp.controller.status, wp.controller.items, wp.controller.wallpaper, wp.redditUser, wp.providers,
                container.backup.profiles.profiles, container.apps.installer.state, container.apps.installer.installed,
                container.apps.iconPacks.packs, container.system.accessibility.started, PageRevision.tick, container.updates.state,
                container.apps.iconSets.status, container.apps.iconSets.manifest, container.apps.iconSets.releases,
            ).collect { revision.value = revision.value + 1 }
        }

        setContent {
            val colors by container.theme.colors.collectAsStateWithLifecycle()
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            val envFlow = remember { combine(environment, revision) { e, r -> e.copy(revision = r) } }
            val envState by envFlow.collectAsStateWithLifecycle(Environment())
            val fontFamily by container.backup.fonts.fontFamily.collectAsStateWithLifecycle()
            val stack = remember { mutableStateListOf(start to GuidedPageState()) }
            val pageEnv = remember {
                PageEnv(this, container, navigate = { target ->
                    if (target == SettingsPages.NAV_BACK) {
                        if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish()
                    } else {
                        stack.add(target to GuidedPageState())
                    }
                }, actions = this)
            }
            QLauncherTheme(colors = colors, fontFamily = fontFamily, fontScale = settings[Keys.Font.scale], bold = settings[Keys.Font.bold], italic = settings[Keys.Font.italic]) {
                val accent = QTheme.colors.accent
                CompositionLocalProvider(
                    SettingsSlots.Background provides { m -> DynamicColorsBackground(m) },
                    SettingsSlots.LivePreview provides { m ->
                        TiltedPreview(m) {
                            Box(Modifier.fillMaxSize()) {
                                HomeSlots.wallpaper(Modifier.fillMaxSize())
                                HomePreview(Modifier.fillMaxSize())
                            }
                        }
                    },
                    SettingsSlots.WallpaperPreview provides { m -> WallpaperHost(m) },
                    SettingsSlots.Qr provides { text, m -> QrCode(text, m, accent) },
                ) {
                    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                        BackHandler(enabled = overlay == null) {
                            if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish()
                        }
                        if (overlay == Overlay.ParentalVerify) {
                            OverlayLayer()
                            return@Box
                        }
                        val (pageId, state) = stack.last()
                        val ctx = RowContext(settings, envState)
                        AnimatedContent(
                            targetState = pageId to state,
                            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                            label = "page",
                            contentKey = { it.first + "#" + System.identityHashCode(it.second) },
                        ) { (id, st) ->
                            val page = remember(id) { SettingsPages.build(id, pageEnv) }
                            if (page != null) {
                                GuidedPage(
                                    page = page,
                                    ctx = ctx,
                                    repo = container.settings,
                                    state = st,
                                    active = overlay == null && id == pageId,
                                    onNavigate = { pageEnv.navigate(it) },
                                    onOpenColor = { row -> openColor(row) },
                                )
                            } else {
                                MissingPage { pageEnv.navigate(SettingsPages.NAV_BACK) }
                            }
                        }
                        OverlayLayer()
                    }
                }
            }
        }
    }

    @Composable
    private fun OverlayLayer() {
        val container = container
        val o = overlay
        PanelHost(visible = o is Overlay.Color || o is Overlay.Gallery || o is Overlay.MediaFiles || o is Overlay.IconMatches) {
            when (o) {
                is Overlay.Color -> ColorPickerPanel(
                    initial = o.initial,
                    allowDynamic = o.allowDynamic,
                    onResult = {
                        o.onPicked(it)
                        overlay = null
                    },
                    onCancel = { overlay = null },
                )
                is Overlay.MediaFiles -> WallpaperGallery(
                    items = o.items.map { GalleryItem(it.uri, it.name) },
                    onPick = { i ->
                        o.items.getOrNull(i)?.let { o.onPicked(it.uri, it.mimeType) }
                        overlay = null
                    },
                    onBack = { overlay = null },
                )
                is Overlay.IconMatches -> IconMatchPanel(
                    matches = o.matches,
                    onApply = { chosen ->
                        overlay = null
                        // In the app's scope: leaving Settings must not stop it half way.
                        val app = applicationContext
                        container.scope.launch {
                            val sets = container.apps.iconSets
                            val done = chosen.count { sets.apply(it.itemKey, it.icon) }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(app, app.resources.getQuantityString(R.plurals.iconset_applied, done, done), Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onBack = { overlay = null },
                )
                Overlay.Gallery -> {
                    val items by container.wallpaper.controller.items.collectAsStateWithLifecycle()
                    WallpaperGallery(
                        items = items.map { GalleryItem(it.uri, it.title.orEmpty()) },
                        onPick = { i ->
                            items.getOrNull(i)?.let { container.wallpaper.controller.show(it) }
                            overlay = null
                        },
                        onBack = { overlay = null },
                    )
                }
                else -> Unit
            }
        }
        when (o) {
            Overlay.ParentalSetup -> ParentalPrompt(ParentalPromptMode.SetCode, onSuccess = { overlay = null }, onCancel = { overlay = null })
            Overlay.ParentalVerify -> ParentalPrompt(ParentalPromptMode.Verify, onSuccess = { overlay = null }, onCancel = { finish() })
            is Overlay.Export -> {
                BackHandler { overlay = null }
                ExportScreen(if (o.theme) BackupKind.Theme else BackupKind.Settings, if (o.share) ExportDestination.Share else ExportDestination.SaveLocally) { overlay = null }
            }
            is Overlay.Import -> {
                BackHandler { overlay = null }
                ImportScreen(ImportSource.Document(o.uri)) { overlay = null }
            }
            Overlay.AutoBackups -> {
                BackHandler { overlay = null }
                AutoBackupList(
                    onPick = { entry -> overlay = Overlay.AutoImport(ImportSource.AutoBackup(entry)) },
                    onDeleteAll = { lifecycleScope.launch { container.backup.manager.autoBackups.deleteAll() } },
                    onBack = { overlay = null },
                )
            }
            is Overlay.Files -> BackupFileList(
                kind = if (o.theme) BackupKind.Theme else BackupKind.Settings,
                onPick = { uri -> overlay = Overlay.Import(uri) },
                onBack = { overlay = null },
            )
            is Overlay.MediaFolders -> MediaFolderList(
                onPick = { dir ->
                    o.onPicked(Uri.fromFile(dir))
                    overlay = null
                },
                onBack = { overlay = null },
            )
            is Overlay.AutoImport -> {
                BackHandler { overlay = null }
                ImportScreen(o.source) { overlay = null }
            }
            is Overlay.Pattern -> CalibrationFullScreen(o.index, onExit = { overlay = null }, onIndexChange = { overlay = Overlay.Pattern(it) })
            else -> Unit
        }
    }

    private fun openColor(row: Row.Color) {
        val repo = container.settings
        overlay = Overlay.Color(repo.current[row.key], row.allowDynamic) { repo.update(row.key, it) }
    }

    override fun onResume() {
        super.onResume()
        container.system.accessibility.refresh()
        container.system.notificationHub.refreshAccess()
        container.apps.onResume()
        environment.value = EnvironmentProbe.read(this).copy(iconPackInstalled = container.apps.iconPacks.packs.value.isNotEmpty())
    }

    // SettingsActions

    override fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    override fun openDefaultLauncherSettings() {
        if (!container.system.actions.openDefaultLauncherSettings(this)) toast(getString(R.string.settings_default_launcher_unavailable))
    }

    override fun openNotificationAccess() {
        if (!container.system.actions.openNotificationAccessSettings(this)) {
            toast(getString(R.string.settings_notification_adb, SystemActions.NOTIFICATION_ACCESS_ADB_COMMAND))
        }
    }

    override fun openAccessibilitySettings() {
        val actions = container.system.actions
        if (!actions.openAccessibilitySettings(this)) toast(getString(actions.accessibilityNoSettingsMessage()))
    }

    override fun setLanguage(tag: String) {
        lifecycleScope.launch {
            // Before the stored value changes, so whatever it re-renders already resolves in the new language.
            LocaleHelper.applyToApplication(this@SettingsActivity, tag)
            container.settings.set(Keys.General.language, tag)
            // The re-created activity wraps its context with settings.current: wait until it holds the new tag.
            container.settings.settings.first { it[Keys.General.language] == tag }
            LocaleHelper.applySystem(this@SettingsActivity, tag)
            recreate()
        }
    }

    override fun importFromFile(theme: Boolean) {
        overlay = Overlay.Files(theme)
    }

    override fun importFromAutoBackup() {
        overlay = Overlay.AutoBackups
    }

    override fun export(theme: Boolean, share: Boolean) {
        overlay = Overlay.Export(theme, share)
    }

    override fun resetAll() {
        lifecycleScope.launch { container.backup.resetAll(this@SettingsActivity) }
    }

    override fun setParentalCode() {
        overlay = Overlay.ParentalSetup
    }

    override fun requestTvListings() {
        requestPermissions(arrayOf(EnvironmentProbe.PERMISSION_TV_LISTINGS), 1)
    }

    override fun openWallpaperGallery() {
        overlay = Overlay.Gallery
    }

    override fun startActivity(intent: Intent) {
        try {
            super.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.home_no_activity))
        } catch (_: SecurityException) {
            toast(getString(R.string.home_no_activity))
        }
    }

    override fun pickFolder(onPicked: (Uri) -> Unit) {
        if (!DocumentPickers.available(this, Intent.ACTION_OPEN_DOCUMENT_TREE)) {
            toast(getString(R.string.settings_no_file_picker))
            return
        }
        folderCallback = onPicked
        try {
            pickFolderLauncher.launch(null)
        } catch (_: ActivityNotFoundException) {
            folderCallback = null
            toast(getString(R.string.settings_no_file_picker))
        }
    }

    override fun pickWallpaperFile(onPicked: (Uri, String?) -> Unit) {
        withMediaAccess {
            lifecycleScope.launch {
                val items = LocalMedia.files(this@SettingsActivity)
                // Something else opened while the scan ran: leave it, do not cover it with the gallery.
                if (overlay != null) return@launch
                if (items.isEmpty()) toast(getString(R.string.wp_media_none)) else overlay = Overlay.MediaFiles(items, onPicked)
            }
        }
    }

    override fun pickWallpaperFolder(onPicked: (Uri) -> Unit) {
        withMediaAccess { overlay = Overlay.MediaFolders(onPicked) }
    }

    /** Runs [action] once QLauncher may list pictures and videos, asking for the media permission first. */
    private fun withMediaAccess(action: () -> Unit) {
        if (LocalMedia.hasAccess(this)) {
            action()
            return
        }
        mediaAccessCallback = action
        try {
            mediaPermissionLauncher.launch(LocalMedia.permissions())
        } catch (_: ActivityNotFoundException) {
            mediaAccessCallback = null
            action()
        }
    }

    override fun pickDocument(mimeTypes: Array<String>, onPicked: (Uri) -> Unit) {
        if (!DocumentPickers.available(this, type = mimeTypes.singleOrNull() ?: "*/*")) {
            toast(getString(R.string.settings_no_file_picker))
            return
        }
        documentCallback = onPicked
        try {
            pickDocumentLauncher.launch(mimeTypes)
        } catch (_: ActivityNotFoundException) {
            documentCallback = null
            toast(getString(R.string.settings_no_file_picker))
        }
    }

    override fun pickColor(initial: Int, onPicked: (Int) -> Unit) {
        overlay = Overlay.Color(initial, allowDynamic = false, onPicked)
    }

    override fun showPattern(index: Int) {
        overlay = Overlay.Pattern(index)
    }

    override fun matchIconSet() {
        lifecycleScope.launch {
            val matches = container.apps.iconSets.matches()
            if (matches.isEmpty()) toast(getString(R.string.iconset_match_none)) else overlay = Overlay.IconMatches(matches)
        }
    }

    companion object {
        const val EXTRA_PAGE = "org.cygnusx1.qlauncher.extra.PAGE"

        fun intent(context: Context, page: String = PageIds.ROOT): Intent =
            Intent(context, SettingsActivity::class.java).putExtra(EXTRA_PAGE, page)
    }
}

@Composable
private fun MissingPage(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize())
}
