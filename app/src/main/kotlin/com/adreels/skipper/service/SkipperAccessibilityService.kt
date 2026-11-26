package com.adreels.skipper.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.adreels.skipper.MainActivity
import com.adreels.skipper.R
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    internal var foregroundPromoter: (Int, Notification) -> Unit = { id, notification ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, notification)
        }
    }
    internal var foregroundStopper: () -> Unit = {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private var serviceScope: CoroutineScope? = null
    private var settingsObserverJob: Job? = null
    private var isForegroundActive: Boolean = false
    private var isKeepAliveChannelCreated: Boolean = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isConnectedFlow.value = true
        syncKeepAliveForeground(isMasterEnabled = configProvider().isMasterEnabled)
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

    /** Begins collecting live user settings so toggle changes cancel pending skips and sync keep-alive promptly. */
    internal fun startObservingSettings() {
        settingsObserverJob?.cancel()
        val scope = ensureServiceScope()
        settingsObserverJob = scope.launch {
            settingsRepositoryProvider().settingsFlow.collect { settings ->
                if (_isConnectedFlow.value) {
                    syncKeepAliveForeground(isMasterEnabled = settings.isMasterEnabled)
                }
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
        _isConnectedFlow.value = false
        stopKeepAliveForeground()
        delayScheduler.cancelAll()
        detector.reset()
        stopServiceScope()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        _isConnectedFlow.value = false
        stopKeepAliveForeground()
        delayScheduler.cancelAll()
        detector.reset()
        stopServiceScope()
        super.onDestroy()
    }

    private fun syncKeepAliveForeground(isMasterEnabled: Boolean) {
        if (!_isConnectedFlow.value || !isMasterEnabled) {
            stopKeepAliveForeground()
            return
        }
        if (isForegroundActive) return
        try {
            ensureKeepAliveChannel()
            val notification = buildKeepAliveNotification()
            foregroundPromoter(KEEP_ALIVE_NOTIFICATION_ID, notification)
            isForegroundActive = true
        } catch (_: RuntimeException) {
            isForegroundActive = false
            Log.w(TAG, "Failed to start keep-alive foreground notification")
        }
    }

    private fun stopKeepAliveForeground() {
        if (!isForegroundActive) return
        isForegroundActive = false
        try {
            foregroundStopper()
        } catch (_: RuntimeException) {
            Log.w(TAG, "Failed to stop keep-alive foreground notification")
        }
    }

    private fun ensureKeepAliveChannel() {
        if (isKeepAliveChannelCreated) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            KEEP_ALIVE_CHANNEL_ID,
            getString(R.string.keep_alive_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.keep_alive_channel_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
        isKeepAliveChannelCreated = true
    }

    private fun buildKeepAliveNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, KEEP_ALIVE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.keep_alive_notification_title))
            .setContentText(getString(R.string.keep_alive_notification_text))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
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
        const val KEEP_ALIVE_CHANNEL_ID = "skipper_keep_alive_channel"
        const val KEEP_ALIVE_NOTIFICATION_ID = 1001

        private val _isConnectedFlow = MutableStateFlow(false)
        val isConnectedFlow: StateFlow<Boolean> = _isConnectedFlow.asStateFlow()

        internal fun setConnectedForTesting(connected: Boolean) {
            _isConnectedFlow.value = connected
        }
    }
}
