/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup.projectivy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reads Projectivy Launcher's export files, for import only. This is the one place
 * Projectivy's format appears in QLauncher; QLauncher never writes it.
 *
 * - A `.plbackup` is `salt (16) | IV (16) | AES-256-CBC ciphertext` of a JSON object of
 *   preference keys and values. The key is SHA-256 applied 10 000 times, each time to the
 *   previous digest followed by the salt, starting from a passphrase built into the app. It is
 *   obfuscation, not protection.
 * - A `.pltheme` is a ZIP of `manifest` (JSON), `appearance.plbackup` (as above) and optional
 *   `wallpapers/` files.
 */
object ProjectivyDecoder {
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 16
    private const val ROUNDS = 10_000
    private val PASSPHRASE = "walt_sent_me".encodeToByteArray()

    const val THEME_MANIFEST = "manifest"
    const val THEME_PAYLOAD = "appearance.plbackup"
    const val THEME_WALLPAPERS = "wallpapers/"

    class FormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** A theme's `manifest`. */
    @Serializable
    data class ThemeManifest(
        val projectivyLauncherVersion: Int = 0,
        val themeVersion: Int = 0,
        val date: String = "",
        val author: String = "",
        val name: String = "",
        val desc: String = "",
        val url: String = "",
        val credits: String = "",
    )

    /** A decoded theme: its manifest (if readable), the settings JSON and the extracted wallpapers. */
    data class Theme(val manifest: ThemeManifest?, val settingsJson: String, val wallpapers: List<File>)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** Decrypts a `.plbackup` payload into its JSON text. */
    fun decodeBackup(bytes: ByteArray): String {
        if (bytes.size < SALT_LENGTH + IV_LENGTH + 16 || (bytes.size - SALT_LENGTH - IV_LENGTH) % 16 != 0) {
            throw FormatException("not a Projectivy backup")
        }
        val salt = bytes.copyOfRange(0, SALT_LENGTH)
        val iv = bytes.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
        val plain = try {
            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key(salt), "AES"), IvParameterSpec(iv))
                doFinal(bytes, SALT_LENGTH + IV_LENGTH, bytes.size - SALT_LENGTH - IV_LENGTH)
            }
        } catch (e: GeneralSecurityException) {
            throw FormatException("cannot decrypt", e)
        }
        val text = plain.decodeToString()
        if (!text.trimStart().startsWith("{")) throw FormatException("not a JSON object")
        return text
    }

    private fun key(salt: ByteArray): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        var k = PASSPHRASE
        repeat(ROUNDS) {
            sha.update(k)
            sha.update(salt)
            k = sha.digest()
        }
        return k
    }

    /**
     * Reads a `.pltheme` from [input], writing its wallpapers into [wallpaperDir] (file names
     * only; anything else is skipped).
     */
    fun readTheme(input: InputStream, wallpaperDir: File): Theme {
        var manifest: ThemeManifest? = null
        var payload: ByteArray? = null
        val wallpapers = ArrayList<File>()
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    when {
                        e.name == THEME_MANIFEST -> manifest = runCatching {
                            json.decodeFromString(ThemeManifest.serializer(), zip.readBytes().decodeToString())
                        }.getOrNull()
                        e.name == THEME_PAYLOAD -> payload = zip.readBytes()
                        e.name.startsWith(THEME_WALLPAPERS) -> {
                            val name = e.name.substringAfterLast('/')
                            if (name.isEmpty() || name == "." || name == "..") continue
                            wallpaperDir.mkdirs()
                            val f = File(wallpaperDir, name)
                            f.outputStream().use { zip.copyTo(it) }
                            wallpapers += f
                        }
                    }
                }
            }
        } catch (e: IOException) {
            throw FormatException("damaged theme", e)
        }
        val p = payload ?: throw FormatException("theme without $THEME_PAYLOAD")
        return Theme(manifest, decodeBackup(p), wallpapers)
    }

    /** Projectivy's id for an installed app: the Java hash of its package name. */
    fun appId(packageName: String): Int = packageName.hashCode()

    /** Projectivy's id for a folder card that opens category [categoryId]. */
    fun folderId(categoryId: Int): Int = "category_$categoryId".hashCode()
}
