package com.adreels.skipper.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adreels.skipper.data.SkipperSettings
import com.adreels.skipper.data.SkipperSettingsRepository
import com.adreels.skipper.data.SkipperSettingsRepositoryProvider
import com.adreels.skipper.domain.KeywordValidationResult
import com.adreels.skipper.domain.KeywordValidator
import com.adreels.skipper.domain.SkipperConfig
import java.io.IOException
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SkipperAccessibilityServiceTest {

    private class RecordingDelayScheduler : DelayScheduler {
        var scheduledDelayMs: Long? = null
        var pendingAction: (() -> Unit)? = null
        var cancelCount: Int = 0

        override fun schedule(delayMs: Long, action: () -> Unit) {
            scheduledDelayMs = delayMs
            pendingAction = action
        }

        override fun cancelAll() {
            cancelCount++
            pendingAction = null
        }

        fun firePending() {
            val action = pendingAction ?: error("No pending skip action scheduled")
            pendingAction = null
            action.invoke()
        }
    }

    private class FakeSkipperSettingsRepository : SkipperSettingsRepository {
        private val mutableSettings = MutableStateFlow(SkipperSettings())
        override val settingsFlow: StateFlow<SkipperSettings> = mutableSettings.asStateFlow()
        var incrementException: Throwable? = null

        override suspend fun setMasterEnabled(enabled: Boolean) {
            mutableSettings.value = mutableSettings.value.copy(isMasterEnabled = enabled)
        }

        override suspend fun setSkipDelayMs(delayMs: Long) {
            mutableSettings.value = mutableSettings.value.copy(
                skipDelayMs = SkipperConfig.clampSkipDelay(delayMs),
            )
        }

        override suspend fun addCustomKeyword(rawKeyword: String): KeywordValidationResult {
            val result = KeywordValidator.validateCustomKeyword(
                raw = rawKeyword,
                existingCustomKeywords = mutableSettings.value.customKeywords,
            )
            if (result is KeywordValidationResult.Valid) {
                mutableSettings.value = mutableSettings.value.copy(
                    customKeywords = mutableSettings.value.customKeywords + result.trimmedKeyword,
                )
            }
            return result
        }

        override suspend fun removeCustomKeyword(keyword: String): Boolean {
            if (KeywordValidator.isBuiltInKeyword(keyword)) return false
            val current = mutableSettings.value.customKeywords
            val updated = current - keyword
            mutableSettings.value = mutableSettings.value.copy(customKeywords = updated)
            return updated.size != current.size
        }

        override suspend fun incrementSkippedReelsCount() {
            incrementException?.let { throw it }
            mutableSettings.value = mutableSettings.value.copy(
                skippedReelsCount = mutableSettings.value.skippedReelsCount + 1,
            )
        }
    }

    private lateinit var service: SkipperAccessibilityService
    private lateinit var scheduler: RecordingDelayScheduler
    private lateinit var settingsRepository: FakeSkipperSettingsRepository
    private var fakeClockMs: Long = 1_000L

    @Before
    fun setUp() {
        fakeClockMs = 1_000L
        scheduler = RecordingDelayScheduler()
        settingsRepository = FakeSkipperSettingsRepository()
        SkipperSettingsRepositoryProvider.setForTesting(settingsRepository)
        service = Robolectric.setupService(SkipperAccessibilityService::class.java).apply {
            clockMs = { fakeClockMs }
            delayScheduler = scheduler
            displayBoundsProvider = { 1080 to 2400 }
        }
    }

    @After
    fun tearDown() {
        SkipperSettingsRepositoryProvider.setForTesting(null)
    }

    private fun instagramEvent(packageName: CharSequence?): AccessibilityEvent {
        return AccessibilityEvent.obtain().apply {
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            this.packageName = packageName
        }
    }

    private fun node(
        text: String? = null,
        contentDescription: String? = null,
        visible: Boolean = true,
        selected: Boolean = false,
        clickable: Boolean = false,
    ): AccessibilityNodeInfo {
        return AccessibilityNodeInfo.obtain().apply {
            this.packageName = SkipperConfig.TARGET_PACKAGE
            this.text = text
            this.contentDescription = contentDescription
            this.isVisibleToUser = visible
            this.isSelected = selected
            this.isClickable = clickable
        }
    }

    private fun treeWith(vararg children: AccessibilityNodeInfo, packageName: String = SkipperConfig.TARGET_PACKAGE): AccessibilityNodeInfo {
        val root = AccessibilityNodeInfo.obtain().apply {
            this.packageName = packageName
            isVisibleToUser = true
        }
        val shadowRoot = Shadows.shadowOf(root)
        for (child in children) {
            shadowRoot.addChild(child)
        }
        return root
    }

    @Test
    fun `normalizes CharSequence package name and respects Master Toggle gate`() {
        service.activeRootProvider = {
            treeWith(
                node(text = "Reels", selected = true, clickable = true),
                node(text = "Sponsored"),
            )
        }

        service.onAccessibilityEvent(instagramEvent(StringBuilder("com.other.app")))
        assertNull(scheduler.pendingAction)

        service.configProvider = { SkipperConfig(isMasterEnabled = false) }
        service.onAccessibilityEvent(instagramEvent(StringBuilder("com.instagram.android")))
        assertNull(scheduler.pendingAction)

        service.configProvider = { SkipperConfig(isMasterEnabled = true) }
        service.onAccessibilityEvent(instagramEvent(StringBuilder("com.instagram.android")))
        assertNotNull(scheduler.pendingAction)
        assertEquals(400L, scheduler.scheduledDelayMs)
    }

    @Test
    fun `dispatches single upward swipe gesture after 400 ms delay on sponsored Reel`() {
        service.activeRootProvider = {
            treeWith(
                node(text = "Reels", selected = true, clickable = true),
                node(text = "Sponsored"),
            )
        }

        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertEquals(400L, scheduler.scheduledDelayMs)

        val shadowService = Shadows.shadowOf(service as AccessibilityService)
        assertTrue(shadowService.gesturesDispatched.isEmpty())

        fakeClockMs += 400L
        scheduler.firePending()

        assertEquals(1, shadowService.gesturesDispatched.size)
        val dispatched = shadowService.gesturesDispatched.single()
        val gesture = dispatched.description()
        assertEquals(1, gesture.strokeCount)
        val stroke = gesture.getStroke(0)
        assertEquals(200L, stroke.duration)
        val strokePoints = Shadows.shadowOf(stroke.path).points
        assertEquals(2, strokePoints.size)
        assertEquals(strokePoints[0].x, strokePoints[1].x)
        assertTrue(strokePoints[0].y > strokePoints[1].y)
        dispatched.callback()?.onCompleted(gesture)

        fakeClockMs += 500L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNull(scheduler.pendingAction)
    }

    @Test
    fun `cancels pending skip without swiping when ad disappears before 400 ms delay`() {
        var isSponsored = true
        service.activeRootProvider = {
            if (isSponsored) {
                treeWith(
                    node(text = "Reels", selected = true, clickable = true),
                    node(text = "Sponsorlu"),
                )
            } else {
                treeWith(
                    node(text = "Reels", selected = true, clickable = true),
                    node(text = "Organic cooking reel"),
                )
            }
        }

        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)

        fakeClockMs += 200L
        isSponsored = false
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))

        assertNull(scheduler.pendingAction)
        val shadowService = Shadows.shadowOf(service as AccessibilityService)
        assertTrue(shadowService.gesturesDispatched.isEmpty())
    }

    @Test
    fun `suppresses extraction during throttle, in-flight gesture window, and 1200 ms cooldown`() {
        var rootFetches = 0
        var showSponsored = true
        service.activeRootProvider = {
            rootFetches++
            if (showSponsored) {
                treeWith(
                    node(text = "Reels", selected = true, clickable = true),
                    node(text = "Sponsored"),
                )
            } else {
                treeWith(
                    node(text = "Reels", selected = true, clickable = true),
                    node(text = "Organic reel"),
                )
            }
        }

        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertEquals(1, rootFetches)
        assertNotNull(scheduler.pendingAction)

        // Rapid event inside 150 ms throttle window must skip Binder tree extraction.
        fakeClockMs += 50L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertEquals(1, rootFetches)

        // Fire delayed skip at +400 ms (total 1_400 ms). Gesture 1 is now in flight.
        fakeClockMs = 1_400L
        scheduler.firePending()
        assertEquals(2, rootFetches)
        val shadowService = Shadows.shadowOf(service as AccessibilityService)
        assertEquals(1, shadowService.gesturesDispatched.size)

        // Mid-swipe scroll events (organic then next ad) arrive before onCompleted must not extract or schedule.
        showSponsored = false
        fakeClockMs = 1_460L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        showSponsored = true
        fakeClockMs = 1_520L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertEquals(2, rootFetches)
        assertNull(scheduler.pendingAction)

        // Gesture 1 completes at 1_600 ms -> starts 1200 ms cooldown until 2_800 ms.
        fakeClockMs = 1_600L
        val dispatched = shadowService.gesturesDispatched.single()
        dispatched.callback()?.onCompleted(dispatched.description())

        fakeClockMs = 2_799L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertEquals(2, rootFetches)
        assertNull(scheduler.pendingAction)

        fakeClockMs = 2_800L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertEquals(3, rootFetches)
        assertNotNull(scheduler.pendingAction)
    }

    @Test
    fun `caps visible node extraction at 500, skips invisible nodes, and verifies swipe geometry`() {
        val extractor = AccessibilityTreeExtractor(maxVisibleNodes = 500)
        val children = arrayOf(
            node(text = "Sponsored", visible = false),
            node(text = "Reels", contentDescription = "Reels", visible = false, selected = true),
        ) + Array(550) { idx -> node(text = "item-$idx", visible = true) }
        val bigRoot = treeWith(*children)

        val snapshot = extractor.extract(SkipperConfig.TARGET_PACKAGE, bigRoot)
        assertEquals(500, snapshot.nodes.size)
        assertTrue(snapshot.nodes.all { it.isVisibleToUser })
        assertTrue(snapshot.nodes.none { it.text == "Sponsored" || it.text == "Reels" })
        assertTrue(extractor.extract(SkipperConfig.TARGET_PACKAGE, null).nodes.isEmpty())

        val dispatcher = SwipeGestureDispatcher()
        val spec = dispatcher.calculateSwipeSpec(screenWidth = 1080, screenHeight = 2400)
        assertNotNull(spec)
        assertEquals(540f, spec.centerX)
        assertEquals(1800f, spec.startY)
        assertEquals(480f, spec.endY)
        assertEquals(200L, spec.durationMs)
        assertNull(dispatcher.calculateSwipeSpec(screenWidth = 0, screenHeight = 2400))
        assertNull(dispatcher.calculateSwipeSpec(screenWidth = 1080, screenHeight = 0))
    }

    @Test
    fun `recovers fail-safely when root is null bounds are invalid or gesture dispatch fails`() {
        val dispatcher = SwipeGestureDispatcher()
        var failedCount = 0
        var completedCount = 0

        assertFalse(
            dispatcher.dispatchUpwardSwipe(
                service = service,
                screenWidth = 0,
                screenHeight = 2400,
                onCompleted = { completedCount++ },
                onFailed = { failedCount++ },
            ),
        )
        assertEquals(1, failedCount)
        assertEquals(0, completedCount)

        val shadowService = Shadows.shadowOf(service as AccessibilityService)
        shadowService.setCanDispatchGestures(false)
        assertFalse(
            dispatcher.dispatchUpwardSwipe(
                service = service,
                screenWidth = 1080,
                screenHeight = 2400,
                onCompleted = { completedCount++ },
                onFailed = { failedCount++ },
            ),
        )
        assertEquals(2, failedCount)
        assertEquals(0, completedCount)

        service.activeRootProvider = {
            treeWith(
                node(text = "Reels", selected = true, clickable = true),
                node(text = "Sponsored"),
            )
        }
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)

        service.activeRootProvider = { null }
        fakeClockMs += 400L
        scheduler.firePending()
        assertTrue(shadowService.gesturesDispatched.isEmpty())

        service.activeRootProvider = { throw IllegalStateException("Window binder died") }
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
    }

    @Test
    fun `aborts pre-dispatch on stale freshRoot, recovers from cancelled swipe, and resets on lifecycle callbacks`() {
        var rootFetches = 0
        service.activeRootProvider = {
            rootFetches++
            null
        }
        service.onAccessibilityEvent(null)
        service.onAccessibilityEvent(instagramEvent(null))
        assertEquals(0, rootFetches)

        // 1. Pre-dispatch abort when window flips to organic Reel before timer fires (no intermediate event).
        var phase = "ad"
        service.activeRootProvider = {
            when (phase) {
                "ad" -> treeWith(node(text = "Reels", selected = true, clickable = true), node(text = "Sponsored"))
                "other_pkg" -> treeWith(
                    node(text = "Reels", selected = true, clickable = true),
                    node(text = "Sponsored"),
                    packageName = "com.android.launcher",
                )
                else -> treeWith(node(text = "Reels", selected = true, clickable = true), node(text = "Organic"))
            }
        }
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)
        phase = "organic"
        fakeClockMs += 400L
        scheduler.firePending()
        val shadowService = Shadows.shadowOf(service as AccessibilityService)
        assertTrue(shadowService.gesturesDispatched.isEmpty())

        // 2. Pre-dispatch abort when window package leaves Instagram.
        phase = "ad"
        fakeClockMs += 200L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)
        phase = "other_pkg"
        fakeClockMs += 400L
        scheduler.firePending()
        assertTrue(shadowService.gesturesDispatched.isEmpty())

        // 3. Dispatched swipe cancelled by OS recovers cleanly via onSkipFailed().
        phase = "ad"
        fakeClockMs += 200L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)
        fakeClockMs += 400L
        scheduler.firePending()
        assertEquals(1, shadowService.gesturesDispatched.size)
        val firstDispatch = shadowService.gesturesDispatched.single()
        firstDispatch.callback()?.onCancelled(firstDispatch.description())

        // Should not wait 1200 ms cooldown after cancellation; resumes once throttle passes.
        fakeClockMs += 200L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)

        // 4. onInterrupt() and onUnbind() cancel pending skip and reset detector state.
        service.onInterrupt()
        assertNull(scheduler.pendingAction)

        fakeClockMs += 200L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)
        service.onUnbind(null)
        assertNull(scheduler.pendingAction)
    }

    @Test
    @Config(sdk = [33])
    fun `recycles root and child accessibility nodes safely on API 33`() {
        service.activeRootProvider = {
            val container = node(text = "Container").also { containerNode ->
                Shadows.shadowOf(containerNode).addChild(node(text = "Sponsored"))
            }
            treeWith(
                node(text = "Reels", selected = true, clickable = true),
                container,
            )
        }

        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)

        fakeClockMs += 400L
        scheduler.firePending()

        val shadowService = Shadows.shadowOf(service as AccessibilityService)
        assertEquals(1, shadowService.gesturesDispatched.size)
        val dispatched = shadowService.gesturesDispatched.single()
        dispatched.callback()?.onCompleted(dispatched.description())

        fakeClockMs += 1_200L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)
    }

    @Test
    fun `observes live repository config updates and increments skip counter only on completed swipe`() = runBlocking {
        service.startObservingSettings()

        var phase = "custom_ad"
        service.activeRootProvider = {
            when (phase) {
                "custom_ad" -> treeWith(
                    node(text = "Reels", selected = true, clickable = true),
                    node(text = "Reklam"),
                )
                else -> treeWith(
                    node(text = "Reels", selected = true, clickable = true),
                    node(text = "Organic content"),
                )
            }
        }

        // Default keywords do not match "Reklam" until the custom keyword is saved.
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNull(scheduler.pendingAction)

        assertEquals(KeywordValidationResult.Valid("Reklam"), settingsRepository.addCustomKeyword("Reklam"))
        settingsRepository.setSkipDelayMs(650L)

        fakeClockMs += 200L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)
        assertEquals(650L, scheduler.scheduledDelayMs)

        // Turning Master Toggle OFF cancels pending skip immediately via Flow collection.
        settingsRepository.setMasterEnabled(false)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(scheduler.pendingAction)

        // Re-enable toggle and complete one upward swipe -> counter increments to 1.
        settingsRepository.setMasterEnabled(true)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        fakeClockMs += 200L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)

        fakeClockMs += 650L
        scheduler.firePending()
        val shadowService = Shadows.shadowOf(service as AccessibilityService)
        assertEquals(1, shadowService.gesturesDispatched.size)
        val firstGesture = shadowService.gesturesDispatched[0]
        firstGesture.callback()?.onCompleted(firstGesture.description())
        assertEquals(1, settingsRepository.settingsFlow.value.skippedReelsCount)

        // Cancelled swipe by OS must NOT increment counter.
        fakeClockMs += 1_200L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)
        fakeClockMs += 650L
        scheduler.firePending()
        assertEquals(2, shadowService.gesturesDispatched.size)
        val cancelledGesture = shadowService.gesturesDispatched[1]
        cancelledGesture.callback()?.onCancelled(cancelledGesture.description())
        assertEquals(1, settingsRepository.settingsFlow.value.skippedReelsCount)

        // Pre-dispatch abort when window becomes organic must NOT increment counter.
        fakeClockMs += 200L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)
        phase = "organic"
        fakeClockMs += 650L
        scheduler.firePending()
        assertEquals(2, shadowService.gesturesDispatched.size)
        assertEquals(1, settingsRepository.settingsFlow.value.skippedReelsCount)
    }

    @Test
    fun `survives IOException when persisting completed skip count`() = runBlocking {
        val uncaughtExceptions = Collections.synchronizedList(mutableListOf<Throwable>())
        val currentThread = Thread.currentThread()
        val previousThreadHandler = currentThread.uncaughtExceptionHandler
        val previousDefaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        val recordingHandler = Thread.UncaughtExceptionHandler { _, throwable ->
            uncaughtExceptions.add(throwable)
        }
        currentThread.uncaughtExceptionHandler = recordingHandler
        Thread.setDefaultUncaughtExceptionHandler(recordingHandler)

        try {
            settingsRepository.incrementException = IOException("Simulated disk full")
            service.activeRootProvider = {
                treeWith(
                    node(text = "Reels", selected = true, clickable = true),
                    node(text = "Sponsored"),
                )
            }

            service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
            assertNotNull(scheduler.pendingAction)
            fakeClockMs += 400L
            scheduler.firePending()

            val shadowService = Shadows.shadowOf(service as AccessibilityService)
            assertEquals(1, shadowService.gesturesDispatched.size)
            val firstGesture = shadowService.gesturesDispatched[0]
            firstGesture.callback()?.onCompleted(firstGesture.description())
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(
                uncaughtExceptions.isEmpty(),
                "Expected IOException to be caught inside serviceScope, but got uncaught: $uncaughtExceptions",
            )
            assertEquals(0, settingsRepository.settingsFlow.value.skippedReelsCount)

            // Clear simulated I/O error and verify service still processes subsequent sponsored reels.
            settingsRepository.incrementException = null
            fakeClockMs += 1_200L
            service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
            assertNotNull(scheduler.pendingAction)
            fakeClockMs += 400L
            scheduler.firePending()
            assertEquals(2, shadowService.gesturesDispatched.size)
            val secondGesture = shadowService.gesturesDispatched[1]
            secondGesture.callback()?.onCompleted(secondGesture.description())
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(
                uncaughtExceptions.isEmpty(),
                "Expected zero uncaught exceptions after recovery, but got: $uncaughtExceptions",
            )
            assertEquals(1, settingsRepository.settingsFlow.value.skippedReelsCount)
        } finally {
            currentThread.uncaughtExceptionHandler = previousThreadHandler
            Thread.setDefaultUncaughtExceptionHandler(previousDefaultHandler)
        }
    }
}
