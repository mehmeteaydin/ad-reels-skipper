package com.adreels.skipper

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ManifestVerificationTest {

    @Test
    fun `manifest must declare accessibility service and forbid internet permission`() {
        val manifestFile = listOf(
            File("app/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml"),
            File("../app/src/main/AndroidManifest.xml")
        ).firstOrNull { it.exists() }

        assertTrue(manifestFile != null && manifestFile.exists(), "AndroidManifest.xml must exist")
        val content = manifestFile!!.readText()

        // AC-2: Must declare SkipperAccessibilityService with BIND_ACCESSIBILITY_SERVICE
        assertTrue(
            content.contains(".service.SkipperAccessibilityService"),
            "Manifest must declare SkipperAccessibilityService"
        )
        assertTrue(
            content.contains("android.permission.BIND_ACCESSIBILITY_SERVICE"),
            "Manifest must guard service with BIND_ACCESSIBILITY_SERVICE"
        )

        // AC-2: Strictly zero network access
        assertFalse(
            content.contains("android.permission.INTERNET"),
            "Manifest MUST NOT request android.permission.INTERNET"
        )
    }
}
