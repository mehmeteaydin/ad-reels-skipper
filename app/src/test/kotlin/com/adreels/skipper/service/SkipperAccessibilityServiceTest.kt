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

    @Suppress("DEPRECATION")
    private fun instagramEvent(packageName: CharSequence?): AccessibilityEvent {
        return AccessibilityEvent.obtain().apply {
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            this.packageName = packageName
        }
    }

    @Suppress("DEPRECATION")
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

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private class TrackingNode(
        private val packageNameValue: CharSequence? = SkipperConfig.TARGET_PACKAGE,
        private val textValue: CharSequence? = null,
        private val contentDescriptionValue: CharSequence? = null,
        private val visibleValue: Boolean = true,
        private val selectedValue: Boolean = false,
        private val clickableValue: Boolean = false,
        private val throwOnPackageName: Boolean = false,
    ) : AccessibilityNodeInfo() {
        private val childNodes = mutableListOf<AccessibilityNodeInfo>()
        var childQueries: Int = 0
            private set
        var recycleCalls: Int = 0
            private set

        fun addTrackedChild(vararg children: AccessibilityNodeInfo): TrackingNode {
            childNodes.addAll(children)
            return this
        }

        override fun getPackageName(): CharSequence? {
            check(!throwOnPackageName) { "Dead binder" }
            return packageNameValue
        }

        override fun getText(): CharSequence? = textValue
        override fun getContentDescription(): CharSequence? = contentDescriptionValue
        override fun isVisibleToUser(): Boolean = visibleValue
        override fun isSelected(): Boolean = selectedValue
        override fun isClickable(): Boolean = clickableValue

        override fun getChildCount(): Int {
            childQueries++
            return childNodes.size
        }

        override fun getChild(index: Int): AccessibilityNodeInfo? {
            childQueries++
            return childNodes.getOrNull(index)
        }

        override fun recycle() {
            recycleCalls++
        }
    }

    @Suppress("DEPRECATION")
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
        assertEquals(150L, scheduler.scheduledDelayMs)
    }

    @Test
    fun `dispatches single upward flick swipe after 150 ms delay on sponsored Reel`() {
        service.activeRootProvider = {
            treeWith(
                node(text = "Reels", selected = true, clickable = true),
                node(text = "Sponsored"),
            )
        }

        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertEquals(150L, scheduler.scheduledDelayMs)

        val shadowService = Shadows.shadowOf(service as AccessibilityService)
        assertTrue(shadowService.gesturesDispatched.isEmpty())

        fakeClockMs += 150L
        scheduler.firePending()

        assertEquals(1, shadowService.gesturesDispatched.size)
        val dispatched = shadowService.gesturesDispatched.single()
        val gesture = dispatched.description()
        assertEquals(1, gesture.strokeCount)
        val stroke = gesture.getStroke(0)
        assertEquals(150L, stroke.duration)
        val strokePoints = Shadows.shadowOf(stroke.path).points
        assertEquals(2, strokePoints.size)
        assertEquals(594f, strokePoints[0].x, absoluteTolerance = 0.01f)
        assertEquals(strokePoints[0].x, strokePoints[1].x)
        assertEquals(1728f, strokePoints[0].y, absoluteTolerance = 0.01f)
        assertEquals(480f, strokePoints[1].y, absoluteTolerance = 0.01f)
        dispatched.callback()?.onCompleted(gesture)

        fakeClockMs += 500L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNull(scheduler.pendingAction)
    }

    @Test
    fun `suppresses intermediate Instagram events while skip is pending and cancels on app switch`() {
        var rootFetches = 0
        service.activeRootProvider = {
            rootFetches++
            treeWith(
                node(text = "Reels", selected = true, clickable = true),
                node(text = "Sponsorlu"),
            )
        }

        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertEquals(1, rootFetches)
        assertNotNull(scheduler.pendingAction)

        // Intermediate Instagram events while skip is pending skip Binder IPC extraction.
        fakeClockMs += 130L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertEquals(1, rootFetches)
        assertNotNull(scheduler.pendingAction)

        // Leaving Instagram cancels pending skip immediately without swiping.
        fakeClockMs += 10L
        service.onAccessibilityEvent(instagramEvent("com.android.launcher"))
        assertNull(scheduler.pendingAction)
        assertEquals(1, rootFetches)
        val shadowService = Shadows.shadowOf(service as AccessibilityService)
        assertTrue(shadowService.gesturesDispatched.isEmpty())
    }

    @Test
    fun `suppresses extraction during pending skip, throttle, in-flight gesture window, and 700 ms cooldown`() {
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

        // Rapid event inside 120 ms throttle window must skip Binder tree extraction.
        fakeClockMs += 50L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertEquals(1, rootFetches)

        // Fire delayed skip at +150 ms (total 1_150 ms). Gesture 1 is now in flight.
        fakeClockMs = 1_150L
        scheduler.firePending()
        assertEquals(2, rootFetches)
        val shadowService = Shadows.shadowOf(service as AccessibilityService)
        assertEquals(1, shadowService.gesturesDispatched.size)

        // Mid-swipe scroll events (organic then next ad) arrive before onCompleted must not extract or schedule.
        showSponsored = false
        fakeClockMs = 1_180L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        showSponsored = true
        fakeClockMs = 1_240L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertEquals(2, rootFetches)
        assertNull(scheduler.pendingAction)

        // Gesture 1 completes at 1_250 ms -> starts 700 ms cooldown until 1_950 ms.
        fakeClockMs = 1_250L
        val dispatched = shadowService.gesturesDispatched.single()
        dispatched.callback()?.onCompleted(dispatched.description())

        fakeClockMs = 1_949L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertEquals(2, rootFetches)
        assertNull(scheduler.pendingAction)

        fakeClockMs = 1_950L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertEquals(3, rootFetches)
        assertNotNull(scheduler.pendingAction)
    }

    @Test
    fun `caps visible labeled extraction at 160, skips unlabeled containers, caps total visited at 2000, stops early, and verifies swipe geometry`() {
        val extractor = AccessibilityTreeExtractor(maxVisibleNodes = 160)
        val children = arrayOf(
            node(text = "Sponsored", visible = false),
            node(text = "Reels", contentDescription = "Reels", visible = false, selected = true),
        ) + Array(200) { idx -> node(text = "item-$idx", visible = true) }
        val bigRoot = treeWith(*children)

        val snapshot = extractor.extract(SkipperConfig.TARGET_PACKAGE, bigRoot)
        assertEquals(160, snapshot.nodes.size)
        assertTrue(snapshot.nodes.all { it.isVisibleToUser })
        assertTrue(snapshot.nodes.none { it.text == "Sponsored" || it.text == "Reels" })
        assertTrue(extractor.extract(SkipperConfig.TARGET_PACKAGE, null).nodes.isEmpty())

        // Regression test: 250+ visible unlabeled/whitespace layout containers must NOT exhaust the 160-node budget.
        val unlabeledWrappers = Array(200) { node(text = null, contentDescription = null, visible = true) } +
            Array(50) { node(text = "   ", contentDescription = "", visible = true) }
        val deepWrapper = node(text = null, contentDescription = null, visible = true).also { wrapper ->
            Shadows.shadowOf(wrapper).addChild(node(text = "Sponsorlu", visible = true))
        }
        val unlabeledHeavyTree = treeWith(
            *unlabeledWrappers,
            node(text = "Reels", selected = true, clickable = true, visible = true),
            deepWrapper,
        )
        val unlabeledHeavySnapshot = AccessibilityTreeExtractor().extract(SkipperConfig.TARGET_PACKAGE, unlabeledHeavyTree)
        assertEquals(listOf("Reels", "Sponsorlu"), unlabeledHeavySnapshot.nodes.map { it.text })

        service.activeRootProvider = {
            treeWith(
                *Array(250) { node(text = null, contentDescription = null, visible = true) },
                node(text = "Reels", selected = true, clickable = true, visible = true),
                node(text = "Sponsorlu", visible = true),
            )
        }
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertEquals(150L, scheduler.scheduledDelayMs)
        assertNotNull(scheduler.pendingAction)

        // Early exit stops immediately once Reels context and a matching keyword are both observed.
        val earlyExitTree = treeWith(
            node(text = "Reels", selected = true, clickable = true),
            node(text = "Sponsored"),
            *Array(50) { idx -> node(text = "trailing-node-$idx", visible = true) },
        )
        val earlySnapshot = AccessibilityTreeExtractor().extract(SkipperConfig.TARGET_PACKAGE, earlyExitTree)
        assertEquals(2, earlySnapshot.nodes.size)

        // Total visited cap (2000): root + 1997 hidden + Reels + Sponsored = 2000 collects Sponsored;
        // 1998 hidden stops at Reels (2000th visit) before reaching Sponsored (2001st).
        val exactCapChildren = Array(1997) { idx -> node(text = "hidden-$idx", visible = false) } +
            arrayOf(
                node(text = "Reels", selected = true, clickable = true, visible = true),
                node(text = "Sponsored", visible = true),
            )
        val exactCapSnapshot = AccessibilityTreeExtractor().extract(SkipperConfig.TARGET_PACKAGE, treeWith(*exactCapChildren))
        assertTrue(exactCapSnapshot.nodes.any { it.text == "Sponsored" })

        val nodeAt2000 = TrackingNode(textValue = "Reels", selectedValue = true, clickableValue = true).addTrackedChild(
            TrackingNode(textValue = "Unqueried child at 2000 cap"),
        )
        val overCapRoot = TrackingNode(textValue = "root").addTrackedChild(
            *Array<AccessibilityNodeInfo>(1998) { idx -> node(text = "hidden-$idx", visible = false) },
            nodeAt2000,
            node(text = "Sponsored", visible = true),
        )
        val overCapSnapshot = AccessibilityTreeExtractor().extract(SkipperConfig.TARGET_PACKAGE, overCapRoot)
        assertTrue(overCapSnapshot.nodes.any { it.text == "Reels" })
        assertTrue(overCapSnapshot.nodes.none { it.text == "Sponsored" })
        assertEquals(0, nodeAt2000.childQueries)

        val dispatcher = SwipeGestureDispatcher()
        val spec = dispatcher.calculateSwipeSpec(screenWidth = 1080, screenHeight = 2400)
        assertNotNull(spec)
        assertEquals(594f, spec.centerX, absoluteTolerance = 0.01f)
        assertEquals(1728f, spec.startY, absoluteTolerance = 0.01f)
        assertEquals(480f, spec.endY, absoluteTolerance = 0.01f)
        assertEquals(150L, spec.durationMs)
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
        fakeClockMs += 150L
        scheduler.firePending()
        assertTrue(shadowService.gesturesDispatched.isEmpty())

        service.activeRootProvider = { throw IllegalStateException("Window binder died") }
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))

        // Non-target freshRoot package must abort before traversing children during executeScheduledSkip().
        val launcherRoot = TrackingNode(packageNameValue = "com.android.launcher").addTrackedChild(
            TrackingNode(textValue = "Reels", selectedValue = true, clickableValue = true),
            TrackingNode(textValue = "Sponsored"),
        )
        var phase = "ad"
        service.activeRootProvider = {
            when (phase) {
                "launcher" -> launcherRoot
                "throwing" -> TrackingNode(throwOnPackageName = true)
                else -> treeWith(node(text = "Reels", selected = true, clickable = true), node(text = "Sponsored"))
            }
        }
        fakeClockMs += 200L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)
        phase = "launcher"
        fakeClockMs += 150L
        scheduler.firePending()
        assertEquals(0, launcherRoot.childQueries)
        assertTrue(shadowService.gesturesDispatched.isEmpty())

        // Stale binder throwing from freshRoot.packageName must abort safely without crashing.
        phase = "ad"
        fakeClockMs += 200L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)
        phase = "throwing"
        fakeClockMs += 150L
        scheduler.firePending()
        assertTrue(shadowService.gesturesDispatched.isEmpty())
    }

    @Test
    fun `aborts pre-dispatch on stale freshRoot across default delay, recovers from cancelled swipe, and resets on lifecycle callbacks`() {
        var rootFetches = 0
        service.activeRootProvider = {
            rootFetches++
            null
        }
        service.onAccessibilityEvent(null)
        service.onAccessibilityEvent(instagramEvent(null))
        assertEquals(0, rootFetches)

        // 1. Default 150 ms delay performs early-exit Reels verification and aborts if Reel turned organic,
        //    switched to comments/non-Reels screen inside Instagram, or left Instagram before dispatch.
        var phase = "ad"
        service.activeRootProvider = {
            when (phase) {
                "ad" -> treeWith(node(text = "Reels", selected = true, clickable = true), node(text = "Sponsored"))
                "other_pkg" -> treeWith(
                    node(text = "Reels", selected = true, clickable = true),
                    node(text = "Sponsored"),
                    packageName = "com.android.launcher",
                )
                "non_reels_screen" -> treeWith(
                    node(text = "Home", visible = true),
                    node(text = "Sponsored"),
                    packageName = SkipperConfig.TARGET_PACKAGE,
                )
                else -> treeWith(node(text = "Reels", selected = true, clickable = true), node(text = "Organic"))
            }
        }
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)
        phase = "organic"
        fakeClockMs += 150L
        scheduler.firePending()
        val shadowService = Shadows.shadowOf(service as AccessibilityService)
        assertTrue(shadowService.gesturesDispatched.isEmpty())

        phase = "ad"
        fakeClockMs += 150L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)
        phase = "non_reels_screen"
        fakeClockMs += 150L
        scheduler.firePending()
        assertTrue(shadowService.gesturesDispatched.isEmpty())

        // 2. Abort when root package leaves Instagram before timer fires.
        phase = "ad"
        fakeClockMs += 150L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)
        phase = "other_pkg"
        fakeClockMs += 150L
        scheduler.firePending()
        assertTrue(shadowService.gesturesDispatched.isEmpty())

        // 3. Dispatched swipe when Reels ad remains present, cancelled by OS, recovers cleanly via onSkipFailed().
        phase = "ad"
        fakeClockMs += 150L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)
        fakeClockMs += 150L
        scheduler.firePending()
        assertEquals(1, shadowService.gesturesDispatched.size)
        val firstDispatch = shadowService.gesturesDispatched.single()
        firstDispatch.callback()?.onCancelled(firstDispatch.description())

        // Should not wait 700 ms cooldown after cancellation; resumes once 180 ms throttle passes.
        fakeClockMs += 150L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)

        // 4. onInterrupt() and onUnbind() cancel pending skip and reset detector state.
        service.onInterrupt()
        assertNull(scheduler.pendingAction)

        fakeClockMs += 150L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)
        service.onUnbind(null)
        assertNull(scheduler.pendingAction)
    }

    @Test
    @Config(sdk = [33])
    fun `recycles root, child, and early-exit queued accessibility nodes safely on API 33`() {
        val extractorReels = TrackingNode(textValue = "Reels", selectedValue = true, clickableValue = true)
        val extractorUnlabeled = TrackingNode(textValue = null, contentDescriptionValue = null)
        val extractorSponsored = TrackingNode(textValue = "Sponsored").addTrackedChild(
            TrackingNode(textValue = "Never queried child"),
        )
        val extractorSibling1 = TrackingNode(textValue = "Queued sibling 1")
        val extractorSibling2 = TrackingNode(textValue = "Queued sibling 2")
        val earlyTree = TrackingNode(textValue = "root").addTrackedChild(
            extractorReels,
            extractorUnlabeled,
            extractorSponsored,
            extractorSibling1,
            extractorSibling2,
        )
        val earlySnapshot = AccessibilityTreeExtractor().extract(SkipperConfig.TARGET_PACKAGE, earlyTree)
        assertEquals(3, earlySnapshot.nodes.size)
        assertEquals(0, earlyTree.recycleCalls)
        assertEquals(1, extractorReels.recycleCalls)
        assertEquals(1, extractorUnlabeled.recycleCalls)
        assertEquals(1, extractorSponsored.recycleCalls)
        assertEquals(0, extractorSponsored.childQueries)
        assertEquals(1, extractorSibling1.recycleCalls)
        assertEquals(1, extractorSibling2.recycleCalls)

        val capturedNodes = mutableListOf<TrackingNode>()
        service.activeRootProvider = {
            val reels = TrackingNode(textValue = "Reels", selectedValue = true, clickableValue = true)
            val sponsored = TrackingNode(textValue = "Sponsored")
            val unlabeledContainer = TrackingNode(textValue = null, contentDescriptionValue = null).addTrackedChild(sponsored)
            val trailing = TrackingNode(textValue = "Remaining trailing node")
            val root = TrackingNode(textValue = "root").addTrackedChild(reels, unlabeledContainer, trailing)
            capturedNodes.addAll(listOf(root, reels, unlabeledContainer, sponsored, trailing))
            root
        }

        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)
        assertEquals(5, capturedNodes.size)
        assertTrue(capturedNodes.all { it.recycleCalls == 1 })

        fakeClockMs += 150L
        scheduler.firePending()
        assertEquals(10, capturedNodes.size)
        assertTrue(capturedNodes.all { it.recycleCalls == 1 })

        val shadowService = Shadows.shadowOf(service as AccessibilityService)
        assertEquals(1, shadowService.gesturesDispatched.size)
        val dispatched = shadowService.gesturesDispatched.single()
        dispatched.callback()?.onCompleted(dispatched.description())

        fakeClockMs += 700L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)
        assertEquals(15, capturedNodes.size)
        assertTrue(capturedNodes.all { it.recycleCalls == 1 })
    }

    @Test
    fun `does not invoke deprecated recycle on API 34+`() {
        val childReels = TrackingNode(textValue = "Reels", selectedValue = true, clickableValue = true)
        val childSponsored = TrackingNode(textValue = "Sponsored")
        val childTrailing = TrackingNode(textValue = "Trailing")
        val rootTree = TrackingNode(textValue = "root").addTrackedChild(childReels, childSponsored, childTrailing)

        val snapshot = AccessibilityTreeExtractor().extract(SkipperConfig.TARGET_PACKAGE, rootTree)
        assertEquals(3, snapshot.nodes.size)
        assertTrue(listOf(rootTree, childReels, childSponsored, childTrailing).all { it.recycleCalls == 0 })

        val serviceNodes = mutableListOf<TrackingNode>()
        service.activeRootProvider = {
            val reels = TrackingNode(textValue = "Reels", selectedValue = true, clickableValue = true)
            val sponsored = TrackingNode(textValue = "Sponsored")
            val trailing = TrackingNode(textValue = "Trailing")
            val root = TrackingNode(textValue = "root").addTrackedChild(reels, sponsored, trailing)
            serviceNodes.addAll(listOf(root, reels, sponsored, trailing))
            root
        }

        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)
        fakeClockMs += 150L
        scheduler.firePending()

        assertEquals(8, serviceNodes.size)
        assertTrue(serviceNodes.all { it.recycleCalls == 0 })
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
        fakeClockMs += 700L
        service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
        assertNotNull(scheduler.pendingAction)
        fakeClockMs += 650L
        scheduler.firePending()
        assertEquals(2, shadowService.gesturesDispatched.size)
        val cancelledGesture = shadowService.gesturesDispatched[1]
        cancelledGesture.callback()?.onCancelled(cancelledGesture.description())
        assertEquals(1, settingsRepository.settingsFlow.value.skippedReelsCount)

        // Pre-dispatch abort when window becomes organic must NOT increment counter.
        fakeClockMs += 150L
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
            fakeClockMs += 150L
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
            fakeClockMs += 700L
            service.onAccessibilityEvent(instagramEvent("com.instagram.android"))
            assertNotNull(scheduler.pendingAction)
            fakeClockMs += 150L
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
