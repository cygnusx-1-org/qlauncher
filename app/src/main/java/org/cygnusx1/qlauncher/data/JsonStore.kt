/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** The app's JSON settings: lenient on input, compact on output, defaults omitted. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
    explicitNulls = false
    coerceInputValues = true
}

/** A DataStore whose value is one JSON document. */
class JsonSerializer<T>(
    private val serializer: KSerializer<T>,
    override val defaultValue: T,
    private val json: Json = AppJson,
) : Serializer<T> {
    override suspend fun readFrom(input: InputStream): T = try {
        json.decodeFromString(serializer, input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Cannot read JSON store", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("Cannot read JSON store", e)
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(json.encodeToString(serializer, t).encodeToByteArray())
    }
}

fun <T> jsonDataStore(
    context: Context,
    fileName: String,
    serializer: KSerializer<T>,
    defaultValue: T,
    scope: CoroutineScope,
): DataStore<T> = jsonDataStore(File(context.filesDir, "datastore/$fileName"), serializer, defaultValue, scope)

fun <T> jsonDataStore(
    file: File,
    serializer: KSerializer<T>,
    defaultValue: T,
    scope: CoroutineScope,
): DataStore<T> = DataStoreFactory.create(
    serializer = JsonSerializer(serializer, defaultValue),
    corruptionHandler = ReplaceFileCorruptionHandler { defaultValue },
    scope = scope,
    produceFile = { file },
)
