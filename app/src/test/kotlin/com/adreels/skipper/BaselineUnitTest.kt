package com.adreels.skipper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BaselineUnitTest {

    @Test
    fun `baseline environment check passes`() {
        val appName = "Ad Reels Skipper"
        assertEquals("Ad Reels Skipper", appName)
        assertTrue(true, "Baseline unit test assertion must succeed")
    }
}
