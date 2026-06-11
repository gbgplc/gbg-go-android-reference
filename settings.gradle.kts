pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    // The GBGBridge SDK is published to Maven Central
    // (com.gbg:gbgbridge-sdk).
    mavenCentral()
  }
}

rootProject.name = "GBGGoReference"

include(":app")
