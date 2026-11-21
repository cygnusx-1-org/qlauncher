/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.util.LruCache
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cygnusx1.qlauncher.container
import org.cygnusx1.qlauncher.parental.ParentalLaunchRequest
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.Settings
import org.cygnusx1.qlauncher.system.overlay.HotkeyHelpOverlay
import org.cygnusx1.qlauncher.system.overlay.HotkeyHelpState
import org.cygnusx1.qlauncher.system.overlay.IdleCountdownOverlay
import org.cygnusx1.qlauncher.system.overlay.IdleOutcome
import org.cygnusx1.qlauncher.system.overlay.OverlayWindow

/**
 * QLauncher's accessibility service. It does only what its consent text says: it sees hardware
 * key presses and the name of the foreground app, keeps both in memory, and sends nothing
 * anywhere. Each feature is gated by its setting:
 *
 * - *Override current launcher* ([Keys.General.overrideHome]): when a stock launcher's HOME
 *   activity comes to the front, QLauncher is started instead. With *Enable button remapping*
 *   the HOME key is taken too: one press opens QLauncher, two open Recents.
 * - *Display info on "Menu" double press* ([Keys.Remote.menuDoublePress]): Menu twice shows the
 *   hotkey help overlay; while it shows, UP/DOWN step display profiles and LEFT/RIGHT inputs.
 * - *Volume buttons fix* ([Keys.Service.volumeFix]): a volume key (or the volume panel appearing)
 *   suspends key filtering for [VOLUME_SUSPEND_MILLIS] so the system sees long presses.
 * - *Enable internal idle detection* ([Keys.Power.idleDetection]): see [InternalIdleDetector].
 * - *Prevent system automatic shutdown when idle* ([Keys.Power.simulateUserActivity]): see
 *   [simulateUserActivity].
 * - Parental control: a locked app reaching the foreground by any route brings up the code
 *   prompt in `MainActivity` (extras in [ParentalLaunchRequest]).
 * - Autostart: when the service connects shortly after boot, and on screen-on with
 *   *…and upon resuming from standby* (see [Autostart]).
 */
class QLAccessibilityService : AccessibilityService() {
    private val system: SystemModule get() = applicationContext.container.system

    private var scope: CoroutineScope = MainScope()
    private val clock = MonotonicClock.Uptime
    private val scheduler = HandlerScheduler()
    private val gestures = KeyGestureEngine(clock, scheduler)
    private val idle = InternalIdleDetector(clock, scheduler, IdleListener())

    private var config = ServiceConfig.from(Settings.DEFAULTS)
    private var connected = false

    private lateinit var helpWindow: OverlayWindow
    private lateinit var idleWindow: OverlayWindow
    private var helpHide: Cancellable? = null
    private var helpOkDown = false
    private var helpBackDown = false

    /** Keys whose down cancelled the idle countdown: their repeats and up are swallowed too. */
    private val swallowedKeys = HashSet<Int>()

    private var filterSuspended = false
    private var filterResume: Cancellable? = null

    /** The system apps' HOME activities, which the launcher override replaces. */
    private var stockHomes: Set<ComponentName> = emptySet()
    private val activityCache = LruCache<String, Boolean>(256)
    private var lastForeground: String? = null

    private var screenOn = true
    private var resumeLaunch: Job? = null

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            activityCache.evictAll()
            refreshStockHomes()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    resumeLaunch?.cancel()
                    idle.pause()
                    hideHelp()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    idle.resume()
                    onScreenOn()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        scope = MainScope()
        helpWindow = OverlayWindow(this)
        idleWindow = OverlayWindow(this)
        system.accessibility.onServiceStarted()
        idle.shouldDefer = ::mediaIsPlaying

        val settings = applicationContext.container.settings
        apply(ServiceConfig.from(settings.current), force = true)
        scope.launch {
            settings.settings.map { ServiceConfig.from(it) }.distinctUntilChanged().collect { apply(it, force = false) }
        }

