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

java {
  withSourcesJar()
  withJavadocJar()
}

dependencies {
  testImplementation(platform("org.junit:junit-bom:6.0.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      from(components["java"])
      artifactId = "metaray-core"
    }
  }
}
