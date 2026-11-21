/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.apps.image

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.DisplayMetrics
import androidx.core.graphics.drawable.toBitmap
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.map.Mapper
import coil3.request.Options
import coil3.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cygnusx1.qlauncher.apps.AppKeys
import org.cygnusx1.qlauncher.apps.AppRepository
import org.cygnusx1.qlauncher.apps.ItemKind
import org.cygnusx1.qlauncher.apps.iconpack.IconPackRepository
import org.cygnusx1.qlauncher.apps.shortcuts.ShortcutRepository
import org.cygnusx1.qlauncher.channels.inputs.TvInputRepository
import org.cygnusx1.qlauncher.data.ItemOverrideStore

/**
 * The image of an item card, for Coil: `AsyncImage(model = AppImage(itemKey, AppImage.Kind.Banner))`.
 *
 * Works for `app:`, `shortcut:` and `input:` keys. Programs carry their own artwork URIs.
 */
data class AppImage(
    val itemKey: String,
    val kind: Kind = Kind.Banner,
    /**
     * The item's custom picture (`ItemOverride.iconUri`). Not read when loading, but part of the
     * model: a card asks Coil again only when its model changes, so without it a new or reset
     * icon stayed on screen until QLauncher restarted.
     */
    val customUri: String? = null,
) {
    enum class Kind {
        /** 16:9: the activity banner, else the app banner, else the icon. */
        Banner,

        /** 1:1: the icon. */
        Icon,
    }
}

/**
 * Which image an [AppImage] request gets. Everything but a banner (and usually a custom picture)
 * is an icon that the card draws on its card-coloured background.
 */
enum class AppImageSource {
    /** The user's own picture (`ItemOverride.iconUri`). */
    Custom,
    IconPack,
    ActivityBanner,
    AppBanner,
    Icon,
    ShortcutIcon,
    InputIcon,

    /** Nothing to show (unknown key, app gone): the card shows its placeholder. */
    None,
    ;

    val isBanner: Boolean get() = this == ActivityBanner || this == AppBanner
}

