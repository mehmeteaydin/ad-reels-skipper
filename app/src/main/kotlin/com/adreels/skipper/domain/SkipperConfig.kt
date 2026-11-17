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
        const val DEFAULT_THROTTLE_INTERVAL_MS: Long = 150L
        const val DEFAULT_SKIP_DELAY_MS: Long = 400L
        const val DEFAULT_COOLDOWN_MS: Long = 1200L
        const val DEFAULT_MAX_VISIBLE_NODES: Int = 500
        val DEFAULT_KEYWORDS: Set<String> = setOf("sponsored", "sponsorlu", "paid partnership")
    }
}
