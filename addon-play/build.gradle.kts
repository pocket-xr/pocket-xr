// 1.6.3 (Google Play): the Pocket XR add-on's services and setup screen as a library that only the Play build of Pocket XR
// takes (settings.gradle.kts, app/build.gradle.kts), so on Play the DeX cursor and the Notifications page need no second
// app. It has no source files of its own: it builds the add-on's (addon/src/main/kotlin, res and aidl) under the add-on's
// namespace, so their R class and package stay as they are. Its manifest (src/main/AndroidManifest.xml) declares the
// services in Pocket XR without the add-on's launcher entry, and the link service is not exported, as Pocket XR binds it
// in its own process. Pocket XR's own resources win over the add-on's where both have one (app_name, the launcher icon,
// locales_config), so Pocket XR keeps its name. The GitHub and debug builds never include this module.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.hayeco.flipspaces.addon"
    // As the app and the add-on: core 1.19 needs API 37 to compile against.
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // IAddon and IAddonCallback, compiled here once for the Play build (the app leaves its copy out there).
        aidl = true
    }

    sourceSets {
        getByName("main") {
            kotlin.directories.add("../addon/src/main/kotlin")
            res.directories.add("../addon/src/main/res")
            aidl.directories.add("../addon/src/main/aidl")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
}