/** Resolves and loads item images; registered into the app's Coil image loader. */
class AppImages internal constructor(
    private val context: Context,
    private val apps: AppRepository,
    private val overrides: ItemOverrideStore,
    private val iconPacks: IconPackRepository,
    private val shortcuts: ShortcutRepository,
    private val inputs: TvInputRepository,
) {
    private val pm: PackageManager = context.packageManager

    /** Adds the [AppImage] mapper, keyer and fetcher to a Coil component registry. */
    fun register(builder: ComponentRegistry.Builder) {
        builder.add(CustomIconMapper(), AppImage::class)
        builder.add(AppImageKeyer(), AppImage::class)
        builder.add(AppImageFetcherFactory(), AppImage::class)
    }

    /** What [image] shows now, without loading it. */
    fun source(image: AppImage): AppImageSource {
        if (!overrides[image.itemKey]?.iconUri.isNullOrBlank()) return AppImageSource.Custom
        return when (AppKeys.kind(image.itemKey)) {
            ItemKind.App -> {
                val cn = AppKeys.component(image.itemKey) ?: return AppImageSource.None
                val app = apps[image.itemKey]
                when {
                    iconPacks.hasIconFor(cn.packageName, cn.className) -> AppImageSource.IconPack
                    app == null -> if (apps.isInstalled(cn.packageName)) AppImageSource.Icon else AppImageSource.None
                    image.kind == AppImage.Kind.Banner && app.hasActivityBanner -> AppImageSource.ActivityBanner
                    image.kind == AppImage.Kind.Banner && app.hasAppBanner -> AppImageSource.AppBanner
                    else -> AppImageSource.Icon
                }
            }
            ItemKind.Shortcut -> AppImageSource.ShortcutIcon
            ItemKind.Input -> AppImageSource.InputIcon
            else -> AppImageSource.None
        }
    }

    /** The memory-cache key: changes when the pack, the app version or the kind changes. */
    fun cacheKey(image: AppImage): String {
        val src = source(image)
        val extra = when (src) {
            AppImageSource.IconPack -> iconPacks.selected.value?.let { "${it.packPackage}@${it.versionCode}" }.orEmpty()
            AppImageSource.ShortcutIcon -> shortcuts.pinnedByKey.value[image.itemKey]?.iconPath.orEmpty()
            else -> apps[image.itemKey]?.lastUpdateTime?.toString().orEmpty()
        }
        return "qlauncher-item:${image.itemKey}#${image.kind}#$src#$extra"
    }

    /** Loads [image]. Blocking; call on a background thread. */
    fun load(image: AppImage): Pair<Drawable, AppImageSource>? = when (val src = source(image)) {
        AppImageSource.IconPack -> {
            val cn = AppKeys.component(image.itemKey)
            val d = cn?.let { iconPacks.iconFor(it.packageName, it.className) }
            if (d != null) d to src else loadApp(image)
        }
        AppImageSource.ActivityBanner, AppImageSource.AppBanner, AppImageSource.Icon -> loadApp(image)
        AppImageSource.ShortcutIcon -> shortcuts.iconDrawable(image.itemKey)?.let { it to src }
        AppImageSource.InputIcon -> inputs.icon(image.itemKey)?.let { it to src }
        AppImageSource.Custom, AppImageSource.None -> null
    }

    private fun loadApp(image: AppImage): Pair<Drawable, AppImageSource>? {
        val cn = AppKeys.component(image.itemKey) ?: return null
        val ai = activityInfo(cn) ?: return null
        if (image.kind == AppImage.Kind.Banner) {
            if (ai.banner != 0) highRes(ai.packageName, ai.banner)?.let { return it to AppImageSource.ActivityBanner }
            if (ai.applicationInfo.banner != 0) highRes(ai.packageName, ai.applicationInfo.banner)?.let { return it to AppImageSource.AppBanner }
        }
        val icon = ai.iconResource.takeIf { it != 0 }?.let { highRes(ai.packageName, it) }
            ?: runCatching { ai.loadIcon(pm) }.getOrNull()
            ?: return null
        return icon to AppImageSource.Icon
    }

    private fun activityInfo(cn: android.content.ComponentName): ActivityInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getActivityInfo(cn, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getActivityInfo(cn, 0)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** A resource drawable at a density high enough for large TV cards. */
    private fun highRes(pkg: String, resId: Int): Drawable? = runCatching {
        pm.getResourcesForApplication(pkg).getDrawableForDensity(resId, DisplayMetrics.DENSITY_XXXHIGH, null)
    }.getOrNull()

    private inner class CustomIconMapper : Mapper<AppImage, Uri> {
        override fun map(data: AppImage, options: Options): Uri? =
            overrides[data.itemKey]?.iconUri?.takeIf { it.isNotBlank() }?.toUri()
    }

    private inner class AppImageKeyer : Keyer<AppImage> {
        override fun key(data: AppImage, options: Options): String = cacheKey(data)
    }

    private inner class AppImageFetcher(private val data: AppImage) : Fetcher {
        override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
            val (drawable, _) = load(data) ?: throw IllegalStateException("No image for ${data.itemKey}")
            ImageFetchResult(rasterize(drawable).asImage(), isSampled = false, dataSource = DataSource.DISK)
        }
    }

    private inner class AppImageFetcherFactory : Fetcher.Factory<AppImage> {
        override fun create(data: AppImage, options: Options, imageLoader: ImageLoader): Fetcher = AppImageFetcher(data)
    }

    /** A bitmap copy, so Coil's memory cache can keep it; bounded to [MAX_SIDE] pixels. */
    private fun rasterize(d: Drawable): android.graphics.Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        var w = d.intrinsicWidth.takeIf { it > 0 } ?: DEFAULT_SIDE
        var h = d.intrinsicHeight.takeIf { it > 0 } ?: DEFAULT_SIDE
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))
        w = (w * scale).toInt().coerceAtLeast(1)
        h = (h * scale).toInt().coerceAtLeast(1)
        return d.toBitmap(w, h)
    }

    private companion object {
        const val MAX_SIDE = 640
        const val DEFAULT_SIDE = 256
    }
}
