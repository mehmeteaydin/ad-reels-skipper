package com.adreels.skipper.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.adreels.skipper.data.SkipperSettingsRepository
import com.adreels.skipper.data.SkipperSettingsRepositoryProvider
import com.adreels.skipper.domain.ReelAdDetector
import com.adreels.skipper.domain.SkipDecision
import com.adreels.skipper.domain.SkipperConfig
import com.adreels.skipper.domain.WindowSnapshot
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    internal var todayProvider: () -> LocalDate = { LocalDate.now() }
    internal var delayScheduler: DelayScheduler = HandlerDelayScheduler()
    internal var detector: ReelAdDetector = ReelAdDetector()
    internal var treeExtractor: AccessibilityTreeExtractor = AccessibilityTreeExtractor()
    internal var gestureDispatcher: SwipeGestureDispatcher = SwipeGestureDispatcher()
    internal var settingsRepositoryProvider: () -> SkipperSettingsRepository = {
        SkipperSettingsRepositoryProvider.get(applicationContext)
    }
    internal var callbackDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
    internal var configProvider: () -> SkipperConfig = {
        settingsRepositoryProvider().settingsFlow.value.toSkipperConfig()
    }
    internal var activeRootProvider: () -> AccessibilityNodeInfo? = { rootInActiveWindow }
    internal var displayBoundsProvider: () -> Pair<Int, Int> = {
        val metrics = resources.displayMetrics
        metrics.widthPixels to metrics.heightPixels
    }

    private var serviceScope: CoroutineScope? = null
    private var settingsObserverJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        startObservingSettings()
    }

    private fun cancelPendingSkipIfNeeded(
        packageName: String?,
        nowMs: Long,
        config: SkipperConfig,
    ) {
        val decision = detector.evaluate(
            snapshot = WindowSnapshot(packageName = packageName, nodes = emptyList()),
            nowMs = nowMs,
            config = config,
        )
        if (decision is SkipDecision.CancelPendingSkip) {
            delayScheduler.cancelAll()
            Log.d(TAG, "Pending skip cancelled")
        }
    }

    /** Begins collecting live user settings so toggle changes cancel pending skips promptly. */
    internal fun startObservingSettings() {
        settingsObserverJob?.cancel()
        val scope = ensureServiceScope()
        settingsObserverJob = scope.launch {
            settingsRepositoryProvider().settingsFlow.collect { settings ->
                if (!settings.isMasterEnabled) {
                    cancelPendingSkipIfNeeded(
                        packageName = TARGET_PACKAGE,
                        nowMs = clockMs(),
                        config = settings.toSkipperConfig(),
                    )
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString()
        val config = configProvider()
        val now = clockMs()

        if (!config.isMasterEnabled || packageName != TARGET_PACKAGE) {
            cancelPendingSkipIfNeeded(packageName = packageName, nowMs = now, config = config)
            return
        }

        if (!detector.shouldInspectSnapshot(packageName = packageName, nowMs = now, config = config)) {
            return
        }

        val root = safeActiveRoot()
        val snapshot = try {
            treeExtractor.extract(packageName = packageName, root = root, keywords = config.keywords)
        } finally {
            safeRecycleRoot(root)
        }

        when (val decision = detector.evaluate(snapshot = snapshot, nowMs = now, config = config)) {
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
        val now = clockMs()
        val freshRoot = safeActiveRoot()
        if (freshRoot == null) {
            delayScheduler.cancelAll()
            detector.onSkipFailed()
            return
        }

        val freshSnapshot = try {
            val freshPackage = safePackageName(freshRoot)
            if (freshPackage != TARGET_PACKAGE) {
                delayScheduler.cancelAll()
                detector.onSkipFailed()
                Log.d(TAG, "Pending skip aborted before dispatch")
                return
            }
            treeExtractor.extract(
                packageName = freshPackage,
                root = freshRoot,
                keywords = config.keywords,
            )
        } finally {
            safeRecycleRoot(freshRoot)
        }
        if (!detector.isSponsoredReelPresent(snapshot = freshSnapshot, config = config, nowMs = now)) {
            delayScheduler.cancelAll()
            detector.onSkipFailed()
            Log.d(TAG, "Pending skip aborted before dispatch")
            return
        }

        val (screenWidth, screenHeight) = safeDisplayBounds()
        detector.onSkipDispatchStarted()
        gestureDispatcher.dispatchUpwardSwipe(
            service = this,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            onCompleted = {
                delayScheduler.cancelAll()
                detector.onSkipDispatched(clockMs())
                recordCompletedSkip()
                Log.d(TAG, "Swipe gesture completed")
            },
            onFailed = {
                delayScheduler.cancelAll()
                detector.onSkipFailed()
                Log.d(TAG, "Swipe gesture failed or cancelled")
            },
        )
    }

    private fun recordCompletedSkip() {
        val completedDate = todayProvider()
        ensureServiceScope().launch {
            try {
                settingsRepositoryProvider().incrementSkippedReelsCount(completedDate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                Log.w(TAG, "Failed to persist completed skip count")
            } catch (_: RuntimeException) {
                Log.w(TAG, "Failed to persist completed skip count")
            }
        }
    }

    private fun ensureServiceScope(): CoroutineScope {
        return serviceScope ?: CoroutineScope(SupervisorJob() + callbackDispatcher).also { created ->
            serviceScope = created
        }
    }

    override fun onInterrupt() {
        delayScheduler.cancelAll()
        detector.reset()
        Log.d(TAG, "SkipperAccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        delayScheduler.cancelAll()
        detector.reset()
        stopServiceScope()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        delayScheduler.cancelAll()
        detector.reset()
        stopServiceScope()
        super.onDestroy()
    }

    private fun stopServiceScope() {
        settingsObserverJob?.cancel()
        settingsObserverJob = null
        serviceScope?.cancel()
        serviceScope = null
    }

    private fun safePackageName(root: AccessibilityNodeInfo): String? {
        return try {
            root.packageName?.toString()
        } catch (_: RuntimeException) {
            null
        }
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

    @Suppress("DEPRECATION")
    private fun safeRecycleRoot(root: AccessibilityNodeInfo?) {
        if (root != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                root.recycle()
            } catch (_: RuntimeException) {
                // Node already recycled or backed by a unit-test stub.
            }
        }
    }

    companion object {
        const val TAG = "SkipperAccessibility"
        const val TARGET_PACKAGE = SkipperConfig.TARGET_PACKAGE
    }
}
