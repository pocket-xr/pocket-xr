pluginManagement {
    repositories {
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pocket-xr"
include(":core")
include(":app")
// 1.1.0: the add-on with the accessibility service and the notification listener (addon/build.gradle.kts).
include(":addon")
// 1.6.3 (Google Play): the add-on's services and setup screen built into Pocket XR itself (addon-play/build.gradle.kts),
// from the add-on's own source files. Only the Play build (-Ppocketxr.play=true) has it; every other build still uses the
// separate add-on.
if (providers.gradleProperty("pocketxr.play").orNull == "true") include(":addon-play")
