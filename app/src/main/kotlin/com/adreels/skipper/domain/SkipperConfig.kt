package com.adreels.skipper.domain

/**
 * Pure domain configuration parameters governing Instagram Reels ad detection and timing.
 */
data class SkipperConfig(
    val isMasterEnabled: Boolean = true,
    val keywords: Set<String> = DEFAULT_KEYWORDS,
    val throttleIntervalMs: Long = DEFAULT_THROTTLE_INTERVAL_MS,
    val skipDelayMs: Long = DEFAULT_SKIP_DELAY_MS,
    val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    val maxVisibleNodes: Int = DEFAULT_MAX_VISIBLE_NODES,
) {
    companion object {
        const val TARGET_PACKAGE: String = "com.instagram.android"
        const val DEFAULT_THROTTLE_INTERVAL_MS: Long = 180L
        const val DEFAULT_SKIP_DELAY_MS: Long = 150L
        const val MIN_SKIP_DELAY_MS: Long = 50L
        const val MAX_SKIP_DELAY_MS: Long = 2000L
        const val SKIP_DELAY_STEP_MS: Long = 50L
        const val DEFAULT_COOLDOWN_MS: Long = 700L
        const val DEFAULT_MAX_VISIBLE_NODES: Int = 160
        const val MIN_CUSTOM_KEYWORD_LENGTH: Int = 2
        const val MAX_CUSTOM_KEYWORD_LENGTH: Int = 40
        val BUILT_IN_DISPLAY_KEYWORDS: List<String> = listOf("Sponsored", "Sponsorlu", "Paid partnership")
        val DEFAULT_KEYWORDS: Set<String> = setOf("sponsored", "sponsorlu", "paid partnership")

        /** Clamps any candidate skip delay in milliseconds to the allowed `50..2000 ms` range. */
        fun clampSkipDelay(delayMs: Long): Long = delayMs.coerceIn(MIN_SKIP_DELAY_MS, MAX_SKIP_DELAY_MS)

        /** Snaps [delayMs] to the nearest `50 ms` increment within `50..2000 ms`. */
        fun snapAndClampSkipDelay(delayMs: Long): Long {
            val clamped = clampSkipDelay(delayMs)
            val steps = (clamped - MIN_SKIP_DELAY_MS + SKIP_DELAY_STEP_MS / 2L) / SKIP_DELAY_STEP_MS
            return (MIN_SKIP_DELAY_MS + steps * SKIP_DELAY_STEP_MS).coerceIn(MIN_SKIP_DELAY_MS, MAX_SKIP_DELAY_MS)
        }
    }
}

/** Validation failure causes when adding a user-defined custom keyword. */
enum class KeywordValidationError {
    BLANK,
    TOO_SHORT,
    TOO_LONG,
    NO_ALPHANUMERIC,
    DUPLICATE,
}

/** Result of validating a user-supplied custom keyword candidate. */
sealed interface KeywordValidationResult {
    data class Valid(val trimmedKeyword: String) : KeywordValidationResult
    data class Invalid(val error: KeywordValidationError) : KeywordValidationResult
}

/** Pure Kotlin validator and deduplicator for built-in and custom ad keywords (`BR-3`, `BR-6`). */
object KeywordValidator {
    private val builtInNormalizedTokens: Set<String> by lazy {
        SkipperConfig.DEFAULT_KEYWORDS
            .map(ReelAdDetector.Companion::normalizeKeywordTokens)
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /** Returns `true` when [raw] normalizes to one of the read-only built-in keywords. */
    fun isBuiltInKeyword(raw: String): Boolean {
        val normalized = ReelAdDetector.normalizeKeywordTokens(raw)
        return normalized.isNotEmpty() && normalized in builtInNormalizedTokens
    }

    /**
     * Trims [raw] and validates length (`2..40` chars), alphanumeric content, and case/Turkish-folded
     * uniqueness against both built-in keywords and [existingCustomKeywords].
     */
    fun validateCustomKeyword(
        raw: String,
        existingCustomKeywords: Set<String> = emptySet(),
    ): KeywordValidationResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return KeywordValidationResult.Invalid(KeywordValidationError.BLANK)
        }
        if (trimmed.length < SkipperConfig.MIN_CUSTOM_KEYWORD_LENGTH) {
            return KeywordValidationResult.Invalid(KeywordValidationError.TOO_SHORT)
        }
        if (trimmed.length > SkipperConfig.MAX_CUSTOM_KEYWORD_LENGTH) {
            return KeywordValidationResult.Invalid(KeywordValidationError.TOO_LONG)
        }
        val normalized = ReelAdDetector.normalizeKeywordTokens(trimmed)
        if (normalized.isEmpty()) {
            return KeywordValidationResult.Invalid(KeywordValidationError.NO_ALPHANUMERIC)
        }
        val existingNormalized = existingCustomKeywords
            .map(ReelAdDetector.Companion::normalizeKeywordTokens)
            .toSet()
        if (normalized in builtInNormalizedTokens || normalized in existingNormalized) {
            return KeywordValidationResult.Invalid(KeywordValidationError.DUPLICATE)
        }
        return KeywordValidationResult.Valid(trimmed)
    }
}
