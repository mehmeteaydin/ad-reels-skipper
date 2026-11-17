package com.adreels.skipper.domain

import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ReelAdDetectorTest {

    private fun reelsContextNode(): VisibleNode = VisibleNode(
        text = "Reels",
        isVisibleToUser = true,
        isSelected = true,
        isClickable = true,
    )

    @Test
    fun `ignores null or non-target package and cancels pending skip when package leaves Instagram`() {
        val detector = ReelAdDetector()

        val nullPackage = detector.evaluate(
            WindowSnapshot(packageName = null, nodes = listOf(reelsContextNode(), VisibleNode(text = "Sponsored"))),
            nowMs = 100L,
        )
        assertEquals(SkipDecision.Ignore(IgnoreReason.NON_TARGET_PACKAGE), nullPackage)

        val otherApp = detector.evaluate(
            WindowSnapshot(packageName = "com.zhiliaoapp.musically", nodes = listOf(reelsContextNode(), VisibleNode(text = "Sponsored"))),
            nowMs = 300L,
        )
        assertEquals(SkipDecision.Ignore(IgnoreReason.NON_TARGET_PACKAGE), otherApp)

        val scheduled = detector.evaluate(
            WindowSnapshot(packageName = SkipperConfig.TARGET_PACKAGE, nodes = listOf(reelsContextNode(), VisibleNode(text = "Sponsored"))),
            nowMs = 500L,
        )
        assertEquals(SkipDecision.ScheduleSkip(400L), scheduled)

        val cancelled = detector.evaluate(
            WindowSnapshot(packageName = "com.android.launcher", nodes = emptyList()),
            nowMs = 550L,
        )
        assertEquals(SkipDecision.CancelPendingSkip, cancelled)
    }

    @Test
    fun `ignores events and cancels pending skip when master toggle is OFF`() {
        val detector = ReelAdDetector()
        val adSnapshot = WindowSnapshot(
            packageName = SkipperConfig.TARGET_PACKAGE,
            nodes = listOf(reelsContextNode(), VisibleNode(text = "Sponsored")),
        )

        val ignoredWhenOff = detector.evaluate(
            adSnapshot,
            nowMs = 100L,
            config = SkipperConfig(isMasterEnabled = false),
        )
        assertEquals(SkipDecision.Ignore(IgnoreReason.MASTER_DISABLED), ignoredWhenOff)

        assertEquals(SkipDecision.ScheduleSkip(400L), detector.evaluate(adSnapshot, nowMs = 300L))
        assertEquals(
            SkipDecision.CancelPendingSkip,
            detector.evaluate(adSnapshot, nowMs = 350L, config = SkipperConfig(isMasterEnabled = false)),
        )
    }

    @Test
    fun `matches sponsored keywords on word boundaries across Turkish and English locales`() {
        val previousLocale = Locale.getDefault()
        try {
            for (locale in listOf(Locale.forLanguageTag("tr-TR"), Locale.US)) {
                Locale.setDefault(locale)
                val matchingLabels = listOf(
                    "Sponsored",
                    "• Sponsored •",
                    "Sponsorlu",
                    "SPONSORLU",
                    "Paid partnership",
                    "PAID PARTNERSHIP",
                    "PAİD PARTNERSHİP",
                    "Paıd partnershıp",
                    "Paid partnership with Brand",
                )
                matchingLabels.forEachIndexed { idx, label ->
                    val detector = ReelAdDetector()
                    val decision = detector.evaluate(
                        WindowSnapshot(
                            packageName = SkipperConfig.TARGET_PACKAGE,
                            nodes = listOf(
                                reelsContextNode(),
                                VisibleNode(text = label, isVisibleToUser = true),
                            ),
                        ),
                        nowMs = 1_000L + idx * 500L,
                    )
                    assertEquals(
                        SkipDecision.ScheduleSkip(400L),
                        decision,
                        "Expected '$label' to match in locale $locale",
                    )
                }
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `matches sponsored content description while ignoring partial substrings blank text and invisible nodes`() {
        val detector = ReelAdDetector()

        val partialWord = detector.evaluate(
            WindowSnapshot(
                packageName = SkipperConfig.TARGET_PACKAGE,
                nodes = listOf(reelsContextNode(), VisibleNode(text = "This is an unsponsored review")),
            ),
            nowMs = 100L,
        )
        assertEquals(SkipDecision.Ignore(IgnoreReason.NO_SPONSORED_LABEL), partialWord)

        val blankNode = detector.evaluate(
            WindowSnapshot(
                packageName = SkipperConfig.TARGET_PACKAGE,
                nodes = listOf(reelsContextNode(), VisibleNode(text = "   ", contentDescription = "\n")),
            ),
            nowMs = 300L,
        )
        assertEquals(SkipDecision.Ignore(IgnoreReason.NO_SPONSORED_LABEL), blankNode)

        val invisibleAd = detector.evaluate(
            WindowSnapshot(
                packageName = SkipperConfig.TARGET_PACKAGE,
                nodes = listOf(
                    reelsContextNode(),
                    VisibleNode(text = "Sponsored", isVisibleToUser = false),
                ),
            ),
            nowMs = 500L,
        )
        assertEquals(SkipDecision.Ignore(IgnoreReason.NO_SPONSORED_LABEL), invisibleAd)

        val contentDescAd = detector.evaluate(
            WindowSnapshot(
                packageName = SkipperConfig.TARGET_PACKAGE,
                nodes = listOf(
                    VisibleNode(contentDescription = "Reel by @creator"),
                    VisibleNode(contentDescription = "Sponsorlu içerik", isVisibleToUser = true),
                ),
            ),
            nowMs = 700L,
        )
        assertEquals(SkipDecision.ScheduleSkip(400L), contentDescAd)
    }

    @Test
    fun `ignores sponsored posts on non-Reels screens even when unselected Reels tab is present`() {
        val detector = ReelAdDetector()

        val homeFeedWithUnselectedTab = WindowSnapshot(
            packageName = SkipperConfig.TARGET_PACKAGE,
            nodes = listOf(
                VisibleNode(text = "Instagram", isClickable = false),
                VisibleNode(text = "Sponsored"),
                VisibleNode(
                    text = "Reels",
                    contentDescription = "Reels",
                    isVisibleToUser = true,
                    isSelected = false,
                    isClickable = true,
                ),
            ),
        )
        assertEquals(
            SkipDecision.Ignore(IgnoreReason.NOT_REELS_CONTEXT),
            detector.evaluate(homeFeedWithUnselectedTab, nowMs = 100L),
        )

        val nonClickableHeader = WindowSnapshot(
            packageName = SkipperConfig.TARGET_PACKAGE,
            nodes = listOf(
                VisibleNode(text = "Reels", isClickable = false, isSelected = false),
                VisibleNode(text = "Sponsored"),
            ),
        )
        assertEquals(
            SkipDecision.ScheduleSkip(400L),
            detector.evaluate(nonClickableHeader, nowMs = 300L),
        )

        val feedMentioningReels = ReelAdDetector()
        val homeCaptionReelsMention = WindowSnapshot(
            packageName = SkipperConfig.TARGET_PACKAGE,
            nodes = listOf(
                VisibleNode(text = "Instagram", isClickable = false),
                VisibleNode(text = "Check out these Reels templates", isClickable = false),
                VisibleNode(text = "Watch this reel by @creator", isClickable = false),
                VisibleNode(text = "Sponsored"),
                VisibleNode(
                    text = "Reels",
                    contentDescription = "Reels",
                    isVisibleToUser = true,
                    isSelected = false,
                    isClickable = true,
                ),
            ),
        )
        assertEquals(
            SkipDecision.Ignore(IgnoreReason.NOT_REELS_CONTEXT),
            feedMentioningReels.evaluate(homeCaptionReelsMention, nowMs = 500L),
        )
    }

    @Test
    fun `caps inspection at 500 visible nodes while skipping invisible nodes from the budget`() {
        val visibleLimitNodes = List(499) { VisibleNode(text = "comment $it", isVisibleToUser = true) } +
            VisibleNode(text = "Reels", isSelected = true, isVisibleToUser = true)

        val atLimitDetector = ReelAdDetector()
        val withInvisibleBefore500 = listOf(
            VisibleNode(text = "Offscreen ad", isVisibleToUser = false),
        ) + visibleLimitNodes.dropLast(1) + listOf(
            VisibleNode(text = "Sponsored", isVisibleToUser = true),
        )
        assertEquals(
            SkipDecision.Ignore(IgnoreReason.NOT_REELS_CONTEXT),
            atLimitDetector.evaluate(WindowSnapshot(SkipperConfig.TARGET_PACKAGE, withInvisibleBefore500), nowMs = 100L),
        )

        val exact500th = listOf(
            VisibleNode(text = "Offscreen organic", isVisibleToUser = false),
            reelsContextNode(),
        ) + List(498) { VisibleNode(text = "node $it", isVisibleToUser = true) } +
            VisibleNode(text = "Sponsored", isVisibleToUser = true)
        assertEquals(
            SkipDecision.ScheduleSkip(400L),
            atLimitDetector.evaluate(WindowSnapshot(SkipperConfig.TARGET_PACKAGE, exact500th), nowMs = 300L),
        )

        val over500Detector = ReelAdDetector()
        val atNode501 = listOf(reelsContextNode()) +
            List(499) { VisibleNode(text = "node $it", isVisibleToUser = true) } +
            VisibleNode(text = "Sponsored", isVisibleToUser = true)
        assertEquals(
            SkipDecision.Ignore(IgnoreReason.NO_SPONSORED_LABEL),
            over500Detector.evaluate(WindowSnapshot(SkipperConfig.TARGET_PACKAGE, atNode501), nowMs = 500L),
        )
    }

    @Test
    fun `throttles rapid events confirms pending skips and cancels when ad disappears before delay`() {
        val detector = ReelAdDetector()
        val adSnapshot = WindowSnapshot(
            packageName = SkipperConfig.TARGET_PACKAGE,
            nodes = listOf(reelsContextNode(), VisibleNode(text = "Sponsored")),
        )
        val organicSnapshot = WindowSnapshot(
            packageName = SkipperConfig.TARGET_PACKAGE,
            nodes = listOf(reelsContextNode(), VisibleNode(text = "Funny cat video")),
        )

        assertEquals(SkipDecision.Ignore(IgnoreReason.NO_SPONSORED_LABEL), detector.evaluate(organicSnapshot, nowMs = 1_000L))
        assertEquals(SkipDecision.Ignore(IgnoreReason.THROTTLED), detector.evaluate(adSnapshot, nowMs = 1_100L))
        assertEquals(SkipDecision.ScheduleSkip(400L), detector.evaluate(adSnapshot, nowMs = 1_150L))

        assertEquals(SkipDecision.AwaitPendingSkip, detector.evaluate(adSnapshot, nowMs = 1_200L))
        assertEquals(SkipDecision.AwaitPendingSkip, detector.evaluate(adSnapshot, nowMs = 1_350L))
        assertTrue(detector.isSponsoredReelPresent(adSnapshot))

        assertEquals(SkipDecision.CancelPendingSkip, detector.evaluate(organicSnapshot, nowMs = 1_500L))
        assertFalse(detector.isSponsoredReelPresent(organicSnapshot))
    }

    @Test
    fun `suppresses skip triggers during 1200 ms cooldown window and resumes afterward`() {
        val detector = ReelAdDetector()
        val adSnapshot = WindowSnapshot(
            packageName = SkipperConfig.TARGET_PACKAGE,
            nodes = listOf(reelsContextNode(), VisibleNode(text = "Sponsored")),
        )

        assertTrue(detector.shouldInspectSnapshot(SkipperConfig.TARGET_PACKAGE, nowMs = 1_000L))
        assertEquals(SkipDecision.ScheduleSkip(400L), detector.evaluate(adSnapshot, nowMs = 1_000L))
        assertFalse(detector.shouldInspectSnapshot(SkipperConfig.TARGET_PACKAGE, nowMs = 1_100L))

        detector.onSkipDispatchStarted()
        assertFalse(detector.shouldInspectSnapshot(SkipperConfig.TARGET_PACKAGE, nowMs = 1_400L))
        assertEquals(
            SkipDecision.Ignore(IgnoreReason.COOLDOWN_ACTIVE),
            detector.evaluate(adSnapshot, nowMs = 1_450L),
        )
        assertFalse(detector.isSponsoredReelPresent(adSnapshot, nowMs = 1_450L))

        detector.onSkipDispatched(nowMs = 1_400L)
        assertFalse(detector.shouldInspectSnapshot(SkipperConfig.TARGET_PACKAGE, nowMs = 2_599L))
        assertFalse(detector.isSponsoredReelPresent(adSnapshot, nowMs = 2_599L))

        assertEquals(
            SkipDecision.Ignore(IgnoreReason.COOLDOWN_ACTIVE),
            detector.evaluate(adSnapshot, nowMs = 2_599L),
        )
        assertTrue(detector.shouldInspectSnapshot(SkipperConfig.TARGET_PACKAGE, nowMs = 2_600L))
        assertEquals(
            SkipDecision.ScheduleSkip(400L),
            detector.evaluate(adSnapshot, nowMs = 2_600L),
        )
    }

    @Test
    fun `clamps and snaps skip delay values to 100 through 2000 ms bounds`() {
        assertEquals(100L, SkipperConfig.clampSkipDelay(0L))
        assertEquals(100L, SkipperConfig.clampSkipDelay(100L))
        assertEquals(650L, SkipperConfig.clampSkipDelay(650L))
        assertEquals(2_000L, SkipperConfig.clampSkipDelay(2_000L))
        assertEquals(2_000L, SkipperConfig.clampSkipDelay(5_000L))

        assertEquals(100L, SkipperConfig.snapAndClampSkipDelay(25L))
        assertEquals(100L, SkipperConfig.snapAndClampSkipDelay(124L))
        assertEquals(150L, SkipperConfig.snapAndClampSkipDelay(125L))
        assertEquals(400L, SkipperConfig.snapAndClampSkipDelay(400L))
        assertEquals(2_000L, SkipperConfig.snapAndClampSkipDelay(2_400L))
    }

    @Test
    fun `validates custom keywords with trim length bounds punctuation check and Turkish duplicate folding`() {
        assertEquals(
            KeywordValidationResult.Invalid(KeywordValidationError.BLANK),
            KeywordValidator.validateCustomKeyword("   \n\t "),
        )
        assertEquals(
            KeywordValidationResult.Invalid(KeywordValidationError.TOO_SHORT),
            KeywordValidator.validateCustomKeyword(" a "),
        )
        assertEquals(
            KeywordValidationResult.Invalid(KeywordValidationError.TOO_LONG),
            KeywordValidator.validateCustomKeyword("x".repeat(41)),
        )
        assertEquals(
            KeywordValidationResult.Invalid(KeywordValidationError.NO_ALPHANUMERIC),
            KeywordValidator.validateCustomKeyword(" ••• "),
        )

        val builtInDup = KeywordValidator.validateCustomKeyword("sponsorlu")
        assertEquals(KeywordValidationResult.Invalid(KeywordValidationError.DUPLICATE), builtInDup)

        val turkishIWithDotDup = KeywordValidator.validateCustomKeyword("PAİD PARTNERSHİP")
        assertEquals(KeywordValidationResult.Invalid(KeywordValidationError.DUPLICATE), turkishIWithDotDup)

        val customDup = KeywordValidator.validateCustomKeyword(
            raw = "  işbirliği  ",
            existingCustomKeywords = setOf("İşbirliği"),
        )
        assertEquals(KeywordValidationResult.Invalid(KeywordValidationError.DUPLICATE), customDup)

        val minBoundary = KeywordValidator.validateCustomKeyword("  Ad  ")
        assertEquals(KeywordValidationResult.Valid("Ad"), minBoundary)

        val maxBoundary = "r".repeat(40)
        assertEquals(KeywordValidationResult.Valid(maxBoundary), KeywordValidator.validateCustomKeyword(maxBoundary))

        assertTrue(KeywordValidator.isBuiltInKeyword("SPONSORED"))
        assertTrue(KeywordValidator.isBuiltInKeyword("Sponsorlu"))
        assertFalse(KeywordValidator.isBuiltInKeyword("Reklam"))
    }
}
