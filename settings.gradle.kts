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
    // mavenLocal first so the locally-published gbgbridge-sdk
    // (`./gradlew :bridge-sdk:publishToMavenLocal` in the SDK repo) resolves
    // ahead of any future Maven Central artefact during pre-release work.
    // Once the SDK ships to Central this can be removed (or kept behind a
    // gradle property) without changing the rest of the build.
    mavenLocal()
    google()
    mavenCentral()
  }
}

rootProject.name = "GBGGoReference"

include(":app")
