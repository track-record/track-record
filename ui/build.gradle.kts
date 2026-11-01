import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The app's screens, texts and logic, shared by the Android and the desktop app (PLATFORMS.md phase 5). Packages
// stay com.trackrecord.app.*; platform code lives in androidApp and desktopApp behind interfaces.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "com.trackrecord.app.ui"
        compileSdk = 37
        minSdk = 26
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
        // Compose Resources reach the app as Android assets.
        androidResources {
            enable = true
        }
        withHostTest {}
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }
    jvm("desktop") {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    sourceSets {
        // Shared by both apps, with Java (as in data): what depends on data's jvmMain or on java.*.
        val jvmMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(jvmMain)
        getByName("desktopMain").dependsOn(jvmMain)

        commonMain.dependencies {
            api(project(":data"))
            api(project(":remote"))
            api(libs.cmp.runtime)
            api(libs.cmp.foundation)
            api(libs.cmp.ui)
            api(libs.cmp.material3)
            api(libs.cmp.material.icons.core)
            api(libs.cmp.components.resources)
            api(libs.jetbrains.lifecycle.viewmodel.compose)
            api(libs.jetbrains.lifecycle.runtime.compose)
            api(libs.jetbrains.navigation.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            implementation(libs.zxing.core)
            implementation(libs.okhttp)
        }
        androidMain.dependencies {
            implementation(project(":playback"))
            implementation(project(":fingerprint"))
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.session)
            implementation(libs.kotlinx.coroutines.guava)
            implementation(libs.androidx.exifinterface)
            implementation(libs.androidx.browser)
            implementation(libs.androidx.webkit)
            implementation(libs.kotlinx.coroutines.android)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            runtimeOnly(libs.junit.platform.launcher)
        }
        getByName("desktopMain").dependencies {
            // Esc as Back, through the same dispatcher as the navigation's (PLATFORMS.md phase 7).
            implementation(libs.cmp.ui.backhandler)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.kotlin.test.junit4)
        }
        getByName("desktopTest").dependencies {
            implementation(kotlin("test"))
            // Skiko's native library, which Compose Resources ask for the system's theme.
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.trackrecord.app.resources"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // WebStringsTest, WebThemeTest and ProtocolVersionTest read the guest page.
    inputs.dir(rootProject.file("web/remote"))
    systemProperty("trackrecord.web", rootProject.file("web").path)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// The device tests' files (PlaceholderLeakTest's song).
androidComponents {
    onVariants { variant ->
        variant.deviceTests.values.forEach { test ->
            test.sources.assets?.addStaticSourceDirectory("$projectDir/src/androidDeviceTest/assets")
        }
    }
}
