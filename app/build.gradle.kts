plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.codemew.bluecall"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.codemew.bluecall"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
                cFlags += listOf("-O2")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    signingConfigs {
        create("release") {
            // A private key can be supplied through these environment variables (e.g. CI secrets).
            // Without them, release builds use the public test key in app/signing (testing only).
            val keystore = System.getenv("BLUECALL_KEYSTORE")
            if (!keystore.isNullOrBlank()) {
                storeFile = file(keystore)
                storePassword = System.getenv("BLUECALL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BLUECALL_KEY_ALIAS")
                keyPassword = System.getenv("BLUECALL_KEY_PASSWORD")
            } else {
                storeFile = file("signing/bluecall-test.jks")
                storePassword = "bluecall-test"
                keyAlias = "bluecall-test"
                keyPassword = "bluecall-test"
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Android 16 (API 36) is the newest behaviour set this app is verified against.
        disable += "OldTargetApi"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
