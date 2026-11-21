/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.system

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** A monotonic clock in milliseconds. Injected so timing logic can be tested with a fake. */
fun interface MonotonicClock {
    fun nowMillis(): Long

    companion object {
        /** `SystemClock.uptimeMillis()`, the time base of key events and `Handler`. */
        val Uptime = MonotonicClock { SystemClock.uptimeMillis() }

        /** `SystemClock.elapsedRealtime()`, which keeps counting in deep sleep. */
        val Elapsed = MonotonicClock { SystemClock.elapsedRealtime() }
    }
}

/** A scheduled task that can be cancelled; cancelling twice or after it ran is harmless. */
fun interface Cancellable {
    fun cancel()
}

/** Runs a task after a delay on one thread. Injected so timing logic can be tested. */
fun interface TaskScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): Cancellable
}

/** A [TaskScheduler] on a [Handler], by default the main thread's. */
class HandlerScheduler(private val handler: Handler = Handler(Looper.getMainLooper())) : TaskScheduler {
    override fun schedule(delayMillis: Long, task: () -> Unit): Cancellable {
        val runnable = Runnable { task() }
        handler.postDelayed(runnable, delayMillis.coerceAtLeast(0L))
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}
