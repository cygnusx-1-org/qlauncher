/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.cygnusx1.qlauncher.util.DocumentPickers
import org.cygnusx1.qlauncher.parental.ParentalLaunchRequest
import org.cygnusx1.qlauncher.parental.ParentalPrompt
import org.cygnusx1.qlauncher.parental.ParentalPromptMode
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.shared.MediaAction
import org.cygnusx1.qlauncher.system.SystemActions
import org.cygnusx1.qlauncher.ui.EnvironmentProbe
import org.cygnusx1.qlauncher.ui.LocaleHelper
import org.cygnusx1.qlauncher.ui.components.PanelHost
import org.cygnusx1.qlauncher.ui.firstrun.FirstRun
import org.cygnusx1.qlauncher.ui.firstrun.FirstRunActions
import org.cygnusx1.qlauncher.ui.home.BuiltInAction
import org.cygnusx1.qlauncher.ui.home.CardItem
import org.cygnusx1.qlauncher.ui.home.CardKind
import org.cygnusx1.qlauncher.ui.home.CardStyle
import org.cygnusx1.qlauncher.ui.home.HomeController
import org.cygnusx1.qlauncher.ui.home.HomeEvent
import org.cygnusx1.qlauncher.ui.home.HomeFocus
import org.cygnusx1.qlauncher.ui.home.HomeRow
import org.cygnusx1.qlauncher.ui.home.HomeScreen
import org.cygnusx1.qlauncher.ui.home.HomeUi
import org.cygnusx1.qlauncher.ui.home.Region
import org.cygnusx1.qlauncher.ui.home.StatusBarInfo
import org.cygnusx1.qlauncher.ui.home.StatusButton
import org.cygnusx1.qlauncher.ui.home.panels.HomeActions
import org.cygnusx1.qlauncher.ui.home.panels.HomePanelHost
import org.cygnusx1.qlauncher.ui.home.panels.PanelSpec
import org.cygnusx1.qlauncher.ui.settings.SettingsActivity
import org.cygnusx1.qlauncher.ui.settings.pages.PageIds
import org.cygnusx1.qlauncher.ui.theme.QLauncherTheme
import java.util.Calendar
import java.util.Date
import org.cygnusx1.qlauncher.wallpaper.source.LocalMedia
import org.cygnusx1.qlauncher.wallpaper.source.MediaItem

/**
 * The home screen activity (HOME, LEANBACK_LAUNCHER and LAUNCHER). It draws the wallpaper, the
 * category rows and the side panels, and handles the launcher-level keys.
 */
class MainActivity : ComponentActivity(), HomeActions, FirstRunActions {

    private lateinit var controller: HomeController
    private val panels = mutableStateListOf<PanelSpec>()
    private val focus = HomeFocus()
    private var parental by mutableStateOf<ParentalRequest?>(null)
    private var easterEgg by mutableStateOf(false)
    private val clock = MutableStateFlow(AnnotatedString(""))
    private val network = MutableStateFlow<String?>(null)
    private var lastBack = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastPause = 0L
    private var isResumed = false
    private var permissionCallback: (() -> Unit)? = null
    private var imageCallback: ((String) -> Unit)? = null
    private var mediaAccessCallback: (() -> Unit)? = null

