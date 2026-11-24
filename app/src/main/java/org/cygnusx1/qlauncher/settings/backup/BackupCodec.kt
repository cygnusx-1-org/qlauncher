/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.cygnusx1.qlauncher.data.CategoryList
import org.cygnusx1.qlauncher.data.ItemOverride
import org.cygnusx1.qlauncher.data.ItemOverrides
import org.cygnusx1.qlauncher.displayprofiles.DisplayProfile
import org.cygnusx1.qlauncher.displayprofiles.DisplayProfileList
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.SettingKey

/**
 * The data of a QLauncher backup, apart from its files. A theme has no categories, app
 * customisations, profiles or Reddit login (null).
 *
 * @property settings setting name → Boolean, Int, Long, Float or String.
 * @property redditLogin null when logged out, and in files that predate it.
 */
data class BackupData(
    val manifest: BackupManifest,
    val settings: Map<String, Any>,
    val categories: CategoryList? = null,
    val apps: Map<String, ItemOverride>? = null,
    val profiles: List<DisplayProfile>? = null,
    val wallpaperParams: Map<String, String>? = null,
    val redditLogin: RedditLogin? = null,
)

/** Settings read from a file: those QLauncher knows, and the names it does not. */
data class DecodedSettings(val values: Map<SettingKey<*>, Any>, val unknown: List<String>)

/**
 * Turns [BackupData] into the JSON entries of an archive and back. Paths inside the app's files
 * folder are stored relative to a placeholder, so icons and wallpapers still resolve after a
 * restore on another device.
 *
 * @param filesDirPaths the absolute paths that name `filesDir` (the real one and its aliases).
 */
class BackupCodec(private val filesDirPaths: List<String> = emptyList()) {
    private val json = BackupFormat.json
    private val paramsSerializer = MapSerializer(String.serializer(), String.serializer())

