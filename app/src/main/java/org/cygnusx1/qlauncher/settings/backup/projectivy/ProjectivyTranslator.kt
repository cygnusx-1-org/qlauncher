/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.cygnusx1.qlauncher.settings.backup.projectivy

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.cygnusx1.qlauncher.data.Category
import org.cygnusx1.qlauncher.data.CategoryIds
import org.cygnusx1.qlauncher.data.CategoryList
import org.cygnusx1.qlauncher.data.CategoryType
import org.cygnusx1.qlauncher.data.ItemKeys
import org.cygnusx1.qlauncher.data.ItemOverride
import org.cygnusx1.qlauncher.displayprofiles.DisplayProfile
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.SettingKey
import org.cygnusx1.qlauncher.settings.backup.BackupCodec
import org.cygnusx1.qlauncher.settings.backup.BackupKind
import org.cygnusx1.qlauncher.settings.backup.ImportOrigin
import org.cygnusx1.qlauncher.settings.backup.ImportPlan
import org.cygnusx1.qlauncher.settings.backup.NotCarried
import java.util.UUID

/** The launchable apps on this device, as Projectivy refers to them. */
interface InstalledApps {
    /** The item key (`app:<package>/<activity>`) of the installed package whose Projectivy id is [id]. */
    fun itemKeyForAppId(id: Int): String?

    /** The item key of [packageName]'s launchable activity, or null when it is not installed. */
    fun itemKeyForPackage(packageName: String): String?

    /** True when [packageName] is installed at all (for wallpaper plugins). */
    fun isInstalled(packageName: String): Boolean
}

/**
 * Translates a decoded Projectivy backup or theme (the JSON object [ProjectivyDecoder] returns)
 * into an [ImportPlan]: known keys through `Keys.byProjectivyKey`, the category list and
 * per-app customisations field for field, display profiles, and per-provider wallpaper
 * parameters. Anything that cannot carry over is listed, never silently dropped.
 */
class ProjectivyTranslator(private val apps: InstalledApps) {

