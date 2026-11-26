plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// aapt2 cannot open non-ASCII paths on Windows, so build outputs go to an ASCII folder. The app's only res/ is the
// launcher icon, so the repo itself must sit on an ASCII path too (README: C:\dev\escape).
// On macOS, iCloud "Desktop & Documents" sync copies busy build files as "name 2.dex" and the dex merge then fails
// ("defined multiple times"); iCloud skips folders named *.nosync, so the build goes to car/app/build.nosync.
// Linux keeps the default car/app/build.
val hostOs = System.getProperty("os.name")
when {
    hostOs.startsWith("Windows") -> layout.buildDirectory.set(file("C:/dev/build/escape-car/app"))
    hostOs.startsWith("Mac") -> layout.buildDirectory.set(file("build.nosync"))
}

// The story server. On the emulator: run it on this PC and `adb reverse tcp:8787 tcp:8787`.
// For a build that talks to a deployed server: ./gradlew assembleRelease -PstoryUrl=https://...
val storyUrl = (findProperty("storyUrl") as String?) ?: "http://127.0.0.1:8787"

// The user rulebook lives in docs/ (one place to edit). The build copies it into the app's assets under an ASCII name,
// since aapt2 cannot open the Korean file name on Windows; the app reads it as rulebook/rulebook.md (guide/GuideText).
val rulebookAssets = layout.buildDirectory.dir("generated/rulebook")
val copyRulebook by tasks.registering(Copy::class) {
    from(rootProject.file("../docs/차근차근탐험단_룰북.md")) { rename { "rulebook.md" } }
    into(rulebookAssets.map { it.dir("rulebook") })
}

android {
    namespace = "kr.escape.car"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.escape.car"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "STORY_URL", "\"$storyUrl\"")
        // plain http only for the local server; a deployed one is https
        manifestPlaceholders["cleartext"] = storyUrl.startsWith("http://").toString()
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Contest build: signed with the local debug key so the APK installs anywhere for judging.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    sourceSets["main"].assets.srcDir(rulebookAssets)

    testOptions {
        unitTests {
            // Robolectric draws the Compose screens for the screenshot tests (ScreenshotTest) with the app's own assets
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("screenshots.dir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
                it.systemProperty("rulebook.file", rootProject.file("../docs/차근차근탐험단_룰북.md").absolutePath)
                it.maxHeapSize = "3g"
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    useLibrary("android.car")
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
    // the real org.json for JVM tests (android.jar only has stubs)
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // screenshots without an emulator: Robolectric draws the screens on the JVM and Roborazzi saves them as PNG
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.43.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.43.0")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(platform("androidx.compose:compose-bom:2024.09.03"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.named("preBuild") { dependsOn(copyRulebook) }
