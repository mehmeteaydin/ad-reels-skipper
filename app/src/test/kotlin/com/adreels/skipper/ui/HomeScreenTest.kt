package com.adreels.skipper.ui

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adreels.skipper.AccessibilityServiceStatusChecker
import com.adreels.skipper.MainActivity
import com.adreels.skipper.data.SkipperSettings
import com.adreels.skipper.data.SkipperSettingsRepository
import com.adreels.skipper.data.SkipperSettingsRepositoryProvider
import com.adreels.skipper.domain.KeywordValidationResult
import com.adreels.skipper.domain.KeywordValidator
import com.adreels.skipper.domain.SkipperConfig
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreenTest {

    private class InMemorySkipperSettingsRepository(
        initial: SkipperSettings = SkipperSettings(),
    ) : SkipperSettingsRepository {
        private val state = MutableStateFlow(initial)
        override val settingsFlow: StateFlow<SkipperSettings> = state.asStateFlow()

        override suspend fun setMasterEnabled(enabled: Boolean) {
            state.value = state.value.copy(isMasterEnabled = enabled)
        }

        override suspend fun setSkipDelayMs(delayMs: Long) {
            state.value = state.value.copy(skipDelayMs = SkipperConfig.clampSkipDelay(delayMs))
        }

        override suspend fun addCustomKeyword(rawKeyword: String): KeywordValidationResult {
            val result = KeywordValidator.validateCustomKeyword(rawKeyword, state.value.customKeywords)
            if (result is KeywordValidationResult.Valid) {
                state.value = state.value.copy(
                    customKeywords = state.value.customKeywords + result.trimmedKeyword,
                )
            }
            return result
        }

        override suspend fun removeCustomKeyword(keyword: String): Boolean {
            if (KeywordValidator.isBuiltInKeyword(keyword)) return false
            val updated = state.value.customKeywords - keyword
            val changed = updated.size != state.value.customKeywords.size
            if (changed) {
                state.value = state.value.copy(customKeywords = updated)
            }
            return changed
        }

        override suspend fun incrementSkippedReelsCount(date: LocalDate) {
            val updatedCount = (state.value.dailySkipCounts[date] ?: 0) + 1
            state.value = state.value.copy(
                skippedReelsCount = state.value.skippedReelsCount + 1,
                dailySkipCounts = state.value.dailySkipCounts + (date to updatedCount),
            )
        }
    }

    private lateinit var fakeRepository: InMemorySkipperSettingsRepository
    private var fakeServiceEnabled: Boolean = false

    private val environmentRule = object : ExternalResource() {
        override fun before() {
            fakeRepository = InMemorySkipperSettingsRepository()
            fakeServiceEnabled = false
            SkipperSettingsRepositoryProvider.setForTesting(fakeRepository)
            AccessibilityServiceStatusChecker.setForTesting { fakeServiceEnabled }
        }

        override fun after() {
            AccessibilityServiceStatusChecker.setForTesting(null)
            SkipperSettingsRepositoryProvider.setForTesting(null)
        }
    }

    private val composeTestRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(environmentRule).around(composeTestRule)

    private fun captureStateScreenshotIfRequested(fileName: String) {
        val outputDirPath = System.getProperty("skipper.screenshot.dir")?.trim().orEmpty()
        if (outputDirPath.isEmpty()) return
        composeTestRule.waitForIdle()
        val bitmap = composeTestRule.runOnIdle {
            val decorView = composeTestRule.activity.window.decorView
            assertTrue(decorView.width > 0 && decorView.height > 0)
            Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888).also { target ->
                val canvas = android.graphics.Canvas(target)
                decorView.draw(canvas)
            }
        }
        val outputDir = File(outputDirPath).apply { mkdirs() }
        val outputFile = File(outputDir, fileName)
        FileOutputStream(outputFile).use { stream ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    @Test
    fun `renders state S-1 permission required dispatches accessibility settings intent and refreshes on resume`() {
        composeTestRule.onNodeWithText("Ad Reels Skipper").assertIsDisplayed()
        composeTestRule.onNodeWithText("Permission Required").assertIsDisplayed()
        composeTestRule.onNodeWithText("Enable Accessibility Service").assertIsDisplayed()
        composeTestRule.onNodeWithText("0").assertIsDisplayed()
        composeTestRule.onNodeWithText("150 ms").assertIsDisplayed()
        composeTestRule.onNodeWithTag(DashboardTags.MASTER_TOGGLE).assertIsOn()
        composeTestRule.onNodeWithText("Sponsored").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sponsorlu").assertIsDisplayed()
        composeTestRule.onNodeWithText("Paid partnership").assertIsDisplayed()

        captureStateScreenshotIfRequested("S-1-permission-required.png")

        composeTestRule.onNodeWithText("Enable Accessibility Service").performClick()
        val launchedIntent = Shadows.shadowOf(composeTestRule.activity).nextStartedActivity
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, launchedIntent?.action)

        fakeServiceEnabled = true
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Service Active").assertIsDisplayed()
        composeTestRule.onNodeWithText("Enable Accessibility Service").assertDoesNotExist()
    }

    @Test
    fun `renders state S-2 active service updates delay slider adds and removes custom keywords`() {
        fakeServiceEnabled = true
        repeat(12) {
            kotlinx.coroutines.runBlocking { fakeRepository.incrementSkippedReelsCount() }
        }
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        composeTestRule.onNodeWithText("Service Active").assertIsDisplayed()
        composeTestRule.onNodeWithText("12").assertIsDisplayed()
        composeTestRule.onNodeWithText("Adjustable from 50 ms to 2000 ms (50 ms steps)")
            .performScrollTo()
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag(DashboardTags.SKIP_DELAY_SLIDER)
            .performScrollTo()
            .assert(
                SemanticsMatcher("Skip delay range 50..2000 ms with 38 steps") { node ->
                    SemanticsProperties.ProgressBarRangeInfo in node.config &&
                        node.config[SemanticsProperties.ProgressBarRangeInfo] ==
                        ProgressBarRangeInfo(current = 150f, range = 50f..2000f, steps = 38)
                },
            )
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(50f)
            }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("50 ms").assertIsDisplayed()
        assertEquals(50L, fakeRepository.settingsFlow.value.skipDelayMs)

        composeTestRule.onNodeWithTag(DashboardTags.SKIP_DELAY_SLIDER)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(600f)
            }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("600 ms").assertIsDisplayed()
        assertEquals(600L, fakeRepository.settingsFlow.value.skipDelayMs)

        composeTestRule.onNodeWithTag(DashboardTags.SKIP_DELAY_SLIDER)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(2000f)
            }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("2000 ms").assertIsDisplayed()
        assertEquals(2000L, fakeRepository.settingsFlow.value.skipDelayMs)

        composeTestRule.onNodeWithTag(DashboardTags.KEYWORD_INPUT)
            .performScrollTo()
            .performTextInput("Reklam")
        composeTestRule.onNodeWithTag(DashboardTags.ADD_KEYWORD_BUTTON)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Reklam").assertIsDisplayed()
        assertEquals(setOf("Reklam"), fakeRepository.settingsFlow.value.customKeywords)

        captureStateScreenshotIfRequested("S-2-service-active.png")

        composeTestRule.onNodeWithContentDescription("Remove keyword Sponsorlu").assertDoesNotExist()
        composeTestRule.onNodeWithTag(DashboardTags.removeKeywordButton("Reklam"))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Reklam").assertDoesNotExist()
        assertTrue(fakeRepository.settingsFlow.value.customKeywords.isEmpty())
    }

    @Test
    fun `renders state S-3 paused skipping when master toggle is switched OFF preserving settings`() {
        fakeServiceEnabled = true
        kotlinx.coroutines.runBlocking {
            fakeRepository.setSkipDelayMs(800L)
            fakeRepository.addCustomKeyword("Reklam")
            repeat(7) { fakeRepository.incrementSkippedReelsCount() }
        }
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(DashboardTags.MASTER_TOGGLE)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(DashboardTags.MASTER_TOGGLE).assertIsOff()
        composeTestRule.onNodeWithText("Skipping Paused").assertIsDisplayed()
        composeTestRule.onNodeWithText("Service Active").assertIsDisplayed()
        composeTestRule.onNodeWithText("7").assertIsDisplayed()
        composeTestRule.onNodeWithText("800 ms").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reklam").performScrollTo().assertIsDisplayed()
        assertFalse(fakeRepository.settingsFlow.value.isMasterEnabled)
        assertEquals(800L, fakeRepository.settingsFlow.value.skipDelayMs)
        assertEquals(setOf("Reklam"), fakeRepository.settingsFlow.value.customKeywords)
        assertEquals(7, fakeRepository.settingsFlow.value.skippedReelsCount)

        captureStateScreenshotIfRequested("S-3-skipping-paused.png")
    }

    @Test
    fun `renders state S-4 inline validation error on invalid or duplicate keyword and clears on valid submit`() {
        fakeServiceEnabled = true
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        // Submit built-in duplicate keyword "sponsorlu" and capture S-4 error screenshot.
        composeTestRule.onNodeWithTag(DashboardTags.KEYWORD_INPUT)
            .performScrollTo()
            .performTextInput("sponsorlu")
        composeTestRule.onNodeWithTag(DashboardTags.ADD_KEYWORD_BUTTON)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(DashboardTags.KEYWORD_ERROR_TEXT)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Keyword already exists in active keywords.")
            .performScrollTo()
            .assertIsDisplayed()
        assertTrue(fakeRepository.settingsFlow.value.customKeywords.isEmpty())

        captureStateScreenshotIfRequested("S-4-invalid-keyword.png")

        // Submit blank, too-short, too-long, and punctuation-only inputs.
        composeTestRule.onNodeWithTag(DashboardTags.KEYWORD_INPUT).performTextClearance()
        composeTestRule.onNodeWithTag(DashboardTags.KEYWORD_INPUT).performTextInput("   ")
        composeTestRule.onNodeWithTag(DashboardTags.ADD_KEYWORD_BUTTON).performScrollTo().performClick()
        composeTestRule.onNodeWithText("Enter a keyword before adding.").performScrollTo().assertIsDisplayed()
        assertTrue(fakeRepository.settingsFlow.value.customKeywords.isEmpty())

        composeTestRule.onNodeWithTag(DashboardTags.KEYWORD_INPUT).performTextClearance()
        composeTestRule.onNodeWithTag(DashboardTags.KEYWORD_INPUT).performTextInput("a")
        composeTestRule.onNodeWithTag(DashboardTags.ADD_KEYWORD_BUTTON).performScrollTo().performClick()
        composeTestRule.onNodeWithText("Keyword must be at least 2 characters.").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag(DashboardTags.KEYWORD_INPUT).performTextClearance()
        composeTestRule.onNodeWithTag(DashboardTags.KEYWORD_INPUT).performTextInput("x".repeat(41))
        composeTestRule.onNodeWithTag(DashboardTags.ADD_KEYWORD_BUTTON).performScrollTo().performClick()
        composeTestRule.onNodeWithText("Keyword must be 40 characters or fewer.").performScrollTo().assertIsDisplayed()
        assertTrue(fakeRepository.settingsFlow.value.customKeywords.isEmpty())

        composeTestRule.onNodeWithTag(DashboardTags.KEYWORD_INPUT).performTextClearance()
        composeTestRule.onNodeWithTag(DashboardTags.KEYWORD_INPUT).performTextInput("•••")
        composeTestRule.onNodeWithTag(DashboardTags.ADD_KEYWORD_BUTTON).performScrollTo().performClick()
        composeTestRule.onNodeWithText("Keyword must include at least one letter or digit.").performScrollTo().assertIsDisplayed()

        // Valid submission clears error and persists the keyword.
        composeTestRule.onNodeWithTag(DashboardTags.KEYWORD_INPUT).performTextClearance()
        composeTestRule.onNodeWithTag(DashboardTags.KEYWORD_INPUT).performTextInput("İşbirliği")
        composeTestRule.onNodeWithTag(DashboardTags.ADD_KEYWORD_BUTTON).performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(DashboardTags.KEYWORD_ERROR_TEXT).assertDoesNotExist()
        composeTestRule.onNodeWithText("İşbirliği").performScrollTo().assertIsDisplayed()
        assertEquals(setOf("İşbirliği"), fakeRepository.settingsFlow.value.customKeywords)
    }
}
