import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 1.1.0: the Pocket XR add-on, a second small app. It holds the two services Google's install check blocks on
// sideloaded apps in some countries (the pointer accessibility service and the notification listener), so Pocket XR
// itself installs everywhere and only people who want the DeX cursor or the Notifications page add this.
android {
    namespace = "com.hayeco.flipspaces.addon"
    // As the app: core 1.19 needs API 37 to compile against; targetSdk stays 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hayeco.flipspaces.addon"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
    }

    // Signed like the app (app/build.gradle.kts): the release key for an official build, else the debug key. The app
    // binds the add-on only through a signature permission, so the two must always be signed with the same key.
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

    // 1.1.11 (Google Play): a Play upload is signed with the upload key in keystore.properties, as the app's
    // (app/build.gradle.kts, docs/PLAY.md). Not shrunk: it is small.
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
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (play) "play" else if (official) "official" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // IAddon: the link the app binds (src/main/aidl, also compiled into the app).
        aidl = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
}
