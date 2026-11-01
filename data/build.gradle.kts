import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The library, playlists, profiles, settings and the network clients (PLATFORMS.md phase 3): Kotlin Multiplatform for
// Android and the desktop. commonMain holds what needs neither Java nor a platform; jvmMain, shared by both apps,
// what needs Java (OkHttp, files, threads); androidMain and desktopMain the rest.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "com.trackrecord.data"
        compileSdk = 37
        minSdk = 26
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
        // Media3's tag mapping, on the JVM (Media3TagReaderTest).
        withHostTest {}
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        // For the device tests' assets (below).
        androidResources {
            enable = true
        }
    }
    jvm("desktop") {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    sourceSets {
        val jvmMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(jvmMain)
        getByName("desktopMain").dependsOn(jvmMain)

        commonMain.dependencies {
            api(project(":core-engine"))
            api(libs.room.runtime)
            implementation(libs.datastore.preferences)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            implementation(libs.okhttp)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.kotlinx.coroutines.guava)
            implementation(libs.media3.inspector)
            implementation(libs.media3.extractor)
            implementation(libs.media3.exoplayer)
        }
        getByName("desktopMain").dependencies {
            implementation(libs.sqlite.bundled)
            implementation(libs.jaudiotagger)
        }
        getByName("desktopTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
            runtimeOnly(libs.junit.platform.launcher)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.kotlin.test.junit4)
            implementation(libs.room.testing)
        }
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspDesktop", libs.room.compiler)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The device tests' files: the exported schemas for MigrationTest (REVIEW-2 T-07), the test songs for TagReaderDeviceTest.
androidComponents {
    onVariants { variant ->
        variant.deviceTests.values.forEach { test ->
            test.sources.assets?.addStaticSourceDirectory("$projectDir/schemas")
            test.sources.assets?.addStaticSourceDirectory("$projectDir/src/androidDeviceTest/assets")
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // DesktopLibraryTest reads the device tests' library.
    systemProperty("trackrecord.testlib", file("src/androidDeviceTest/assets/testlib").path)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
