import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hayeco.flipspaces"
    // Compose 1.12 and core 1.19 need API 37 to compile against. targetSdk stays 36, so the app behaves as before.
    compileSdk = 37

    val playBuild = (findProperty("pocketxr.play") as String?) == "true"

    defaultConfig {
        // The private test copy (-Ppocketxr.dev=true), beside the real app, debug signed. See docs/OPENXR-PLAN.md.
        applicationId = if (findProperty("pocketxr.dev") == "true") "com.hayeco.flipspaces.dev" else "com.hayeco.flipspaces"
        // Its own name on the phone, so it is never mistaken for the real app.
        resValue("string", "app_label", if (findProperty("pocketxr.dev") == "true") "Pocket XR Test" else "Pocket XR")
        minSdk = 30
        targetSdk = 36
        versionCode = 169
        versionName = "1.3.3"
        // The Flip 7 is arm64; MediaPipe's native code for other ABIs only adds size.
        ndk { abiFilters += "arm64-v8a" }
        // 1.0.1: the Google Play build (-Ppocketxr.play=true) hides the Ko-fi row (1.1.15: atop the trackpad's Menu), as Play's payments
        // policy does not allow a link to tip the developer ([com.hayeco.flipspaces.core.Support]). Every other build,
        // the GitHub release included, shows it.
        buildConfigField("boolean", "PLAY_BUILD", playBuild.toString())
        // 1.1.15: the GitHub update check's background job (UpdateJobService) is disabled in the Play build.
        manifestPlaceholders["selfUpdates"] = (!playBuild).toString()
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

    // 1.1.11 (Google Play): a Play upload (-Ppocketxr.play=true, docs/PLAY.md) is signed with the Pocket XR upload key, which
    // keystore.properties at the repository root points to (storeFile, storePassword, keyAlias, keyPassword; gitignored,
    // like the other Haye & Co apps). Google Play re-signs it with the app signing key. It is also shrunk by R8, with the
    // mapping kept for Play's crash reports (proguard-rules.pro keeps Pocket XR's own classes and the native libraries').
    val play = (findProperty("pocketxr.play") as String?) == "true"
    if (play) {
        val props = Properties().apply {
            val f = rootProject.file("keystore.properties")
            if (!f.exists()) error("keystore.properties is missing at the repository root (docs/PLAY.md)")
            f.inputStream().use { load(it) }
        }
        signingConfigs {
            create("play") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = play
            if (play) proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 0.5.49 (Mike: "switch to a non-debuggable build"): ART runs it fully optimised, without the debugger's
            // hooks. Signed with the same debug key as every build so far, so it installs over them; the debug-only
            // tools (src/debug: DebugReceiver, the hand replays, the NPU self test) are not in it. 0.5.98: an official
            // release is signed with the release key instead (above).
            signingConfig = signingConfigs.getByName(if (play) "play" else if (official) "official" else "debug")
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
        // IPanelHost: the Shizuku user service that hosts app panels. 1.1.0: IAddon, the link to the add-on.
        aidl = true
        // 1.0.1: BuildConfig.PLAY_BUILD (above).
        buildConfig = true
        resValues = true
    }

    // 1.0.1: the unit tests check BuildConfig.PLAY_BUILD against the build that was asked for.
    testOptions {
        unitTests.all { it.systemProperty("pocketxr.play", playBuild.toString()) }
        // 1.1.14: the Robolectric tests read the merged manifest and res/xml (the reports FileProvider).
        unitTests.isIncludeAndroidResources = true
    }

    // 1.1.0: the add-on's link (IAddon, IAddonCallback) is compiled into both apps from the add-on's one copy.
    sourceSets {
        getByName("main") {
            aidl.directories.add("../addon/src/main/aidl")
            // 1.1.14: the Samsung NPU parts (src/npu: the hand models, our LiteRT build and the Samsung dispatch) are not
            // in the public repository. For a GitHub release, tools/fetch-npu.sh copies them in from the private one
            // (docs/RELEASE.md). Play builds never take them; there, and when src/npu is absent, LiteRT is the AAR's and
            // hands run MediaPipe.
            if (!play) {
                jniLibs.directories.add("src/npu/jniLibs")
                assets.directories.add("src/npu/assets")
            }
        }
        // 1.2.1: REQUEST_INSTALL_PACKAGES for the add-on's one-tap install, in every release but Google Play's (debug
        // builds declare it in src/debug).
        if (!play) getByName("release").manifest.srcFile("src/selfinstall/AndroidManifest.xml")
    }
}

dependencies {
    implementation(project(":core"))

    // OpenXR: the Java side of Monado, built from the pocket-xr branch of our Monado fork (docs/OPENXR.md).
    implementation(files("libs/monado/monado-ipc.aar", "libs/monado/monado-auxiliary.aar"))

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
    // build are not in the public repository; without them hand tracking runs MediaPipe on the GPU or CPU. 1.1.14: GitHub
    // releases fetch them into src/npu (sourceSets below, docs/RELEASE.md).
    implementation("com.google.ai.edge.litert:litert:2.3.2")
    // 0.5.3: EGL images of the panel ImageReaders' HardwareBuffers (eglCreateImageFromHardwareBuffer).
    implementation(libs.androidx.graphics.core)
    // 0.5.49: the release build's lint (InvalidFragmentVersionForActivityResult): a library brings an old Fragment, under
    // which registerForActivityResult's permission results can be lost; this one is new enough.
    implementation("androidx.fragment:fragment:1.9.1")

    testImplementation(libs.junit)
    // 1.1.14: Send logs to Mike and the Head tracking switch, tested on Robolectric (app/src/test).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
