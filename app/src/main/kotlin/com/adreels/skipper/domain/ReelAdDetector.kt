package com.adreels.skipper.domain

import java.util.Locale

/**
 * Stateful pure-Kotlin evaluator that decides whether an Instagram window snapshot
 * represents a sponsored Reel that should be skipped.
 */
class ReelAdDetector {

    private var lastEvaluatedAtMs: Long? = null
    private var lastSkipDispatchedAtMs: Long? = null
    private var isSkipPending: Boolean = false
    private var isGestureInFlight: Boolean = false

    /**
     * Lightweight pre-check allowing callers to avoid accessibility tree extraction when package,
     * toggle, in-flight gesture, cooldown, or throttle gates already suppress snapshot inspection.
     */
    fun shouldInspectSnapshot(
        packageName: String?,
        nowMs: Long,
        config: SkipperConfig = SkipperConfig(),
    ): Boolean {
        if (!config.isMasterEnabled || packageName != SkipperConfig.TARGET_PACKAGE) {
            return false
        }
        if (isGestureInFlight) {
            return false
        }
        val lastSkip = lastSkipDispatchedAtMs
        if (!isSkipPending && lastSkip != null && nowMs - lastSkip in 0L until config.cooldownMs) {
            return false
        }
        val lastEval = lastEvaluatedAtMs
        if (lastEval != null && nowMs - lastEval in 0L until config.throttleIntervalMs) {
            return false
        }
        return true
    }

    /**
     * Evaluates [snapshot] at timestamp [nowMs] against [config], applying package/toggle gates,
     * in-flight and cooldown suppression, throttling, visible-only node caps, Reels context rules,
     * and locale-independent keyword boundary matching.
     */
    fun evaluate(
        snapshot: WindowSnapshot,
        nowMs: Long,
        config: SkipperConfig = SkipperConfig(),
    ): SkipDecision {
        if (!config.isMasterEnabled) {
            return cancelPendingOrIgnore(IgnoreReason.MASTER_DISABLED)
        }
        if (snapshot.packageName != SkipperConfig.TARGET_PACKAGE) {
            return cancelPendingOrIgnore(IgnoreReason.NON_TARGET_PACKAGE)
        }
        if (isGestureInFlight) {
            return SkipDecision.Ignore(IgnoreReason.COOLDOWN_ACTIVE)
        }
        val lastSkip = lastSkipDispatchedAtMs
        if (!isSkipPending && lastSkip != null && nowMs - lastSkip in 0L until config.cooldownMs) {
            return SkipDecision.Ignore(IgnoreReason.COOLDOWN_ACTIVE)
        }
        val lastEval = lastEvaluatedAtMs
        if (lastEval != null && nowMs - lastEval in 0L until config.throttleIntervalMs) {
            return if (isSkipPending) {
                SkipDecision.AwaitPendingSkip
            } else {
                SkipDecision.Ignore(IgnoreReason.THROTTLED)
            }
        }

        lastEvaluatedAtMs = nowMs
        val inspection = inspectSnapshot(snapshot, config)

        if (isSkipPending) {
            return if (inspection.hasReelsContext && inspection.hasSponsoredLabel) {
                SkipDecision.AwaitPendingSkip
            } else {
                isSkipPending = false
                SkipDecision.CancelPendingSkip
            }
        }

        if (!inspection.hasReelsContext) {
            return SkipDecision.Ignore(IgnoreReason.NOT_REELS_CONTEXT)
        }
        if (!inspection.hasSponsoredLabel) {
            return SkipDecision.Ignore(IgnoreReason.NO_SPONSORED_LABEL)
        }

        isSkipPending = true
        return SkipDecision.ScheduleSkip(config.skipDelayMs)
    }

    /**
     * Re-checks whether [snapshot] still satisfies all rules right before executing a delayed swipe,
     * rejecting execution if a gesture is already in flight or cooldown is still active at [nowMs].
     */
    fun isSponsoredReelPresent(
        snapshot: WindowSnapshot,
        config: SkipperConfig = SkipperConfig(),
        nowMs: Long? = null,
    ): Boolean {
        if (!config.isMasterEnabled || snapshot.packageName != SkipperConfig.TARGET_PACKAGE || isGestureInFlight) {
            return false
        }
        if (nowMs != null) {
            val lastSkip = lastSkipDispatchedAtMs
            if (lastSkip != null && nowMs - lastSkip in 0L until config.cooldownMs) {
                return false
            }
        }
        val inspection = inspectSnapshot(snapshot, config)
        return inspection.hasReelsContext && inspection.hasSponsoredLabel
    }

    /** Marks that an upward swipe gesture has started dispatching asynchronously. */
    fun onSkipDispatchStarted() {
        isSkipPending = false
        isGestureInFlight = true
    }

