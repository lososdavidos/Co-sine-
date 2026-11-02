import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

sourceSets {
    main {
        // Phase, the same source as Sine on Android (§6.15).
        kotlin.srcDir("../phase/src/main/kotlin")
        // Its fonts, the same files: the Android app's res/font.
        resources.srcDir("../app/src/main/res")
        resources.include("font/**", "sine/**")
    }
}

/**
 * Which build of FFmpeg's libraries goes in: the one for the system this
 * builds on, as each installer is built on its own system. -PsineNatives
 * picks another (linux-arm64, say).
 */
val natives: String = (findProperty("sineNatives") as String?) ?: run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    when {
        "win" in os -> "windows-x86_64"
        "mac" in os -> if (arm) "macosx-arm64" else "macosx-x86_64"
        else -> if (arm) "linux-arm64" else "linux-x86_64"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // FFmpeg plays the sound (Q84): its Java side, and its libraries for the system this builds on, inside the installer.
    implementation(libs.ffmpeg)
    implementation(variantOf(libs.ffmpeg) { classifier(natives) })
    implementation(variantOf(libs.javacpp) { classifier(natives) })

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(libs.okhttp.mockwebserver)
}

/** The release's version, as the release workflow passes it (v1.2.3), for the installers' metadata. */
val sineVersion = (findProperty("sineVersion") as String?)?.removePrefix("v")?.takeIf { Regex("""\d+\.\d+\.\d+""").matches(it) } ?: "0.10.0"

/**
 * macOS and Windows installers want a major version above 0: Sine's 0.x.y
 * goes in them as 1.x.y, which only they read, to tell an upgrade.
 */
val installerVersion = sineVersion.split(".").let { (major, minor, patch) -> "${major.toInt().coerceAtLeast(1)}.$minor.$patch" }

compose.desktop {
    application {
        mainClass = "app.sine.desktop.MainKt"
        jvmArgs += listOf("-Dsun.java2d.uiScale.enabled=true")
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "Sine"
            packageVersion = sineVersion
            description = "Sine: music from your Cosine or Subsonic server"
            vendor = "Sine"
            modules("java.instrument", "java.management", "java.naming", "jdk.unsupported")
            linux {
                iconFile.set(project.file("src/main/resources/sine/icon.png"))
                debMaintainer = "sine@localhost"
                appCategory = "AudioVideo"
            }
            windows {
                iconFile.set(project.file("src/main/resources/sine/icon.ico"))
                menuGroup = "Sine"
                msiPackageVersion = installerVersion
                upgradeUuid = "5b0e6c1e-7f43-4a51-9d0b-3c6a2f1d9e47"
            }
            macOS {
                iconFile.set(project.file("src/main/resources/sine/icon.icns"))
                bundleID = "app.sine.desktop"
                packageVersion = installerVersion
                dmgPackageVersion = installerVersion
            }
        }
    }
}
