import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject

// The desktop app (PLATFORMS.md phase 6): the shared ui in a Compose window, with Chromium from the JetBrains Runtime
// (JBR with JCEF) for the music and the internet room. `gradlew :desktopApp:run` downloads the pinned JBR once.
// -Prelease builds for a release: the published guest page and the update check, as the Android release.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.buildconfig)
}

val release = providers.gradleProperty("release").isPresent

// The built-in AcoustID key and MusicBrainz contact, from the git-ignored secrets.properties (D-177).
val secrets = Properties().apply {
    rootProject.file("secrets.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

// The same version as the Android app: one more with every commit (D-273).
val commits: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

// ---- The JetBrains Runtime with JCEF (PLATFORMS.md phase 0): pinned, checked, kept in the Gradle user home ----
val jbrName_ = "jbrsdk_jcef-21.0.11-windows-x64-b1163.125"
val jbrSha512 = "bc76250b9f15d1b18daa1765ec083ffbb4c4c162606a97e20567a3fc7c7e3fe11fe59bd53bbba7015ed7a46d6eb0c2b8ab32a75e8e74dd01ba7e86ee50982978"
val jbrRoot = gradle.gradleUserHomeDir.resolve("trackrecord/jbr")
val jbrHome: File = providers.gradleProperty("jbrHome").orNull?.let(::File) ?: jbrRoot.resolve(jbrName_)

/** Downloads and unpacks the pinned JBR into [root], once; checked against JetBrains' SHA-512. */
abstract class DownloadJbr : DefaultTask() {
    @get:Input abstract val jbrName: Property<String>

    @get:Input abstract val sha512: Property<String>

    @get:Internal abstract val root: DirectoryProperty

    @get:OutputDirectory abstract val home: DirectoryProperty

    @get:Inject abstract val archives: ArchiveOperations

    @get:Inject abstract val files: FileSystemOperations

    @TaskAction
    fun download() {
        val installed = home.get().asFile.resolve("bin")
        if (installed.resolve("java.exe").isFile || installed.resolve("java").isFile) return
        val name = jbrName.get()
        val zip = root.get().asFile.resolve("$name.zip")
        zip.parentFile.mkdirs()
        if (!zip.isFile) {
            logger.lifecycle("Downloading $name (about 600 MB, once)")
            URI("https://cache-redirector.jetbrains.com/intellij-jbr/$name.zip").toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
        }
        val digest = MessageDigest.getInstance("SHA-512")
        zip.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != sha512.get()) {
            zip.delete()
            throw GradleException("The JBR download doesn't match JetBrains' SHA-512; deleted it, try again.")
        }
        files.copy {
            from(archives.zipTree(zip))
            into(root)
        }
        zip.delete()
    }
}

/** JCEF's classes (org.cef, com.jetbrains.cef) from the JBR's jcef.jmod, to compile against with any JDK. */
abstract class ExtractJcefApi : DefaultTask() {
    @get:InputFile abstract val jmod: RegularFileProperty

    @get:OutputFile abstract val jar: RegularFileProperty

    @TaskAction
    fun extract() {
        val out = jar.get().asFile
        out.parentFile.mkdirs()
        ZipOutputStream(out.outputStream()).use { target ->
            ZipFile(jmod.get().asFile).use { zip ->
                for (entry in zip.entries()) {
                    if (!entry.name.startsWith("classes/") || entry.isDirectory || entry.name.endsWith("module-info.class")) continue
                    target.putNextEntry(ZipEntry(entry.name.removePrefix("classes/")))
                    zip.getInputStream(entry).use { it.copyTo(target) }
                    target.closeEntry()
                }
            }
        }
    }
}

val downloadJbr = tasks.register<DownloadJbr>("downloadJbr") {
    description = "Downloads and unpacks the pinned JetBrains Runtime with JCEF, once."
    jbrName = jbrName_
    sha512 = jbrSha512
    root = jbrRoot
    home = jbrHome
}

val jcefApi = layout.buildDirectory.file("jcef/jcef-api.jar")
val extractJcefApi = tasks.register<ExtractJcefApi>("extractJcefApi") {
    dependsOn(downloadJbr)
    jmod = jbrHome.resolve("jmods/jcef.jmod")
    jar = jcefApi
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

sourceSets.main {
    // The guest page and the room page (web/remote, web/p2p-host), served to guests and to Chromium.
    resources.srcDir(rootProject.file("web"))
}

dependencies {
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jaudiotagger)
    implementation(libs.jaad)
    implementation(libs.lwjgl)
    implementation(libs.lwjgl.nfd)
    runtimeOnly(variantOf(libs.lwjgl) { classifier("natives-windows") })
    runtimeOnly(variantOf(libs.lwjgl.nfd) { classifier("natives-windows") })
    compileOnly(files(jcefApi).builtBy(extractJcefApi))

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.compose.ui:ui-test:${libs.versions.composeMultiplatform.get()}")
}

buildConfig {
    packageName("com.trackrecord.desktop")
    buildConfigField("VERSION_NAME", "0.4.$commits")
    buildConfigField("VERSION_CODE", commits)
    buildConfigField("RELEASE", release)
    buildConfigField("ACOUSTID_KEY", secrets.getProperty("acoustid.key", "").trim())
    buildConfigField("MUSICBRAINZ_CONTACT", secrets.getProperty("musicbrainz.contact", "").trim())
    // Development runs invite to the dev guest page, as Android's debug builds (D-313).
    buildConfigField(
        "GUEST_PAGE",
        if (release) secrets.getProperty("guest.page", "https://track-record.github.io/") else secrets.getProperty("guest.page.dev", "https://track-record.github.io/dev/"),
    )
}

compose.desktop {
    application {
        mainClass = "com.trackrecord.desktop.MainKt"
        javaHome = jbrHome.path
        // Silent unless asked for sound, for development runs (the silent-test rule): TRACKRECORD_MUTE=0 plays.
        jvmArgs += listOf("-Dtrackrecord.mute=${providers.environmentVariable("TRACKRECORD_MUTE").orNull ?: if (release) "0" else "1"}")
        nativeDistributions {
            // The Windows installer (PLATFORMS.md phase 8): `gradlew :desktopApp:packageMsi -Prelease`.
            targetFormats(TargetFormat.Msi)
            packageName = "TrackRecord"
            // The APK's version: one more with every commit (D-273).
            packageVersion = "0.4.$commits"
            description = "Track Record, the music party game"
            vendor = "Track Record"
            modules("jcef", "java.net.http", "jdk.crypto.ec", "jdk.unsupported", "java.sql", "java.naming")
            // A double-clicked library export opens in the app (feedback round 55; the installer registers it).
            fileAssociation(mimeType = "application/x-trackrecord", extension = "trackrecord", description = "Track Record library")
            windows {
                iconFile = file("icons/TrackRecord.ico")
                // For the user who installs it, without admin rights; a newer MSI replaces the old one (the same id).
                perUserInstall = true
                upgradeUuid = "24968fd4-32c9-4285-851e-6d75133cf102"
                menu = true
                menuGroup = "Track Record"
                shortcut = true
            }
        }
    }
}

// Everything that runs or packages with the JBR comes after its download.
tasks.matching { it.name == "run" || it.name == "checkRuntime" }.configureEach { dependsOn(downloadJbr) }

// Compose builds the runtime with --strip-native-commands, which drops what jcef.jmod keeps under bin/ (Chromium's
// .pak files, icudtl.dat, jcef_helper.exe, locales/): copy them back from the JBR (PLATFORMS.md phase 0), but for what
// the app doesn't use.
tasks.matching { it.name == "createRuntimeImage" }.configureEach {
    dependsOn(downloadJbr)
    val jmod = jbrHome.resolve("jmods/jcef.jmod")
    val jbrBin = jbrHome.resolve("bin")
    doLast {
        // Not the out-of-process server nor the languages of Chromium's own pages, which the app never shows (phase 8):
        // en-US only, the locale Chromium starts with (Chromium.kt).
        val unused = Regex("""cef_server\.exe|locales/(?!en-US\.pak).*""")
        val names = ZipFile(jmod).use { zip -> zip.entries().toList().map { it.name }.filter { it.startsWith("bin/") && !it.endsWith("/") }.map { it.removePrefix("bin/") } }
            .filterNot { unused.matches(it) }
        val runtime = outputs.files.files.first().resolve("bin")
        for (name in names) {
            val target = runtime.resolve(name)
            target.parentFile.mkdirs()
            jbrBin.resolve(name).copyTo(target, overwrite = true)
        }
    }
}

// DesktopAppTest runs the app headless, with Chromium: on the JBR, muted.
tasks.test {
    useJUnitPlatform()
    dependsOn(downloadJbr)
    executable = jbrHome.resolve("bin/java.exe").takeIf { it.isFile }?.path ?: jbrHome.resolve("bin/java").path
    systemProperty("trackrecord.testlib", rootProject.file("data/src/androidDeviceTest/assets/testlib").path)
    // fpcalc's fingerprints of two files, which the Android test compares with too.
    systemProperty("trackrecord.fptest", rootProject.file("fingerprint/src/androidTest/assets").path)
    // ScreenTourTest saves the screens there when asked: -Dtrackrecord.screens=<folder>.
    systemProperty("trackrecord.screens", System.getProperty("trackrecord.screens").orEmpty())
    // Chromium starts once per process: a JVM for each test class.
    forkEvery = 1
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
