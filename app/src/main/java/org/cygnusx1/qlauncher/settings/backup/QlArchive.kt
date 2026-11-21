/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.AesVersion
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.Closeable
import java.io.File
import java.io.IOException

/** A file on disk to store in an archive under [entryName]. */
data class ArchiveFile(val entryName: String, val source: File)

/**
 * QLauncher's export container: a standard ZIP whose entries are encrypted with AES-256 (WinZip
 * AE-2) by zip4j, so any archive tool that knows the password can open it.
 */
object QlArchive {
    private fun params(name: String, encrypt: Boolean) = ZipParameters().apply {
        fileNameInZip = name
        compressionMethod = CompressionMethod.DEFLATE
        if (encrypt) {
            isEncryptFiles = true
            encryptionMethod = EncryptionMethod.AES
            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            aesVersion = AesVersion.TWO
        }
    }

    /**
     * Writes [entries] and then [files] into [target], replacing it. With a null [password] the
     * archive is not encrypted (used only by tests and internal staging).
     */
    fun write(target: File, password: CharArray?, entries: Map<String, ByteArray>, files: List<ArchiveFile> = emptyList()) {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".part")
        try {
            ZipOutputStream(tmp.outputStream().buffered(), password).use { zos ->
                for ((name, bytes) in entries) {
                    zos.putNextEntry(params(name, password != null))
                    zos.write(bytes)
                    zos.closeEntry()
                }
                for (f in files) {
                    if (!f.source.isFile) continue
                    zos.putNextEntry(params(f.entryName, password != null))
                    f.source.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IOException("cannot move ${tmp.name} into place")
        } finally {
            tmp.delete()
        }
    }

    /** Every regular file under [dir], as entries under [prefix] (`prefix` + relative path). */
    fun filesUnder(dir: File, prefix: String): List<ArchiveFile> {
        if (dir.isFile) return listOf(ArchiveFile(prefix.trimEnd('/'), dir))
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.map {
            ArchiveFile(prefix + it.relativeTo(dir).invariantSeparatorsPath, it)
        }.toList()
    }

    /** True when [file] starts like a ZIP archive. */
    fun looksLikeZip(file: File): Boolean {
        val head = ByteArray(4)
        val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(-1)
        return n == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2].toInt() == 3 && head[3].toInt() == 4
    }

    /** Reads an archive. [setPassword] before reading an encrypted one. */
    class Reader(file: File) : Closeable {
        private val zip = ZipFile(file)

        val isValid: Boolean get() = runCatching { zip.isValidZipFile }.getOrDefault(false)

        val isEncrypted: Boolean get() = try {
            zip.isEncrypted
        } catch (e: ZipException) {
            throw ImportException.Damaged(e)
        }

        private val headers: List<FileHeader> by lazy {
            try {
                zip.fileHeaders.orEmpty()
            } catch (e: ZipException) {
                throw ImportException.Damaged(e)
            }
        }

        val entryNames: List<String> get() = headers.filterNot { it.isDirectory }.map { it.fileName }

        fun setPassword(password: CharArray) {
            zip.setPassword(password)
        }

        /** The bytes of [name], or null when absent. */
        fun read(name: String): ByteArray? {
            val h = headers.firstOrNull { it.fileName == name } ?: return null
            return guarded { zip.getInputStream(h).use { it.readBytes() } }
        }

        /**
         * Extracts every entry under [prefix] into [targetDir] (relative paths kept) and returns
         * the extracted files. Names that would escape the folder are skipped.
         */
        fun extract(prefix: String, targetDir: File): List<File> {
            val out = ArrayList<File>()
            val root = targetDir.canonicalFile
            val base = prefix.trimEnd('/')
            for (h in headers) {
                if (h.isDirectory || !(h.fileName == base || h.fileName.startsWith("$base/"))) continue
                val rel = h.fileName.removePrefix(base).trimStart('/')
                val dest = if (rel.isEmpty()) File(root.path) else File(root, rel)
                val canonical = dest.canonicalFile
                if (rel.isNotEmpty() && !canonical.path.startsWith(root.path + File.separator)) continue
                canonical.parentFile?.mkdirs()
                guarded { zip.getInputStream(h).use { input -> canonical.outputStream().use { input.copyTo(it) } } }
                out += canonical
            }
            return out
        }

        private inline fun <T> guarded(block: () -> T): T = try {
            block()
        } catch (e: ZipException) {
            throw when (e.type) {
                ZipException.Type.WRONG_PASSWORD, ZipException.Type.CHECKSUM_MISMATCH -> ImportException.WrongPassword()
                else -> if (e.message.orEmpty().contains("password", ignoreCase = true) ||
                    e.message.orEmpty().contains("aes", ignoreCase = true)
                ) ImportException.WrongPassword() else ImportException.Damaged(e)
            }
        } catch (e: IOException) {
            throw ImportException.Damaged(e)
        }

        override fun close() {
            zip.close()
        }
    }
}
