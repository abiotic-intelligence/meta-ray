plugins {
  id("com.android.library")
  `maven-publish`
}

android {
  namespace = "io.metaray.android"
  // If CI only has API 35 installed, set compileSdk = 35.
  compileSdk = 36

  defaultConfig {
    minSdk = 21
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    isCoreLibraryDesugaringEnabled = true
  }

  publishing {
    singleVariant("release") {
      withSourcesJar()
    }
  }
}

dependencies {
  api(project(":core"))
  implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
  implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:2.18.3")
  implementation("com.fasterxml.jackson.module:jackson-module-parameter-names:2.18.3")
  testImplementation(project(":testkit"))
  testImplementation("junit:junit:4.13.2")
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}

afterEvaluate {
  publishing {
    publications {
      create<MavenPublication>("release") {
        from(components["release"])
        artifactId = "metaray-android"
      }
    }
  }
}
