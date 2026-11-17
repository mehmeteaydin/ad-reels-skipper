import java.nio.file.Files

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

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

    buildTypes {
        release {
            isMinifyEnabled = false
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
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
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
