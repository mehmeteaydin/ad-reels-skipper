package com.adreels.skipper.domain

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DomainArchitectureTest {

    private fun resolveSourceDir(relativePath: String): File {
        return listOf(
            File("app/$relativePath"),
            File(relativePath),
            File("../app/$relativePath"),
        ).firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate directory $relativePath from working directory")
    }

    @Test
    fun `domain package contains zero android framework imports`() {
        val domainDir = resolveSourceDir("src/main/kotlin/com/adreels/skipper/domain")
        val kotlinFiles = domainDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(kotlinFiles.isNotEmpty(), "Expected Kotlin files in domain package")

        for (file in kotlinFiles) {
            val androidImports = file.readLines()
                .map { it.trim() }
                .filter { it.startsWith("import android.") || it.startsWith("import androidx.") }
            assertTrue(
                androidImports.isEmpty(),
                "${file.name} must not import Android framework classes: $androidImports",
            )
        }
    }

    @Test
    fun `domain and service packages never log screen node text or content descriptions`() {
        val forbiddenLogPatterns = listOf(
            Regex("""Log\.[vwidfe]\s*\(.*(\.text\b|\.contentDescription\b|VisibleNode|WindowSnapshot|nodes)""", RegexOption.DOT_MATCHES_ALL),
            Regex("""println\s*\(.*(\.text\b|\.contentDescription\b|VisibleNode|WindowSnapshot|nodes)""", RegexOption.DOT_MATCHES_ALL),
        )

        val sourceDirs = listOf(
            resolveSourceDir("src/main/kotlin/com/adreels/skipper/domain"),
            resolveSourceDir("src/main/kotlin/com/adreels/skipper/service"),
        )

        for (dir in sourceDirs) {
            val kotlinFiles = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            assertTrue(kotlinFiles.isNotEmpty(), "Expected Kotlin files in ${dir.name}")
            for (file in kotlinFiles) {
                val content = file.readText()
                for (pattern in forbiddenLogPatterns) {
                    assertFalse(
                        pattern.containsMatchIn(content),
                        "${file.name} must never log inspected screen content or node descriptions",
                    )
                }
            }
        }
    }
}
