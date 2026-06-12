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

    // PART 2 — Smart Capture SDKs.
    //
    // The com.gbg.smartcapture artifacts (commons, documentcamera,
    // facecamera) are distributed from a credentialed S3 Maven repository,
    // not Maven Central. Building this branch therefore requires Smart
    // Capture access — obtain the access key / secret from your GBG account
    // representative and add them to your *user-global* Gradle properties
    // (~/.gradle/gradle.properties), never to this repository:
    //
    //   awsAccessKey=...
    //   awsSecretKey=...
    //
    // Environment variables are also accepted (ORG_GRADLE_PROJECT_awsAccessKey
    // / ORG_GRADLE_PROJECT_awsSecretKey), which is the convention CI uses.
    //
    // Without these credentials the build fails at dependency resolution.
    // The Part 1 build on `main` needs none of this.
    val awsAccessKey = providers.gradleProperty("awsAccessKey").orNull
    val awsSecretKey = providers.gradleProperty("awsSecretKey").orNull
    if (awsAccessKey != null && awsSecretKey != null) {
      // Releases hold the published com.gbg.smartcapture artifacts; snapshots
      // hold transitive SNAPSHOT dependencies (e.g. com.gbg.smartcapture:camera).
      listOf("releases", "snapshots").forEach { channel ->
        maven {
          url = uri("s3://maven-mobile-repo/$channel")
          credentials(AwsCredentials::class.java) {
            accessKey = awsAccessKey
            secretKey = awsSecretKey
          }
        }
      }
    } else {
      logger.warn(
        "Smart Capture credentials (awsAccessKey / awsSecretKey) are not set. " +
          "The com.gbg.smartcapture dependencies will not resolve. See " +
          "settings.gradle.kts for setup, or build the `main` branch for the " +
          "stub-only Part 1 app.",
      )
    }
  }
}

rootProject.name = "GBGGoReference"

include(":app")
