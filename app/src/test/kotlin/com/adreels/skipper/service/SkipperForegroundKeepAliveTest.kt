package com.adreels.skipper.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Looper
import com.adreels.skipper.MainActivity
import com.adreels.skipper.data.SkipperSettings
import com.adreels.skipper.data.SkipperSettingsRepository
import com.adreels.skipper.data.SkipperSettingsRepositoryProvider
import com.adreels.skipper.domain.KeywordValidationResult
import com.adreels.skipper.domain.SkipperConfig
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config

private class KeepAliveFakeRepository(
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
        return KeywordValidationResult.Valid(rawKeyword)
    }

    override suspend fun removeCustomKeyword(keyword: String): Boolean = false

    override suspend fun incrementSkippedReelsCount(date: LocalDate) {
        state.value = state.value.copy(skippedReelsCount = state.value.skippedReelsCount + 1)
    }
}

private fun invokeOnServiceConnected(service: SkipperAccessibilityService) {
    SkipperAccessibilityService::class.java
        .getDeclaredMethod("onServiceConnected")
        .apply { isAccessible = true }
        .invoke(service)
}

@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class SkipperForegroundKeepAliveTest {

    private lateinit var fakeRepository: KeepAliveFakeRepository

    @Before
    fun setUp() {
        SkipperAccessibilityService.setConnectedForTesting(false)
        fakeRepository = KeepAliveFakeRepository()
        SkipperSettingsRepositoryProvider.setForTesting(fakeRepository)
    }

    @After
    fun tearDown() {
        SkipperAccessibilityService.setConnectedForTesting(false)
        SkipperSettingsRepositoryProvider.setForTesting(null)
    }

    @Test
    @Config(sdk = [33])
    fun `promotes silent keep-alive foreground on Android 13 and stops on toggle off or unbind`() = runBlocking {
        val controller = Robolectric.buildService(SkipperAccessibilityService::class.java).create()
        val service = controller.get()
        val shadowService = Shadows.shadowOf(service as Service)

        invokeOnServiceConnected(service)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val notificationManager = service.getSystemService(NotificationManager::class.java)
        val channel = notificationManager.getNotificationChannel(SkipperAccessibilityService.KEEP_ALIVE_CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_MIN, channel.importance)
        assertFalse(channel.canShowBadge())
        assertFalse(channel.shouldVibrate())

        val notification = shadowService.lastForegroundNotification
        assertNotNull(notification)
        assertEquals(
            SkipperAccessibilityService.KEEP_ALIVE_NOTIFICATION_ID,
            shadowService.lastForegroundNotificationId,
        )
        assertTrue((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertEquals("Ad Reels Skipper Active", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(
            "Monitoring Instagram Reels locally on-device",
            notification.extras.getString(Notification.EXTRA_TEXT),
        )
        assertNotNull(notification.contentIntent)
        val pendingActivityIntent = Shadows.shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, pendingActivityIntent.component?.className)
        assertTrue(Shadows.shadowOf(notification.contentIntent).isImmutable)

        // Skip metric increments keep existing notification active without resetting foreground state.
        fakeRepository.incrementSkippedReelsCount()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadowService.isForegroundStopped)

        // Master Toggle OFF stops foreground and removes the ongoing notification.
        fakeRepository.setMasterEnabled(false)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowService.isForegroundStopped)
        assertTrue(shadowService.notificationShouldRemoved)

        // Turning Master Toggle back ON while still connected resumes foreground keep-alive.
        fakeRepository.setMasterEnabled(true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadowService.isForegroundStopped)
        assertTrue(shadowService.isLastForegroundNotificationAttached)

        // System unbind removes foreground keep-alive notification.
        service.onUnbind(null)
        assertTrue(shadowService.isForegroundStopped)
        assertTrue(shadowService.notificationShouldRemoved)
        assertFalse(SkipperAccessibilityService.isConnectedFlow.value)
    }

    @Test
    @Config(sdk = [35])
    fun `promotes specialUse foreground service on API 35 and stops on destroy`() {
        val controller = Robolectric.buildService(SkipperAccessibilityService::class.java).create()
        val service = controller.get()
        val shadowService = Shadows.shadowOf(service as Service)

        invokeOnServiceConnected(service)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(shadowService.isLastForegroundNotificationAttached)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            service.foregroundServiceType,
        )

        controller.destroy()
        assertTrue(shadowService.isForegroundStopped)
        assertTrue(shadowService.notificationShouldRemoved)
        assertFalse(SkipperAccessibilityService.isConnectedFlow.value)
    }

    @Test
    @Config(sdk = [33])
    fun `recovers fail-safely when foreground promotion or removal throws SecurityException`() {
        val promoterController = Robolectric.buildService(SkipperAccessibilityService::class.java).create()
        val promoterService = promoterController.get().apply {
            foregroundPromoter = { _, _ -> throw SecurityException("Blocked by OEM battery policy") }
        }

        invokeOnServiceConnected(promoterService)
        assertTrue(SkipperAccessibilityService.isConnectedFlow.value)
        promoterService.onUnbind(null)
        assertFalse(SkipperAccessibilityService.isConnectedFlow.value)

        val stopperController = Robolectric.buildService(SkipperAccessibilityService::class.java).create()
        var stopperCalled = false
        val stopperService = stopperController.get().apply {
            foregroundStopper = {
                stopperCalled = true
                error("Binder stop failure")
            }
        }

        invokeOnServiceConnected(stopperService)
        assertTrue(SkipperAccessibilityService.isConnectedFlow.value)

        stopperService.onUnbind(null)
        assertTrue(stopperCalled)
        assertFalse(SkipperAccessibilityService.isConnectedFlow.value)
    }
}
