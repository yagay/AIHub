pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.mozilla.org/maven2/")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(org.gradle.api.initialization.resolve.RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.mozilla.org/maven2/")
    }
}

rootProject.name = "AIHub"
include(":app")


includeBuild("vendor/YBrowser") {
    dependencySubstitution {
        substitute(module("com.yagay.ybrowser:browser-core"))
            .using(project(":browser-core"))
    }
}
