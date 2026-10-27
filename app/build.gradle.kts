import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The built-in AcoustID key and MusicBrainz contact, from the git-ignored secrets.properties (D-177).
val secrets = Properties().apply {
    rootProject.file("secrets.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

// The release signing key, kept outside git like the secrets (REVIEW.md C-08; DECISIONS.md D-273):
// keystore.properties with storeFile, storePassword, keyAlias and keyPassword. Without it a release
// build is left unsigned.
val keystore = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

// One more with every commit, so each build installs over the one before (D-273).
val commits: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

fun secret(name: String): String = "\"" + secrets.getProperty(name, "").trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.trackrecord.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.trackrecord.app"
        minSdk = 26
        targetSdk = 37
        versionCode = commits
        versionName = "0.4.$commits"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "ACOUSTID_KEY", secret("acoustid.key"))
        buildConfigField("String", "MUSICBRAINZ_CONTACT", secret("musicbrainz.contact"))
        // Where internet guests open the guest page (SPEC v4; GitHub Pages, tools/publish_pages.py).
        buildConfigField("String", "GUEST_PAGE", "\"" + secrets.getProperty("guest.page", "https://track-record.github.io/") + "\"")
        buildConfigField("Boolean", "UPDATE_CHECK", "true")
    }

    signingConfigs {
        if (keystore.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Beside the release on the same phone (feedback round 47): its own package, name (src/debug/res) and guest
        // page, and no update prompt (the release it would offer is the other app).
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "GUEST_PAGE", "\"" + secrets.getProperty("guest.page.dev", "https://track-record.github.io/dev/") + "\"")
            buildConfigField("Boolean", "UPDATE_CHECK", "false")
        }
        release {
            // -PdebuggableRelease: a one-off release-signed build that `run-as` can write to, to move a debug
            // install's data into the release (feedback round 47). Never published.
            isDebuggable = providers.gradleProperty("debuggableRelease").isPresent
            // R8 shrinks and optimises release builds; the rules for JNI are in proguard-rules.pro (D-273).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            // WebStringsTest reads the guest page.
            it.inputs.dir("src/main/assets/remote")
        }
    }
}

dependencies {
    implementation(project(":core-engine"))
    implementation(project(":data"))
    implementation(project(":playback"))
    implementation(project(":fingerprint"))
    implementation(project(":remote"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.media3.exoplayer)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)
    implementation(libs.zxing.core)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
}
