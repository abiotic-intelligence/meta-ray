pluginManagement {
  repositories {
    google()
    gradlePluginPortal()
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

rootProject.name = "Meta-ray"

include(":core", ":jvm", ":android")
include(":testkit")

project(":core").projectDir = file("src/main/java/core")
project(":jvm").projectDir = file("src/main/java/jvm")
project(":android").projectDir = file("src/main/java/android")
project(":testkit").projectDir = file("testkit")
