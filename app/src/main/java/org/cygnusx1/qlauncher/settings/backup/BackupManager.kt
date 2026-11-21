/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.cygnusx1.qlauncher.BuildConfig
import org.cygnusx1.qlauncher.data.CategoryList
import org.cygnusx1.qlauncher.data.CategoryStore
import org.cygnusx1.qlauncher.data.ItemOverride
import org.cygnusx1.qlauncher.data.ItemOverrideStore
import org.cygnusx1.qlauncher.displayprofiles.DisplayProfile
import org.cygnusx1.qlauncher.displayprofiles.DisplayProfileStore
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.SettingKey
import org.cygnusx1.qlauncher.settings.Settings
import org.cygnusx1.qlauncher.settings.SettingsRepository
import org.cygnusx1.qlauncher.settings.WallpaperProviders
import org.cygnusx1.qlauncher.settings.backup.autobackup.AutoBackupEntry
import org.cygnusx1.qlauncher.settings.backup.autobackup.AutoBackupReason
import org.cygnusx1.qlauncher.settings.backup.autobackup.AutoBackupStore
import org.cygnusx1.qlauncher.settings.backup.autobackup.autoBackupTriggers
import org.cygnusx1.qlauncher.settings.backup.projectivy.InstalledApps
import org.cygnusx1.qlauncher.settings.backup.projectivy.ProjectivyDecoder
import org.cygnusx1.qlauncher.settings.backup.projectivy.ProjectivyTranslator
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The per-provider wallpaper parameters, which the wallpaper subsystem owns. Register an
 * implementation with [BackupModule.registerWallpaperParams] so exports carry them and imports
 * restore them.
 */
interface WallpaperParamsBridge {
    /** Provider id → its stored parameter string. */
    suspend fun exportParams(): Map<String, String>

    /** Replaces every provider's parameters with [params] (an empty map clears them). */
    suspend fun importParams(params: Map<String, String>)
}

/**
 * The folders the backup code works in.
 *
 * @property exportDir where exports are written first (the app's external `files/backups/`).
 */
class BackupPaths(
    val filesDir: File,
    val cacheDir: File,
    val packageName: String,
    val exportDir: () -> File,
)

/** A file picked for import, copied into the cache and recognised. Close it when done. */
class ImportSession internal constructor(
    val displayName: String?,
    internal val file: File,
    internal val type: Type,
    internal val stagingDir: File,
) {
    enum class Type { QLauncher, ProjectivyBackup, ProjectivyTheme }

    /** True for a QLauncher file: ask for its password. */
    val needsPassword: Boolean get() = type == Type.QLauncher

    val isProjectivy: Boolean get() = type != Type.QLauncher

    /** Deletes the cached copy and anything staged that was not applied. */
    fun close() {
        (file.parentFile ?: file).deleteRecursively()
    }
}

/**
 * Export, import, automatic backup and reset, over the settings, category, item and profile
 * stores. Every function that touches files or stores is a suspend function that does its work
 * off the main thread.
 */
