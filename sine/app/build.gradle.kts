import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.sine"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.sine"
        minSdk = 26
        targetSdk = 36
        // Release builds pass these from the tag (see .github/workflows/release.yml).
        versionCode = (findProperty("sineVersionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("sineVersionName") as String?) ?: "0.0.0-dev"
    }

    signingConfigs {
        // A fixed key committed to the repo, so every test build (local or CI)
        // installs over the previous one instead of demanding an uninstall,
        // which would lose accounts and the downloads index. It protects
        // nothing: a store release needs its own key, kept out of the repo.
        create("testing") {
            storeFile = file("testing.keystore")
            storePassword = "android"
            keyAlias = "sine-testing"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("testing")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("testing")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.documentfile)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
