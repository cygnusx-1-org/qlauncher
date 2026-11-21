/*
 * QLauncher - an Android TV launcher.
 * Copyright (C) 2026 QLauncher contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file for details.
 */
package org.cygnusx1.qlauncher.wallpaper.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.cygnusx1.qlauncher.settings.WallpaperProviders
import org.cygnusx1.qlauncher.wallpaper.UpdateMode
import org.cygnusx1.qlauncher.wallpaper.Wallpaper
import org.cygnusx1.qlauncher.wallpaper.WallpaperEvent
import org.cygnusx1.qlauncher.wallpaper.WallpaperType
import org.cygnusx1.qlauncher.wallpaper.source.DefaultProvider
import org.cygnusx1.qlauncher.wallpaper.source.WallpaperProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WallpaperControllerTest {

    private class FakeProvider(
        override val id: String,
        var items: List<Wallpaper>,
        override val updateMode: Int = UpdateMode.TIME_ELAPSED,
        override val cacheMillis: Long = 3_600_000L,
    ) : WallpaperProvider {
        val events = mutableListOf<WallpaperEvent>()
        override suspend fun getWallpapers(event: WallpaperEvent, params: String): List<Wallpaper> {
            events += event
            return items
        }
    }

    /** Loads everything except [failing]; [sizes] gives an original size for acceptance. */
    private class FakeLoader(
        val failing: Set<String> = emptySet(),
        val sizes: Map<String, Pair<Int, Int>> = emptyMap(),
    ) : WallpaperLoader {
        val loads = mutableListOf<String>()
        override suspend fun load(wallpaper: Wallpaper): Result<WallpaperContent> {
            loads += wallpaper.uri
            if (wallpaper.uri in failing) return Result.failure(RuntimeException("broken"))
            val (w, h) = sizes[wallpaper.uri] ?: (1920 to 1080)
            return Result.success(WallpaperContent.Video(wallpaper.uri, w, h))
        }
    }

    private class FakeLast(var stored: Wallpaper? = null) : LastWallpaperStore {
        val written = mutableListOf<Wallpaper>()
        override fun read(): Wallpaper? = stored
        override suspend fun write(wallpaper: Wallpaper) {
            written += wallpaper
            stored = wallpaper
        }
    }

    private fun w(name: String, type: WallpaperType = WallpaperType.IMAGE) = Wallpaper(uri = "https://example.org/$name.jpg", type = type)

    private fun config(provider: String = "p1", delay: Int = 60, wrongFormat: Int = 5, ignoreVideo: Boolean = true) = EngineConfig(
        providerId = provider,
        params = "",
        updateDelaySeconds = delay,
        ignoreUpdateOnVideo = ignoreVideo,
        displayMode = 0,
        wrongFormatMode = wrongFormat,
        minAcceptedWidth = 800,
    )

    private class Rig(
        val controller: WallpaperController,
        val config: MutableStateFlow<EngineConfig>,
        val loader: FakeLoader,
        val last: FakeLast,
    ) {
        val shown: String? get() = controller.wallpaper.value?.uri
    }

    private fun TestScope.rig(
        providers: List<WallpaperProvider>,
        cfg: EngineConfig = config(),
        loader: FakeLoader = FakeLoader(),
        last: FakeLast = FakeLast(),
    ): Rig {
        val all = (providers + DefaultProvider()).associateBy { it.id }
        val flow = MutableStateFlow(cfg)
        val controller = WallpaperController(
            scope = backgroundScope,
            config = flow,
            providers = { all[it] },
            loader = loader,
            clock = { testScheduler.currentTime },
            lastWallpaper = last,
            io = StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        return Rig(controller, flow, loader, last)
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun takesItemsInOrderAndWrapsRound() = runTest {
        val a = w("a"); val b = w("b"); val c = w("c")
        val r = rig(listOf(FakeProvider("p1", listOf(a, b, c))))
        assertEquals(a.uri, r.shown)
        r.controller.onStart()
        runCurrent()
        advance(60_000 + WallpaperController.EVENT_DEBOUNCE_MS)
        assertEquals(b.uri, r.shown)
        advance(60_000 + WallpaperController.EVENT_DEBOUNCE_MS)
        assertEquals(c.uri, r.shown)
        advance(60_000 + WallpaperController.EVENT_DEBOUNCE_MS)
        assertEquals(a.uri, r.shown)
        assertEquals(r.controller.items.value, listOf(a, b, c))
    }

    @Test
    fun nothingChangesBeforeTheDelay() = runTest {
        val r = rig(listOf(FakeProvider("p1", listOf(w("a"), w("b")))))
        r.controller.onStart()
        advance(59_000)
        assertEquals(w("a").uri, r.shown)
    }

    @Test
    fun changingProviderResetsTheIndex() = runTest {
        val a = w("a"); val b = w("b"); val c = w("c"); val x = w("x"); val y = w("y")
        val r = rig(listOf(FakeProvider("p1", listOf(a, b, c)), FakeProvider("p2", listOf(x, y))))
        r.controller.next()
        runCurrent()
        advance(1_000)
        assertEquals(b.uri, r.shown)
        r.config.value = config(provider = "p2")
        advance(1_000)
        assertEquals(x.uri, r.shown)
        r.config.value = config(provider = "p1")
        advance(1_000)
        assertEquals(a.uri, r.shown)
    }

    @Test
    fun failedLoadsMoveOnFiveTimesThenShowTheDefault() = runTest {
        val items = (1..8).map { w("f$it") }
        val provider = FakeProvider("p1", items)
        val loader = FakeLoader(failing = items.map { it.uri }.toSet())
        val r = rig(listOf(provider), loader = loader)
        // The first try and five retries, then the default wallpaper.
        assertEquals(items.take(6).map { it.uri } + Wallpaper.DEFAULT.uri, loader.loads)
        assertEquals(Wallpaper.DEFAULT.uri, r.shown)
        assertTrue(WallpaperEvent.LoadingError in provider.events)
    }

    @Test
    fun aFailedLoadTakesTheNextItem() = runTest {
        val items = listOf(w("bad1"), w("bad2"), w("good"))
        val loader = FakeLoader(failing = setOf(items[0].uri, items[1].uri))
        val r = rig(listOf(FakeProvider("p1", items)), loader = loader)
        assertEquals(items[2].uri, r.shown)
    }

    @Test
    fun wrongFormatWithSkipTakesTheNextItem() = runTest {
        val portrait = w("portrait"); val narrow = w("narrow"); val good = w("good")
        val loader = FakeLoader(sizes = mapOf(portrait.uri to (1080 to 1920), narrow.uri to (640 to 360)))
        val r = rig(listOf(FakeProvider("p1", listOf(portrait, narrow, good))), cfg = config(wrongFormat = Acceptance.WRONG_FORMAT_SKIP), loader = loader)
        assertEquals(good.uri, r.shown)
    }

    @Test
    fun wrongFormatWithAModeIsShown() = runTest {
        val portrait = w("portrait")
        val loader = FakeLoader(sizes = mapOf(portrait.uri to (1080 to 1920)))
        val r = rig(listOf(FakeProvider("p1", listOf(portrait, w("b")))), loader = loader)
        assertEquals(portrait.uri, r.shown)
    }

    @Test
    fun emptyListShowsTheDefault() = runTest {
        val r = rig(listOf(FakeProvider("p1", emptyList())))
        assertEquals(Wallpaper.DEFAULT.uri, r.shown)
    }

    @Test
    fun unknownProviderFallsBackToTheDefault() = runTest {
        val r = rig(emptyList(), cfg = config(provider = "gone"))
        assertEquals(Wallpaper.DEFAULT.uri, r.shown)
    }

    @Test
    fun lastWallpaperIsShownAtOnceAndKeptWhenStillListed() = runTest {
        val a = w("a"); val b = w("b"); val c = w("c")
        val last = FakeLast(b)
        val loader = FakeLoader()
        val r = rig(listOf(FakeProvider("p1", listOf(a, b, c))), loader = loader, last = last)
        assertEquals(b.uri, r.shown)
        assertEquals(listOf(b.uri), loader.loads)
        r.controller.onStart()
        advance(60_000 + WallpaperController.EVENT_DEBOUNCE_MS)
        assertEquals(c.uri, r.shown)
        assertEquals(c, last.stored)
    }

    @Test
    fun lastWallpaperNotInTheListIsReplaced() = runTest {
        val a = w("a")
        val r = rig(listOf(FakeProvider("p1", listOf(a))), last = FakeLast(w("old")))
        // The stored one first, then the provider's after the minimum interval.
        assertEquals(w("old").uri, r.shown)
        advance(WallpaperController.MIN_CHANGE_INTERVAL_MS)
        assertEquals(a.uri, r.shown)
    }

    @Test
    fun timerPausesWhileStoppedAndWaitsAtLeastFiveSecondsOnReturn() = runTest {
        val r = rig(listOf(FakeProvider("p1", listOf(w("a"), w("b"), w("c")))))
        r.controller.onStart()
        advance(30_000)
        r.controller.onStop()
        advance(100_000)
        assertEquals(w("a").uri, r.shown)
        // Back at 130 s: the 60 s deadline has passed, so the change comes 5 s later.
        r.controller.onStart()
        advance(4_900)
        assertEquals(w("a").uri, r.shown)
        advance(400)
        assertEquals(w("b").uri, r.shown)
    }

    @Test
    fun onReturnTheRemainingTimeIsKept() = runTest {
        val r = rig(listOf(FakeProvider("p1", listOf(w("a"), w("b")))))
        r.controller.onStart()
        advance(10_000)
        r.controller.onStop()
        advance(20_000)
        r.controller.onStart()
        // 30 s left of the first minute.
        advance(29_000)
        assertEquals(w("a").uri, r.shown)
        advance(1_300)
        assertEquals(w("b").uri, r.shown)
    }

    @Test
    fun zeroDelayNeverChanges() = runTest {
        val r = rig(listOf(FakeProvider("p1", listOf(w("a"), w("b")))), cfg = config(delay = 0))
        r.controller.onStart()
        advance(3_600_000)
        assertEquals(w("a").uri, r.shown)
    }

    @Test
    fun showDoesNotMoveTheRotation() = runTest {
        val a = w("a"); val b = w("b"); val z = w("z")
        val r = rig(listOf(FakeProvider("p1", listOf(a, b))))
        r.controller.onStart()
        advance(10_000)
        r.controller.show(z)
        advance(1_000)
        assertEquals(z.uri, r.shown)
        // The timer carries on from where it was: the next item after a.
        advance(49_200)
        assertEquals(b.uri, r.shown)
    }

    @Test
    fun changesAreAtLeast800MsApart() = runTest {
        val r = rig(listOf(FakeProvider("p1", listOf(w("a"), w("b"), w("c")))))
        r.controller.next()
        runCurrent()
        // "a" appeared at 0 ms: "b" waits for 800 ms.
        assertEquals(w("a").uri, r.shown)
        advance(800)
        assertEquals(w("b").uri, r.shown)
        r.controller.next()
        advance(700)
        assertEquals(w("b").uri, r.shown)
        advance(200)
        assertEquals(w("c").uri, r.shown)
    }

    @Test
    fun eventsAreFilteredByUpdateModeAndDebounced() = runTest {
        val provider = FakeProvider("dyn", listOf(Wallpaper.DYNAMIC_COLORS), updateMode = UpdateMode.CARD_FOCUSED, cacheMillis = 0)
        val r = rig(listOf(provider), cfg = config(provider = "dyn"))
        val before = provider.events.size
        r.controller.onEvent(WallpaperEvent.LauncherIdleModeChanged(true))
        r.controller.onEvent(WallpaperEvent.CardFocused("A", "a", 1, 2))
        advance(100)
        r.controller.onEvent(WallpaperEvent.CardFocused("B", "b", 3, 4))
        advance(150)
        assertEquals(before, provider.events.size)
        advance(100)
        assertEquals(listOf<WallpaperEvent>(WallpaperEvent.CardFocused("B", "b", 3, 4)), provider.events.drop(before))
        // No timer for an event-only provider.
        r.controller.onStart()
        advance(600_000)
        assertEquals(1, provider.events.size - before)
    }

    @Test
    fun timerIsIgnoredWhileAVideoPlaysAndItsEndBringsTheNext() = runTest {
        val v = w("v", WallpaperType.VIDEO); val b = w("b")
        val r = rig(listOf(FakeProvider("p1", listOf(v, b))))
        r.controller.onStart()
        advance(200_000)
        assertEquals(v.uri, r.shown)
        r.controller.onVideoEnded(v)
        advance(1_000)
        assertEquals(b.uri, r.shown)
    }

    @Test
    fun videoFailuresMoveOn() = runTest {
        val v = w("v", WallpaperType.VIDEO); val b = w("b")
        val r = rig(listOf(FakeProvider("p1", listOf(v, b))))
        r.controller.reportFailure(v)
        advance(1_000)
        assertEquals(b.uri, r.shown)
    }

    @Test
    fun theListIsCachedForTheProvidersCacheTime() = runTest {
        val provider = FakeProvider("p1", listOf(w("a"), w("b"), w("c")), cacheMillis = 90_000)
        val r = rig(listOf(provider))
        r.controller.onStart()
        advance(60_000 + WallpaperController.EVENT_DEBOUNCE_MS)
        assertEquals(1, provider.events.size)
        advance(60_000 + WallpaperController.EVENT_DEBOUNCE_MS)
        assertEquals(2, provider.events.size)
    }

    @Test
    fun invalidateRefetchesTheCurrentProvider() = runTest {
        val provider = FakeProvider("p1", listOf(w("a")))
        val r = rig(listOf(provider))
        provider.items = listOf(w("new"))
        r.controller.invalidate("other")
        advance(1_000)
        assertEquals(w("a").uri, r.shown)
        r.controller.invalidate("p1")
        advance(1_000)
        assertEquals(w("new").uri, r.shown)
        // A refreshed list that still holds the wallpaper on screen keeps it.
        provider.items = listOf(w("other"), w("new"))
        r.controller.invalidate("p1")
        advance(1_000)
        assertEquals(w("new").uri, r.shown)
        assertEquals(2, r.controller.items.value.size)
    }

    @Test
    fun invalidateDuringStartUpDoesNotLoseTheLastWallpaper() = runTest {
        val b = w("b")
        val provider = FakeProvider("p1", listOf(w("a"), b))
        val all = listOf(provider, DefaultProvider()).associateBy { it.id }
        val controller = WallpaperController(
            scope = backgroundScope,
            config = MutableStateFlow(config()),
            providers = { all[it] },
            loader = FakeLoader(),
            clock = { testScheduler.currentTime },
            lastWallpaper = FakeLast(b),
            io = StandardTestDispatcher(testScheduler),
        )
        // Posted before the start-up change has run.
        controller.invalidate("p1")
        advance(2_000)
        assertEquals(b.uri, controller.wallpaper.value?.uri)
    }

    @Test
    fun pendingProviderKeepsTheLastWallpaper() = runTest {
        val last = w("last")
        val plugin = FakeProvider("plugin", listOf(w("p")))
        val r = rig(listOf(plugin), cfg = config(provider = "plugin").copy(providerPending = true), last = FakeLast(last))
        assertEquals(last.uri, r.shown)
        assertTrue(plugin.events.isEmpty())
        r.config.value = config(provider = "plugin")
        advance(1_000)
        assertEquals(w("p").uri, r.shown)
    }

    @Test
    fun statusReportsTheCount() = runTest {
        val r = rig(listOf(FakeProvider("p1", listOf(w("a"), w("b")))))
        val s = r.controller.status.value["p1"]!!
        assertEquals(2, s.count)
        assertEquals(false, s.loading)
        assertNull(s.error)
        assertNull(r.controller.status.value[WallpaperProviders.REDDIT])
    }
}
