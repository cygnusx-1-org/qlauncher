/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.parental

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.cygnusx1.qlauncher.data.ItemKeys
import org.cygnusx1.qlauncher.data.ItemOverride
import org.cygnusx1.qlauncher.settings.Keys
import org.cygnusx1.qlauncher.settings.SettingsRepository
import org.cygnusx1.qlauncher.settings.StringKey
import java.security.SecureRandom
import java.util.Calendar

/** The two clocks parental control needs. */
interface ParentalClock {
    /** Monotonic milliseconds for the validation window (`SystemClock.elapsedRealtime`). */
    fun elapsedMillis(): Long

    /** Local wall-clock time of day in milliseconds after midnight, for the protection window. */
    fun millisOfDay(): Long

    object System : ParentalClock {
        override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
        override fun millisOfDay(): Long {
            val now = Calendar.getInstance()
            val minutes = now.get(Calendar.HOUR_OF_DAY) * 60L + now.get(Calendar.MINUTE)
            return (minutes * 60L + now.get(Calendar.SECOND)) * 1000L + now.get(Calendar.MILLISECOND)
        }
    }
}

/** Pure rules, kept apart so they can be tested on their own. */
object ParentalPolicy {
    /** A validation is remembered this long... */
    const val SHORT_VALIDITY_MILLIS = 15_000L

    /** ...or this long with *Don't request code for 15mins after a successful validation*. */
    const val LONG_VALIDITY_MILLIS = 15 * 60_000L

    const val DAY_MILLIS = 24 * 60 * 60_000L

    /**
     * Whether protection applies at [millisOfDay]. Without *Enable protection only between* it
     * always does. The window runs from [start] (inclusive) to [end] (exclusive) and wraps past
     * midnight when [end] is before [start]; equal ends mean the whole day.
     */
    fun inProtectedWindow(partial: Boolean, start: Long, end: Long, millisOfDay: Long): Boolean {
        if (!partial) return true
        val s = start.mod(DAY_MILLIS)
        val e = end.mod(DAY_MILLIS)
        val t = millisOfDay.mod(DAY_MILLIS)
        return when {
            s == e -> true
            s < e -> t in s until e
            else -> t >= s || t < e
        }
    }

    /** Whether a validation at [validatedAt] (elapsed ms, null = never) still holds at [now]. */
    fun validationHolds(validatedAt: Long?, now: Long, longDuration: Boolean): Boolean {
        if (validatedAt == null) return false
        val age = now - validatedAt
        return age >= 0 && age < if (longDuration) LONG_VALIDITY_MILLIS else SHORT_VALIDITY_MILLIS
    }
}

/**
 * Parental control: a code of arrows and digits locks chosen apps, optionally
 * only during part of the day, and can protect the launcher's settings.
 *
 * Only a salted hash and the code's length are stored ([Keys.Parental.codeHash], see
 * [StoredCode]); the recovery code is stored the same way in [Keys.Parental.recoveryHash]. A
 * successful validation is remembered in memory for 15 s, or 15 min with
 * [Keys.Parental.longDuration], process-wide, so the home screen and the accessibility service
 * agree.
 *
 * Thread-safe for reads; [setCode] and [clearCode] suspend on the settings store.
 */
