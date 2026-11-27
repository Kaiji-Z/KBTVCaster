pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    // Every dependency resolves from Google Maven or Maven Central; no extra
    // repository is declared (jCenter is sunset, JitPack has nothing we use).
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "KBTVCaster"
include(":app")