    /**
     * @param isTheme true for the payload of a `.pltheme`: only appearance keys and the display
     *   settings of the built-in categories apply.
     * @param current the categories now in use; a theme restyles them.
     * @param currentSettingsVersion kept as is: Projectivy's app version is not QLauncher's.
     */
    fun translate(
        json: JsonObject,
        isTheme: Boolean,
        current: CategoryList,
        currentSettingsVersion: Int,
        themeName: String? = null,
        themeAuthor: String? = null,
    ): ImportPlan {
        val settings = LinkedHashMap<SettingKey<*>, Any>()
        val unknown = ArrayList<String>()
        val notCarried = LinkedHashSet<NotCarried>()
        val params = LinkedHashMap<String, String>()
        val skipped = ArrayList<Int>()
        var inputBindings = 0
        var profileBindings = 0
        var privateIcons = 0
        var writtenBy: String? = null

        var categoriesRaw: JsonElement? = null
        var defaultCategoriesRaw: JsonElement? = null
        var customizersRaw: JsonElement? = null
        var profilesRaw: JsonElement? = null
        var parentalAppsRaw: JsonElement? = null

        for ((key, value) in json) {
            when {
                key.contains("nexp") -> Unit // never exported by Projectivy either
                key == KEY_APP_VERSION -> writtenBy = (value as? JsonPrimitive)?.content
                key == KEY_CATEGORIES -> categoriesRaw = value
                key == KEY_DEFAULT_CATEGORIES -> defaultCategoriesRaw = value
                key == KEY_CUSTOMIZERS -> customizersRaw = value
                key == KEY_PROFILES -> profilesRaw = value
                key == KEY_PARENTAL_APPS -> parentalAppsRaw = value
                key in PARENTAL_CODE_KEYS -> if (!isEmptyValue(value)) notCarried += NotCarried.ParentalCode
                key.startsWith(KEY_PROFILE_BINDING_PREFIX) -> if (!isEmptyValue(value)) profileBindings++
                key.contains("license") || key.contains("premium") -> notCarried += NotCarried.PremiumState
                key.startsWith(KEY_PROVIDER_PARAMS) -> {
                    val id = key.removePrefix(KEY_PROVIDER_PARAMS)
                    val v = (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                    if (id.isNotEmpty() && v != null) params[id] = v
                }
                key in IGNORED_KEYS -> Unit
                key == KEY_AUTOSTART -> {
                    if (isTheme) continue
                    val raw = (value as? JsonPrimitive)?.content.orEmpty()
                    val source = raw.toIntOrNull()
                    when {
                        source != null -> {
                            // An input source number; 0 is "none".
                            if (source != 0) inputBindings++
                            settings[Keys.Power.autostartSource] = ""
                        }
                        raw.isEmpty() || raw == "screensaver" -> settings[Keys.Power.autostartSource] = raw
                        else -> {
                            val item = apps.itemKeyForPackage(raw)
                            val parsed = item?.let(ItemKeys::parseApp)
                            if (parsed != null) settings[Keys.Power.autostartSource] = "${parsed.first}/${parsed.second}"
                            else skipped += ProjectivyDecoder.appId(raw)
                        }
                    }
                }
                else -> {
                    val k = Keys.byProjectivyKey[key]
                    if (k == null) {
                        unknown += key
                        continue
                    }
                    if (!k.exported) continue
                    if (isTheme && !k.theme) continue
                    val v = BackupCodec.primitiveValue(value)?.let(k::coerce)
                    if (v == null) unknown += key else settings[k] = v
                }
            }
        }

        // A wallpaper plugin that is not installed: keep the current source.
        val pluginPackage = settings[Keys.Wallpaper.providerPackage] as? String
        if (!pluginPackage.isNullOrEmpty() && !apps.isInstalled(pluginPackage)) {
            notCarried += NotCarried.PluginNotInstalled(pluginPackage)
            settings.remove(Keys.Wallpaper.provider)
            settings.remove(Keys.Wallpaper.providerPackage)
        }

        // Projectivy's version is not QLauncher's settings version.
        if (!isTheme) settings[Keys.Internal.settingsVersion] = currentSettingsVersion
        themeName?.takeIf { it.isNotBlank() }?.let { settings[Keys.Internal.themeName] = it }
        themeAuthor?.takeIf { it.isNotBlank() }?.let { settings[Keys.Internal.themeAuthor] = it }

        // Categories.
        val plCategories = categoriesRaw?.let(::decodeCategories)
        val plDefaults = defaultCategoriesRaw?.let(::decodeCategories)
        val folderIds = (plCategories ?: current.categories.map { PlCategory(id = it.id) }).associate {
            ProjectivyDecoder.folderId(it.id) to ItemKeys.folder(it.id)
        }
        fun itemKey(id: Int): String? = apps.itemKeyForAppId(id) ?: folderIds[id]

        val categories: CategoryList? = when {
            isTheme && plDefaults != null -> restyle(current, plDefaults)
            !isTheme && plCategories != null -> {
                val mapped = plCategories.map { pc ->
                    val items = ArrayList<String>()
                    val hidden = ArrayList<String>()
                    for (id in pc.items) {
                        val k = itemKey(id)
                        when {
                            k != null -> items += k
                            pc.type == CategoryType.INPUTS -> inputBindings++
                            else -> skipped += id
                        }
                    }
                    for (id in pc.hiddenItems) itemKey(id)?.let { hidden += it }
                    var icon = pc.customIconUri.ifEmpty { null }
                    if (icon != null && isPrivate(icon)) {
                        privateIcons++
                        icon = null
                    }
                    pc.toCategory(items.distinct(), hidden.distinct(), icon)
                }
                CategoryList(
                    categories = mapped,
                    lastUserId = maxOf(CategoryIds.FIRST_USER - 1, mapped.filter { it.id in CategoryIds.FIRST_USER until CategoryIds.FIRST_CHANNEL }.maxOfOrNull { it.id } ?: 0),
                    lastChannelId = maxOf(CategoryIds.FIRST_CHANNEL - 1, mapped.filter { it.id >= CategoryIds.FIRST_CHANNEL }.maxOfOrNull { it.id } ?: 0),
                )
            }
            else -> null
        }

        // Per-app customisations and parental locks.
        val overrides: Map<String, ItemOverride>? = if (isTheme) null else {
            val result = LinkedHashMap<String, ItemOverride>()
            customizersRaw?.let(::decodeCustomizers)?.forEach { c ->
                val k = itemKey(c.actionId)
                if (k == null) {
                    skipped += c.actionId
                    return@forEach
                }
                var icon = c.customIconUri?.ifEmpty { null }
                if (icon != null && isPrivate(icon)) {
                    privateIcons++
                    icon = null
                }
                result[k] = ItemOverride(
                    title = c.customTitle?.ifEmpty { null },
                    iconUri = icon,
                    cropToFill = c.customIconCrop ?: false,
                    launchCount = c.launchCount,
                    lastLaunch = c.lastLaunchDate,
                )
            }
            parentalAppsRaw?.let(::decodeIdList)?.forEach { id ->
                val k = itemKey(id)
                if (k == null) skipped += id else result[k] = (result[k] ?: ItemOverride()).copy(parentalLocked = true)
            }
            result.filterValues { it != ItemOverride() }
        }

        val profiles = if (isTheme) null else profilesRaw?.let(::decodeProfiles)

        if (inputBindings > 0) notCarried += NotCarried.InputBindings(inputBindings)
        if (profileBindings > 0) notCarried += NotCarried.DisplayProfileBindings(profileBindings)
        if (privateIcons > 0) notCarried += NotCarried.PrivateIcons(privateIcons)

        return ImportPlan(
            origin = if (isTheme) ImportOrigin.ProjectivyTheme else ImportOrigin.ProjectivyBackup,
            kind = if (isTheme) BackupKind.Theme else BackupKind.Settings,
            settings = settings,
            clearOtherSettings = true,
            categories = categories,
            itemOverrides = overrides,
            profiles = profiles,
            wallpaperParams = params.takeIf { it.isNotEmpty() },
            themeName = themeName,
            themeAuthor = themeAuthor,
            writtenBy = writtenBy,
            unknownKeys = unknown,
            skippedItems = skipped.distinct(),
            notCarried = notCarried.toList(),
        )
    }

    /** A theme's built-in category settings applied to [current]: display fields and order, keeping items. */
    private fun restyle(current: CategoryList, theme: List<PlCategory>): CategoryList {
        val themed = theme.filter { it.id < CategoryIds.FIRST_USER }.distinctBy { it.id }
        val byId = themed.associateBy { it.id }
        val existingOrder = themed.map { it.id }.filter { id -> current.categories.any { it.id == id } }
        val result = current.categories.toMutableList()
        var k = 0
        for (i in result.indices) {
            if (result[i].id in byId) {
                val id = existingOrder[k++]
                val old = current.categories.first { it.id == id }
                result[i] = byId.getValue(id).restyle(old)
            }
        }
        val missing = themed.filter { t -> current.categories.none { it.id == t.id } }
        missing.forEach { result += it.toCategory(emptyList(), emptyList(), null) }
        return current.copy(categories = result)
    }

    private fun isPrivate(uri: String) = uri.contains(PROJECTIVY_PACKAGE)

    private fun isEmptyValue(e: JsonElement): Boolean = when (e) {
        is JsonNull -> true
        is JsonPrimitive -> e.content.isEmpty()
        is JsonArray -> e.isEmpty()
        is JsonObject -> e.isEmpty()
    }

    /** A JSON array, or a string holding one. */
    private fun asArray(e: JsonElement): JsonArray? = when (e) {
        is JsonArray -> e
        is JsonPrimitive -> if (e.isString && e.content.trimStart().startsWith("[")) {
            runCatching { json.parseToJsonElement(e.content) as? JsonArray }.getOrNull()
        } else null
        else -> null
    }

    private fun decodeCategories(e: JsonElement): List<PlCategory>? = asArray(e)?.let {
        runCatching { json.decodeFromJsonElement(ListSerializer(PlCategory.serializer()), it) }.getOrNull()
    }

    private fun decodeCustomizers(e: JsonElement): List<PlCustomizer> = asArray(e)?.let {
        runCatching { json.decodeFromJsonElement(ListSerializer(PlCustomizer.serializer()), it) }.getOrNull()
    }.orEmpty()

    private fun decodeIdList(e: JsonElement): List<Int> {
        asArray(e)?.let { arr -> return arr.mapNotNull { (it as? JsonPrimitive)?.let { p -> p.intOrNull ?: p.content.toIntOrNull() } } }
        val s = (e as? JsonPrimitive)?.content.orEmpty()
        return s.split(',', ';', ' ').mapNotNull { it.trim().toIntOrNull() }
    }

    /** `display_profiles_list` is a set of JSON strings, one per profile. */
    private fun decodeProfiles(e: JsonElement): List<DisplayProfile> {
        val arr = asArray(e) ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = when (item) {
                is JsonObject -> item
                is JsonPrimitive -> runCatching { json.parseToJsonElement(item.content) as? JsonObject }.getOrNull()
                else -> null
            } ?: return@mapNotNull null
            runCatching { json.decodeFromJsonElement(PlProfile.serializer(), obj) }.getOrNull()?.toProfile()
        }
    }

