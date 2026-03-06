plugins {
  `java-library`
  `maven-publish`
}

sourceSets {
  named("main") {
    java.setSrcDirs(listOf("."))
    java.exclude("build/**", ".gradle/**")
    java.exclude("src/test/**")
  }
  named("test") {
    java.setSrcDirs(listOf("src/test/java"))
  }
}

dependencies {
  api(project(":core"))
  implementation("org.jmdns:jmdns:3.5.9")
  implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
  implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:2.18.3")
  implementation("com.fasterxml.jackson.module:jackson-module-parameter-names:2.18.3")
  testImplementation(project(":testkit"))
  testImplementation(platform("org.junit:junit-bom:6.0.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
  withSourcesJar()
  withJavadocJar()
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifactId = "metaray-jvm"
    }
  }
}