class BackupManager(
    private val paths: BackupPaths,
    private val settings: SettingsRepository,
    private val categories: CategoryStore,
    private val itemOverrides: ItemOverrideStore,
    private val profiles: DisplayProfileStore,
    val autoBackups: AutoBackupStore,
    private val scope: CoroutineScope,
    private val wallpaperParams: () -> WallpaperParamsBridge?,
    private val installedApps: () -> InstalledApps,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val filesDir: File = paths.filesDir
    private val codec = BackupCodec(filesDirAliases(filesDir, paths.packageName))
    private val applyMutex = Mutex()

    /** Where exports are written first: the app's external `files/backups/`, else internal. */
    val exportDir: File get() = paths.exportDir()

    internal val stagingRoot: File get() = File(paths.cacheDir, "import-staging")

    // ---------------------------------------------------------------------------------------
    // Export

    /** The data an export of [kind] holds right now. */
    suspend fun snapshot(kind: BackupKind): BackupData {
        val s = settings.current
        val manifest = BackupManifest(
            appVersion = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            createdAt = clock(),
            kind = kind,
            name = s[Keys.Internal.themeName].takeIf { kind == BackupKind.Theme && it.isNotEmpty() },
            author = s[Keys.Internal.themeAuthor].takeIf { kind == BackupKind.Theme && it.isNotEmpty() },
        )
        val params = runCatching { wallpaperParams()?.exportParams() }.getOrNull()
        val values = settings.exportValues(themeOnly = kind == BackupKind.Theme)
        return if (kind == BackupKind.Theme) {
            BackupData(manifest, values, wallpaperParams = params)
        } else {
            BackupData(manifest, values, categories.state.value, itemOverrides.overrides.value, profiles.profiles.value, params)
        }
    }

    /**
     * Writes an encrypted export to [exportDir] and returns the file.
     *
     * @throws IllegalArgumentException when the password is shorter than the minimum; nothing is
     *   written then.
     */
    suspend fun export(kind: BackupKind, password: CharArray): File {
        require(password.size >= BackupFormat.MIN_PASSWORD_LENGTH) { "password too short" }
        val data = snapshot(kind)
        return withContext(Dispatchers.IO) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(data.manifest.createdAt))
            val prefix = if (kind == BackupKind.Theme) "qlauncher-theme" else "qlauncher-settings"
            val target = File(exportDir, "$prefix-$stamp.${kind.extension}")
            QlArchive.write(target, password, codec.encode(data), exportAssets(kind))
            target
        }
    }

    private fun exportAssets(kind: BackupKind): List<ArchiveFile> {
        val theme = File(filesDir, THEME_DIR)
        val background = File(filesDir, BACKGROUND)
        if (kind == BackupKind.Settings) {
            return QlArchive.filesUnder(File(filesDir, ICONS_DIR), BackupFormat.ASSET_ICONS) +
                QlArchive.filesUnder(background, if (background.isFile) BackupFormat.ASSET_BACKGROUND else BackupFormat.ASSET_BACKGROUND + "/") +
                QlArchive.filesUnder(theme, BackupFormat.ASSET_THEME)
        }
        // A theme carries the wallpaper being used, when it is a local file QLauncher holds.
        val provider = settings.current[Keys.Wallpaper.provider]
        return when {
            provider == WallpaperProviders.THEME -> QlArchive.filesUnder(File(theme, WALLPAPERS), BackupFormat.ASSET_WALLPAPERS)
            provider == WallpaperProviders.MEDIA_FILE && background.isFile ->
                listOf(ArchiveFile(BackupFormat.ASSET_WALLPAPERS + "background" + MediaSniffer.extension(background), background))
            provider == WallpaperProviders.MEDIA_FILE && background.isDirectory ->
                QlArchive.filesUnder(background, BackupFormat.ASSET_WALLPAPERS)
            else -> emptyList()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Import

    /** Opens a file already on disk (a copy is made; the original is left alone). */
    suspend fun openFile(file: File): ImportSession = withContext(Dispatchers.IO) {
        val dir = File(stagingRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        val copy = File(dir, "incoming")
        file.copyTo(copy, overwrite = true)
        openStaged(file.name, copy, dir)
    }

    /** Removes what imports interrupted more than an hour ago left in the cache. */
    fun cleanStaging() {
        scope.launch(Dispatchers.IO) {
            val cutoff = clock() - 3_600_000L
            stagingRoot.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        }
    }

    /** Recognises a copy that already sits in a staging folder of its own. */
    internal fun openStaged(name: String?, file: File, dir: File): ImportSession = try {
        ImportSession(name, file, recognise(file), File(dir, "staged"))
    } catch (e: ImportException) {
        dir.deleteRecursively()
        throw e
    }

    private fun recognise(file: File): ImportSession.Type {
        if (QlArchive.looksLikeZip(file)) {
            val names = try {
                QlArchive.Reader(file).use { it.entryNames }
            } catch (e: ImportException) {
                throw ImportException.NotABackup(e)
            }
            return when {
                BackupFormat.MANIFEST in names -> ImportSession.Type.QLauncher
                ProjectivyDecoder.THEME_PAYLOAD in names -> ImportSession.Type.ProjectivyTheme
                else -> throw ImportException.NotABackup()
            }
        }
        val bytes = file.readBytes()
        return try {
            ProjectivyDecoder.decodeBackup(bytes)
            ImportSession.Type.ProjectivyBackup
        } catch (e: ProjectivyDecoder.FormatException) {
            throw ImportException.NotABackup(e)
        }
    }

    /**
     * Decodes [session] into a plan, staging its files. Nothing is applied yet.
     *
     * @param password required for QLauncher files.
     * @throws ImportException.WrongPassword, [ImportException.TooNew], [ImportException.Damaged].
     */
    suspend fun plan(session: ImportSession, password: CharArray?): ImportPlan = withContext(Dispatchers.IO) {
        session.stagingDir.deleteRecursively()
        session.stagingDir.mkdirs()
        when (session.type) {
            ImportSession.Type.QLauncher -> planQLauncher(session, password ?: CharArray(0))
            ImportSession.Type.ProjectivyBackup -> planProjectivy(session, theme = null)
            ImportSession.Type.ProjectivyTheme -> {
                val theme = try {
                    session.file.inputStream().use { ProjectivyDecoder.readTheme(it, File(session.stagingDir, "theme/$WALLPAPERS")) }
                } catch (e: ProjectivyDecoder.FormatException) {
                    throw ImportException.Damaged(e)
                }
                planProjectivy(session, theme)
            }
        }
    }

    private fun planQLauncher(session: ImportSession, password: CharArray): ImportPlan {
        QlArchive.Reader(session.file).use { reader ->
            if (reader.isEncrypted) {
                if (password.isEmpty()) throw ImportException.WrongPassword()
                reader.setPassword(password)
            }
            val data = codec.decode { reader.read(it) }
            val staged = ArrayList<StagedAsset>()
            val kind = data.manifest.kind
            var themeWallpapers = false
            if (kind == BackupKind.Settings) {
                fun stage(prefix: String, name: String, destination: String) {
                    val target = File(session.stagingDir, name)
                    if (reader.extract(prefix, target).isNotEmpty()) staged += StagedAsset(target, destination)
                }
                stage(BackupFormat.ASSET_ICONS, "icons", ICONS_DIR)
                stage(BackupFormat.ASSET_BACKGROUND, "background", BACKGROUND)
                stage(BackupFormat.ASSET_THEME, "theme", THEME_DIR)
            } else {
                val themeDir = File(session.stagingDir, "theme")
                themeWallpapers = reader.extract(BackupFormat.ASSET_WALLPAPERS, File(themeDir, WALLPAPERS)).isNotEmpty()
                themeDir.mkdirs()
                File(themeDir, BackupFormat.MANIFEST).writeBytes(reader.read(BackupFormat.MANIFEST) ?: ByteArray(0))
                staged += StagedAsset(themeDir, THEME_DIR)
            }
            val origin = if (kind == BackupKind.Theme) ImportOrigin.QLauncherTheme else ImportOrigin.QLauncherBackup
            return planFromData(data, origin, staged, themeWallpapers)
        }
    }

    private fun planFromData(data: BackupData, origin: ImportOrigin, staged: List<StagedAsset>, themeWallpapers: Boolean): ImportPlan {
        val decoded = BackupCodec.decodeSettings(data.settings)
        val isTheme = data.manifest.kind == BackupKind.Theme
        val values = LinkedHashMap<SettingKey<*>, Any>()
        decoded.values.forEach { (k, v) -> if (!isTheme || k.theme) values[k] = v }
        if (isTheme) {
            data.manifest.name?.let { values[Keys.Internal.themeName] = it }
            data.manifest.author?.let { values[Keys.Internal.themeAuthor] = it }
        }
        return ImportPlan(
            origin = origin,
            kind = data.manifest.kind,
            settings = values,
            clearOtherSettings = true,
            categories = if (isTheme) null else data.categories,
            itemOverrides = if (isTheme) null else data.apps,
            profiles = if (isTheme) null else data.profiles,
            wallpaperParams = data.wallpaperParams,
            assets = staged,
            hasThemeWallpapers = themeWallpapers,
            themeName = data.manifest.name,
            themeAuthor = data.manifest.author,
            writtenBy = data.manifest.appVersion.ifEmpty { null },
            createdAt = data.manifest.createdAt.takeIf { it > 0 },
            unknownKeys = decoded.unknown,
        )
    }

    private fun planProjectivy(session: ImportSession, theme: ProjectivyDecoder.Theme?): ImportPlan {
        val text = theme?.settingsJson ?: try {
            ProjectivyDecoder.decodeBackup(session.file.readBytes())
        } catch (e: ProjectivyDecoder.FormatException) {
            throw ImportException.Damaged(e)
        }
        val json = try {
            PROJECTIVY_JSON.parseToJsonElement(text) as? JsonObject ?: throw ImportException.Damaged()
        } catch (e: SerializationException) {
            throw ImportException.Damaged(e)
        }
        val isTheme = theme != null || ProjectivyTranslator.looksLikeTheme(json)
        val plan = ProjectivyTranslator(installedApps()).translate(
            json = json,
            isTheme = isTheme,
            current = categories.state.value,
            currentSettingsVersion = settings.current[Keys.Internal.settingsVersion],
            themeName = theme?.manifest?.name,
            themeAuthor = theme?.manifest?.author,
        )
        if (!isTheme) return plan
        // Theme: its wallpapers (if any) and a manifest go to themes/current.
        val themeDir = File(session.stagingDir, "theme").apply { mkdirs() }
        val manifest = BackupManifest(
            appVersion = theme?.manifest?.projectivyLauncherVersion?.toString().orEmpty(),
            createdAt = clock(),
            kind = BackupKind.Theme,
            name = theme?.manifest?.name?.ifEmpty { null },
            author = theme?.manifest?.author?.ifEmpty { null },
        )
        File(themeDir, BackupFormat.MANIFEST).writeText(BackupFormat.json.encodeToString(BackupManifest.serializer(), manifest))
        val hasWallpapers = theme?.wallpapers?.isNotEmpty() == true
        return plan.copy(assets = listOf(StagedAsset(themeDir, THEME_DIR)), hasThemeWallpapers = hasWallpapers)
    }

    /** A plan from an automatic backup; no password, no files. */
    suspend fun plan(entry: AutoBackupEntry): ImportPlan {
        val content = autoBackups.read(entry)
        val data = withContext(Dispatchers.Default) { codec.decode { content[it] } }
        return planFromData(data, ImportOrigin.AutomaticBackup, emptyList(), themeWallpapers = false)
            .copy(createdAt = entry.createdAt)
    }

    /**
     * Applies [plan] completely or not at all: an automatic backup is written first, then every
     * store and file is replaced; if any step fails, everything is put back as it was and the
     * error is rethrown. Restart the launcher afterwards ([LauncherRestart]).
     */
    suspend fun apply(plan: ImportPlan) = applyMutex.withLock {
        writeAutoBackup(AutoBackupReason.BeforeImport)
        val before = Rollback(
            settings = exportedValues(settings.current, themeOnly = false),
            themeName = settings.current[Keys.Internal.themeName],
            themeAuthor = settings.current[Keys.Internal.themeAuthor],
            categories = categories.state.value,
            overrides = itemOverrides.overrides.value,
            profiles = profiles.profiles.value,
            params = runCatching { wallpaperParams()?.exportParams() }.getOrNull(),
        )
        val swapped = ArrayList<Pair<File, File?>>() // destination → its previous copy
        try {
            withContext(Dispatchers.IO) {
                for (asset in plan.assets) swapped += install(asset)
            }
            val values = LinkedHashMap(plan.settings)
            if (plan.hasThemeWallpapers) {
                values[Keys.Wallpaper.provider] = WallpaperProviders.THEME
                values[Keys.Wallpaper.providerPackage] = ""
            }
            settings.importValues(values, clearOthers = plan.clearOtherSettings, themeOnly = plan.kind == BackupKind.Theme)
            plan.categories?.let { categories.replaceAll(it) }
            plan.itemOverrides?.let { itemOverrides.replaceAll(it) }
            plan.profiles?.let { profiles.replaceAll(it) }
            plan.wallpaperParams?.let { p -> wallpaperParams()?.importParams(p) }
        } catch (e: Throwable) {
            withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { restore(before) }
                withContext(Dispatchers.IO) {
                    for ((dest, old) in swapped.asReversed()) {
                        dest.deleteRecursively()
                        old?.renameTo(dest)
                    }
                }
            }
            throw e
        }
        withContext(Dispatchers.IO) { swapped.forEach { (_, old) -> old?.deleteRecursively() } }
    }

    private class Rollback(
        val settings: Map<String, Any>,
        val themeName: String,
        val themeAuthor: String,
        val categories: CategoryList,
        val overrides: Map<String, ItemOverride>,
        val profiles: List<DisplayProfile>,
        val params: Map<String, String>?,
    )

    private suspend fun restore(r: Rollback) {
        val values = LinkedHashMap<SettingKey<*>, Any>()
        r.settings.forEach { (name, v) -> Keys.byName[name]?.let { values[it] = v } }
        values[Keys.Internal.themeName] = r.themeName
        values[Keys.Internal.themeAuthor] = r.themeAuthor
        settings.importValues(values, clearOthers = true)
        categories.replaceAll(r.categories)
        itemOverrides.replaceAll(r.overrides)
        profiles.replaceAll(r.profiles)
        r.params?.let { wallpaperParams()?.importParams(it) }
    }

    /** Moves a staged asset into place; returns the destination and where its old copy went. */
    private fun install(asset: StagedAsset): Pair<File, File?> {
        val dest = File(filesDir, asset.destination)
        dest.parentFile?.mkdirs()
        var old: File? = null
        if (dest.exists()) {
            old = File(dest.parentFile, dest.name + ".old-" + clock())
            if (!dest.renameTo(old)) throw IOException("cannot move ${dest.name} aside")
        }
        if (!asset.staged.renameTo(dest)) {
            if (asset.staged.isDirectory) asset.staged.copyRecursively(dest, overwrite = true) else asset.staged.copyTo(dest, overwrite = true)
        }
        return dest to old
    }

    // ---------------------------------------------------------------------------------------
    // Automatic backup

    private var watching = false

    /** Starts writing automatic backups on a debounce after changes. Idempotent. */
    @Synchronized
    fun startAutoBackup() {
        if (watching) return
        watching = true
        scope.launch {
            autoBackups.refresh()
            val state = combine(settings.settings, categories.state, itemOverrides.overrides, profiles.state) { s, c, o, p ->
                Fingerprint(exportedValues(s, themeOnly = false), c, o.mapValues { (_, v) -> v.copy(launchCount = 0, lastLaunch = 0) }, p.profiles)
            }
            autoBackupTriggers(state).collect {
                runCatching { writeAutoBackup(AutoBackupReason.Change) }
            }
        }
    }

    /** What an automatic backup holds, without usage counts (launching an app is not a change). */
    private data class Fingerprint(
        val settings: Map<String, Any>,
        val categories: CategoryList,
        val overrides: Map<String, ItemOverride>,
        val profiles: List<DisplayProfile>,
    )

    /** Writes an automatic backup now. */
    suspend fun writeAutoBackup(reason: AutoBackupReason): AutoBackupEntry =
        autoBackups.write(codec.encode(snapshot(BackupKind.Settings)), reason)

    // ---------------------------------------------------------------------------------------
    // Reset

    /**
     * *Reset to default*: an automatic backup, then settings, categories, app customisations,
     * display profiles and wallpaper parameters back to their defaults. Restart afterwards.
     */
    suspend fun resetAll() = applyMutex.withLock {
        runCatching { writeAutoBackup(AutoBackupReason.BeforeReset) }
        settings.resetAll()
        categories.replaceAll(CategoryList())
        itemOverrides.replaceAll(emptyMap())
        profiles.replaceAll(emptyList())
        runCatching { wallpaperParams()?.importParams(emptyMap()) }
    }

    companion object {
        const val ICONS_DIR = "icons"
        const val BACKGROUND = "background"
        const val THEME_DIR = "themes/current"
        const val WALLPAPERS = "wallpapers"

        private val PROJECTIVY_JSON = Json { isLenient = true }

        /** The stored, exported values of [s] that differ from their defaults. */
        fun exportedValues(s: Settings, themeOnly: Boolean): Map<String, Any> = Keys.registry
            .filter { it.exported && (!themeOnly || it.theme) && s.isSet(it) }
            .associate { it.name to s[it] }

        /** The paths that name `filesDir`: the real one, and the legacy `/data/data` alias. */
        fun filesDirAliases(filesDir: File, packageName: String): List<String> = listOfNotNull(
            filesDir.absolutePath,
            runCatching { filesDir.canonicalPath }.getOrNull(),
            "/data/data/$packageName/files",
        ).distinct()

    }
}

/** Recognises common image and video files by their first bytes, to give a wallpaper an extension. */
internal object MediaSniffer {
    fun extension(file: File): String {
        val head = ByteArray(12)
        val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(-1)
        if (n < 12) return ""
        fun at(i: Int) = head[i].toInt() and 0xFF
        val ascii = String(head, Charsets.ISO_8859_1)
        return when {
            at(0) == 0xFF && at(1) == 0xD8 -> ".jpg"
            at(0) == 0x89 && ascii.substring(1, 4) == "PNG" -> ".png"
            ascii.startsWith("GIF8") -> ".gif"
            ascii.startsWith("RIFF") && ascii.substring(8, 12) == "WEBP" -> ".webp"
            ascii.substring(4, 8) == "ftyp" -> ".mp4"
            at(0) == 0x1A && at(1) == 0x45 && at(2) == 0xDF && at(3) == 0xA3 -> ".mkv"
            else -> ""
        }
    }
}
