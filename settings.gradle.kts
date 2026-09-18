pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
    maven { url "https://www.jitpack.io" }
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    maven { url "https://www.jitpack.io" }
  }
}

rootProject.name = "Qabas Studio"

include(":app")
