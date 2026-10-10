plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// A release is built from a tag: CI passes -Pps3dec.versionName=X.Y.Z. Anything else is a development build.
val releaseVersion: String = (findProperty("ps3dec.versionName") as String?) ?: "0.0.0-dev"
val releaseVersionCode: Int = if (releaseVersion == "0.0.0-dev") {
    1
} else {
    val parts = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(releaseVersion)?.destructured?.toList()?.map { it.toInt() }
        ?: throw GradleException("ps3dec.versionName must look like X.Y.Z, got '$releaseVersion'")
    val (major, minor, patch) = parts
    if (minor >= 100 || patch >= 100) {
        throw GradleException("ps3dec.versionName '$releaseVersion': minor and patch must be below 100 (versionCode = major*10000 + minor*100 + patch)")
    }
    major * 10000 + minor * 100 + patch
}

android {
    namespace = "io.github.tonyp7.ps3dec"
    // The current Compose libraries require compiling against API 37; targetSdk stays at 36 so the
    // app does not opt in to Android 17 behavior changes yet.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.tonyp7.ps3dec"
        minSdk = 30
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Signing comes only from the environment (see android/README.md, "Releases and signing"), so
    // nothing secret is in the repository and a plain build without it still works, unsigned.
    val keystoreFile = System.getenv("PS3DEC_KEYSTORE_FILE")
    if (keystoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("PS3DEC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PS3DEC_KEY_ALIAS")
                keyPassword = System.getenv("PS3DEC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":engine"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
