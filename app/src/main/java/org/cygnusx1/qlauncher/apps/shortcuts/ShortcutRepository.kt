/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.apps.shortcuts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.graphics.drawable.toBitmap
import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.cygnusx1.qlauncher.apps.AppKeys
import org.cygnusx1.qlauncher.data.ItemKeys
import java.io.File
import java.security.MessageDigest

/** A shortcut another app pinned to QLauncher; it lives in the Shortcuts category. */
@Serializable
data class PinnedShortcut(
    val packageName: String,
    val shortcutId: String,
    val label: String,
    val longLabel: String? = null,
    /** Our PNG copy of the icon, taken when it was pinned. */
    val iconPath: String? = null,
    val addedAt: Long = 0L,
) {
    val key: String get() = ItemKeys.shortcut(packageName, shortcutId)
}

@Serializable
data class PinnedShortcuts(val items: List<PinnedShortcut> = emptyList())

/** One of an app's own launcher shortcuts (Android 7.1+), for the app menu's *Shortcuts* group. */
data class AppShortcut(
    val key: String,
    val packageName: String,
    val id: String,
    val shortLabel: String,
    val longLabel: String?,
    val enabled: Boolean,
    val rank: Int,
)

/**
 * Launcher shortcuts through [LauncherApps]. Reading other apps' shortcuts needs the shortcut
 * host permission, which Android grants only to the default home app; without it the lists are
 * empty rather than failing. Shortcuts exist from Android 7.1 and pin requests from Android 8;
 * before that every list is empty and nothing starts.
 */