    /** Records that the swipe gesture completed/dispatched at [nowMs], opening the cooldown window. */
    fun onSkipDispatched(nowMs: Long) {
        isSkipPending = false
        isGestureInFlight = false
        lastSkipDispatchedAtMs = nowMs
    }

    /** Clears pending and in-flight skip state when a scheduled gesture was aborted or failed before completion. */
    fun onSkipFailed() {
        isSkipPending = false
        isGestureInFlight = false
    }

    /** Resets all timing and pending state, e.g. when the service disconnects or is interrupted. */
    fun reset() {
        isSkipPending = false
        isGestureInFlight = false
        lastEvaluatedAtMs = null
        lastSkipDispatchedAtMs = null
    }

    private fun cancelPendingOrIgnore(reason: IgnoreReason): SkipDecision {
        return if (isSkipPending) {
            isSkipPending = false
            SkipDecision.CancelPendingSkip
        } else {
            SkipDecision.Ignore(reason)
        }
    }

    private fun inspectSnapshot(snapshot: WindowSnapshot, config: SkipperConfig): SnapshotInspection {
        val normalizedKeywords = config.keywords
            .map(::normalizeTokens)
            .filter { it.isNotEmpty() }

        var hasReelsContext = false
        var hasSponsoredLabel = false
        var visibleCount = 0

        for (node in snapshot.nodes) {
            if (!node.isVisibleToUser) continue
            visibleCount++
            if (visibleCount > config.maxVisibleNodes) break

            val textTokens = node.text?.let(::normalizeTokens).orEmpty()
            val descTokens = node.contentDescription?.let(::normalizeTokens).orEmpty()

            if (!hasReelsContext && matchesReelsContext(node, textTokens, descTokens)) {
                hasReelsContext = true
            }
            if (!hasSponsoredLabel && matchesAnyKeyword(textTokens, descTokens, normalizedKeywords)) {
                hasSponsoredLabel = true
            }
            if (hasReelsContext && hasSponsoredLabel) break
        }

        return SnapshotInspection(
            hasReelsContext = hasReelsContext,
            hasSponsoredLabel = hasSponsoredLabel,
        )
    }

    private fun matchesReelsContext(
        node: VisibleNode,
        textTokens: String,
        descTokens: String,
    ): Boolean {
        val isSelectedReelsTab = node.isSelected &&
            (containsNormalizedPhrase(textTokens, REELS_TOKEN) ||
                containsNormalizedPhrase(descTokens, REELS_TOKEN))
        val isReelsViewerHeader = !node.isClickable && textTokens == REELS_TOKEN
        val isReelContentDescription =
            descTokens == REEL_BY_PREFIX || descTokens.startsWith("$REEL_BY_PREFIX ")
        return isSelectedReelsTab || isReelsViewerHeader || isReelContentDescription
    }

    private fun matchesAnyKeyword(
        textTokens: String,
        descTokens: String,
        normalizedKeywords: List<String>,
    ): Boolean {
        if (normalizedKeywords.isEmpty()) return false
        return normalizedKeywords.any { keyword ->
            containsNormalizedPhrase(textTokens, keyword) ||
                containsNormalizedPhrase(descTokens, keyword)
        }
    }

    private data class SnapshotInspection(
        val hasReelsContext: Boolean,
        val hasSponsoredLabel: Boolean,
    )

    companion object {
        private const val REELS_TOKEN = "reels"
        private const val REEL_BY_PREFIX = "reel by"

        /**
         * Normalizes [raw] into lowercase alphanumeric tokens separated by single spaces,
         * folding Turkish dotted/dotless `İ`/`I`/`ı`/`i` consistently across system locales.
         */
        fun normalizeTokens(raw: String): String {
            if (raw.isBlank()) return ""
            val builder = StringBuilder(raw.length)
            var pendingSpace = false

            for (ch in raw) {
                val folded = foldTurkishCharacters(ch)
                if (folded.isLetterOrDigit()) {
                    if (pendingSpace && builder.isNotEmpty()) {
                        builder.append(' ')
                    }
                    builder.append(folded)
                    pendingSpace = false
                } else if (builder.isNotEmpty()) {
                    pendingSpace = true
                }
            }
            return builder.toString().lowercase(Locale.ROOT)
        }

        private fun containsNormalizedPhrase(textTokens: String, phraseTokens: String): Boolean {
            if (textTokens.isEmpty() || phraseTokens.isEmpty()) return false
            return " $textTokens ".contains(" $phraseTokens ")
        }

        private fun foldTurkishCharacters(ch: Char): Char = when (ch) {
            'İ', 'I', 'ı' -> 'i'
            else -> ch
        }
    }
}
