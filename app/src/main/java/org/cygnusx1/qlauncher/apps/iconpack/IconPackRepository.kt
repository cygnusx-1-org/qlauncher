/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.apps.iconpack

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import android.util.Xml
import androidx.core.net.toUri
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.IntentCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cygnusx1.qlauncher.apps.PackageEvent
import org.cygnusx1.qlauncher.apps.PmCompat
import org.cygnusx1.qlauncher.data.ItemOverrideStore
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.SettingsRepository
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** An installed icon pack. */
data class IconPack(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val versionName: String?,
    /** The pack has a Leanback launcher activity (it was made with TV in mind). */
    val hasLeanbackActivity: Boolean,
)

/**
 * Icon packs: discovery, `appfilter.xml` maps (cached in the cache directory per pack and
 * version), drawables, and single icons picked with the pack's own picker.
 *
 * The selected pack is `Keys.Cards.iconPack` (a package name, empty for none).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IconPackRepository internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val overrides: ItemOverrideStore,
    packageEvents: SharedFlow<PackageEvent>,
) {
    private val pm: PackageManager = context.packageManager
    private val cacheDir = File(context.cacheDir, "apps_iconpacks")
    private val iconsDir = File(context.filesDir, "icons")
    private val resources = ConcurrentHashMap<String, Resources>()
    private val filters = ConcurrentHashMap<String, AppFilter>()
    private val loadLock = Mutex()
    private val _packs = MutableStateFlow<List<IconPack>>(emptyList())

    /** Installed icon packs, by label. */
    val packs: StateFlow<List<IconPack>> = _packs.asStateFlow()

    /** The selected pack's map, or null for none (or while it loads, or when it is gone). */
    val selected: StateFlow<AppFilter?> = combine(settings.flow(Keys.Cards.iconPack), _packs) { pkg, packs ->
        packs.firstOrNull { it.packageName == pkg }
    }.distinctUntilChanged()
        .mapLatest { pack -> pack?.let { load(it) } }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch(Dispatchers.IO) { discover() }
        scope.launch {
            packageEvents.collect { e ->
                e.packageName?.let { resources.remove(it) }
                withContext(Dispatchers.IO) { discover() }
            }
        }
    }

    fun refresh() {
        scope.launch(Dispatchers.IO) { discover() }
    }

    fun find(packageName: String): IconPack? = _packs.value.firstOrNull { it.packageName == packageName }

    /** Selects [packageName] (empty for none) as *Cards › Icon pack*. */
    fun select(packageName: String) = settings.update(Keys.Cards.iconPack, packageName)

    /** The selected pack's drawable for an activity, or null. Call off the main thread. */
    fun iconFor(packageName: String, className: String): Drawable? {
        val filter = selected.value ?: return null
        val name = filter.drawableFor(packageName, className) ?: return null
        return drawable(filter.packPackage, name)
    }

    /** True when the selected pack has an icon for this activity (or its package). */
    fun hasIconFor(packageName: String, className: String): Boolean =
        selected.value?.drawableFor(packageName, className) != null

    /** A drawable of [packPackage] by name, from `drawable` or else `mipmap`. */
    fun drawable(packPackage: String, name: String): Drawable? {
        val res = resourcesOf(packPackage) ?: return null
        @Suppress("DiscouragedApi")
        var id = res.getIdentifier(name, "drawable", packPackage)
        @Suppress("DiscouragedApi")
        if (id == 0) id = res.getIdentifier(name, "mipmap", packPackage)
        if (id == 0) return null
        return try {
            ResourcesCompat.getDrawable(res, id, null)
        } catch (e: Resources.NotFoundException) {
            null
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot load $name from $packPackage", e)
            null
        }
    }

    /** The parsed map of [pack], from the cache when it is current. */
    suspend fun load(pack: IconPack): AppFilter? = withContext(Dispatchers.IO) {
        loadLock.withLock {
            val key = "${pack.packageName}@${pack.versionCode}"
            filters[key]?.let { return@withLock it }
            val cacheFile = File(cacheDir, "${pack.packageName}@${pack.versionCode}.tsv")
            val fromCache = if (cacheFile.isFile) {
                runCatching { AppFilter.deserialize(pack.packageName, pack.versionCode, cacheFile.readText()) }.getOrNull()
            } else {
                null
            }
            val filter = fromCache ?: parse(pack)?.also { writeCache(pack, cacheFile, it) }
            filter?.also { filters[key] = it }
        }
    }

    private fun writeCache(pack: IconPack, file: File, filter: AppFilter) {
        runCatching {
            cacheDir.mkdirs()
            cacheDir.listFiles { f -> f.name.startsWith("${pack.packageName}@") }?.forEach { it.delete() }
            val tmp = File(cacheDir, file.name + ".tmp")
            tmp.writeText(filter.serialize())
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "Cannot cache ${pack.packageName}", it) }
    }

    private fun parse(pack: IconPack): AppFilter? {
        val builder = AppFilterParser.Builder()
        val res = resourcesOf(pack.packageName) ?: return null
        @Suppress("DiscouragedApi")
        val id = res.getIdentifier("appfilter", "xml", pack.packageName)
        if (id != 0) {
            runCatching {
                val parser = res.getXml(id)
                try {
                    AppFilterParser.parse(parser, builder)
                } finally {
                    parser.close()
                }
            }.onFailure { Log.w(TAG, "Bad appfilter resource in ${pack.packageName}", it) }
        }
        if (builder.isEmpty) {
            runCatching {
                context.createPackageContext(pack.packageName, 0).assets.open("appfilter.xml").use { stream ->
                    val parser = Xml.newPullParser()
                    parser.setInput(stream, null)
                    AppFilterParser.parse(parser, builder)
                }
            }
        }
        return builder.build(pack.packageName, pack.versionCode)
    }

    private fun resourcesOf(pkg: String): Resources? = resources[pkg] ?: try {
        pm.getResourcesForApplication(pkg).also { resources[pkg] = it }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun discover() {
        val found = PmCompat.queryActivities(pm, Intent(ACTION_PICK_ICON))
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .mapNotNull { pkg -> describe(pkg) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        _packs.value = found
    }

    private fun describe(pkg: String): IconPack? {
        val res = resourcesOf(pkg) ?: return null
        @Suppress("DiscouragedApi")
        val hasFilter = res.getIdentifier("appfilter", "xml", pkg) != 0
        @Suppress("DiscouragedApi")
        val boolId = res.getIdentifier("config_iconpack", "bool", pkg)
        val flagged = boolId != 0 && runCatching { res.getBoolean(boolId) }.getOrDefault(false)
        val inAssets = !hasFilter && !flagged && runCatching {
            context.createPackageContext(pkg, 0).assets.list("")?.contains("appfilter.xml") == true
        }.getOrDefault(false)
        if (!hasFilter && !flagged && !inAssets) return null
        val info = PmCompat.packageInfo(pm, pkg) ?: return null
        val label = info.applicationInfo?.loadLabel(pm)?.toString() ?: pkg
        return IconPack(
            packageName = pkg,
            label = label,
            versionCode = PmCompat.versionCode(info),
            versionName = info.versionName,
            hasLeanbackActivity = pm.getLeanbackLaunchIntentForPackage(pkg) != null,
        )
    }

    // --- A single icon from a pack's picker ----------------------------------------------------

    /** Stores the icon a pack's picker returned as the custom icon of [itemKey]. */
    suspend fun savePickedIcon(itemKey: String, result: Intent?): Boolean = withContext(Dispatchers.IO) {
        val bitmap = result?.let(::bitmapFrom) ?: return@withContext false
        val uri = storeIcon(itemKey, bitmap) ?: return@withContext false
        overrides.update(itemKey) { it.copy(iconUri = uri) }
        true
    }

    /** Removes [itemKey]'s custom icon (the menu's *Reset*), deleting our copy of it. */
    fun resetIcon(itemKey: String) {
        val current = overrides[itemKey]?.iconUri
        overrides.update(itemKey) { it.copy(iconUri = null) }
        if (current != null) scope.launch(Dispatchers.IO) { deleteOwnIcon(current) }
    }

    // The ADW picker protocol still returns the legacy shortcut-icon extra.
    @Suppress("DEPRECATION")
    private fun bitmapFrom(data: Intent): Bitmap? {
        IntentCompat.getParcelableExtra(data, "icon", Bitmap::class.java)?.let { return it }
        val res = IntentCompat.getParcelableExtra(data, Intent.EXTRA_SHORTCUT_ICON_RESOURCE, Intent.ShortcutIconResource::class.java)
            ?: return null
        val r = resourcesOf(res.packageName) ?: return null
        @Suppress("DiscouragedApi")
        val id = r.getIdentifier(res.resourceName, null, null)
        if (id == 0) return null
        val d = runCatching { ResourcesCompat.getDrawable(r, id, null) }.getOrNull() ?: return null
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: 256
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: 256
        return d.toBitmap(w, h)
    }

    private fun storeIcon(itemKey: String, bitmap: Bitmap): String? = runCatching {
        iconsDir.mkdirs()
        val name = "app_${sha1(itemKey).take(16)}_${System.currentTimeMillis()}.png"
        val file = File(iconsDir, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        overrides[itemKey]?.iconUri?.let(::deleteOwnIcon)
        Uri.fromFile(file).toString()
    }.getOrNull()

    private fun deleteOwnIcon(uri: String) {
        val path = uri.toUri().takeIf { it.scheme == "file" }?.path ?: return
        val file = File(path)
        if (file.parentFile?.canonicalPath == iconsDir.canonicalPath && file.name.startsWith("app_")) file.delete()
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        /** The ADW icon-pack action: packs answer it, and it starts a pack's own icon picker. */
        const val ACTION_PICK_ICON = "org.adw.launcher.icons.ACTION_PICK_ICON"
        private const val TAG = "QLIconPacks"
    }
}

/**
 * Starts an icon pack's own picker (input: the pack's package) and returns its result intent,
 * or null when cancelled. Hand the result to [IconPackRepository.savePickedIcon].
 */
class PickIconFromPack : ActivityResultContract<String, Intent?>() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent(IconPackRepository.ACTION_PICK_ICON).setPackage(input)

    override fun parseResult(resultCode: Int, intent: Intent?): Intent? =
        intent.takeIf { resultCode == Activity.RESULT_OK }
}
