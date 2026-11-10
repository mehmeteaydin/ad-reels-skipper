package com.adreels.skipper.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class SkipperAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName != TARGET_PACKAGE) return
        Log.d(TAG, "Observed accessibility event from $TARGET_PACKAGE: ${event.eventType}")
    }

    override fun onInterrupt() {
        Log.d(TAG, "SkipperAccessibilityService interrupted")
    }

    companion object {
        const val TAG = "SkipperAccessibility"
        const val TARGET_PACKAGE = "com.instagram.android"
    }
}
