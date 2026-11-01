plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.buildconfig) apply false
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

/**
 * Shared code stays free of Java, which keeps iOS possible at no cost (PLATFORMS.md section 6): a `commonMain`
 * file that uses `java.` or `javax.`, or a Kotlin API only the JVM has (`String.format`, `synchronized`, `@Volatile`,
 * `System`, …), fails the build. That belongs in `jvmMain`. Comments don't count.
 */
abstract class NoJavaInCommonMain : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        // java.* and javax.*, and the Kotlin APIs that only exist on the JVM.
        val java = Regex("""(^|[^\w.])javax?\.[a-z]|String\.format\(|"\.format\(|\bsynchronized\(|@Synchronized|@Volatile|@Jvm[A-Z]|\bSystem\.|\bMath\.|\bThread\b""")
        val found = sources.files.sortedBy { it.path }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*") || !java.containsMatchIn(code)) null else "${file.path}:${i + 1}: $code"
            }
        }
        report.get().asFile.writeText(found.joinToString("\n"))
        if (found.isNotEmpty()) throw GradleException("Java in commonMain (put it in jvmMain):\n" + found.joinToString("\n"))
    }
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        val noJava = tasks.register<NoJavaInCommonMain>("checkNoJavaInCommonMain") {
            sources.from(fileTree("src/commonMain") { include("**/*.kt") })
            report = layout.buildDirectory.file("reports/noJavaInCommonMain.txt")
        }
        tasks.matching { it.name.startsWith("compileKotlin") || it.name == "check" }.configureEach { dependsOn(noJava) }
        // `gradlew test` keeps running these modules' tests, as before they were multiplatform.
        tasks.register("test") { dependsOn("allTests") }
    }
}
