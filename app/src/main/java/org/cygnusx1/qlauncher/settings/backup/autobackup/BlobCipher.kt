/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.settings.backup.autobackup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts automatic backups at rest. */
interface BlobCipher {
    fun encrypt(plain: ByteArray): ByteArray

    /** @throws GeneralSecurityException when the blob was not made with this key or is damaged. */
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a random 96-bit IV per blob: `QLA1 | IV (12) | ciphertext and tag`.
 */
open class AesGcmBlobCipher(private val key: () -> SecretKey) : BlobCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv.size == IV_LENGTH) { "unexpected IV length ${iv.size}" }
        return MAGIC + iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        if (blob.size < MAGIC.size + IV_LENGTH + 16 || !blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw GeneralSecurityException("not an automatic backup")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, MAGIC.size, IV_LENGTH))
        return cipher.doFinal(blob, MAGIC.size + IV_LENGTH, blob.size - MAGIC.size - IV_LENGTH)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        val MAGIC = "QLA1".encodeToByteArray()
    }
}

/**
 * [AesGcmBlobCipher] with a non-exportable key in the Android Keystore, created on first use.
 * The key never leaves the device, so automatic backups cannot be read anywhere else.
 */
class KeystoreBlobCipher(private val alias: String = DEFAULT_ALIAS) : AesGcmBlobCipher({ keystoreKey(alias) }) {
    companion object {
        const val DEFAULT_ALIAS = "qlauncher_autobackup"
        private const val PROVIDER = "AndroidKeyStore"

        @Synchronized
        private fun keystoreKey(alias: String): SecretKey {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            gen.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return gen.generateKey()
        }
    }
}
