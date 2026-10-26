plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.spotless)
}

// Formatting checks (REVIEW.md C-01, DECISIONS.md D-169): `gradlew spotlessCheck`, fix with
// `gradlew spotlessApply`. The rules in use are listed in .editorconfig.
spotless {
    // Line endings are git's business (core.autocrlf); files keep whatever they have.
    lineEndings = com.diffplug.spotless.LineEnding.PRESERVE
    kotlin {
        target("**/src/**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}
