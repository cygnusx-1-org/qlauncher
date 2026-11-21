/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.ui.settings

import org.cygnusx1.qlauncher.settings.Settings
import org.cygnusx1.qlauncher.settings.SettingsRepository
import org.cygnusx1.qlauncher.ui.settings.model.Row
import org.cygnusx1.qlauncher.ui.settings.model.ValueFormat
import kotlin.math.abs
import kotlin.math.roundToInt

/** A slider's range and value as doubles, whatever the key type. */
data class SliderModel(val min: Double, val max: Double, val step: Double, val value: Double, val origin: Double) {
    val stops: Int get() = ((max - min) / step).roundToInt() + 1
    val position: Float get() = if (max == min) 0f else ((value - min) / (max - min)).toFloat()
    val originFraction: Float get() = if (max == min) 0f else ((origin - min) / (max - min)).toFloat()

    /** The value [delta] stops away, clamped to the range. */
    fun stepped(delta: Int): Double {
        val index = ((value - min) / step).roundToInt() + delta
        return (min + index * step).coerceIn(min, max)
    }
}

object SliderValues {
    fun model(row: Row.Slider, s: Settings): SliderModel {
        val (min, max, step, value) = when {
            row.intKey != null -> listOf(row.intKey.min.toDouble(), row.intKey.max.toDouble(), row.intKey.step.toDouble(), s[row.intKey].toDouble())
            row.floatKey != null -> listOf(row.floatKey.min.toDouble(), row.floatKey.max.toDouble(), row.floatKey.step.toDouble(), s[row.floatKey].toDouble())
            else -> {
                val k = row.choiceKey!!
                listOf(row.choiceMin.toDouble(), (k.count - 1).toDouble(), 1.0, s[k].toDouble().coerceAtLeast(row.choiceMin.toDouble()))
            }
        }
        val origin = when {
            min < 0 && max > 0 -> 0.0
            row.centred -> (min + max) / 2
            else -> min
        }
        return SliderModel(min, max, step, value.coerceIn(min, max), origin)
    }

    /** Stores the value [delta] stops away; returns true when it changed. */
    fun step(row: Row.Slider, repo: SettingsRepository, delta: Int): Boolean {
        val m = model(row, repo.current)
        val next = m.stepped(delta)
        if (abs(next - m.value) < 1e-9) return false
        when {
            row.intKey != null -> repo.update(row.intKey, next.roundToInt())
            row.floatKey != null -> repo.update(row.floatKey, ((next * 10000).roundToInt() / 10000.0).toFloat())
            else -> repo.update(row.choiceKey!!, next.roundToInt())
        }
        return true
    }

    fun format(row: Row.Slider, value: Double): String = when (row.format) {
        ValueFormat.Plain -> if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else trim(value)
        ValueFormat.Percent -> if (row.floatKey != null) "${(value * 100).roundToInt()}%" else "${value.roundToInt()}%"
        ValueFormat.MinutesSeconds -> {
            val total = value.roundToInt()
            "%d:%02d".format(total / 60, total % 60)
        }
        ValueFormat.Letters -> row.letters.getOrNull(value.roundToInt() - row.choiceMin) ?: value.roundToInt().toString()
        ValueFormat.OneDecimal -> trim(value)
        ValueFormat.Seconds -> "${value.roundToInt()} s"
    }

    private fun trim(v: Double): String {
        val r = (v * 100).roundToInt() / 100.0
        return if (r == r.roundToInt().toDouble()) r.roundToInt().toString() else r.toString()
    }
}