    // Refused or not, the gallery shows what can be seen (limited access on Android 14 shows the chosen items).
    private val mediaPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        mediaAccessCallback?.invoke()
        mediaAccessCallback = null
    }
    private val shortcutCache = androidx.compose.runtime.mutableStateMapOf<String, List<Pair<String, () -> Unit>>>()

    private val idle by lazy { container.system.newLauncherIdleTimer() }

    private var iconPackTarget: String? = null

    /** The *Change application language* tag this instance was created with. */
    private var language = ""

    private val iconPackPicker = registerForActivityResult(org.cygnusx1.qlauncher.apps.iconpack.PickIconFromPack()) { result ->
        val key = iconPackTarget ?: return@registerForActivityResult
        iconPackTarget = null
        lifecycleScope.launch { container.apps.iconPacks.savePickedIcon(key, result) }
    }

    private val tvListingsRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionCallback?.invoke()
        permissionCallback = null
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            imageCallback?.invoke(uri.toString())
        }
        imageCallback = null
    }

    /** A pending parental prompt and what to do once it is passed. */
    private class ParentalRequest(val mode: ParentalPromptMode, val onSuccess: () -> Unit)

    override fun attachBaseContext(newBase: Context) {
        language = LocaleHelper.tag(newBase)
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onRestart() {
        super.onRestart()
        // *Change application language* while home was stopped: below Android 13 nothing else re-creates it.
        if (LocaleHelper.tag(this) != language) recreate()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // The home screen and its panels handle BACK themselves; the launcher never finishes on it.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (panels.isNotEmpty()) panels.removeAt(panels.lastIndex)
            }
        })
        controller = HomeUi.controller(this)
        val container = container
        val s0 = container.settings.current
        focus.headersOpen = s0[Keys.LeftHeaders.enabled] && s0[Keys.LeftHeaders.showOnLaunch]
        handleIntent(intent, fresh = true)
        startClock()
        watchNetwork()

        lifecycleScope.launch {
            container.settings.settings.collect { s ->
                idle.configure(s[Keys.Idle.timeout], s[Keys.Idle.opacity])
                applySystemBars(s[Keys.StatusBar.showSystemBar])
            }
        }

        setContent {
            val colors by container.theme.colors.collectAsStateWithLifecycle()
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            val rows by controller.rows.collectAsStateWithLifecycle()
            val clockText by clock.collectAsStateWithLifecycle()
            val net by network.collectAsStateWithLifecycle()
            val isIdle by idle.isIdle.collectAsStateWithLifecycle()
            val notifications = controller.sources.notifications
            val notifCount = notifications?.notifications?.collectAsStateWithLifecycle()?.value?.size ?: 0
            val notifAccess = notifications?.accessGranted?.collectAsStateWithLifecycle()?.value ?: false
            val mediaPlaying = notifications?.nowPlaying?.collectAsStateWithLifecycle()?.value?.isPlaying == true
            val style = remember(settings, colors) { CardStyle.from(settings, colors.palette, colors.accent) }
            val onboarding = !settings[Keys.Internal.onboardingCompleted]

            val idleOpacity = settings[Keys.Idle.opacity]
            val uiAlpha by animateFloatAsState(if (isIdle) idleOpacity else 1f, tween(if (isIdle) 1000 else 300), label = "idle")
            val clockAlpha by animateFloatAsState(if (isIdle) (if (settings[Keys.Idle.keepClock]) 0.8f else idleOpacity) else 1f, tween(if (isIdle) 1000 else 300), label = "idleClock")
            LaunchedEffect(isIdle, settings[Keys.Idle.preventScreensaver]) {
                if (isIdle && settings[Keys.Idle.preventScreensaver]) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            val tilt by animateFloatAsState(if (easterEgg) 1f else 0f, tween(1000), label = "easterEgg")
            LaunchedEffect(isIdle) { HomeSlots.onIdleChanged(isIdle) }
            val fontFamily by container.backup.fonts.fontFamily.collectAsStateWithLifecycle()
            val currentWallpaper by container.wallpaper.controller.wallpaper.collectAsStateWithLifecycle()
            val wallpaperLink = currentWallpaper?.actionUri != null

            val status = StatusBarInfo(
                buttons = buildList {
                    if (wallpaperLink) add(StatusButton.WallpaperInfo)
                    if (settings[Keys.StatusBar.showConnectivity] && net != null) add(StatusButton.Connectivity)
                    if (settings[Keys.StatusBar.showNotifications] && notifAccess && (notifCount > 0 || !settings[Keys.StatusBar.autohideNotifications])) add(StatusButton.Notifications)
                    if (settings[Keys.StatusBar.showSettings]) add(StatusButton.Settings)
                },
                showClock = settings[Keys.Clock.show],
                clock = clockText,
                connectivityIcon = net ?: "wifi_off",
                notificationCount = notifCount,
            )

            QLauncherTheme(colors = colors, fontFamily = fontFamily, fontScale = settings[Keys.Font.scale], bold = settings[Keys.Font.bold], italic = settings[Keys.Font.italic]) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        // Every key passes here first: idle mode swallows the key that wakes it.
                        .onPreviewKeyEvent { e -> idle.onKeyEvent(e.type == KeyEventType.KeyDown) }
                        .semantics { testTagsAsResourceId = true },
                ) {
                    HomeWallpaper(Modifier.fillMaxSize())
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1f - 0.2f * tilt
                                scaleY = 1f - 0.2f * tilt
                                rotationX = 35f * tilt
                            },
                    ) {
                        HomeScreen(
                            rows = rows,
                            settings = settings,
                            style = style,
                            focus = focus,
                            status = status,
                            interactive = panels.isEmpty() && parental == null && !onboarding,
                            onEvent = ::onHomeEvent,
                            uiAlpha = uiAlpha,
                            clockAlpha = clockAlpha,
                            images = controller.sources.images,
                            mediaPlaying = mediaPlaying,
                        )
                    }
                    PanelHost(visible = panels.isNotEmpty()) {
                        HomePanelHost(panels, controller, this@MainActivity)
                    }
                    parental?.let { req ->
                        ParentalPrompt(
                            mode = req.mode,
                            onSuccess = {
                                parental = null
                                req.onSuccess()
                            },
                            onCancel = { parental = null },
                        )
                    }
                    if (onboarding) FirstRun(this@MainActivity)
                }
            }
        }
    }

    @Composable
    private fun HomeWallpaper(modifier: Modifier) {
        HomeSlots.wallpaper(modifier)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent, fresh = false)
    }

    private fun handleIntent(intent: Intent?, fresh: Boolean) {
        intent ?: return
        ParentalLaunchRequest.from(intent)?.let { req ->
            ParentalLaunchRequest.consume(intent)
            parental = ParentalRequest(ParentalPromptMode.Verify) { container.system.resumeAfterParentalCheck(req, this) }
            return
        }
        when (intent.action) {
            ACTION_SETTINGS -> openPanel(PanelSpec.Root)
            ACTION_ALL_APPS, Intent.ACTION_ALL_APPS -> openPanel(PanelSpec.AllApps)
            ACTION_NOTIFICATIONS -> openPanel(PanelSpec.Notifications)
            Intent.ACTION_MAIN -> {
                val home = intent.hasCategory(Intent.CATEGORY_HOME) || intent.getBooleanExtra(SystemActions.EXTRA_FROM_HOME_KEY, false)
                // Home while already on the launcher (or within 50 ms of leaving it) goes to the
                // default category.
                if (!fresh && home && (isResumed || SystemClock.uptimeMillis() - lastPause < 50)) goHome()
                else if (!fresh && home) {
                    panels.clear()
                    parental = null
                }
            }
        }
    }

    private fun openPanel(spec: PanelSpec) {
        panels.clear()
        panels.add(spec)
    }

    /** Home: close everything and focus the default category's first card. */
    private fun goHome() {
        panels.clear()
        parental = null
        focus.region = Region.Rows
        focus.headersOpen = container.settings.current.let { it[Keys.LeftHeaders.enabled] && it[Keys.LeftHeaders.showOnLaunch] }
        focus.reorderCard = null
        focus.reorderCategory = false
        val rows = controller.rows.value
        val d = controller.defaultRowIndex(rows)
        rows.getOrNull(d)?.let { focus.cards[it.id] = 0 }
        focus.row = d
    }

    private fun onHomeEvent(e: HomeEvent) {
        when (e) {
            is HomeEvent.Open -> open(e.card, e.row)
            is HomeEvent.Menu -> withSettingsCode { panels.add(PanelSpec.AppMenu(e.row.id, e.card.key)) }
            is HomeEvent.Focused -> HomeSlots.onCardFocused(e.row, e.card)
            is HomeEvent.Status -> onStatus(e.button, e.long)
            is HomeEvent.EditCategory -> withSettingsCode { panels.add(PanelSpec.CategoryEditor(e.categoryId)) }
            is HomeEvent.CategoryContent -> panels.add(PanelSpec.Folder(e.categoryId))
            is HomeEvent.MoveCard -> controller.moveCard(e.categoryId, e.key, e.delta)
            is HomeEvent.MoveCategory -> controller.moveCategory(e.categoryId, e.delta)
            HomeEvent.OpenRootPanel -> openPanel(PanelSpec.Root)
            HomeEvent.NextWallpaper -> HomeSlots.nextWallpaper()
            HomeEvent.EasterEgg -> easterEgg = !easterEgg
            HomeEvent.Back -> onBack()
        }
    }

    /**
     * Back: to the first card of the row, then to the default category; twice within 400 ms
     * starts the screensaver.
     */
    private fun onBack() {
        val now = SystemClock.uptimeMillis()
        val s = container.settings.current
        if (now - lastBack < 400 && s[Keys.Remote.backDoubleAmbient]) {
            lastBack = 0L
            if (!container.system.actions.startScreensaver()) toast(getString(R.string.home_screensaver_unavailable))
            return
        }
        lastBack = now
        val rows = controller.rows.value
        val row = rows.getOrNull(focus.row) ?: return
        if (focus.cardIndex(row.id) > 0) {
            focus.cards[row.id] = 0
        } else {
            goHome()
        }
    }

    private fun onStatus(button: StatusButton, long: Boolean) {
        when (button) {
            StatusButton.Settings -> if (long) openAndroidSettings() else openPanel(PanelSpec.Root)
            StatusButton.Notifications -> if (long) {
                tryStart(Intent("com.android.tv.action.OPEN_NOTIFICATIONS_PANEL"))
            } else {
                openPanel(PanelSpec.Notifications)
            }
            StatusButton.Connectivity -> if (!long) {
                if (!tryStart(Intent(Settings.ACTION_WIFI_SETTINGS))) tryStart(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
            StatusButton.WallpaperInfo -> HomeSlots.openWallpaperAction(this)
        }
    }

    // HomeActions

    override fun open(card: CardItem, row: HomeRow?) {
        withLaunchCode(card.key, card.packageName) { launchCard(card, row) }
    }

    /**
     * Runs [block] after the parental code when the item, or the app it belongs to (a program,
     * a shortcut, a Now Playing card), is locked.
     */
    private fun withLaunchCode(key: String, packageName: String?, block: () -> Unit) {
        val p = container.system.parental
        val locked = p.needsCodeToLaunch(key) || (packageName != null && p.needsCodeForPackage(packageName))
        if (locked) parental = ParentalRequest(ParentalPromptMode.Verify, block) else block()
    }

    private fun launchCard(card: CardItem, row: HomeRow?) {
        when (card.kind) {
            CardKind.TvApp, CardKind.MobileApp -> {
                when {
                    HomeSlots.launchApp(this, card.key) -> Unit
                    container.system.actions.launchAppItem(card.key, this) -> container.itemOverrides.recordLaunch(card.key)
                    else -> toast(getString(R.string.home_no_activity))
                }
            }
            CardKind.Action -> BuiltInAction.byKey(card.key)?.let { runAction(it) }
            CardKind.Folder -> card.key.removePrefix("folder:").toIntOrNull()?.let { panels.add(PanelSpec.Folder(it)) }
            CardKind.Placeholder -> row?.let { panels.add(PanelSpec.CategoryEditor(it.id)) }
            CardKind.NowPlaying -> card.packageName?.let { container.system.actions.launchPackage(it, this) }
            CardKind.Program -> {
                val uri = card.program?.intentUri
                val started = uri != null && runCatching { tryStart(Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)) }.getOrDefault(false)
                if (!started) card.packageName?.let { container.system.actions.launchPackage(it, this) }
            }
            CardKind.Input, CardKind.Shortcut -> if (!HomeSlots.launchOther(this, card)) toast(getString(R.string.home_no_activity))
        }
    }

    override fun runAction(action: BuiltInAction) {
        when (action) {
            BuiltInAction.AllApps -> openPanel(PanelSpec.AllApps)
            BuiltInAction.EditCategories -> withSettingsCode { openPanel(PanelSpec.EditCategories) }
            BuiltInAction.EditChannels -> withSettingsCode { openPanel(PanelSpec.Channels) }
            BuiltInAction.LauncherSettings -> withSettingsCode { openSettings(PageIds.ROOT) }
            BuiltInAction.AndroidSettings -> openAndroidSettings()
            BuiltInAction.DisplayProfiles -> openSettings(PageIds.DISPLAY_PROFILES)
            BuiltInAction.InstallApps -> openSettings(PageIds.INSTALL_APPS)
            BuiltInAction.Accessibility -> openSettings(PageIds.ACCESSIBILITY)
            BuiltInAction.AboutApp -> openSettings(PageIds.ABOUT_APP)
            BuiltInAction.AboutDevice -> openSettings(PageIds.ABOUT_DEVICE)
            BuiltInAction.AboutWallpaper -> openSettings(PageIds.ABOUT_WALLPAPER)
            BuiltInAction.AmlogicSettings, BuiltInAction.SmartHome, BuiltInAction.SwitchProfile -> {
                panels.clear()
                HomeSlots.runDeviceAction(this, action)
            }
        }
    }

    private fun openSettings(page: String) {
        panels.clear()
        startActivity(SettingsActivity.intent(this, page))
    }

    /** Starts the system settings directly: two apps may answer the generic intent on a Shield. */
    private fun openAndroidSettings() {
        panels.clear()
        val intent = Intent(Settings.ACTION_SETTINGS)
        val handlers = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val system = handlers.firstOrNull { (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
        if (system != null) intent.setClassName(system.activityInfo.packageName, system.activityInfo.name)
        tryStart(intent)
    }

    override fun appInfo(packageName: String) {
        tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
    }

    override fun uninstall(packageName: String) {
        tryStart(Intent(Intent.ACTION_DELETE, android.net.Uri.fromParts("package", packageName, null)))
    }

    override fun isLaunchOnBoot(key: String): Boolean = container.settings.current[Keys.Power.autostartSource] == key

    override fun setLaunchOnBoot(key: String) {
        val current = container.settings.current[Keys.Power.autostartSource]
        container.settings.update(Keys.Power.autostartSource, if (current == key) "" else key)
    }

    override fun setParental(key: String, locked: Boolean) {
        val p = container.system.parental
        val apply = { p.setLocked(key, locked) }
        when {
            !p.isCodeSet() -> parental = ParentalRequest(ParentalPromptMode.SetCode) { apply() }
            p.needsCodeToManage() -> parental = ParentalRequest(ParentalPromptMode.Verify) { apply() }
            else -> apply()
        }
    }

    override fun hasImagePicker(): Boolean = DocumentPickers.available(this, type = "image/*")

    override fun findPictures(onFound: (List<MediaItem>) -> Unit) {
        val search = {
            lifecycleScope.launch {
                val pictures = LocalMedia.files(this@MainActivity).filter { it.mimeType?.startsWith("image/") == true }
                if (pictures.isEmpty()) toast(getString(R.string.menu_icon_no_pictures)) else onFound(pictures)
            }
            Unit
        }
        if (LocalMedia.hasAccess(this)) {
            search()
            return
        }
        mediaAccessCallback = search
        try {
            mediaPermission.launch(LocalMedia.permissions())
        } catch (_: ActivityNotFoundException) {
            mediaAccessCallback = null
            search()
        }
    }

    override fun pickImage(onPicked: (String) -> Unit) {
        if (!DocumentPickers.available(this, type = "image/*")) {
            toast(getString(R.string.settings_no_file_picker))
            return
        }
        imageCallback = onPicked
        runCatching { pickImage.launch(arrayOf("image/*")) }.onFailure { imageCallback = null }
    }

    override fun iconPacks(): List<Pair<String, String>> =
        container.apps.iconPacks.packs.value.map { it.packageName to it.label }

    override fun pickIconFromPack(pack: String, itemKey: String) {
        iconPackTarget = itemKey
        runCatching { iconPackPicker.launch(pack) }.onFailure { iconPackTarget = null }
    }

    override fun mediaAction(action: MediaAction) {
        controller.sources.notifications?.mediaAction(action)
    }

    override fun requestNotificationAccess() = openNotificationAccess()

    override fun startCardReorder(categoryId: Int, key: String) {
        val rows = controller.rows.value
        val i = rows.indexOfFirst { it.id == categoryId }
        if (i < 0) return
        focus.row = i
        focus.region = Region.Rows
        focus.cards[categoryId] = rows[i].items.indexOfFirst { it.key == key }.coerceAtLeast(0)
        focus.reorderCard = key
        toast(getString(R.string.home_reorder_hint))
    }

    /** Shortcuts load after the menu opens; the panel recomposes when they arrive. */
    override fun appShortcuts(packageName: String): List<Pair<String, () -> Unit>> {
        shortcutCache[packageName]?.let { return it }
        shortcutCache[packageName] = emptyList()
        lifecycleScope.launch {
            shortcutCache[packageName] = runCatching { HomeSlots.loadAppShortcuts(this@MainActivity, packageName) }
                .getOrDefault(emptyList())
                .map { (label, launch) -> label to { withLaunchCode("", packageName, launch) } }
        }
        return emptyList()
    }

    // FirstRunActions

    override fun openAccessibilitySettings() {
        container.system.actions.openAccessibilitySettings(this)
    }

    override fun requestTvListings(onDone: () -> Unit) {
        // Before Android 8 there are no channels to read, so there is nothing to ask for.
        if (Build.VERSION.SDK_INT < 26 || EnvironmentProbe.read(this).tvListingsGranted) {
            onDone()
            return
        }
        permissionCallback = onDone
        runCatching { tvListingsRequest.launch(EnvironmentProbe.PERMISSION_TV_LISTINGS) }.onFailure {
            permissionCallback = null
            onDone()
        }
    }

    override fun openNotificationAccess() {
        if (!container.system.actions.openNotificationAccessSettings(this)) {
            toast(getString(R.string.settings_notification_adb, SystemActions.NOTIFICATION_ACCESS_ADB_COMMAND))
        }
    }

    override fun openDefaultLauncher() {
        if (!container.system.actions.openDefaultLauncherSettings(this)) toast(getString(R.string.settings_default_launcher_unavailable))
    }

    override fun setCrashErrorReports(enabled: Boolean) {
        container.settings.update(Keys.General.crashErrorReports, enabled)
    }

    override fun completeOnboarding() {
        container.settings.update(Keys.Internal.onboardingCompleted, true)
    }

    // Lifecycle and input

    override fun onResume() {
        super.onResume()
        isResumed = true
        container.system.onHomeResumed(this)
        container.apps.onResume()
        shortcutCache.clear()
        idle.start()
        HomeSlots.onHomeVisible(true)
        if (container.system.showAccessibilityReminder.value && container.settings.current[Keys.Internal.onboardingCompleted]) {
            toast(getString(R.string.settings_a11y_unbound_toast))
        }
        if (container.settings.current[Keys.Internal.onboardingCompleted]) {
            container.updates.autoCheck { version -> toast(getString(R.string.update_available_toast, version)) }
        }
    }

    override fun onPause() {
        super.onPause()
        isResumed = false
        lastPause = SystemClock.uptimeMillis()
        idle.stop()
        HomeSlots.onHomeVisible(false)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && idle.onUserInput()) return true
        return super.dispatchTouchEvent(ev)
    }

    // Helpers

    /** Runs [block] after the parental code when *Protect launcher settings and UI changes* applies. */
    private fun withSettingsCode(block: () -> Unit) {
        if (container.system.parental.needsCodeForSettings()) {
            parental = ParentalRequest(ParentalPromptMode.Verify, block)
        } else {
            block()
        }
    }

    private fun startClock() {
        lifecycleScope.launch {
            container.settings.settings.collect { updateClock() }
        }
        lifecycleScope.launch {
            while (true) {
                updateClock()
                val now = Calendar.getInstance()
                delay((60 - now.get(Calendar.SECOND)) * 1000L - now.get(Calendar.MILLISECOND) + 50)
            }
        }
    }

    private fun updateClock() {
        val s = container.settings.current
        clock.value = HomeSlots.formatClock(this, s[Keys.Clock.formatLine1], s[Keys.Clock.formatLine2], Date())
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        fun iconFor(caps: NetworkCapabilities?): String? {
            caps ?: return null
            val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "settings_ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> if (internet) "wifi" else "wifi_off"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "signal_cellular_alt"
                else -> "lan"
            }
        }
        network.value = iconFor(cm.getNetworkCapabilities(cm.activeNetwork))
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                network.value = iconFor(caps)
            }

            override fun onLost(n: Network) {
                network.value = null
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    override fun onDestroy() {
        networkCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        super.onDestroy()
    }

    private fun applySystemBars(show: Boolean) {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (show) {
            c.show(WindowInsetsCompat.Type.systemBars())
        } else {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        const val ACTION_SETTINGS = "org.cygnusx1.qlauncher.SETTINGS"
        const val ACTION_ALL_APPS = "org.cygnusx1.qlauncher.ALL_APPS"
        const val ACTION_NOTIFICATIONS = "org.cygnusx1.qlauncher.NOTIFICATIONS"
    }
}
