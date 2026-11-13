package com.adreels.skipper.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adreels.skipper.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HomeScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainActivityDisplaysApplicationTitleAndStatus() {
        composeTestRule.onNodeWithText("Ad Reels Skipper").assertIsDisplayed()
        composeTestRule.onNodeWithText("Status: Service Ready").assertIsDisplayed()
    }
}
