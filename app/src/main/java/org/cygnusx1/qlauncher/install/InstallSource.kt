/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.install

import java.io.File

/** What the *Custom URL / Downloader code* field (or a catalogue URL) points at. */
sealed interface InstallSource {
    /** An http(s) URL to download. */
    data class Remote(val url: String) : InstallSource

    /** A file on the device, for example on a USB drive. */
    data class LocalFile(val path: String) : InstallSource

    /** An AFTVnews Downloader code, handed to the Downloader app. */
    data class DownloaderCode(val code: String) : InstallSource

    data class Invalid(val reason: Reason) : InstallSource

    enum class Reason { Empty, NoUsbVolume, UnsupportedScheme, Archive }
}

/**
 * URL normalisation, as Projectivy does it:
 *
 * - surrounding blanks are trimmed;
 * - digits only (3 to 10 of them) is a Downloader code;
 * - `usb://path` is the first `/storage/XXXX-XXXX/` volume plus `path`;
 * - `file://path` and absolute paths are local files;
 * - no scheme at all gets `https://`;
 * - http and https download; anything else (ftp, content, ...) is refused, as are `.tar.gz`
 *   archives, which Projectivy unpacks with root only.
 */
object InstallUrls {
    private val CODE = Regex("^\\d{3,10}$")
    private val VOLUME = Regex("^\\w{4}-\\w{4}$")

    fun parse(input: String, usbVolume: () -> String? = { firstUsbVolume() }): InstallSource {
        val s = input.trim()
        if (s.isEmpty()) return InstallSource.Invalid(InstallSource.Reason.Empty)
        if (CODE.matches(s)) return InstallSource.DownloaderCode(s)
        if (s.endsWith(".tar.gz", ignoreCase = true)) return InstallSource.Invalid(InstallSource.Reason.Archive)
        if (s.startsWith("usb://", ignoreCase = true)) {
            val volume = usbVolume() ?: return InstallSource.Invalid(InstallSource.Reason.NoUsbVolume)
            return InstallSource.LocalFile(volume.trimEnd('/') + "/" + s.substring("usb://".length).trimStart('/'))
        }
        if (s.startsWith("file://", ignoreCase = true)) return InstallSource.LocalFile(s.substring("file://".length))
        if (s.startsWith("/")) return InstallSource.LocalFile(s)
        val schemeEnd = s.indexOf("://")
        if (schemeEnd < 0) return InstallSource.Remote("https://$s")
        val scheme = s.substring(0, schemeEnd).lowercase()
        return if (scheme == "http" || scheme == "https") {
            InstallSource.Remote(scheme + s.substring(schemeEnd))
        } else {
            InstallSource.Invalid(InstallSource.Reason.UnsupportedScheme)
        }
    }

    /** The first `/storage/XXXX-XXXX` directory (a USB drive or SD card), or null. */
    fun firstUsbVolume(storageRoot: File = File("/storage")): String? =
        storageRoot.listFiles()?.filter { it.isDirectory && VOLUME.matches(it.name) }?.minByOrNull { it.name }?.absolutePath

    /** A file name for a downloaded APK, from the last path segment of [url]. */
    fun fileNameOf(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        val last = path.substringAfterLast('/').ifEmpty { "download" }
        val safe = last.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        return if (safe.endsWith(".apk", ignoreCase = true)) safe else "$safe.apk"
    }
}
