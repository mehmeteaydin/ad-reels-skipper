package com.adreels.skipper.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adreels.skipper.domain.SkipperConfig
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

    private lateinit var service: SkipperAccessibilityService
    private lateinit var scheduler: RecordingDelayScheduler
    private var fakeClockMs: Long = 1_000L

    @Before
    fun setUp() {
        fakeClockMs = 1_000L
        scheduler = RecordingDelayScheduler()
        service = Robolectric.setupService(SkipperAccessibilityService::class.java).apply {
            clockMs = { fakeClockMs }
            delayScheduler = scheduler
            displayBoundsProvider = { 1080 to 2400 }
        }
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
        assertEquals(200L, gesture.getStroke(0).duration)
        dispatched.callback()?.onCompleted(gesture)

        fakeClockMs += 500L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNull(scheduler.pendingAction)
    }

    @Test
    fun `cancels pending skip without swiping when ad disappears before 400 ms delay`() {
        var currentRoot = treeWith(
            node(text = "Reels", selected = true, clickable = true),
            node(text = "Sponsorlu"),
        )
        service.activeRootProvider = { currentRoot }

        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)

        fakeClockMs += 200L
        currentRoot = treeWith(
            node(text = "Reels", selected = true, clickable = true),
            node(text = "Organic cooking reel"),
        )
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))

        assertNull(scheduler.pendingAction)
        val shadowService = Shadows.shadowOf(service as AccessibilityService)
        assertTrue(shadowService.gesturesDispatched.isEmpty())
    }

    @Test
    fun `caps visible node extraction at 500 and verifies swipe geometry`() {
        val extractor = AccessibilityTreeExtractor(maxVisibleNodes = 500)
        val children = Array(550) { idx -> node(text = "item-$idx") }
        val bigRoot = treeWith(*children)

        val snapshot = extractor.extract(SkipperConfig.TARGET_PACKAGE, bigRoot)
        assertEquals(500, snapshot.nodes.size)
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
}
