plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.l0gic0l.anduino"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.l0gic0l.anduino"
        minSdk = 29
        targetSdk = 37
        versionCode = 4
        versionName = "0.1.3"

        // The bundled proot binaries exist for these two ABIs only
        // (arm64 = phones/foldables, x86_64 = emulator).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        // A fixed, public debug key (committed on purpose) so every CI or local build can be
        // installed over the previous one; a new key would force an uninstall, which deletes
        // the installed Linux environment and cores.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // Shrinks away unused icons; reflection-based libraries are kept whole (see proguard-rules.pro).
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the release build can be side-loaded for testing.
            signingConfig = signingConfigs.getByName("debug")
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

    packaging {
        jniLibs {
            // proot and its loader are executables that must exist as real files in
            // nativeLibraryDir so they can be exec()'d, so native libs are extracted at install.
            useLegacyPackaging = true
            // Prebuilt and already stripped; don't let AGP try to strip them again.
            keepDebugSymbols += "**/*.so"
        }
        resources {
            excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.window)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.material3.adaptive.navigation.suite)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.commons.compress)

    implementation(libs.sora.editor)
    implementation(libs.sora.language.textmate)
    implementation(libs.usb.serial)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
