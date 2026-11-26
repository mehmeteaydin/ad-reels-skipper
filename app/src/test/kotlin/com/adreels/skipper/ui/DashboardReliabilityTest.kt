package com.adreels.skipper.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adreels.skipper.AccessibilityServiceStatusChecker
import com.adreels.skipper.BatteryOptimizationStatusChecker
import com.adreels.skipper.MainActivity
import com.adreels.skipper.NotificationPermissionStatusChecker
import com.adreels.skipper.data.SkipperSettings
import com.adreels.skipper.data.SkipperSettingsRepository
import com.adreels.skipper.data.SkipperSettingsRepositoryProvider
import com.adreels.skipper.domain.KeywordValidationResult
import com.adreels.skipper.domain.KeywordValidator
import com.adreels.skipper.domain.SkipperConfig
import com.adreels.skipper.service.SkipperAccessibilityService
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardReliabilityTest {

    private class FakeSettingsRepository : SkipperSettingsRepository {
        private val state = MutableStateFlow(SkipperSettings())
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
                state.value = state.value.copy(customKeywords = state.value.customKeywords + result.trimmedKeyword)
            }
            return result
        }

        override suspend fun removeCustomKeyword(keyword: String): Boolean = false

        override suspend fun incrementSkippedReelsCount(date: LocalDate) {
            val count = (state.value.dailySkipCounts[date] ?: 0) + 1
            state.value = state.value.copy(
                skippedReelsCount = state.value.skippedReelsCount + 1,
                dailySkipCounts = state.value.dailySkipCounts + (date to count),
            )
        }
    }

    private var fakeBatteryUnrestricted: Boolean = false
    private var fakeKeepAliveNotificationEnabled: Boolean = false

    private val environmentRule = object : ExternalResource() {
        override fun before() {
            DashboardClock.testingTodayOverride = null
            fakeBatteryUnrestricted = false
            fakeKeepAliveNotificationEnabled = false
            SkipperSettingsRepositoryProvider.setForTesting(FakeSettingsRepository())
            SkipperAccessibilityService.setConnectedForTesting(false)
            AccessibilityServiceStatusChecker.setStateForTesting(null)
            AccessibilityServiceStatusChecker.setForTesting(null)
            BatteryOptimizationStatusChecker.setForTesting { fakeBatteryUnrestricted }
            NotificationPermissionStatusChecker.setForTesting { fakeKeepAliveNotificationEnabled }

            val context = RuntimeEnvironment.getApplication()
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                "${context.packageName}/.service.SkipperAccessibilityService",
            )
        }

        override fun after() {
            val context = RuntimeEnvironment.getApplication()
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                "",
            )
            DashboardClock.testingTodayOverride = null
            AccessibilityServiceStatusChecker.setStateForTesting(null)
            AccessibilityServiceStatusChecker.setForTesting(null)
            BatteryOptimizationStatusChecker.setForTesting(null)
            NotificationPermissionStatusChecker.setForTesting(null)
            SkipperAccessibilityService.setConnectedForTesting(false)
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
            val target = Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888)
            decorView.draw(Canvas(target))
            target
        }
        val outputFile = File(File(outputDirPath).apply { mkdirs() }, fileName)
        FileOutputStream(outputFile).use { stream ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    private fun minTouchTargetPx(): Float = 48f * composeTestRule.activity.resources.displayMetrics.density

    @Test
    fun `renders state S-9 service stopped dispatches reconnect intent and transitions live on connect and disconnect`() {
        composeTestRule.onNodeWithText("Service Stopped").assertIsDisplayed()
        val expectedStoppedDesc = "The background service was stopped by the system. " +
            "Toggle Ad Reels Skipper off and back on in Accessibility Settings to resume automatic skipping."
        composeTestRule.onNodeWithText(expectedStoppedDesc).assertIsDisplayed()
        composeTestRule.onNodeWithText("Permission Required").assertDoesNotExist()
        composeTestRule.onNodeWithText("Service Active").assertDoesNotExist()

        val reconnectButton = composeTestRule.onNodeWithTag(DashboardTags.RECONNECT_SERVICE_BUTTON)
        reconnectButton.assertIsDisplayed()
        val reconnectBounds = reconnectButton.fetchSemanticsNode().boundsInRoot
        assertTrue(
            reconnectBounds.height >= minTouchTargetPx() - 1f,
            "Reconnect button height ${reconnectBounds.height} must satisfy 48dp touch target",
        )

        captureStateScreenshotIfRequested("S-9-service-stopped.png")

        reconnectButton.performClick()
        val reconnectIntent = Shadows.shadowOf(composeTestRule.activity).nextStartedActivity
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, reconnectIntent?.action)

        // Live connection transition without restarting or manually resuming the Activity.
        SkipperAccessibilityService.setConnectedForTesting(true)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Service Active").assertIsDisplayed()
        composeTestRule.onNodeWithTag(DashboardTags.RECONNECT_SERVICE_BUTTON).assertDoesNotExist()

        // Live disconnection reverts immediately to S-9 recovery prompt.
        SkipperAccessibilityService.setConnectedForTesting(false)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Service Stopped").assertIsDisplayed()
        composeTestRule.onNodeWithTag(DashboardTags.RECONNECT_SERVICE_BUTTON).assertIsDisplayed()
    }

    @Test
    fun `renders state S-10 background reliability below keywords updates battery status on resume and opens settings`() {
        val addKeywordNode = composeTestRule.onNodeWithTag(DashboardTags.ADD_KEYWORD_BUTTON).performScrollTo()
        val reliabilityCard = composeTestRule.onNodeWithTag(DashboardTags.BACKGROUND_RELIABILITY_CARD).performScrollTo()
        assertTrue(
            addKeywordNode.fetchSemanticsNode().boundsInRoot.bottom <= reliabilityCard.fetchSemanticsNode().boundsInRoot.top + 1f,
            "BackgroundReliabilityCard must appear below KeywordsCard",
        )

        composeTestRule.onNodeWithTag(DashboardTags.BATTERY_STATUS_TEXT)
            .performScrollTo()
            .assertTextEquals("Battery Optimization Active")
        val batteryButton = composeTestRule.onNodeWithTag(DashboardTags.CONFIGURE_BATTERY_BUTTON)
            .performScrollTo()
            .assertIsDisplayed()
        assertTrue(
            batteryButton.fetchSemanticsNode().boundsInRoot.height >= minTouchTargetPx() - 1f,
            "Configure Battery Settings button must satisfy 48dp touch target",
        )
        composeTestRule.onNodeWithTag(DashboardTags.KEEP_ALIVE_STATUS_TEXT)
            .performScrollTo()
            .assertTextEquals("Keep-Alive Notification Off")
        val keepAliveButton = composeTestRule.onNodeWithTag(DashboardTags.ENABLE_KEEP_ALIVE_BUTTON)
            .performScrollTo()
            .assertIsDisplayed()
        assertTrue(
            keepAliveButton.fetchSemanticsNode().boundsInRoot.height >= minTouchTargetPx() - 1f,
            "Enable Keep-Alive Notification button must satisfy 48dp touch target",
        )
        var requestedPermission = false
        composeTestRule.activity.notificationPermissionRequester = { requestedPermission = true }
        keepAliveButton.performClick()
        assertTrue(requestedPermission, "Expected inline Android 13+ notification permission prompt trigger")
        composeTestRule.activity.notificationPermissionRequester = null

        val autostartButton = composeTestRule.onNodeWithTag(DashboardTags.OPEN_AUTOSTART_BUTTON)
            .performScrollTo()
            .assertIsDisplayed()
        assertTrue(
            autostartButton.fetchSemanticsNode().boundsInRoot.height >= minTouchTargetPx() - 1f,
            "Open Autostart Settings button must satisfy 48dp touch target",
        )
        composeTestRule.onNodeWithText("Android 13+ Sideloaded Install Tip").assertDoesNotExist()
        composeTestRule.onNodeWithText("Open App Info").assertDoesNotExist()

        captureStateScreenshotIfRequested("S-10a-battery-optimized.png")

        batteryButton.performScrollTo().performClick()
        val batteryIntent = Shadows.shadowOf(composeTestRule.activity).nextStartedActivity
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, batteryIntent?.action)

        // Exempting battery optimization + enabling notifications and resuming updates S-10a -> S-10b.
        fakeBatteryUnrestricted = true
        fakeKeepAliveNotificationEnabled = true
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(DashboardTags.BATTERY_STATUS_TEXT)
            .performScrollTo()
            .assertTextEquals("Battery Unrestricted")
        composeTestRule.onNodeWithTag(DashboardTags.CONFIGURE_BATTERY_BUTTON).assertDoesNotExist()
        composeTestRule.onNodeWithTag(DashboardTags.KEEP_ALIVE_STATUS_TEXT)
            .performScrollTo()
            .assertTextEquals("Keep-Alive Notification Active")
        composeTestRule.onNodeWithTag(DashboardTags.ENABLE_KEEP_ALIVE_BUTTON).assertDoesNotExist()
        composeTestRule.onNodeWithTag(DashboardTags.OPEN_AUTOSTART_BUTTON)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Android 13+ Sideloaded Install Tip").assertDoesNotExist()
        composeTestRule.onNodeWithText("Open App Info").assertDoesNotExist()

        captureStateScreenshotIfRequested("S-10b-battery-unrestricted.png")
    }

    private fun recordIntentsWithFailure(failureForIntent: (Intent) -> Throwable?): List<Intent> {
        val recorded = mutableListOf<Intent>()
        composeTestRule.activity.activityStarter = { intent ->
            recorded.add(intent)
            failureForIntent(intent)?.let { throw it }
        }
        return recorded
    }

    @Test
    fun `battery autostart and notification fallbacks resolve safely when settings activities fail`() {
        // Verify Xiaomi MIUI Autostart intent and fallback on both ActivityNotFoundException and SecurityException.
        composeTestRule.onNodeWithTag(DashboardTags.OPEN_AUTOSTART_BUTTON)
            .performScrollTo()
            .performClick()
        val autostartIntent = Shadows.shadowOf(composeTestRule.activity).nextStartedActivity
        assertEquals(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            autostartIntent?.component,
        )

        val autostartSecurityIntents = recordIntentsWithFailure { intent ->
            if (intent.component?.packageName == "com.miui.securitycenter") {
                SecurityException("MIUI permission denied")
            } else {
                null
            }
        }
        composeTestRule.onNodeWithTag(DashboardTags.OPEN_AUTOSTART_BUTTON)
            .performScrollTo()
            .performClick()
        assertEquals(2, autostartSecurityIntents.size)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, autostartSecurityIntents[1].action)

        // Verify Keep-Alive Notification fallback once POST_NOTIFICATIONS is granted but system channel is off.
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notificationIntents = recordIntentsWithFailure { intent ->
            if (intent.action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
                ActivityNotFoundException("No notification settings activity")
            } else {
                null
            }
        }
        composeTestRule.onNodeWithTag(DashboardTags.ENABLE_KEEP_ALIVE_BUTTON)
            .performScrollTo()
            .performClick()
        assertEquals(
            listOf(
                Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ),
            notificationIntents.map { it.action },
        )

        // Simulate device lacking ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS -> falls back to App Info.
        val dispatchedIntents = recordIntentsWithFailure { intent ->
            if (intent.action == Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) {
                ActivityNotFoundException("No battery optimization settings activity")
            } else {
                null
            }
        }
        composeTestRule.onNodeWithTag(DashboardTags.CONFIGURE_BATTERY_BUTTON)
            .performScrollTo()
            .performClick()
        assertEquals(
            listOf(
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ),
            dispatchedIntents.map { it.action },
        )
        assertEquals("package:${composeTestRule.activity.packageName}", dispatchedIntents[1].data?.toString())

        // Simulate missing settings activities everywhere -> all buttons stay crash-free.
        recordIntentsWithFailure { ActivityNotFoundException("No settings activity") }
        composeTestRule.onNodeWithTag(DashboardTags.RECONNECT_SERVICE_BUTTON).performScrollTo().performClick()
        composeTestRule.onNodeWithTag(DashboardTags.CONFIGURE_BATTERY_BUTTON).performScrollTo().performClick()
        composeTestRule.onNodeWithTag(DashboardTags.ENABLE_KEEP_ALIVE_BUTTON).performScrollTo().performClick()
        composeTestRule.onNodeWithTag(DashboardTags.OPEN_AUTOSTART_BUTTON).performScrollTo().performClick()
        composeTestRule.onNodeWithText("Service Stopped").performScrollTo().assertIsDisplayed()
    }
}
