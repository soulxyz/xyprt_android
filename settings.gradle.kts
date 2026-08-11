pluginManagement {
    repositories {
        val offline = System.getenv("ANDROID_OFFLINE_MAVEN_REPO")
        if (!offline.isNullOrBlank()) maven { url = uri(offline) }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val offline = System.getenv("ANDROID_OFFLINE_MAVEN_REPO")
        if (!offline.isNullOrBlank()) maven { url = uri(offline) }
        google()
        mavenCentral()
    }
}
rootProject.name = "xyprt"
include(":app")
