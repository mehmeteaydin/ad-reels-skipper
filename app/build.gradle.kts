import java.io.File
import java.nio.file.Files
import java.util.Properties
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}

fun signingValue(propertyKey: String, envKey: String): String {
    val fromProperties = keystoreProperties.getProperty(propertyKey)?.trim().orEmpty()
    if (fromProperties.isNotEmpty()) return fromProperties
    return System.getenv(envKey)?.trim().orEmpty()
}

val releaseStorePath = signingValue("storeFile", "SKIPPER_KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "SKIPPER_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "SKIPPER_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "SKIPPER_KEY_PASSWORD")
val releaseStoreFile = releaseStorePath
    .takeIf { it.isNotEmpty() }
    ?.let { path ->
        val candidate = File(path)
        if (candidate.isAbsolute) candidate else rootProject.file(path)
    }
val hasReleaseSigning = releaseStoreFile != null &&
    releaseStoreFile.isFile &&
    releaseStorePassword.isNotEmpty() &&
    releaseKeyAlias.isNotEmpty() &&
    releaseKeyPassword.isNotEmpty()

android {
    namespace = "com.adreels.skipper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.adreels.skipper"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        allWarningsAsErrors = true
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    testImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.3")
}

val robolectricSdkJars = listOf(
    "org.robolectric:android-all-instrumented:15-robolectric-12650502-i7",
    "org.robolectric:android-all-instrumented:13-robolectric-9030017-i7",
).map { coordinate ->
    configurations.detachedConfiguration(dependencies.create(coordinate)).apply {
        isTransitive = false
    }
}

val robolectricSdkDir = layout.buildDirectory.dir("robolectric-sdk")

tasks.withType<Test> {
    useJUnitPlatform()
    robolectricSdkJars.forEach { inputs.files(it) }
    inputs.files(
        layout.projectDirectory.file("build.gradle.kts"),
        rootProject.file(".editorconfig"),
        rootProject.file(".gitignore"),
        rootProject.file("config/detekt/detekt.yml"),
        rootProject.file("scripts/check.conf"),
        rootProject.file(".github/workflows/check.yml"),
    )
    doFirst {
        val sdkDir = robolectricSdkDir.get().asFile
        sdkDir.mkdirs()
        robolectricSdkJars.flatMap { it.files }.forEach { jarFile ->
            val target = sdkDir.resolve(jarFile.name)
            if (!target.exists()) {
                Files.deleteIfExists(target.toPath())
                try {
                    Files.createSymbolicLink(target.toPath(), jarFile.toPath())
                } catch (_: Exception) {
                    jarFile.copyTo(target, overwrite = true)
                }
            }
        }
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", sdkDir.absolutePath)
    }
    System.getProperty("skipper.screenshot.dir")
        ?.takeIf { it.isNotBlank() }
        ?.let { systemProperty("skipper.screenshot.dir", it) }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

val ktlintCli = configurations.detachedConfiguration(
    dependencies.create("com.pinterest.ktlint:ktlint-cli:1.4.1"),
)

val detektCli = configurations.detachedConfiguration(
    dependencies.create("io.gitlab.arturbosch.detekt:detekt-cli:1.23.7"),
)

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Runs Ktlint code-style checks on Kotlin sources."
    classpath = ktlintCli
    mainClass.set("com.pinterest.ktlint.Main")
    workingDir = rootProject.projectDir
    val editorConfigFile = rootProject.file(".editorconfig")
    val reportFile = layout.buildDirectory.file("reports/ktlint/ktlint-check.txt")
    inputs.file(editorConfigFile)
    inputs.files(fileTree("src") { include("**/*.kt") })
    outputs.file(reportFile)
    args(
        "--editorconfig=${editorConfigFile.absolutePath}",
        "--reporter=plain,output=${reportFile.get().asFile.absolutePath}",
        "app/src/**/*.kt",
    )
}

tasks.register<JavaExec>("detekt") {
    group = "verification"
    description = "Runs Detekt static analysis on Kotlin sources."
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    workingDir = rootProject.projectDir
    val detektConfigFile = rootProject.file("config/detekt/detekt.yml")
    val mainSources = projectDir.resolve("src/main/kotlin")
    val testSources = projectDir.resolve("src/test/kotlin")
    val reportFile = layout.buildDirectory.file("reports/detekt/detekt.txt")
    inputs.file(detektConfigFile)
    inputs.files(fileTree(mainSources), fileTree(testSources))
    outputs.file(reportFile)
    args(
        "--input",
        "${mainSources.absolutePath},${testSources.absolutePath}",
        "--config",
        detektConfigFile.absolutePath,
        "--build-upon-default-config",
        "--report",
        "txt:${reportFile.get().asFile.absolutePath}",
    )
}
