import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
  base
  id("com.android.library") version "9.0.1" apply false
}

val publishGroup = providers.gradleProperty("PUBLISH_GROUP").orElse("io.metaray")
val publishVersion = providers.gradleProperty("PUBLISH_VERSION").orElse("0.1.0-SNAPSHOT")

allprojects {
  group = publishGroup.get()
  version = publishVersion.get()
}

subprojects {
  // Keep builds deterministic: no silent “it compiled, shrug” behavior.
  plugins.withId("java") {
    the<JavaPluginExtension>().toolchain.languageVersion.set(JavaLanguageVersion.of(17))

    tasks.withType<JavaCompile>().configureEach {
      options.encoding = "UTF-8"
      options.compilerArgs.addAll(listOf(
        "-Xlint:all",
        "-Werror",
        "-parameters"
      ))
    }

    tasks.withType<Javadoc>().configureEach {
      options.encoding = "UTF-8"
      (options as StandardJavadocDocletOptions).charSet("UTF-8")
    }

    tasks.withType<Test>().configureEach {
      useJUnitPlatform()
    }
  }

  plugins.withId("maven-publish") {
    configure<org.gradle.api.publish.PublishingExtension> {
      repositories {
        maven {
          name = "GitHubPackages"

          val owner = (providers.gradleProperty("gpr.owner").orNull
            ?: System.getenv("GITHUB_REPOSITORY_OWNER")
            ?: "OWNER").lowercase()
          val repo = providers.gradleProperty("gpr.repo").orNull
            ?: System.getenv("GITHUB_REPOSITORY")?.substringAfter('/')
            ?: "REPO"

          url = uri("https://maven.pkg.github.com/$owner/$repo")
          credentials {
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
          }
        }
      }
    }
  }
}
