import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Remote play (SPEC v3, v4): the protocol and the guests' views in commonMain; the hub, the HTTP server and the
// tunnel, which use threads and sockets, in jvmMain (PLATFORMS.md phase 2).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core-engine"))
            api(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