    /** Projectivy's category model, with its defaults. */
    @Serializable
    private data class PlCategory(
        @JsonNames("mId") val id: Int = 0,
        val type: Int = 0,
        val channelUniqueId: String = "",
        val scale: Float = 1f,
        val isVisible: Boolean = true,
        val isMultiline: Boolean = false,
        val maxItemsPerLine: Int = 0,
        val hasMovingSelection: Boolean = false,
        val isAppTitleHidden: Boolean = false,
        val orderType: Int = 0,
        val title: String = "",
        val items: List<Int> = emptyList(),
        val hiddenItems: List<Int> = emptyList(),
        val maxVisibleRows: Int = 0,
        val customIconUri: String = "",
        val startMargin: Int = 0,
        val endMargin: Int = 100,
        val topMargin: Int = 0,
        val backgroundColor: Int = 0,
    ) {
        fun toCategory(items: List<String>, hidden: List<String>, icon: String?) = Category(
            id = id,
            type = type,
            title = title.ifEmpty { null },
            customIconUri = icon,
            scale = scale,
            isVisible = isVisible,
            isMultiline = isMultiline,
            maxItemsPerLine = maxItemsPerLine,
            maxVisibleRows = maxVisibleRows,
            hasMovingSelection = hasMovingSelection,
            isAppTitleHidden = isAppTitleHidden,
            orderType = orderType,
            items = items,
            hiddenItems = hidden,
            channelUniqueId = channelUniqueId.ifEmpty { null },
            backgroundColor = backgroundColor.takeIf { it != 0 },
            startMargin = startMargin,
            // Projectivy stores 100 for "follow the global setting"; QLauncher's default is 0.
            endMargin = if (endMargin >= 100) 0 else endMargin,
            topMargin = topMargin,
        )

        /** The display fields of this theme entry on top of [old], keeping its items and title. */
        fun restyle(old: Category) = old.copy(
            scale = scale,
            isVisible = isVisible,
            isMultiline = isMultiline,
            maxItemsPerLine = maxItemsPerLine,
            maxVisibleRows = maxVisibleRows,
            hasMovingSelection = hasMovingSelection,
            isAppTitleHidden = isAppTitleHidden,
            orderType = orderType,
            backgroundColor = backgroundColor.takeIf { it != 0 },
            startMargin = startMargin,
            endMargin = if (endMargin >= 100) 0 else endMargin,
            topMargin = topMargin,
        )
    }

