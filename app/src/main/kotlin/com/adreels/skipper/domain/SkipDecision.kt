package com.adreels.skipper.domain

/**
 * Immutable decision emitted by [ReelAdDetector] for an evaluated window snapshot.
 */
sealed interface SkipDecision {
    /** No swipe should be dispatched and no pending skip is awaiting execution. */
    data class Ignore(val reason: IgnoreReason) : SkipDecision

    /** A sponsored Reel was newly confirmed; schedule an upward swipe after [delayMs]. */
    data class ScheduleSkip(val delayMs: Long) : SkipDecision

    /** A previously scheduled swipe remains valid and is still waiting to fire. */
    data object AwaitPendingSkip : SkipDecision

    /** Conditions no longer hold; cancel any pending scheduled swipe immediately. */
    data object CancelPendingSkip : SkipDecision
}

/**
 * Specific domain reason explaining an [SkipDecision.Ignore] outcome.
 */
enum class IgnoreReason {
    MASTER_DISABLED,
    NON_TARGET_PACKAGE,
    COOLDOWN_ACTIVE,
    THROTTLED,
    NOT_REELS_CONTEXT,
    NO_SPONSORED_LABEL,
}
