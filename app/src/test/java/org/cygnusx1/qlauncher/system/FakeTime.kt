/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.system

/** A fake clock and scheduler: time moves only through [advanceBy] / [advanceTo]. */
class FakeTime(start: Long = 1_000_000L) : MonotonicClock, TaskScheduler {
    var now: Long = start
        private set

    private class Task(val at: Long, val seq: Long, val action: () -> Unit) {
        var cancelled = false
    }

    private val tasks = mutableListOf<Task>()
    private var seq = 0L

    override fun nowMillis(): Long = now

    override fun schedule(delayMillis: Long, task: () -> Unit): Cancellable {
        val t = Task(now + delayMillis.coerceAtLeast(0L), seq++, task)
        tasks += t
        return Cancellable { t.cancelled = true }
    }

    /** Runs every task due within [millis], in time order, then sets the clock to the end. */
    fun advanceBy(millis: Long) = advanceTo(now + millis)

    fun advanceTo(target: Long) {
        while (true) {
            val next = tasks.filter { !it.cancelled && it.at <= target }.minWithOrNull(compareBy<Task>({ it.at }, { it.seq })) ?: break
            tasks -= next
            now = maxOf(now, next.at)
            next.action()
        }
        tasks.removeAll { it.cancelled }
        now = target
    }

    /** Moves the clock without running anything, as if the scheduler were late. */
    fun skipWithoutRunning(millis: Long) {
        now += millis
    }

    val pendingTasks: Int get() = tasks.count { !it.cancelled }
}