class ParentalManager(
    private val settings: SettingsRepository,
    private val overrides: StateFlow<Map<String, ItemOverride>>,
    private val updateOverride: (itemKey: String, transform: (ItemOverride) -> ItemOverride) -> Unit,
    scope: CoroutineScope,
    private val clock: ParentalClock = ParentalClock.System,
    private val random: SecureRandom = SecureRandom(),
) {
    @Volatile
    private var validatedAt: Long? = null

    /** True once a parental code has been set. */
    val codeSet: StateFlow<Boolean> = settings.settings
        .map { StoredCode.decode(it[Keys.Parental.codeHash]) != null }
        .stateIn(scope, SharingStarted.Eagerly, isCodeSet())

    fun isCodeSet(): Boolean = storedCode() != null

    /** True when the item (an `ItemKeys` key) is locked. */
    fun isLocked(itemKey: String): Boolean = overrides.value[itemKey]?.parentalLocked == true

    /** True when any app item of [packageName] is locked. */
    fun isPackageLocked(packageName: String): Boolean = overrides.value.any { (key, o) ->
        o.parentalLocked && ItemKeys.parseApp(key)?.first == packageName
    }

    /**
     * Whether locked apps need the code right now: a code is set, the time is inside the
     * protection window, and no validation is remembered.
     */
    fun requiresCode(): Boolean = requiresCode(clock.elapsedMillis(), clock.millisOfDay())

    fun requiresCode(nowElapsed: Long, millisOfDay: Long): Boolean {
        if (!isCodeSet()) return false
        val s = settings.current
        if (!ParentalPolicy.inProtectedWindow(s[Keys.Parental.partial], s[Keys.Parental.partialStart], s[Keys.Parental.partialEnd], millisOfDay)) {
            return false
        }
        return !isValidated(nowElapsed)
    }

    /** Check before launching [itemKey] from the home screen: true = show the prompt first. */
    fun needsCodeToLaunch(itemKey: String): Boolean = isLocked(itemKey) && requiresCode()

    /** The same check for a package that reached the foreground by another route. */
    fun needsCodeForPackage(packageName: String): Boolean = isPackageLocked(packageName) && requiresCode()

    /**
     * Check before opening settings or changing the UI (*Protect launcher settings and UI
     * changes*, applied 24/7, so the protection window does not matter).
     */
    fun needsCodeForSettings(): Boolean =
        settings.current[Keys.Parental.protectSettings] && isCodeSet() && !isValidated(clock.elapsedMillis())

    /** Check before locking or unlocking an app or changing parental settings. */
    fun needsCodeToManage(): Boolean = isCodeSet() && !isValidated(clock.elapsedMillis())

    fun isValidated(nowElapsed: Long = clock.elapsedMillis()): Boolean =
        ParentalPolicy.validationHolds(validatedAt, nowElapsed, settings.current[Keys.Parental.longDuration])

    /** Records a successful entry (the prompt calls it). */
    fun onValidated() {
        validatedAt = clock.elapsedMillis()
    }

    /** Forgets a remembered validation, so the next check asks again. */
    fun forgetValidation() {
        validatedAt = null
    }

    /** A matcher for one prompt; null when no code is set. */
    fun newMatcher(): CodeMatcher? {
        val code = storedCode() ?: return null
        return CodeMatcher(code, StoredCode.decode(settings.current[Keys.Parental.recoveryHash]))
    }

    /**
     * Stores [tokens] as the new code and counts as a validation. The first time a code is set a
     * recovery code is generated too; its display form (see [RecoveryCode.display]) is returned
     * so it can be shown once. Later changes keep the existing recovery code and return null.
     */
    suspend fun setCode(tokens: List<ParentalToken>): String? {
        require(tokens.isNotEmpty()) { "A code needs at least one key" }
        require(tokens.size <= MAX_CODE_LENGTH) { "A code has at most $MAX_CODE_LENGTH keys" }
        var shown: String? = null
        if (StoredCode.decode(settings.current[Keys.Parental.recoveryHash]) == null) {
            val recovery = RecoveryCode.generate(random)
            store(Keys.Parental.recoveryHash, StoredCode.create(recovery, random).encode())
            shown = RecoveryCode.display(recovery)
        }
        store(Keys.Parental.codeHash, StoredCode.create(tokens, random).encode())
        onValidated()
        return shown
    }

    /** Removes the code and the recovery code. Locked apps stay marked but are no longer guarded. */
    suspend fun clearCode() {
        store(Keys.Parental.codeHash, "")
        store(Keys.Parental.recoveryHash, "")
        validatedAt = null
    }

    /** Writes and waits until [SettingsRepository.current] shows it, so checks right after agree. */
    private suspend fun store(key: StringKey, value: String) {
        settings.set(key, value)
        settings.settings.first { it[key] == value }
    }

    /** Locks or unlocks an item. Callers check [needsCodeToManage] first. */
    fun setLocked(itemKey: String, locked: Boolean) {
        updateOverride(itemKey) { it.copy(parentalLocked = locked) }
    }

    private fun storedCode(): StoredCode? = StoredCode.decode(settings.current[Keys.Parental.codeHash])

    companion object {
        /** Codes longer than this are refused (the prompt stops recording keys). */
        const val MAX_CODE_LENGTH = 32
    }
}
