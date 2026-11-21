/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.device

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.os.Build
import android.view.Display
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cygnusx1.qlauncher.R
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.SettingsRepository
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.UUID

/** One read-only row of *About › This device*. */
data class DeviceInfoRow(
    val id: String,
    @param:StringRes @get:StringRes val label: Int,
    val value: String,
)

/**
 * *About › This device*: Brand / Manufacturer, Device / Product, Model, Hardware, Fingerprint,
 * Build, Build desc., Device type, HDR, IP address, Root access, PMInfo, ID. Rows without a
 * value are left out, as Projectivy does.
 */
class DeviceInfoRepository internal constructor(
    private val context: Context,
    private val settings: SettingsRepository,
) {
    /** Reads every row. Runs on a background thread. */
    suspend fun rows(): List<DeviceInfoRow> = withContext(Dispatchers.IO) {
        listOf(
            DeviceInfoRow(BRAND, R.string.apps_device_brand_manufacturer, "${Build.BRAND} / ${Build.MANUFACTURER}"),
            DeviceInfoRow(DEVICE, R.string.apps_device_device_product, "${Build.DEVICE} / ${Build.PRODUCT}"),
            DeviceInfoRow(MODEL, R.string.apps_device_model, Build.MODEL.orEmpty()),
            DeviceInfoRow(HARDWARE, R.string.apps_device_hardware, Build.HARDWARE.orEmpty()),
            DeviceInfoRow(FINGERPRINT, R.string.apps_device_fingerprint, Build.FINGERPRINT.orEmpty()),
            DeviceInfoRow(BUILD, R.string.apps_device_build, Build.DISPLAY.orEmpty()),
            DeviceInfoRow(BUILD_DESC, R.string.apps_device_build_desc, buildDescription()),
            DeviceInfoRow(DEVICE_TYPE, R.string.apps_device_device_type, deviceType()),
            DeviceInfoRow(HDR, R.string.apps_device_hdr, hdr()),
            DeviceInfoRow(IP, R.string.apps_device_ip_address, ipAddress().orEmpty()),
            DeviceInfoRow(
                ROOT,
                R.string.apps_device_root_access,
                context.getString(if (DeviceFacts.hasSuBinary()) R.string.apps_device_yes else R.string.apps_device_no),
            ),
            DeviceInfoRow(PM_INFO, R.string.apps_device_pm_info, context.getString(R.string.apps_device_pm_none)),
            DeviceInfoRow(ID, R.string.apps_device_id, uniqueId()),
        ).filter { it.value.isNotBlank() }
    }

    /** `Keys.Internal.uniqueId`, generated as a random UUID on first read. */
    suspend fun uniqueId(): String {
        val current = settings.current[Keys.Internal.uniqueId]
        if (current.isNotEmpty()) return current
        val id = UUID.randomUUID().toString()
        settings.set(Keys.Internal.uniqueId, id)
        return id
    }

    private fun buildDescription(): String =
        DeviceFacts.systemProperty("ro.build.description")?.takeIf { it.isNotBlank() }
            ?: DeviceFacts.buildDescription(Build.PRODUCT, Build.TYPE, Build.VERSION.RELEASE, Build.ID, Build.VERSION.INCREMENTAL, Build.TAGS)

    private fun deviceType(): String {
        DeviceFacts.systemProperty("ro.build.characteristics")?.takeIf { it.isNotBlank() }?.let { return it }
        val ui = context.getSystemService(UiModeManager::class.java)?.currentModeType
        return if (ui == Configuration.UI_MODE_TYPE_TELEVISION) "tv" else "default"
    }

    private fun hdr(): String {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return ""
        @Suppress("DEPRECATION")
        val caps = display.hdrCapabilities ?: return ""
        val types = if (Build.VERSION.SDK_INT >= 34) {
            display.mode.supportedHdrTypes
        } else {
            @Suppress("DEPRECATION")
            caps.supportedHdrTypes
        }
        return DeviceFacts.hdrDescription(
            types,
            caps.desiredMaxAverageLuminance,
            caps.desiredMaxLuminance,
            caps.desiredMinLuminance,
        )
    }

    private fun ipAddress(): String? {
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val props = cm?.getLinkProperties(cm.activeNetwork)
            props?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress?.let { return it }
        }
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()
    }

    companion object {
        const val BRAND = "brand"
        const val DEVICE = "device"
        const val MODEL = "model"
        const val HARDWARE = "hardware"
        const val FINGERPRINT = "fingerprint"
        const val BUILD = "build"
        const val BUILD_DESC = "build_desc"
        const val DEVICE_TYPE = "device_type"
        const val HDR = "hdr"
        const val IP = "ip"
        const val ROOT = "root"
        const val PM_INFO = "pm_info"
        const val ID = "id"
    }
}

/** Device facts as pure functions (and two plain file and property reads). */
object DeviceFacts {
    private val HDR_NAMES = mapOf(1 to "Dolby Vision", 2 to "HDR10", 3 to "HLG", 4 to "HDR10+")

    /**
     * Supported HDR types, then the display's luminance:
     * `HDR10, HLG · Max Average:500 maxLum:500 minLum:0`.
     */
    fun hdrDescription(types: IntArray, maxAverage: Float, max: Float, min: Float): String {
        val names = types.toList().distinct().mapNotNull { HDR_NAMES[it] }
        val lum = String.format(Locale.US, "Max Average:%d maxLum:%d minLum:%d", maxAverage.toInt(), max.toInt(), min.toInt())
        return if (names.isEmpty()) lum else names.joinToString(", ") + " · " + lum
    }

    /** The `ro.build.description` format: `product-type release id incremental tags`. */
    fun buildDescription(product: String?, type: String?, release: String?, id: String?, incremental: String?, tags: String?): String =
        listOf("${product.orEmpty()}-${type.orEmpty()}", release, id, incremental, tags)
            .filterNot { it.isNullOrBlank() }.joinToString(" ")

    /** Directories an `su` binary is found in on rooted devices. */
    val SU_DIRS = listOf(
        "/system/bin", "/system/xbin", "/sbin", "/system/sbin", "/vendor/bin", "/su/bin",
        "/data/local", "/data/local/bin", "/data/local/xbin", "/system/bin/failsafe", "/debug_ramdisk",
    )

    /**
     * Whether an `su` binary exists in a usual directory or on `PATH`. Nothing is executed.
     */
    fun hasSuBinary(dirs: List<String> = SU_DIRS, path: String? = System.getenv("PATH"), exists: (File) -> Boolean = { it.exists() }): Boolean {
        val all = dirs + path.orEmpty().split(':').filter { it.isNotBlank() }
        return all.distinct().any { exists(File(it, "su")) }
    }

    /** A system property through `android.os.SystemProperties`, or null when it cannot be read. */
    @SuppressLint("PrivateApi")
    fun systemProperty(name: String): String? = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        cls.getMethod("get", String::class.java).invoke(null, name) as? String
    }.getOrNull()
}
