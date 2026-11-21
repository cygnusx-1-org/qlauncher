/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.reddit

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The stored Reddit credentials. [toString] never shows a token. */
class RedditTokens(
    val refreshToken: String,
    val accessToken: String?,
    /** Clock milliseconds at which [accessToken] expires. */
    val accessExpiresAt: Long,
    val username: String?,
) {
    fun withAccess(token: String, expiresAt: Long, refresh: String? = null) =
        RedditTokens(refresh ?: refreshToken, token, expiresAt, username)

    override fun toString(): String = "RedditTokens(user=$username, expiresAt=$accessExpiresAt)"
}

/** Where the API keeps its tokens; a DataStore in the app, a map in tests. */
interface RedditCredentials {
    suspend fun current(): RedditTokens?
    suspend fun save(tokens: RedditTokens)
    suspend fun clear()
}

/** Encrypts small secrets before they are written. */
interface TokenCipher {
    fun encrypt(plain: String): String

    /** Null when the value cannot be decrypted (key lost after a restore, for example). */
    fun decrypt(encoded: String): String?
}

/** AES-256-GCM with a key that never leaves the Android Keystore. Output: Base64(iv ‖ ciphertext). */
class KeystoreTokenCipher(private val alias: String = "qlauncher_reddit_tokens") : TokenCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    override fun decrypt(encoded: String): String? = runCatching {
        val all = Base64.decode(encoded, Base64.NO_WRAP)
        if (all.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, IV_BYTES))
        String(cipher.doFinal(all, IV_BYTES, all.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/**
 * The Reddit credentials in their own DataStore file, `datastore/reddit_tokens.preferences_pb`,
 * which the backup rules exclude. Both tokens are encrypted; the user name is not a secret.
 * Nothing here is ever logged.
 */
class RedditTokenStore(
    private val store: DataStore<Preferences>,
    private val cipher: TokenCipher,
    scope: CoroutineScope,
) : RedditCredentials {

    /** The logged-in user name, or null while logged out. Empty when logged in but not yet known. */
    val username: StateFlow<String?> = store.data
        .map { if (it[REFRESH] != null) it[USERNAME].orEmpty() else null }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun current(): RedditTokens? {
        val p = store.data.first()
        val refresh = p[REFRESH]?.let(cipher::decrypt) ?: return null
        val access = p[ACCESS]?.let(cipher::decrypt)
        return RedditTokens(refresh, access, p[EXPIRES] ?: 0L, p[USERNAME])
    }

    override suspend fun save(tokens: RedditTokens) {
        val refresh = cipher.encrypt(tokens.refreshToken)
        val access = tokens.accessToken?.let(cipher::encrypt)
        store.edit {
            it[REFRESH] = refresh
            if (access != null) it[ACCESS] = access else it.remove(ACCESS)
            it[EXPIRES] = tokens.accessExpiresAt
            if (tokens.username != null) it[USERNAME] = tokens.username else it.remove(USERNAME)
        }
    }

    suspend fun setUsername(name: String) {
        store.edit { if (it[REFRESH] != null) it[USERNAME] = name }
    }

    override suspend fun clear() {
        store.edit { it.clear() }
    }

    companion object {
        /** The DataStore file name; the backup rules exclude `datastore/reddit_tokens.preferences_pb`. */
        const val FILE_NAME = "reddit_tokens"

        private val REFRESH = stringPreferencesKey("refresh")
        private val ACCESS = stringPreferencesKey("access")
        private val EXPIRES = longPreferencesKey("access_expires_at")
        private val USERNAME = stringPreferencesKey("username")
    }
}