    /** Projectivy's per-item customiser. */
    @Serializable
    private data class PlCustomizer(
        val actionId: Int,
        val customIconUri: String? = null,
        val customIconCrop: Boolean? = null,
        val customTitle: String? = null,
        val launchCount: Int = 0,
        val lastLaunchDate: Long = 0,
    )

    /** Projectivy's display profile. */
    @Serializable
    private data class PlProfile(
        val profileName: String = "",
        val initValuesWithCurrentSettings: Boolean = false,
        val id: String = "",
        val brightness: Int = 50,
        val contrast: Int = 50,
        val saturation: Int = 50,
        val sharpness: Int = 50,
        val hue: Int = 50,
        val blueGain: Int = 1024,
        val blueOffset: Int = 1000,
        val greenGain: Int = 1024,
        val greenOffset: Int = 1000,
        val redGain: Int = 1024,
        val redOffset: Int = 1000,
        val isDynamicColor: Boolean = false,
        val isDynamicContrast: Boolean = false,
        val isMemc: Boolean = false,
        val isNoiseReduction: Boolean = false,
        val isDeinterlacing: Boolean = false,
        val zoom: Int = 100,
        val backlight: Int = 100,
        val backlightMode: Int = 0,
        val gammaMode: Int = 0,
    ) {
        fun toProfile() = DisplayProfile(
            id = id.ifEmpty { UUID.randomUUID().toString() },
            name = profileName.ifEmpty { "Profile" },
            initFromCurrent = initValuesWithCurrentSettings,
            brightness = brightness,
            contrast = contrast,
            saturation = saturation,
            sharpness = sharpness,
            hue = hue,
            redGain = redGain,
            greenGain = greenGain,
            blueGain = blueGain,
            redOffset = redOffset,
            greenOffset = greenOffset,
            blueOffset = blueOffset,
            dynamicColor = isDynamicColor,
            dynamicContrast = isDynamicContrast,
            memc = isMemc,
            noiseReduction = isNoiseReduction,
            deinterlacing = isDeinterlacing,
            zoom = zoom.coerceIn(DisplayProfile.ZOOM_MIN, DisplayProfile.ZOOM_MAX),
            backlight = backlight,
            backlightMode = backlightMode,
            gammaMode = gammaMode,
        )
    }