        refreshStockHomes()
        ContextCompat.registerReceiver(
            this, packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        autostartAfterBoot()
        scope.launch {
            while (isActive) {
                delay(USER_ACTIVITY_PERIOD_MILLIS)
                if (config.simulateUserActivity && screenOn) simulateUserActivity()
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutDown()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    private fun shutDown() {
        if (!connected) return
        connected = false
        scope.cancel()
        gestures.reset()
        idle.stop()
        helpHide?.cancel()
        filterResume?.cancel()
        helpWindow.hide()
        idleWindow.hide()
        runCatching { unregisterReceiver(packageReceiver) }
        runCatching { unregisterReceiver(screenReceiver) }
        system.accessibility.onServiceStopped()
    }

    // --- Settings ---

    private fun apply(new: ServiceConfig, force: Boolean) {
        val old = config
        config = new
        if (force || old.remapping != new.remapping || old.alternateConfig != new.alternateConfig) applyServiceInfo()
        if (force || old.bindingsKey != new.bindingsKey) gestures.setBindings(bindings(new))
        idle.configure(new.idleDetection, new.idleTimeoutMillis)
        if (!new.menuDoublePress) hideHelp()
    }

    /**
     * Event types and flags at run time. *Enable button remapping* is the key filter itself;
     * *Use alternate config* reports generic instead of haptic feedback, which some devices need
     * to keep the service running (Projectivy's two switches do the same).
     */
    private fun applyServiceInfo() {
        val info = serviceInfo ?: return
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_VIEW_FOCUSED
        info.feedbackType = if (config.alternateConfig) AccessibilityServiceInfo.FEEDBACK_GENERIC else AccessibilityServiceInfo.FEEDBACK_HAPTIC
        var flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        if (keyFilterActive()) flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        info.flags = flags
        info.notificationTimeout = 0L
        serviceInfo = info
    }

    private fun keyFilterActive(): Boolean = config.remapping && !filterSuspended

    private fun bindings(c: ServiceConfig): Map<Int, KeyGestureEngine.Binding> = buildMap {
        if (!c.remapping) return@buildMap
        if (c.overrideHome) {
            put(
                KeyEvent.KEYCODE_HOME,
                KeyGestureEngine.Binding(
                    onSingle = { openLauncher() },
                    onDouble = { performGlobalAction(GLOBAL_ACTION_RECENTS) },
                ),
            )
        }
        if (c.menuDoublePress) {
            // Menu keeps working in apps: only the second press of a pair is taken.
            put(KeyEvent.KEYCODE_MENU, KeyGestureEngine.Binding(onSingle = null, onDouble = { toggleHelp() }))
        }
    }

    // --- Keys ---

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val down = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return false
        }
        val code = event.keyCode
        if (idle.onActivity() && down) {
            // "Press any button to cancel": that press does nothing else.
            swallowedKeys += code
            return true
        }
        if (code in swallowedKeys) {
            if (!down) swallowedKeys -= code
            return true
        }
        if (code in VOLUME_KEYS) {
            if (down && config.volumeFix) suspendKeyFilter()
            return false
        }
        if (helpWindow.isShowing && onHelpKey(code, down, event.repeatCount)) return true
        return gestures.onKey(code, down, event.repeatCount)
    }

    /**
     * The volume fix: while the service filters keys, every key goes through it first, which
     * breaks the system's own long-press handling of the volume keys. Filtering is dropped for a
     * few seconds after a volume key and then restored.
     */
    private fun suspendKeyFilter() {
        if (!config.remapping) return
        filterResume?.cancel()
        filterResume = scheduler.schedule(VOLUME_SUSPEND_MILLIS) {
            filterResume = null
            filterSuspended = false
            applyServiceInfo()
        }
        if (filterSuspended) return
        filterSuspended = true
        gestures.reset()
        swallowedKeys.clear()
        applyServiceInfo()
    }

    // --- Hotkey help overlay ---

    private fun toggleHelp() {
        if (helpWindow.isShowing) hideHelp() else showHelp()
    }

    private fun showHelp() {
        helpOkDown = false
        helpBackDown = false
        renderHelp()
        scheduleHelpHide()
    }

    private fun renderHelp() {
        val profiles = system.displayProfiles
        val inputs = system.inputs
        val state = HotkeyHelpState(
            inputsAvailable = inputs.available,
            inputLabel = inputs.currentLabel(),
            profilesAvailable = profiles.available,
            profileLabel = profiles.currentLabel(),
            homeOverridden = config.remapping && config.overrideHome,
        )
        val colors = applicationContext.container.theme.colors.value
        helpWindow.show { HotkeyHelpOverlay(state, colors) }
    }

    private fun scheduleHelpHide() {
        helpHide?.cancel()
        helpHide = scheduler.schedule(HELP_VISIBLE_MILLIS) { hideHelp() }
    }

    private fun hideHelp() {
        helpHide?.cancel()
        helpHide = null
        if (::helpWindow.isInitialized) helpWindow.hide()
    }

    /** Keys while the help shows. Returns true when the key was the overlay's. */
    private fun onHelpKey(code: Int, down: Boolean, repeat: Int): Boolean {
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down && repeat == 0) {
                    system.displayProfiles.step(if (code == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                    renderHelp()
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down && repeat == 0) {
                    system.inputs.step(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                    renderHelp()
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down) {
                    helpOkDown = true
                } else if (helpOkDown) {
                    hideHelp()
                    openLauncher()
                }
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (down) helpBackDown = true else if (helpBackDown) hideHelp()
            }
            else -> return false
        }
        if (helpWindow.isShowing) scheduleHelpHide()
        return true
    }

    // --- Windows ---

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowStateChanged(event)
            // Without the key filter, focus moves are the best sign of someone using the remote.
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> if (!keyFilterActive()) idle.onActivity()
        }
    }

    private fun onWindowStateChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString()
        if (cls != null && config.volumeFix && cls.contains(".volume", ignoreCase = true)) suspendKeyFilter()
        if (!keyFilterActive()) idle.onActivity()
        if (cls == null || !isActivity(pkg, cls)) return

        val previous = lastForeground
        lastForeground = pkg
        system.accessibility.onForegroundPackage(pkg)
        if (pkg == packageName) return

        if (config.overrideHome && ComponentName(pkg, cls) in stockHomes) {
            openLauncher()
            return
        }
        if (pkg != previous && system.parental.needsCodeForPackage(pkg)) {
            // Opens the prompt in MainActivity, which starts the app again on success.
            system.actions.start(ParentalLaunchRequest(pkg, cls).toIntent(this), this)
        }
    }

    /** Whether a window event names an activity (dialogs, toasts and panels are ignored). */
    private fun isActivity(pkg: String, cls: String): Boolean {
        val key = "$pkg/$cls"
        activityCache.get(key)?.let { return it }
        val result = try {
            @Suppress("DEPRECATION")
            packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        activityCache.put(key, result)
        return result
    }

    /**
     * The stock launchers are found by resolving the HOME intent, never by name: every system
     * app's HOME activity except QLauncher's. Third-party HOME apps (other launchers, launcher
     * pickers) are left alone so the user can still open them.
     */
    private fun refreshStockHomes() {
        scope.launch {
            stockHomes = withContext(Dispatchers.IO) {
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(home, 0)
                    .mapNotNull { it.activityInfo }
                    .filter { it.packageName != packageName && it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
                    .mapTo(HashSet()) { ComponentName(it.packageName, it.name) }
            }
        }
    }

    private fun openLauncher() {
        system.actions.openLauncher(this)
    }

    // --- Idle detection ---

    private fun mediaIsPlaying(): Boolean {
        if (!config.idleExcludesMedia) return false
        val hub = system.notificationHub
        return hub.accessGranted.value && hub.nowPlaying.value?.isPlaying == true
    }

    private inner class IdleListener : InternalIdleDetector.Listener {
        override fun onCountdown(secondsLeft: Int) {
            val outcome = when (config.idleAction) {
                1 -> IdleOutcome.ScreenOff
                2 -> IdleOutcome.Screensaver
                else -> IdleOutcome.Shutdown
            }
            val colors = applicationContext.container.theme.colors.value
            idleWindow.show { IdleCountdownOverlay(outcome, secondsLeft, colors) }
        }

        override fun onCountdownCancelled() {
            if (::idleWindow.isInitialized) idleWindow.hide()
        }

        override fun onIdleAction() {
            idleWindow.hide()
            when (config.idleAction) {
                1 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                2 -> system.actions.startScreensaver()
                else -> powerOff()
            }
        }
    }

    /** *Shutdown action*: the power dialog once, twice, or followed by OK (API 33+). */
    private fun powerOff() {
        performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        when (config.poweroffMode) {
            1 -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            2 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Give the dialog time to take focus, so OK lands on it and not on the app below.
                scheduler.schedule(POWER_DIALOG_OK_DELAY_MILLIS) { performGlobalAction(GLOBAL_ACTION_DPAD_CENTER) }
            }
        }
    }

    // --- Preventing the system's own idle shutdown ---

    /**
     * Counts as user activity for the power manager, once a minute.
     *
     * What an accessibility service can do here, and why this is the choice:
     * - Asking a window for an accessibility action makes the system call
     *   `PowerManager.userActivity(…, USER_ACTIVITY_EVENT_ACCESSIBILITY)` first, whether or not
     *   the action succeeds (`AbstractAccessibilityServiceConnection.performAccessibilityAction`,
     *   unchanged from Android 9 to 14). User activity restarts the screen-off timeout, the sleep
     *   timeout and the attentive timeout that TV builds use for "turn off after N hours of no
     *   input". The action used is `ACTION_COPY` on the root of the active window: a root never
     *   has a selection, so nothing is copied and nothing on screen changes, and the window is
     *   already the focused one. This is the mechanism that works on both Android 11 and 14.
     * - Wake locks and `FLAG_KEEP_SCREEN_ON` keep the screen on but do not count as input, so
     *   the attentive timeout still shuts the device down; that timeout exists to override them.
     * - Global actions all do something visible; gestures need another capability, which the
     *   consent text does not cover, and a tap would land on whatever is on screen.
     *
     * The node calls block on the target app, so they run off the main thread.
     */
    private fun simulateUserActivity() {
        scope.launch(Dispatchers.IO) {
            try {
                val root = rootInActiveWindow ?: windows.firstNotNullOfOrNull { it.root }
                if (root == null) {
                    Log.i(TAG, "No window to report user activity through")
                    return@launch
                }
                root.performAction(AccessibilityNodeInfo.ACTION_COPY)
            } catch (e: RuntimeException) {
                Log.w(TAG, "User activity report failed", e)
            }
        }
    }

    // --- Autostart ---

    private fun autostartAfterBoot() {
        val auto = system.autostart
        scope.launch {
            val claimed = withContext(Dispatchers.IO) { auto.isBootPending() && auto.claimBoot() }
            if (!claimed) return@launch
            delay(auto.delayMillis())
            auto.launch(this@QLAccessibilityService)
        }
    }

    private fun onScreenOn() {
        val auto = system.autostart
        if (!auto.onResumeEnabled() || system.boot.isShortlyAfterBoot() || auto.source() == null) return
        resumeLaunch?.cancel()
        resumeLaunch = scope.launch {
            delay(auto.delayMillis())
            if (screenOn) auto.launch(this@QLAccessibilityService)
        }
    }

    /** The settings the service acts on, compared as a whole to skip unrelated changes. */
    private data class ServiceConfig(
        val overrideHome: Boolean,
        val remapping: Boolean,
        val volumeFix: Boolean,
        val alternateConfig: Boolean,
        val menuDoublePress: Boolean,
        val simulateUserActivity: Boolean,
        val idleDetection: Boolean,
        val idleTimeoutMillis: Long,
        val idleAction: Int,
        val idleExcludesMedia: Boolean,
        val poweroffMode: Int,
    ) {
        val bindingsKey: Triple<Boolean, Boolean, Boolean> get() = Triple(remapping, overrideHome, menuDoublePress)

        companion object {
            fun from(s: Settings) = ServiceConfig(
                overrideHome = s[Keys.General.overrideHome],
                remapping = s[Keys.Service.buttonRemapping],
                volumeFix = s[Keys.Service.volumeFix],
                alternateConfig = s[Keys.Service.alternateConfig],
                menuDoublePress = s[Keys.Remote.menuDoublePress],
                simulateUserActivity = s[Keys.Power.simulateUserActivity],
                idleDetection = s[Keys.Power.idleDetection],
                idleTimeoutMillis = s[Keys.Power.idleTimeout],
                // *Screen off* needs the lock-screen action (Android 9); before that it is the screensaver.
                idleAction = s[Keys.Power.idleAction].let { if (it == 1 && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) 2 else it },
                idleExcludesMedia = s[Keys.Power.idleExcludesMedia],
                poweroffMode = s[Keys.Power.poweroffMode],
            )
        }
    }

    private companion object {
        const val TAG = "QLAccessibility"
        const val VOLUME_SUSPEND_MILLIS = 5_000L
        const val HELP_VISIBLE_MILLIS = 5_000L
        const val USER_ACTIVITY_PERIOD_MILLIS = 60_000L
        const val POWER_DIALOG_OK_DELAY_MILLIS = 700L
        val VOLUME_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)
    }
}