class ShortcutRepository internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: DataStore<PinnedShortcuts>,
    packageEvents: SharedFlow<org.cygnusx1.qlauncher.apps.PackageEvent>,
) {
    private val launcherApps: LauncherApps? = context.getSystemService(LauncherApps::class.java)
    private val iconsDir = File(context.filesDir, "apps_shortcuts")

    /** Pinned shortcuts, oldest first. */
    val pinned: StateFlow<List<PinnedShortcut>> = store.data.map { it.items }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val pinnedByKey: StateFlow<Map<String, PinnedShortcut>> = pinned.map { l -> l.associateBy { it.key } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        scope.launch {
            packageEvents.collect { e ->
                if (e.action == Intent.ACTION_PACKAGE_REMOVED && e.packageName != null) {
                    removeWhere { it.packageName == e.packageName }
                } else {
                    reconcile()
                }
            }
        }
        scope.launch { reconcile() }
    }

    /** True while QLauncher may read other apps' shortcuts (it is the default home app). */
    fun hasHostPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 25) return false
        return try {
            launcherApps?.hasShortcutHostPermission() == true
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalStateException) {
            false
        }
    }

    /** [packageName]'s own dynamic and manifest shortcuts, by rank; empty when not allowed. */
    suspend fun appShortcuts(packageName: String): List<AppShortcut> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 25) readAppShortcuts(packageName) else emptyList()
    }

    @RequiresApi(25)
    private fun readAppShortcuts(packageName: String): List<AppShortcut> =
        query(packageName, LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
            .sortedWith(compareBy<ShortcutInfo>({ if (it.isDynamic) 1 else 0 }, { it.rank }))
            .map { it.toAppShortcut() }

    @RequiresApi(25)
    private fun ShortcutInfo.toAppShortcut() = AppShortcut(
        key = ItemKeys.shortcut(`package`, id),
        packageName = `package`,
        id = id,
        shortLabel = (shortLabel ?: longLabel ?: id).toString(),
        longLabel = longLabel?.toString(),
        enabled = isEnabled,
        rank = rank,
    )

    @RequiresApi(25)
    private fun query(packageName: String?, flags: Int, ids: List<String>? = null): List<ShortcutInfo> {
        val la = launcherApps ?: return emptyList()
        if (!hasHostPermission()) return emptyList()
        val q = LauncherApps.ShortcutQuery().setQueryFlags(flags)
        if (packageName != null) q.setPackage(packageName)
        if (ids != null) q.setShortcutIds(ids)
        return try {
            la.getShortcuts(q, Process.myUserHandle()).orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        } catch (e: IllegalStateException) {
            // The user is locked (direct boot) or the service is not ready.
            emptyList()
        }
    }

    /** Starts the shortcut behind [key] (a `shortcut:` item key). */
    fun start(context: Context, key: String, sourceBounds: Rect? = null, options: Bundle? = null): Boolean {
        val (pkg, id) = AppKeys.parseShortcut(key) ?: return false
        return start(context, pkg, id, sourceBounds, options)
    }

    fun start(context: Context, packageName: String, id: String, sourceBounds: Rect? = null, options: Bundle? = null): Boolean {
        if (Build.VERSION.SDK_INT < 25) return false
        val la = launcherApps ?: return false
        return try {
            la.startShortcut(packageName, id, sourceBounds, options, Process.myUserHandle())
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to start shortcut $packageName/$id", e)
            false
        } catch (e: IllegalStateException) {
            false
        }
    }

    /**
     * Accepts a pin request from `CONFIRM_PIN_SHORTCUT` and stores the shortcut. Returns the stored
     * shortcut, or null when the request was no longer valid.
     */
    @RequiresApi(26)
    suspend fun accept(request: LauncherApps.PinItemRequest): PinnedShortcut? {
        if (request.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) return null
        val info = request.shortcutInfo ?: return null
        val accepted = try {
            request.isValid && request.accept()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Pin request failed", e)
            false
        }
        if (!accepted) return null
        val icon = withContext(Dispatchers.IO) { iconOf(info)?.let { saveIcon(info.`package`, info.id, it) } }
        val item = PinnedShortcut(
            packageName = info.`package`,
            shortcutId = info.id,
            label = (info.shortLabel ?: info.longLabel ?: info.id).toString(),
            longLabel = info.longLabel?.toString(),
            iconPath = icon,
            addedAt = System.currentTimeMillis(),
        )
        store.updateData { all -> PinnedShortcuts(all.items.filterNot { it.key == item.key } + item) }
        return item
    }

    /** The drawable a pin request shows in its confirmation. */
    @RequiresApi(25)
    fun iconOf(info: ShortcutInfo): Drawable? = try {
        launcherApps?.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi)
    } catch (e: RuntimeException) {
        null
    }

    /** Removes [key] from the Shortcuts category and unpins it. */
    fun unpin(key: String) {
        scope.launch {
            val removed = pinned.value.firstOrNull { it.key == key } ?: return@launch
            val keep = pinned.value.filter { it.packageName == removed.packageName && it.key != key }.map { it.shortcutId }
            removeWhere { it.key == key }
            withContext(Dispatchers.IO) {
                removed.iconPath?.let { File(it).delete() }
                if (Build.VERSION.SDK_INT >= 25) repin(removed.packageName, keep)
            }
        }
    }

    /** Leaves exactly [ids] of [packageName]'s shortcuts pinned for QLauncher. */
    @RequiresApi(25)
    private fun repin(packageName: String, ids: List<String>) {
        val la = launcherApps ?: return
        if (!hasHostPermission()) return
        runCatching { la.pinShortcuts(packageName, ids, Process.myUserHandle()) }
    }

    /** The icon of a pinned or app shortcut. Call off the main thread. */
    fun iconDrawable(key: String): Drawable? {
        pinnedByKey.value[key]?.iconPath?.let { path ->
            android.graphics.drawable.Drawable.createFromPath(path)?.let { return it }
        }
        if (Build.VERSION.SDK_INT < 25) return null
        val (pkg, id) = AppKeys.parseShortcut(key) ?: return null
        val info = query(
            pkg,
            LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
            listOf(id),
        ).firstOrNull() ?: return null
        return iconOf(info)
    }

    /**
     * Brings the stored list in line with what Android has pinned for us: drops shortcuts the
     * app removed or disabled, and adopts pinned ones we do not know (after a data restore).
     * Does nothing without the host permission.
     */
    suspend fun reconcile() {
        if (Build.VERSION.SDK_INT >= 25) withContext(Dispatchers.IO) { reconcilePinned() }
    }

    @RequiresApi(25)
    private suspend fun reconcilePinned() {
        if (!hasHostPermission()) return
        val live = query(null, LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        val liveKeys = live.filter { it.isEnabled }.associateBy { ItemKeys.shortcut(it.`package`, it.id) }
        store.updateData { all ->
            val kept = all.items.filter { it.key in liveKeys }
            val known = kept.mapTo(HashSet()) { it.key }
            val adopted = liveKeys.filterKeys { it !in known }.values.map { info ->
                PinnedShortcut(
                    packageName = info.`package`,
                    shortcutId = info.id,
                    label = (info.shortLabel ?: info.longLabel ?: info.id).toString(),
                    longLabel = info.longLabel?.toString(),
                    iconPath = iconOf(info)?.let { saveIcon(info.`package`, info.id, it) },
                    addedAt = System.currentTimeMillis(),
                )
            }
            if (adopted.isEmpty() && kept.size == all.items.size) all else PinnedShortcuts(kept + adopted)
        }
    }

    private suspend fun removeWhere(predicate: (PinnedShortcut) -> Boolean) {
        store.updateData { all ->
            val left = all.items.filterNot(predicate)
            if (left.size == all.items.size) all else PinnedShortcuts(left)
        }
    }

    private fun saveIcon(pkg: String, id: String, d: Drawable): String? = runCatching {
        iconsDir.mkdirs()
        val name = MessageDigest.getInstance("SHA-1").digest("$pkg/$id".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(20)
        val file = File(iconsDir, "$name.png")
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: 192
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: 192
        file.outputStream().use { d.toBitmap(w, h).compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.absolutePath
    }.getOrNull()

    private companion object {
        const val TAG = "QLShortcuts"
    }
}
