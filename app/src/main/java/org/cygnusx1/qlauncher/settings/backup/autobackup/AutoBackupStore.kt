/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup.autobackup

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cygnusx1.qlauncher.settings.backup.ImportException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Why an automatic backup was taken. */
enum class AutoBackupReason(val tag: String) {
    /** Settings, categories or app customisations changed. */
    Change("change"),
    BeforeImport("import"),
    BeforeMigration("migration"),
    BeforeReset("reset"),
    ;

    companion object {
        fun of(tag: String): AutoBackupReason = entries.firstOrNull { it.tag == tag } ?: Change
    }
}

/** One automatic backup, as *From automatic backup* lists it. */
data class AutoBackupEntry(val file: File, val createdAt: Long, val sizeBytes: Long, val reason: AutoBackupReason)

/**
 * Automatic backups in app-private storage: the same JSON entries as an export (no icon or
 * wallpaper files, which stay where they are), zipped and encrypted with a key that never leaves
 * the device. Only the newest [keep] are kept.
 */
class AutoBackupStore(
    private val dir: File,
    private val cipher: BlobCipher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val keep: Int = DEFAULT_KEEP,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<AutoBackupEntry>>(emptyList())

    /** Newest first. Call [refresh] once to fill it. */
    val entries: StateFlow<List<AutoBackupEntry>> = _entries.asStateFlow()

    suspend fun refresh(): List<AutoBackupEntry> = withContext(io) { mutex.withLock { scan().also { _entries.value = it } } }

    /** Writes a backup of [content] (entry name → bytes) and drops the oldest beyond [keep]. */
    suspend fun write(content: Map<String, ByteArray>, reason: AutoBackupReason): AutoBackupEntry = withContext(io) {
        mutex.withLock {
            dir.mkdirs()
            val now = clock()
            var file = File(dir, fileName(now, reason))
            var n = 1
            while (file.exists()) file = File(dir, fileName(now, reason, n++))
            val tmp = File(file.path + ".part")
            try {
                tmp.writeBytes(cipher.encrypt(zip(content)))
                if (!tmp.renameTo(file)) throw IOException("cannot store ${file.name}")
            } finally {
                tmp.delete()
            }
            val all = scan()
            all.drop(keep).forEach { it.file.delete() }
            val kept = all.take(keep)
            _entries.value = kept
            kept.firstOrNull { it.file == file } ?: AutoBackupEntry(file, now, file.length(), reason)
        }
    }

    /**
     * The entries of [entry].
     *
     * @throws ImportException.Unreadable when it cannot be decrypted (another device's key, or damage).
     */
    suspend fun read(entry: AutoBackupEntry): Map<String, ByteArray> = withContext(io) {
        val blob = try {
            entry.file.readBytes()
        } catch (e: IOException) {
            throw ImportException.Unreadable(e)
        }
        val plain = try {
            cipher.decrypt(blob)
        } catch (e: GeneralSecurityException) {
            throw ImportException.Unreadable(e)
        } catch (e: RuntimeException) {
            throw ImportException.Unreadable(e)
        }
        try {
            unzip(plain)
        } catch (e: IOException) {
            throw ImportException.Unreadable(e)
        }
    }

    suspend fun deleteAll() = withContext(io) {
        mutex.withLock {
            dir.listFiles()?.forEach { if (it.name.startsWith(PREFIX)) it.delete() }
            _entries.value = emptyList()
        }
    }

    private fun scan(): List<AutoBackupEntry> = dir.listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }
        .map { f ->
            val parsed = parseName(f.name)
            AutoBackupEntry(f, parsed?.first ?: f.lastModified(), f.length(), parsed?.second ?: AutoBackupReason.Change)
        }
        .sortedWith(compareByDescending<AutoBackupEntry> { it.createdAt }.thenByDescending { it.file.name })

    companion object {
        const val DEFAULT_KEEP = 5
        const val PREFIX = "auto-"
        const val SUFFIX = ".qlauto"
        private const val STAMP = "yyyyMMdd-HHmmss-SSS"

        private fun stampFormat() = SimpleDateFormat(STAMP, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        fun fileName(time: Long, reason: AutoBackupReason, n: Int = 0): String =
            PREFIX + stampFormat().format(Date(time)) + "-" + reason.tag + (if (n > 0) "-$n" else "") + SUFFIX

        /** Time and reason from a file name, or null. */
        fun parseName(name: String): Pair<Long, AutoBackupReason>? {
            if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return null
            val body = name.removePrefix(PREFIX).removeSuffix(SUFFIX)
            if (body.length < STAMP.length + 2) return null
            val time = runCatching { stampFormat().parse(body.substring(0, STAMP.length))?.time }.getOrNull() ?: return null
            val reason = AutoBackupReason.of(body.substring(STAMP.length + 1).substringBefore('-'))
            return time to reason
        }

        fun zip(content: Map<String, ByteArray>): ByteArray {
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { z ->
                for ((name, data) in content) {
                    z.putNextEntry(ZipEntry(name))
                    z.write(data)
                    z.closeEntry()
                }
            }
            return bytes.toByteArray()
        }

        fun unzip(bytes: ByteArray): Map<String, ByteArray> {
            val out = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (!e.isDirectory) out[e.name] = z.readBytes()
                }
            }
            if (out.isEmpty()) throw IOException("empty automatic backup")
            return out
        }
    }
}

/**
 * Writes an automatic backup [debounceMillis] after the watched state last changed. [state]
 * emits a fingerprint of what a backup holds; the first value (the state at start) is not a
 * change, and a value equal to the previous one is ignored.
 */
@OptIn(FlowPreview::class)
fun autoBackupTriggers(state: Flow<Any>, debounceMillis: Long = AUTO_BACKUP_DEBOUNCE_MILLIS): Flow<Any> =
    state.distinctUntilChanged().drop(1).debounce(debounceMillis)

/** About ten seconds after the last change. */
const val AUTO_BACKUP_DEBOUNCE_MILLIS = 10_000L
