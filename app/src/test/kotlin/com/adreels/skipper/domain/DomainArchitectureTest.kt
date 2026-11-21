package com.adreels.skipper.domain

import java.io.File
import kotlin.test.assertEquals
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

    private fun resolveRepoFile(relativePath: String): File {
        return listOf(
            File(relativePath),
            File("../$relativePath"),
        ).firstOrNull { it.exists() && it.isFile }
            ?: error("Could not locate file $relativePath from working directory")
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

    @Test
    fun `ui package never references AccessibilityService or gesture dispatch APIs`() {
        val uiDir = resolveSourceDir("src/main/kotlin/com/adreels/skipper/ui")
        val kotlinFiles = uiDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(kotlinFiles.isNotEmpty(), "Expected Kotlin files in ui package")

        val forbiddenUiPatterns = listOf(
            "AccessibilityService",
            "SkipperAccessibilityService",
            "GestureDescription",
            "dispatchGesture",
        )

        for (file in kotlinFiles) {
            val content = file.readText()
            for (forbidden in forbiddenUiPatterns) {
                assertFalse(
                    content.contains(forbidden),
                    "${file.name} in ui package must not reference $forbidden",
                )
            }
        }
    }

    @Test
    fun `no application package imports networking libraries`() {
        val srcRoot = resolveSourceDir("src/main/kotlin/com/adreels/skipper")
        val kotlinFiles = srcRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(kotlinFiles.isNotEmpty(), "Expected Kotlin files in main source tree")

        val forbiddenNetworkImports = listOf(
            "import java.net.",
            "import javax.net.",
            "import okhttp3.",
            "import retrofit2.",
            "import io.ktor.",
            "import android.net.http.",
            "import android.webkit.",
        )

        for (file in kotlinFiles) {
            val networkImports = file.readLines()
                .map { it.trim() }
                .filter { line -> forbiddenNetworkImports.any { prefix -> line.startsWith(prefix) } }
            assertTrue(
                networkImports.isEmpty(),
                "${file.name} must not import networking APIs (INV-1): $networkImports",
            )
        }
    }

    @Test
    fun `build enforces allWarningsAsErrors ktlintCheck and detekt without runtime APK dependencies`() {
        val gradleBuild = resolveRepoFile("app/build.gradle.kts").readText()
        assertTrue(gradleBuild.contains("allWarningsAsErrors = true"), "kotlinOptions must enable allWarningsAsErrors")
        assertTrue(gradleBuild.contains("allWarningsAsErrors.set(true)"), "KotlinCompile tasks must enable allWarningsAsErrors")
        assertTrue(gradleBuild.contains("com.pinterest.ktlint:ktlint-cli:"), "Detached ktlint-cli configuration required")
        assertTrue(gradleBuild.contains("io.gitlab.arturbosch.detekt:detekt-cli:"), "Detached detekt-cli configuration required")
        assertTrue(gradleBuild.contains("tasks.register<JavaExec>(\"ktlintCheck\")"), "ktlintCheck task must be registered")
        assertTrue(gradleBuild.contains("tasks.register<JavaExec>(\"detekt\")"), "detekt task must be registered")

        val runtimeDependencyLines = gradleBuild.lines()
            .map { it.trim() }
            .filter { line ->
                line.startsWith("implementation(") ||
                    line.startsWith("api(") ||
                    line.startsWith("runtimeOnly(")
            }
        assertTrue(
            runtimeDependencyLines.none { it.contains("ktlint") || it.contains("detekt") },
            "Static analysis tools must never leak into runtime APK dependencies: $runtimeDependencyLines",
        )

        assertTrue(resolveRepoFile(".editorconfig").readText().isNotBlank(), ".editorconfig must exist")
        assertTrue(resolveRepoFile("config/detekt/detekt.yml").readText().isNotBlank(), "Detekt config must exist")

        val checkConf = resolveRepoFile("scripts/check.conf").readLines()
        val lintLine = checkConf.firstOrNull { it.trimStart().startsWith("lint [fast]:") }
            ?: error("scripts/check.conf must define lint [fast]")
        assertTrue(
            lintLine.contains("./gradlew ktlintCheck detekt lintDebug --no-daemon"),
            "lint [fast] must run ktlintCheck, detekt, and lintDebug: $lintLine",
        )
    }

    @Test
    fun `ci workflow enforces read-only permissions gradle setup in doctor and scoped chmod`() {
        val workflow = resolveRepoFile(".github/workflows/check.yml").readText()
        val expectedChmod = "chmod +x gradlew scripts/check scripts/doctor scripts/residue scripts/selftest scripts/init"

        assertTrue(
            Regex("""(?m)^permissions:\s*\n\s+contents:\s*read\b""").containsMatchIn(workflow),
            "CI workflow must declare top-level permissions: contents: read",
        )
        assertFalse(workflow.contains("scripts/*"), "CI workflow must not wildcard chmod scripts/*")
        assertFalse(workflow.contains("scripts/check.conf"), "CI workflow must not mark scripts/check.conf executable")
        assertEquals(
            2,
            Regex(Regex.escape(expectedChmod)).findAll(workflow).count(),
            "Both check and doctor jobs must use scoped chmod +x",
        )

        val setupGradleCount = Regex("""gradle/actions/setup-gradle@v4""").findAll(workflow).count()
        assertEquals(2, setupGradleCount, "Both check and doctor jobs must configure gradle/actions/setup-gradle@v4")

        val doctorSection = workflow.substringAfter("  doctor:")
        assertTrue(
            doctorSection.contains("gradle/actions/setup-gradle@v4"),
            "doctor job must configure gradle/actions/setup-gradle@v4",
        )
    }
}
