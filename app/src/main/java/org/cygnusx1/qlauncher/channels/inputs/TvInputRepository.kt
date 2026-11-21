/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.channels.inputs

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.cygnusx1.qlauncher.apps.AppKeys
import org.cygnusx1.qlauncher.apps.PmCompat
import org.cygnusx1.qlauncher.data.ItemKeys
import org.cygnusx1.qlauncher.data.ItemOverrideStore
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.SettingsRepository

/** A TV input from [TvInputManager]. */
data class TvInput(
    val id: String,
    /** The user's custom label when set, otherwise the input's own label. */
    val label: String,
    /** A `TvInputInfo.TYPE_*` constant. */
    val type: Int,
    /** HDMI, composite and the like; false for tuner inputs that apps register. */
    val isPassthrough: Boolean,
    val packageName: String,
    val parentId: String? = null,
) {
    val key: String get() = ItemKeys.input(id)

    /** A hardware input: tuned through its passthrough channel URI. */
    val isHardware: Boolean get() = TvInputs.isHardwareId(id)
}

/** Rules about inputs that need no Android objects. */
object TvInputs {
    /** Hardware input ids carry `HW` (for example `…/HW5`), as the platform builds them. */
    fun isHardwareId(id: String): Boolean = id.contains("HW")

    /** A custom label wins when it is not blank. */
    fun label(custom: CharSequence?, own: CharSequence?): String =
        custom?.toString()?.takeIf { it.isNotBlank() } ?: own?.toString().orEmpty()
}

/**
 * TV inputs. Available when the device has
 * `android.software.live_tv`. [inputs] are the passthrough inputs (the *Inputs* category);
 * [liveSources] are the non-hidden tuner inputs that apps register (the *Live TV* category).
 *
 * A [TvInputManager.TvInputCallback] keeps both lists current for the life of the process.
 */
class TvInputRepository internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val overrides: ItemOverrideStore,
) {
    /** The device supports live TV inputs. */
    val supported: Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LIVE_TV)

    private val manager: TvInputManager? =
        if (supported) context.getSystemService(TvInputManager::class.java) else null

    private data class Lists(val passthrough: List<TvInput>, val tuners: List<TvInput>)

    private val lists = MutableStateFlow(Lists(emptyList(), emptyList()))

    /** Passthrough inputs (HDMI 1–4, AV, ...): the Inputs category. */
    val inputs: StateFlow<List<TvInput>> = lists.map { it.passthrough }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Non-hidden tuner inputs that apps register: the Live TV category. */
    val liveSources: StateFlow<List<TvInput>> = lists.map { it.tuners }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Every input by item key. */
    val byKey: StateFlow<Map<String, TvInput>> = lists.map { l -> (l.passthrough + l.tuners).associateBy { it.key } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val callback = object : TvInputManager.TvInputCallback() {
        override fun onInputAdded(inputId: String) = refresh()
        override fun onInputRemoved(inputId: String) = refresh()
        override fun onInputUpdated(inputId: String) = refresh()
        override fun onTvInputInfoUpdated(inputInfo: TvInputInfo) = refresh()
    }

    init {
        manager?.let { m ->
            runCatching { m.registerCallback(callback, Handler(Looper.getMainLooper())) }
            refresh()
        }
    }

    fun refresh() {
        val m = manager ?: return
        scope.launch(Dispatchers.IO) {
            val all = try {
                m.tvInputList.orEmpty()
            } catch (e: RuntimeException) {
                Log.w(TAG, "getTvInputList failed", e)
                emptyList()
            }
            val passthrough = ArrayList<TvInput>()
            val tuners = ArrayList<TvInput>()
            for (info in all) {
                if (info.isPassthroughInput) {
                    passthrough += info.toInput()
                } else if (!runCatching { info.isHidden(context) }.getOrDefault(false)) {
                    tuners += info.toInput()
                }
            }
            lists.value = Lists(passthrough, tuners)
        }
    }

    private fun TvInputInfo.toInput() = TvInput(
        id = id,
        label = TvInputs.label(runCatching { loadCustomLabel(context) }.getOrNull(), runCatching { loadLabel(context) }.getOrNull()),
        type = type,
        isPassthrough = isPassthroughInput,
        packageName = serviceInfo?.packageName.orEmpty(),
        parentId = parentId,
    )

    operator fun get(itemKey: String): TvInput? = byKey.value[itemKey]

    /** The channel URI the system TV app tunes to for [input]. */
    fun channelUri(input: TvInput): Uri =
        if (input.isHardware) TvContract.buildChannelUriForPassthroughInput(input.id) else TvContract.buildChannelsUriForInput(input.id)

    /** Opens [input] in the system TV app. */
    fun openIntent(input: TvInput): Intent =
        Intent(Intent.ACTION_VIEW, channelUri(input)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens the input behind [itemKey] (an `input:` key) and remembers it as the last input. */
    fun open(context: Context, itemKey: String, options: Bundle? = null): Boolean {
        val id = AppKeys.inputId(itemKey) ?: return false
        val input = get(itemKey) ?: TvInput(id, id, TvInputInfo.TYPE_OTHER, TvInputs.isHardwareId(id), "")
        val ok = PmCompat.start(context, openIntent(input), options)
        if (ok) {
            settings.update(Keys.Internal.lastInput, id)
            overrides.recordLaunch(itemKey)
        }
        return ok
    }

    /** The input's icon. Call off the main thread. */
    fun icon(itemKey: String): Drawable? {
        val id = AppKeys.inputId(itemKey) ?: return null
        val info = runCatching { manager?.getTvInputInfo(id) }.getOrNull() ?: return null
        return runCatching { info.loadIcon(context) }.getOrNull()
    }

    private companion object {
        const val TAG = "QLInputs"
    }
}