    /** Entry name → content. */
    fun encode(data: BackupData): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        out[BackupFormat.MANIFEST] = json.encodeToString(BackupManifest.serializer(), data.manifest).encodeToByteArray()
        out[BackupFormat.SETTINGS] = encodeTree(settingsToJson(data.settings))
        data.categories?.let {
            out[BackupFormat.CATEGORIES] = encodeTree(json.encodeToJsonElement(CategoryList.serializer(), it))
        }
        data.apps?.let {
            out[BackupFormat.APPS] = encodeTree(json.encodeToJsonElement(ItemOverrides.serializer(), ItemOverrides(it)))
        }
        data.profiles?.let {
            out[BackupFormat.PROFILES] = encodeTree(json.encodeToJsonElement(DisplayProfileList.serializer(), DisplayProfileList(it)))
        }
        data.wallpaperParams?.let {
            out[BackupFormat.WALLPAPER_PARAMS] = encodeTree(json.encodeToJsonElement(paramsSerializer, it))
        }
        // Written as is: a token holds no paths for portable() to rewrite.
        data.redditLogin?.let {
            out[BackupFormat.REDDIT_LOGIN] = json.encodeToString(RedditLogin.serializer(), it).encodeToByteArray()
        }
        return out
    }

    /**
     * Reads the entries back. [entry] returns an entry's bytes, or null when it is absent.
     *
     * @throws ImportException.NotABackup without a manifest.
     * @throws ImportException.TooNew for a newer format.
     * @throws ImportException.Damaged for malformed JSON.
     */
    fun decode(entry: (String) -> ByteArray?): BackupData {
        val manifest = readManifest(entry(BackupFormat.MANIFEST))
        try {
            val settingsObj = entry(BackupFormat.SETTINGS)?.let { restoreTree(parse(it)) as? JsonObject } ?: JsonObject(emptyMap())
            val categories = entry(BackupFormat.CATEGORIES)?.let { json.decodeFromJsonElement(CategoryList.serializer(), restoreTree(parse(it))) }
            val apps = entry(BackupFormat.APPS)?.let { json.decodeFromJsonElement(ItemOverrides.serializer(), restoreTree(parse(it))).items }
            val profiles = entry(BackupFormat.PROFILES)?.let { json.decodeFromJsonElement(DisplayProfileList.serializer(), restoreTree(parse(it))).profiles }
            val params = entry(BackupFormat.WALLPAPER_PARAMS)?.let { json.decodeFromJsonElement(paramsSerializer, restoreTree(parse(it))) }
            val login = entry(BackupFormat.REDDIT_LOGIN)
                ?.let { json.decodeFromString(RedditLogin.serializer(), it.decodeToString()) }
                ?.takeIf { it.refreshToken.isNotBlank() }
            return BackupData(manifest, settingsFromJsonRaw(settingsObj), categories, apps, profiles, params, login)
        } catch (e: SerializationException) {
            throw ImportException.Damaged(e)
        } catch (e: IllegalArgumentException) {
            throw ImportException.Damaged(e)
        }
    }

    fun readManifest(bytes: ByteArray?): BackupManifest {
        bytes ?: throw ImportException.NotABackup()
        val m = try {
            json.decodeFromString(BackupManifest.serializer(), bytes.decodeToString())
        } catch (e: SerializationException) {
            throw ImportException.NotABackup(e)
        } catch (e: IllegalArgumentException) {
            throw ImportException.NotABackup(e)
        }
        if (m.format != BackupFormat.FORMAT_ID || m.formatVersion < 1) throw ImportException.NotABackup()
        if (m.formatVersion > BackupFormat.FORMAT_VERSION) throw ImportException.TooNew(m.formatVersion)
        return m
    }

    private fun parse(bytes: ByteArray): JsonElement = json.parseToJsonElement(bytes.decodeToString())

    private fun encodeTree(e: JsonElement): ByteArray = json.encodeToString(JsonElement.serializer(), portable(e)).encodeToByteArray()

    /** Replaces the files folder in every string with the placeholder. */
    fun portable(e: JsonElement): JsonElement = mapStrings(e) { s -> filesDirPaths.fold(s) { acc, p -> if (p.isEmpty()) acc else acc.replace(p, BackupFormat.FILES_PLACEHOLDER) } }

    /** Replaces the placeholder with this device's files folder. */
    fun restoreTree(e: JsonElement): JsonElement {
        val target = filesDirPaths.firstOrNull() ?: return e
        return mapStrings(e) { it.replace(BackupFormat.FILES_PLACEHOLDER, target) }
    }

    private fun mapStrings(e: JsonElement, f: (String) -> String): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { (_, v) -> mapStrings(v, f) })
        is JsonArray -> JsonArray(e.map { mapStrings(it, f) })
        is JsonNull -> e
        is JsonPrimitive -> if (e.isString) JsonPrimitive(f(e.content)) else e
    }

    companion object {
        /** Setting values as a JSON object. */
        fun settingsToJson(values: Map<String, Any>): JsonObject = JsonObject(
            values.mapValues { (_, v) ->
                when (v) {
                    is Boolean -> JsonPrimitive(v)
                    is Number -> JsonPrimitive(v)
                    else -> JsonPrimitive(v.toString())
                }
            },
        )

        /** A JSON primitive as Boolean, Long, Double or String. */
        fun primitiveValue(e: JsonElement): Any? {
            if (e !is JsonPrimitive || e is JsonNull) return null
            if (e.isString) return e.content
            return e.booleanOrNull ?: e.longOrNull ?: e.doubleOrNull
        }

        private fun settingsFromJsonRaw(obj: JsonObject): Map<String, Any> =
            obj.mapNotNull { (k, v) -> primitiveValue(v)?.let { k to it } }.toMap()

        /**
         * Matches stored names against the registry. Unknown names, and values that cannot be
         * used, are listed; keys that are never exported are ignored.
         */
        fun decodeSettings(values: Map<String, Any>): DecodedSettings {
            val known = LinkedHashMap<SettingKey<*>, Any>()
            val unknown = ArrayList<String>()
            for ((name, raw) in values) {
                val key = Keys.byName[name]
                if (key == null) {
                    unknown += name
                    continue
                }
                if (!key.exported) continue
                val v = key.coerce(raw)
                if (v == null) unknown += name else known[key] = v
            }
            return DecodedSettings(known, unknown)
        }
    }
}
