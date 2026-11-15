package com.adreels.skipper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

/** Immutable coordinate and timing parameters for an upward vertical Reels swipe. */
data class SwipeSpec(
    val centerX: Float,
    val startY: Float,
    val endY: Float,
    val durationMs: Long,
)

/**
 * Computes fail-safe swipe geometry (`X = 50%`, `Y = 75% -> 20%`, `200 ms`) and dispatches gestures
 * through [AccessibilityService.dispatchGesture].
 */
open class SwipeGestureDispatcher {

    /** Returns computed [SwipeSpec] for positive display bounds, or `null` when invalid. */
    fun calculateSwipeSpec(screenWidth: Int, screenHeight: Int): SwipeSpec? {
        if (screenWidth <= 0 || screenHeight <= 0) return null
        return SwipeSpec(
            centerX = screenWidth * HORIZONTAL_CENTER_RATIO,
            startY = screenHeight * START_Y_RATIO,
            endY = screenHeight * END_Y_RATIO,
            durationMs = SWIPE_DURATION_MS,
        )
    }

    /**
     * Dispatches an upward vertical swipe via [service]. Invokes [onCompleted] on gesture success
     * and [onFailed] if bounds are invalid, the OS refuses/cancels dispatch, or an exception occurs.
     */
    open fun dispatchUpwardSwipe(
        service: AccessibilityService,
        screenWidth: Int,
        screenHeight: Int,
        onCompleted: () -> Unit,
        onFailed: () -> Unit,
    ): Boolean {
        val spec = calculateSwipeSpec(screenWidth, screenHeight)
        if (spec == null) {
            onFailed()
            return false
        }

        return try {
            val path = Path().apply {
                moveTo(spec.centerX, spec.startY)
                lineTo(spec.centerX, spec.endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, spec.durationMs)
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            val accepted = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        onCompleted()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        onFailed()
                    }
                },
                null,
            )
            if (!accepted) {
                onFailed()
            }
            accepted
        } catch (_: RuntimeException) {
            onFailed()
            false
        }
    }

    companion object {
        const val HORIZONTAL_CENTER_RATIO: Float = 0.50f
        const val START_Y_RATIO: Float = 0.75f
        const val END_Y_RATIO: Float = 0.20f
        const val SWIPE_DURATION_MS: Long = 200L
    }
}
