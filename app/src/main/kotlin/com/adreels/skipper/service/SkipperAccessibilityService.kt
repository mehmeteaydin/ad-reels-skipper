package com.adreels.skipper.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.adreels.skipper.domain.ReelAdDetector
import com.adreels.skipper.domain.SkipDecision
import com.adreels.skipper.domain.SkipperConfig
import com.adreels.skipper.domain.WindowSnapshot

/** Scheduler abstraction enabling deterministic delayed skip testing without thread sleeps. */
interface DelayScheduler {
    fun schedule(delayMs: Long, action: () -> Unit)
    fun cancelAll()
}

internal class HandlerDelayScheduler : DelayScheduler {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRunnable: Runnable? = null

    override fun schedule(delayMs: Long, action: () -> Unit) {
        cancelAll()
        val runnable = Runnable {
            pendingRunnable = null
            action()
        }
        pendingRunnable = runnable
        handler.postDelayed(runnable, delayMs.coerceAtLeast(0L))
    }

    override fun cancelAll() {
        pendingRunnable?.let(handler::removeCallbacks)
        pendingRunnable = null
    }
}

/**
 * Accessibility service monitoring Instagram Reels windows and dispatching fail-safe upward swipes
 * when sponsored Reels are detected cleanly inside active Reels context.
 */
class SkipperAccessibilityService : AccessibilityService() {

    internal var clockMs: () -> Long = { SystemClock.uptimeMillis() }
    internal var delayScheduler: DelayScheduler = HandlerDelayScheduler()
    internal var detector: ReelAdDetector = ReelAdDetector()
    internal var treeExtractor: AccessibilityTreeExtractor = AccessibilityTreeExtractor()
    internal var gestureDispatcher: SwipeGestureDispatcher = SwipeGestureDispatcher()
    internal var configProvider: () -> SkipperConfig = { SkipperConfig() }
    internal var activeRootProvider: () -> AccessibilityNodeInfo? = { rootInActiveWindow }
    internal var displayBoundsProvider: () -> Pair<Int, Int> = {
        val metrics = resources.displayMetrics
        metrics.widthPixels to metrics.heightPixels
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString()
        val config = configProvider()

        if (!config.isMasterEnabled || packageName != TARGET_PACKAGE) {
            val decision = detector.evaluate(
                snapshot = WindowSnapshot(packageName = packageName, nodes = emptyList()),
                nowMs = clockMs(),
                config = config,
            )
            if (decision is SkipDecision.CancelPendingSkip) {
                delayScheduler.cancelAll()
                Log.d(TAG, "Pending skip cancelled")
            }
            return
        }

        val root = safeActiveRoot()
        val snapshot = treeExtractor.extract(packageName = packageName, root = root)

        when (val decision = detector.evaluate(snapshot = snapshot, nowMs = clockMs(), config = config)) {
            is SkipDecision.ScheduleSkip -> {
                delayScheduler.cancelAll()
                delayScheduler.schedule(decision.delayMs) { executeScheduledSkip() }
                Log.d(TAG, "Sponsored Reel detected; skip scheduled")
            }

            SkipDecision.CancelPendingSkip -> {
                delayScheduler.cancelAll()
                Log.d(TAG, "Pending skip cancelled")
            }

            SkipDecision.AwaitPendingSkip,
            is SkipDecision.Ignore,
            -> Unit
        }
    }

    private fun executeScheduledSkip() {
        val config = configProvider()
        val freshRoot = safeActiveRoot()
        if (freshRoot == null) {
            detector.onSkipFailed()
            return
        }

        val freshPackage = freshRoot.packageName?.toString()
        val freshSnapshot = treeExtractor.extract(packageName = freshPackage, root = freshRoot)
        if (!detector.isSponsoredReelPresent(snapshot = freshSnapshot, config = config)) {
            detector.onSkipFailed()
            Log.d(TAG, "Pending skip aborted before dispatch")
            return
        }

        val (screenWidth, screenHeight) = safeDisplayBounds()
        gestureDispatcher.dispatchUpwardSwipe(
            service = this,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            onCompleted = {
                detector.onSkipDispatched(clockMs())
                Log.d(TAG, "Swipe gesture completed")
            },
            onFailed = {
                detector.onSkipFailed()
                Log.d(TAG, "Swipe gesture failed or cancelled")
            },
        )
    }

    override fun onInterrupt() {
        delayScheduler.cancelAll()
        detector.reset()
        Log.d(TAG, "SkipperAccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        delayScheduler.cancelAll()
        detector.reset()
        return super.onUnbind(intent)
    }

    private fun safeActiveRoot(): AccessibilityNodeInfo? {
        return try {
            activeRootProvider()
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun safeDisplayBounds(): Pair<Int, Int> {
        return try {
            displayBoundsProvider()
        } catch (_: RuntimeException) {
            0 to 0
        }
    }

    companion object {
        const val TAG = "SkipperAccessibility"
        const val TARGET_PACKAGE = SkipperConfig.TARGET_PACKAGE
    }
}
