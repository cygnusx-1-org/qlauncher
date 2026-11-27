/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.system

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cygnusx1.qlauncher.AppContainer
import org.cygnusx1.qlauncher.parental.ParentalLaunchRequest
import org.cygnusx1.qlauncher.parental.ParentalManager
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.shared.NotificationSource
import org.cygnusx1.qlauncher.system.notifications.NotificationHub

/**
 * System services and parental control: the accessibility service's state, the notification
 * listener's hub, system broadcasts, parental control and small system helpers. One per process
 * (`container.system`).
 */
class SystemModule(private val container: AppContainer) {
    private val app: Context = container.app

    internal val eventBus = SystemEventBus()

    /** Boot, locale, USB and storage broadcasts (see [SystemEvent]). Not replayed. */
    val events: SharedFlow<SystemEvent> = eventBus.events

    val boot = BootTracker()

    /** Screensaver, default launcher, permission checks, settings pages, launching. */
    val actions = SystemActions(app)

    /** *Enabled* / *Started* rows and the foreground package. */
    val accessibility = AccessibilityStatus(app)

    private val hubLazy = lazy { NotificationHub(app, container.scope, actions) }

    /** The notification listener's hub; also available as [notifications]. */
    val notificationHub: NotificationHub by hubLazy

    /** Notifications and Now Playing, whether or not the listener is bound. */
    val notifications: NotificationSource get() = notificationHub

    val parental = ParentalManager(
        settings = container.settings,
        overrides = container.itemOverrides.overrides,
        updateOverride = container.itemOverrides::update,
        scope = container.scope,
    )

    val autostart = Autostart(app, container.settings, boot, actions)

    /** Display profiles stepped by UP/DOWN in the hotkey overlay; none until a backend exists. */
    @Volatile
    var displayProfiles: HotkeyTarget = HotkeyTarget.Unavailable

    /** TV inputs stepped by LEFT/RIGHT in the hotkey overlay; none until a backend exists. */
    @Volatile
    var inputs: HotkeyTarget = HotkeyTarget.Unavailable

    /**
     * *Show reminder* is on and the accessibility service is not enabled. Not while Home on Fire's
     * service is on: on a Fire TV that is what brings QLauncher up on Home, and Fire OS has no way
     * to switch on QLauncher's own.
     */
    val showAccessibilityReminder: StateFlow<Boolean> =
        combine(
            container.settings.flow(Keys.Service.reminder),
            accessibility.enabled,
            accessibility.homeOnFireEnabled,
        ) { reminder, enabled, homeOnFire -> reminder && !enabled && !homeOnFire }
            .stateIn(container.scope, SharingStarted.Eagerly, false)

    /** A launcher idle timer for the home screen (main thread, `SystemClock.uptimeMillis`). */
    fun newLauncherIdleTimer(): LauncherIdleTimer = LauncherIdleTimer(MonotonicClock.Uptime, HandlerScheduler())

    /** A gesture engine for the home screen's own keys (Back twice for the screensaver). */
    fun newKeyGestureEngine(): KeyGestureEngine = KeyGestureEngine(MonotonicClock.Uptime, HandlerScheduler())

    /**
     * Call from the home screen's `onResume`: re-reads the accessibility and notification-access
     * states (the user may come back from the settings pages), and runs the boot autostart itself
     * when the accessibility service is not running to do it.
     */
    fun onHomeResumed(activity: Activity) {
        accessibility.refresh()
        notificationHub.refreshAccess()
        if (accessibility.started.value) return
        val appContext = activity.applicationContext
        container.scope.launch {
            val claimed = withContext(Dispatchers.IO) { autostart.isBootPending() && autostart.claimBoot() }
            if (!claimed) return@launch
            delay(autostart.delayMillis())
            withContext(Dispatchers.Main) { autostart.launch(appContext) }
        }
    }

    /** After [ParentalPrompt] succeeded for a [ParentalLaunchRequest]: start the app again. */
    fun resumeAfterParentalCheck(request: ParentalLaunchRequest, caller: Context = app): Boolean =
        actions.launchPackage(request.packageName, caller)

    internal fun onLocaleChanged() {
        if (hubLazy.isInitialized()) notificationHub.clearLabelCache()
    }
}
