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

    private fun stripKotlinComments(source: String): String {
        val result = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val ch = source[i]
            val next = source.getOrNull(i + 1)
            when {
                source.startsWith("\"\"\"", i) -> {
                    val close = source.indexOf("\"\"\"", i + 3)
                    val end = if (close == -1) source.length else close + 3
                    result.append(source, i, end)
                    i = end
                }
                ch == '"' -> {
                    val end = findClosingQuote(source, i)
                    result.append(source, i, end)
                    i = end
                }
                ch == '/' && next == '/' -> {
                    val newline = source.indexOf('\n', i + 2)
                    i = if (newline == -1) source.length else newline
                }
                ch == '/' && next == '*' -> {
                    val close = source.indexOf("*/", i + 2)
                    i = if (close == -1) source.length else close + 2
                }
                else -> {
                    result.append(ch)
                    i++
                }
            }
        }
        return result.toString()
    }

    private fun findClosingQuote(source: String, openIndex: Int): Int {
        var i = openIndex + 1
        while (i < source.length) {
            when (source[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        return source.length
    }

    private fun stripYamlComments(source: String): String {
        return source.lines().joinToString("\n") { line ->
            line.replace(Regex("""(?:^|\s)#.*$"""), "")
        }
    }

    private fun gradleTaskBlock(buildScript: String, taskName: String): String {
        val marker = """tasks.register<JavaExec>("$taskName") {"""
        val afterStart = buildScript.substringAfter(marker, missingDelimiterValue = "")
        assertTrue(afterStart.isNotBlank(), "$taskName task must be registered in app/build.gradle.kts")
        return afterStart.substringBefore("\n}\n")
    }

    @Test
    fun `build enforces allWarningsAsErrors ktlintCheck and detekt without runtime APK dependencies`() {
        val gradleBuild = stripKotlinComments(resolveRepoFile("app/build.gradle.kts").readText())
        assertTrue(gradleBuild.contains("allWarningsAsErrors = true"), "kotlinOptions must enable allWarningsAsErrors")
        assertTrue(gradleBuild.contains("allWarningsAsErrors.set(true)"), "KotlinCompile tasks must enable allWarningsAsErrors")
        assertTrue(gradleBuild.contains("com.pinterest.ktlint:ktlint-cli:"), "Detached ktlint-cli configuration required")
        assertTrue(gradleBuild.contains("io.gitlab.arturbosch.detekt:detekt-cli:"), "Detached detekt-cli configuration required")

        listOf("ktlintCheck", "detekt").forEach { taskName ->
            val taskBody = gradleTaskBlock(gradleBuild, taskName)
            assertTrue(taskBody.contains("inputs."), "$taskName task must declare Gradle inputs")
            assertTrue(taskBody.contains("outputs."), "$taskName task must declare Gradle outputs")
        }

        val pluginsBlock = Regex("""plugins\s*\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
            .find(gradleBuild)?.groupValues?.get(1)
            ?: error("plugins block must exist in app/build.gradle.kts")
        assertFalse(
            pluginsBlock.contains("ktlint") || pluginsBlock.contains("detekt"),
            "plugins block must not apply Gradle ktlint/detekt plugins: $pluginsBlock",
        )

        val dependenciesBlock = Regex("""dependencies\s*\{(.*?)\n\}""", RegexOption.DOT_MATCHES_ALL)
            .find(gradleBuild)?.groupValues?.get(1)
            ?: error("dependencies block must exist in app/build.gradle.kts")
        assertFalse(
            dependenciesBlock.contains("ktlint", ignoreCase = true) || dependenciesBlock.contains("detekt", ignoreCase = true),
            "Static analysis tools must never leak into dependencies {}: $dependenciesBlock",
        )

        val apkConfigs = listOf(
            "implementation",
            "debugImplementation",
            "releaseImplementation",
            "api",
            "runtimeOnly",
            "compileOnly",
            "annotationProcessor",
            "ksp",
            "kapt",
        ).joinToString("|")
        val dependencyCallPattern = Regex(
            """\b(?:$apkConfigs)\s*\([^)]*(?:ktlint|detekt)[^)]*\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
        assertFalse(
            dependencyCallPattern.containsMatchIn(gradleBuild),
            "Static analysis tools must never leak into runtime/build APK dependency configurations",
        )

        val editorConfig = stripYamlComments(resolveRepoFile(".editorconfig").readText())
        assertTrue(Regex("""(?m)^indent_size\s*=\s*4\b""").containsMatchIn(editorConfig), ".editorconfig must enforce indent_size = 4")
        assertTrue(
            editorConfig.contains("ktlint_function_naming_ignore_when_annotated_with = Composable"),
            ".editorconfig must exempt @Composable naming",
        )
        assertFalse(
            Regex("""(?m)^ktlint_standard_indent\s*=\s*disabled\b""").containsMatchIn(editorConfig),
            ".editorconfig must not disable ktlint_standard_indent",
        )

        val detektConfig = stripYamlComments(resolveRepoFile("config/detekt/detekt.yml").readText())
        assertTrue(Regex("""(?m)^\s*maxIssues:\s*0\b""").containsMatchIn(detektConfig), "Detekt config must enforce maxIssues: 0")
        assertTrue(
            Regex("""TooGenericExceptionCaught:\s*\n\s*active:\s*true\b""").containsMatchIn(detektConfig),
            "Detekt config must keep TooGenericExceptionCaught active",
        )
        assertTrue(
            Regex("""SwallowedException:\s*\n\s*active:\s*true\b""").containsMatchIn(detektConfig),
            "Detekt config must keep SwallowedException active",
        )
        assertTrue(detektConfig.contains("'Composable'"), "Detekt config must exempt @Composable functions")

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
        val workflow = stripYamlComments(resolveRepoFile(".github/workflows/check.yml").readText())
        val expectedChmod = "chmod +x gradlew scripts/check scripts/doctor scripts/residue scripts/selftest scripts/init"

        assertTrue(
            Regex("""(?m)^permissions:\s*\n\s+contents:\s*read\b""").containsMatchIn(workflow),
            "CI workflow must declare top-level permissions: contents: read",
        )

        val chmodLines = workflow.lines().filter { it.contains("chmod") }
        assertTrue(chmodLines.isNotEmpty(), "CI workflow must configure executable permissions via chmod")
        for (line in chmodLines) {
            assertFalse(line.contains("scripts/*"), "CI workflow must not wildcard chmod scripts/*: $line")
            assertFalse(line.contains("scripts/check.conf"), "CI workflow must not mark scripts/check.conf executable: $line")
        }

        val checkJob = workflow.substringAfter("\n  check:", missingDelimiterValue = "").substringBefore("\n  doctor:")
        val doctorJob = workflow.substringAfter("\n  doctor:", missingDelimiterValue = "")
        val jobContracts = listOf(
            Triple("check", checkJob, "./scripts/check"),
            Triple("doctor", doctorJob, "./scripts/doctor --strict"),
        )

        for ((jobName, jobBody, execCommand) in jobContracts) {
            assertTrue(jobBody.isNotBlank(), "$jobName job must exist in .github/workflows/check.yml")
            assertFalse(
                Regex("""(?m)^\s+permissions:""").containsMatchIn(jobBody),
                "$jobName job must not override top-level read-only permissions",
            )
            val execIndex = jobBody.indexOf(execCommand)
            val gradleSetupIndex = jobBody.indexOf("gradle/actions/setup-gradle@v4")
            val chmodIndex = jobBody.indexOf(expectedChmod)
            assertTrue(execIndex >= 0, "$jobName job must run $execCommand")
            assertTrue(gradleSetupIndex in 0 until execIndex, "$jobName job must run setup-gradle@v4 before $execCommand")
            assertTrue(chmodIndex in 0 until execIndex, "$jobName job must run scoped chmod +x before $execCommand")
        }
    }
}
