plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hayeco.flipspaces"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hayeco.flipspaces"
        minSdk = 30
        targetSdk = 36
        versionCode = 129
        versionName = "1.0.0"
        // The Flip 7 is arm64; MediaPipe's native code for other ABIs only adds size.
        ndk { abiFilters += "arm64-v8a" }
    }

    // 0.5.98 (open source plan: the release signing key, kept by Mike only): official releases are signed with the
    // project's release key when the build is asked for it (-Ppocketxr.official=true, tools/release-build.ps1). The key's
    // file and alias come from the user's own ~/.gradle/gradle.properties (pocketxr.storeFile, pocketxr.keyAlias) and
    // its passwords from the environment (POCKETXR_STORE_PASSWORD, POCKETXR_KEY_PASSWORD), so nothing secret is in the
    // repository. Every other build, contributors' and CI's included, is signed with the debug key as before.
    val official = (findProperty("pocketxr.official") as String?) == "true"
    if (official) {
        signingConfigs {
            create("official") {
                storeFile = file(findProperty("pocketxr.storeFile") as String? ?: error("pocketxr.storeFile is not set in ~/.gradle/gradle.properties"))
                storePassword = System.getenv("POCKETXR_STORE_PASSWORD") ?: error("POCKETXR_STORE_PASSWORD is not set")
                keyAlias = findProperty("pocketxr.keyAlias") as String? ?: "pocketxr"
                keyPassword = System.getenv("POCKETXR_KEY_PASSWORD") ?: System.getenv("POCKETXR_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 0.5.49 (Mike: "switch to a non-debuggable build"): ART runs it fully optimised, without the debugger's
            // hooks. Signed with the same debug key as every build so far, so it installs over them; the debug-only
            // tools (src/debug: DebugReceiver, the hand replays, the NPU self test) are not in it. 0.5.98: an official
            // release is signed with the release key instead (above).
            signingConfig = signingConfigs.getByName(if (official) "official" else "debug")
        }
        debug {
            // Debug builds also run on an x86_64 Android emulator (docs/EMULATOR_TESTING.md).
            ndk { abiFilters += "x86_64" }
        }
    }

    // 0.5.58 (findings 13.5 item 1): each frame's present from EGL_ANDROID_get_frame_timestamps (src/main/cpp). The NDK
    // and CMake installed on the PC, so the build never downloads one.
    ndkVersion = "27.0.12077973"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // 0.5.43: our LiteRT build (jniLibs) over the AAR's; the dispatch is opened from the native library folder.
            pickFirsts += "**/libLiteRt.so"
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        compose = true
        // IPanelHost: the Shizuku user service that hosts app panels.
        aidl = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.mediapipe.tasks.vision)
    // 0.5.43: the hand models on the Exynos 2500's NPU (NpuHandEngine). The AAR's libLiteRt.so is replaced by our own
    // LiteRT v2.3.2 build in jniLibs, which exports what the Samsung dispatch needs. 0.5.96: the NPU models and that
    // build are not in the public repository; without them hand tracking runs MediaPipe on the GPU or CPU.
    implementation("com.google.ai.edge.litert:litert:2.3.2")
    // 0.5.3: EGL images of the panel ImageReaders' HardwareBuffers (eglCreateImageFromHardwareBuffer).
    implementation(libs.androidx.graphics.core)
    // 0.5.49: the release build's lint (InvalidFragmentVersionForActivityResult): a library brings an old Fragment, under
    // which registerForActivityResult's permission results can be lost; this one is new enough.
    implementation("androidx.fragment:fragment:1.9.1")

    testImplementation(libs.junit)
}
