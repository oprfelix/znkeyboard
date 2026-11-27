import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Kept outside the repo; release builds are left unsigned when it is missing.
val releaseSigningFile = File(System.getProperty("user.home"), ".android/keystores/zn-keyboard-release.properties")
val releaseSigning = releaseSigningFile
    .takeIf { it.isFile }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

android {
    namespace = "dev.zain.znkeyboard"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.zain.znkeyboard"
        minSdk = 34
        targetSdk = 36
        versionCode = 84
        versionName = "1.9.49"
        // ONNX Runtime's native library (18 MB) for 64-bit ARM only: every phone this minSdk runs on.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    androidResources {
        // pinyin/lm.bin and the sentence scorer's model are memory-mapped straight from the APK,
        // which needs them stored uncompressed.
        noCompress += listOf("bin", "onnx")
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = releaseSigningFile.parentFile.resolve(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigning != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            // R8 drops unused Compose/AndroidX code; the APK goes from ~27 MB to a fraction of that.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // site: the APK on the website, which checks for and installs its own updates.
    // play: the bundle for Google Play, which updates it; no update code runs and no install permission.
    flavorDimensions += "store"
    productFlavors {
        create("site") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "false")
            // Play has had 1.10.0; its names stay ahead of that.
            versionName = "1.11.0"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.onnxruntime.android)
    implementation(libs.glide)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