    companion object {
        /**
         * True for a theme payload: the built-in categories' display settings without a category
         * list. Projectivy's bundled presets are such `.plbackup` files.
         */
        fun looksLikeTheme(json: JsonObject): Boolean = KEY_DEFAULT_CATEGORIES in json && KEY_CATEGORIES !in json

        const val KEY_APP_VERSION = "key_app_version"
        const val KEY_CATEGORIES = "action_categories_list"
        const val KEY_DEFAULT_CATEGORIES = "action_default_categories_settings"
        const val KEY_CUSTOMIZERS = "action_customizers_list"
        const val KEY_PROFILES = "display_profiles_list"
        const val KEY_PARENTAL_APPS = "parental_control_apps"
        const val KEY_PROFILE_BINDING_PREFIX = "display_profiles_input_"
        const val KEY_PROVIDER_PARAMS = "appearance_wallpaper_provider_params_"
        const val KEY_AUTOSTART = "power_autostart_source"
        private const val PROJECTIVY_PACKAGE = "com.spocky.projengmenu"

        val PARENTAL_CODE_KEYS = setOf("parental_control_code", "parental_control_emr_code", "parental_control_emr_enc_code")

        /**
         * Stored by Projectivy but not carried: an action row's placeholder, and the reporting
         * switches (consent to Projectivy's services is not consent to QLauncher's).
         */
        val IGNORED_KEYS = setOf("appearance_statusbar_manage_notifications", "general_enable_analytics", "general_enable_crashlytics")

        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    }
}
