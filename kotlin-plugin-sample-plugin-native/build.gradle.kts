import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm")
  kotlin("kapt")
  id("org.jetbrains.dokka")

  signing
  `maven-publish`
}

dependencies {
  compileOnly("org.jetbrains.kotlin:kotlin-compiler")

  kapt("com.google.auto.service:auto-service:1.0-rc6")
  compileOnly("com.google.auto.service:auto-service-annotations:1.0-rc6")
}

tasks.named("compileKotlin") { dependsOn("syncSource") }
tasks.register<Sync>("syncSource") {
  from(project(":kotlin-plugin-sample-plugin").sourceSets.main.get().allSource)
  into("src/main/kotlin")
  filter {
    // Replace shadowed imports from kotlin-power-assert-plugin
    when (it) {
      "import org.jetbrains.kotlin.com.intellij.mock.MockProject" -> "import com.intellij.mock.MockProject"
      else -> it
    }
  }
}

tasks.withType<KotlinCompile> {
  kotlinOptions.jvmTarget = "1.8"
}

tasks.dokka {
  outputFormat = "html"
  outputDirectory = "$buildDir/javadoc"
}

tasks.register("sourcesJar", Jar::class) {
  group = "build"
  description = "Assembles Kotlin sources"

  archiveClassifier.set("sources")
  from(sourceSets.main.get().allSource)
  dependsOn(tasks.classes)
}

tasks.register("dokkaJar", Jar::class) {
  group = "documentation"
  description = "Assembles Kotlin docs with Dokka"

  archiveClassifier.set("javadoc")
  from(tasks.dokka)
  dependsOn(tasks.dokka)
}

publishing {
  // publishing settings go here
}
