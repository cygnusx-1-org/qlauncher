/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cygnusx1.qlauncher.data.ItemOverrideStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** A package was added, removed, changed or replaced. */
data class PackageEvent(val action: String, val packageName: String?)

/**
 * The installed launchable activities, kept current with package broadcasts.
 *
 * Lists: [tvApps] (`MAIN` + `LEANBACK_LAUNCHER`), [mobileApps] (`MAIN` + `LAUNCHER`, packages
 * without a TV activity) and [apps] (TV then mobile). QLauncher itself is in none of them.
 *
 * The receiver is registered on the application context for the life of the process; the
 * module that owns this repository lives as long, so nothing needs unregistering.
 */
class AppRepository internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val overrides: ItemOverrideStore,
) {
    private val pm: PackageManager = context.packageManager
    private val labelCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private val appLabelCache = ConcurrentHashMap<String, String>()
    private val scanned = MutableStateFlow<AppListBuilder.Lists?>(null)
    private val refreshSignal = Channel<Unit>(Channel.CONFLATED)
    private val pendingEvents = ConcurrentLinkedQueue<PackageEvent>()
    private val scanLock = Mutex()
    private val _packageEvents = MutableSharedFlow<PackageEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every package change, after the lists were refreshed for it. */
    val packageEvents: SharedFlow<PackageEvent> = _packageEvents.asSharedFlow()

    /** False until the first scan has finished. */
    val loaded: StateFlow<Boolean> = scanned.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)

    private val titles = overrides.overrides
        .map { all -> all.mapNotNull { (k, v) -> v.title?.let { k to it } }.toMap() }
        .distinctUntilChanged()

    private val lists: StateFlow<AppListBuilder.Lists> = combine(scanned.filterNotNull(), titles) { l, t ->
        if (t.isEmpty()) l else AppListBuilder.Lists(l.tv.map { it.withTitle(t) }, l.mobile.map { it.withTitle(t) })
    }.stateIn(scope, SharingStarted.Eagerly, AppListBuilder.Lists(emptyList(), emptyList()))

    val tvApps: StateFlow<List<AppInfo>> = lists.map { it.tv }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val mobileApps: StateFlow<List<AppInfo>> = lists.map { it.mobile }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** TV apps, then mobile apps. */
    val apps: StateFlow<List<AppInfo>> = lists.map { it.all }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** [apps] by item key. */
    val byKey: StateFlow<Map<String, AppInfo>> = apps.map { l -> l.associateBy { it.key } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val action = intent.action ?: return
            when (action) {
                Intent.ACTION_LOCALE_CHANGED -> {
                    labelCache.clear()
                    appLabelCache.clear()
                    refresh()
                }
                Intent.ACTION_PACKAGE_REMOVED -> {
                    // An update sends REMOVED (replacing) then ADDED and REPLACED; wait for those.
                    if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) onPackageChanged(action, intent.data)
                }
                else -> onPackageChanged(action, intent.data)
            }
        }
    }

    init {
        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val other = IntentFilter().apply {
            addAction(Intent.ACTION_LOCALE_CHANGED)
            addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
            addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)
        }
        ContextCompat.registerReceiver(context, receiver, packages, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(context, receiver, other, ContextCompat.RECEIVER_NOT_EXPORTED)

        scope.launch(Dispatchers.IO) {
            rescan()
            for (signal in refreshSignal) {
                // Package updates arrive as bursts (REMOVED, ADDED, REPLACED); scan once per burst.
                delay(REFRESH_DEBOUNCE_MS)
                refreshSignal.tryReceive()
                rescan()
                while (true) _packageEvents.emit(pendingEvents.poll() ?: break)
            }
        }
    }

    private suspend fun rescan() {
        scanLock.withLock { scanned.value = scan() }
    }

    private fun AppInfo.withTitle(t: Map<String, String>) = t[key]?.let { copy(customTitle = it) } ?: this

    private fun onPackageChanged(action: String, data: Uri?) {
        val pkg = data?.schemeSpecificPart
        if (pkg != null) appLabelCache.remove(pkg)
        pendingEvents.add(PackageEvent(action, pkg))
        refresh()
    }

    /** Rescans in the background; bursts of calls are merged. */
    fun refresh() {
        refreshSignal.trySend(Unit)
    }

    /** Rescans now and returns when the lists are current. */
    suspend fun refreshNow() {
        withContext(Dispatchers.IO) { rescan() }
    }

    operator fun get(itemKey: String): AppInfo? = byKey.value[itemKey]

    fun isInstalled(packageName: String): Boolean = PmCompat.isInstalled(pm, packageName)

    /** The application label of [packageName], or the package name when it is not visible. */
    fun appLabel(packageName: String): String = appLabelCache.getOrPut(packageName) {
        PmCompat.applicationInfo(pm, packageName)?.loadLabel(pm)?.toString() ?: packageName
    }

    // --- Actions on apps ---------------------------------------------------------------------

    /** The intent that starts [app] as a launcher does. */
    fun launchIntent(app: AppInfo): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(if (app.isTv) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
        .setComponent(app.component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    /**
     * Starts the app behind [itemKey] (an `app:` key) and records the launch for the *Last used*
     * and *Frequently used* orders. Falls back to the package's own launch intent when the
     * activity is gone. Parental locks are the caller's business.
     */
    fun launch(context: Context, itemKey: String, options: Bundle? = null): Boolean {
        val app = get(itemKey)
        val intent = when {
            app != null -> launchIntent(app)
            else -> {
                val cn = AppKeys.component(itemKey) ?: return false
                Intent(Intent.ACTION_MAIN).setComponent(cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
        }
        var ok = PmCompat.start(context, intent, options)
        if (!ok) {
            val pkg = AppKeys.packageOf(itemKey) ?: return false
            val fallback = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            if (fallback != null) ok = PmCompat.start(context, fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
        }
        if (ok) overrides.recordLaunch(itemKey)
        return ok
    }

    /** The system's app-info page (*App settings* in the app menu). */
    fun openAppInfo(context: Context, packageName: String): Boolean = PmCompat.start(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    /** Asks the system to uninstall [packageName] (`ACTION_DELETE`, needs `REQUEST_DELETE_PACKAGES`). */
    fun uninstall(context: Context, packageName: String): Boolean = PmCompat.start(
        context,
        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    /**
     * The system settings, started directly in the system's own handler. Firing the generic
     * `android.settings.SETTINGS` intent can end in a chooser that never shows on TV when a second
     * app also answers it.
     */
    fun systemSettingsIntent(): Intent {
        val generic = Intent(Settings.ACTION_SETTINGS)
        val handlers = PmCompat.queryActivities(pm, generic).map {
            SettingsHandler(
                it.activityInfo.packageName,
                it.activityInfo.name,
                (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        }
        val pick = SettingsHandler.pick(handlers)
        val intent = if (pick != null) Intent(generic).setClassName(pick.packageName, pick.className) else generic
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun openSystemSettings(context: Context): Boolean = PmCompat.start(context, systemSettingsIntent())

    // --- Scanning ----------------------------------------------------------------------------

    private fun scan(): AppListBuilder.Lists {
        val packageTimes = HashMap<String, Pair<Long, Long>>()
        val tv = query(Intent.CATEGORY_LEANBACK_LAUNCHER, packageTimes)
        val mobile = query(Intent.CATEGORY_LAUNCHER, packageTimes)
        return AppListBuilder.build(tv, mobile, context.packageName)
    }

    private fun query(category: String, times: MutableMap<String, Pair<Long, Long>>): List<ScannedActivity> {
        val infos = PmCompat.queryActivities(pm, Intent(Intent.ACTION_MAIN).addCategory(category))
        return infos.mapNotNull { ri -> toScanned(ri, times) }
    }

    private fun toScanned(ri: ResolveInfo, times: MutableMap<String, Pair<Long, Long>>): ScannedActivity? {
        val ai = ri.activityInfo ?: return null
        if (!ai.enabled || !ai.applicationInfo.enabled) return null
        val pkg = ai.packageName
        val (installed, updated) = times.getOrPut(pkg) {
            PmCompat.packageInfo(pm, pkg)?.let { it.firstInstallTime to it.lastUpdateTime } ?: (0L to 0L)
        }
        val cacheKey = "$pkg/${ai.name}"
        val cached = labelCache[cacheKey]
        val label = if (cached != null && cached.first == updated) {
            cached.second
        } else {
            val l = runCatching { ri.loadLabel(pm).toString() }.getOrNull()?.trim().orEmpty().ifEmpty { pkg }
            labelCache[cacheKey] = updated to l
            l
        }
        return ScannedActivity(
            packageName = pkg,
            className = ai.name,
            label = label,
            isDefault = ri.isDefault,
            installTime = installed,
            lastUpdateTime = updated,
            hasActivityBanner = ai.banner != 0,
            hasAppBanner = ai.applicationInfo.banner != 0,
            isSystem = (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
        )
    }

    private companion object {
        const val REFRESH_DEBOUNCE_MS = 300L
    }
}

/** An activity answering `android.settings.SETTINGS`. */
data class SettingsHandler(val packageName: String, val className: String, val isSystem: Boolean) {
    companion object {
        private val PREFERRED = listOf("com.android.tv.settings", "com.google.android.tv.settings", "com.android.settings")

        /** The system's own handler: a preferred system package first, then any system app. */
        fun pick(handlers: List<SettingsHandler>): SettingsHandler? {
            val system = handlers.filter { it.isSystem }
            for (p in PREFERRED) system.firstOrNull { it.packageName == p }?.let { return it }
            return system.firstOrNull() ?: handlers.singleOrNull()
        }
    }
}
